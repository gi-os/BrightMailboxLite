package com.gios.brightmailboxlite.mail

import com.gios.brightmailboxlite.text.Html
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Base64

/**
 * Just enough MIME to read mail as text.
 *
 * The full app used Angus Mail for this. A Light SDK tool cannot depend on it, so this
 * file does the part a reader needs: header unfolding and RFC 2047 words, multipart
 * trees, quoted-printable and base64, declared and undeclared charsets, and choosing
 * which part is the message. It reads; it never writes.
 *
 * Bytes are handled as ISO-8859-1 strings internally, which maps every byte to exactly
 * one char and back. That makes boundary searching and line splitting plain string work
 * without ever corrupting an 8-bit body before its charset is known.
 *
 * Tolerant by design: a body fetched with a size cap ends mid-part, mid-line, or
 * mid-base64-quantum, and the reader still has to show what arrived.
 */
object Mime {

    /** Header fields in order. Names are kept as sent; lookups ignore case. */
    class Headers(val fields: List<Pair<String, String>>) {
        fun get(name: String): String? = fields.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

        fun all(name: String): List<String> = fields.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }

        /** Lowercased names; repeated headers joined with ", " — the shape the sorter reads. */
        fun toMap(): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            for ((k, v) in fields) {
                val key = k.lowercase()
                out[key] = out[key]?.let { "$it, $v" } ?: v
            }
            return out
        }
    }

    class Part(
        val headers: Headers,
        /** Lowercased `type/subtype`; `text/plain` when absent, as RFC 2045 says. */
        val type: String,
        val params: Map<String, String>,
        /** Transfer-decoded bytes for a leaf; empty for a multipart. */
        val body: ByteArray,
        val children: List<Part>,
    ) {
        val isMultipart: Boolean get() = type.startsWith("multipart/")

        val disposition: String
            get() = headers.get("content-disposition")?.let { parseParams(it).first }.orEmpty()

        val filename: String?
            get() = headers.get("content-disposition")?.let { parseParams(it).second["filename"] }
                ?: params["name"]

        val isAttachment: Boolean
            get() = disposition == "attachment" ||
                (filename != null && !type.startsWith("text/")) ||
                (filename != null && disposition != "inline" && type != "text/plain" && type != "text/html")
    }

    /** What the reader shows. */
    data class Readable(
        val text: String,
        /** True when no text/plain part was usable and the text came from HTML. */
        val fromHtml: Boolean,
        /** File names of attachments, for a one-line mention. Lite cannot open them. */
        val attachments: List<String>,
    )

    /* ---------------------------------------------------------------- top level */

    fun parse(raw: ByteArray): Part = parsePart(latin1(raw), 0)

    /** Parse a header block on its own, e.g. a `BODY[HEADER]` fetch. */
    fun parseHeaders(raw: ByteArray): Headers = headersOf(latin1(raw))

    /**
     * @param truncated the bytes stop at a fetch cap, so the last character may be cut
     *   in half; only then is an incomplete trailing UTF-8 sequence forgiven.
     */
    fun readable(raw: ByteArray, truncated: Boolean = false): Readable {
        val root = parse(raw)
        val attachments = ArrayList<String>()
        val chosen = choose(root, attachments)
        val text = chosen?.let { textOf(it, truncated) }.orEmpty()
        val fromHtml = chosen?.type == "text/html"
        val cleaned = if (fromHtml) Html.toText(text) else text
        return Readable(cleaned.trim('\n', '\r', ' '), fromHtml, attachments.distinct())
    }

    /* ----------------------------------------------------------------- structure */

    private const val MAX_DEPTH = 12

    private fun parsePart(s: String, depth: Int): Part {
        val split = headerEnd(s)
        val headers = headersOf(s.substring(0, split.first))
        val bodyText = if (split.second <= s.length) s.substring(split.second) else ""
        val (type, params) = headers.get("content-type")?.let { parseParams(it) } ?: ("text/plain" to emptyMap())
        val cleanType = type.ifBlank { "text/plain" }
        if (cleanType.startsWith("multipart/") && depth < MAX_DEPTH) {
            val boundary = params["boundary"]
            if (!boundary.isNullOrEmpty()) {
                val children = splitMultipart(bodyText, boundary).map { parsePart(it, depth + 1) }
                return Part(headers, cleanType, params, ByteArray(0), children)
            }
        }
        val cte = headers.get("content-transfer-encoding")?.trim()?.lowercase().orEmpty()
        val raw = bodyText.toByteArray(Charsets.ISO_8859_1)
        val decoded = when (cte) {
            "base64" -> decodeBase64(raw)
            "quoted-printable" -> decodeQuotedPrintable(raw)
            else -> raw
        }
        return Part(headers, cleanType, params, decoded, emptyList())
    }

    /** Index where the header block ends, and where the body starts. */
    private fun headerEnd(s: String): Pair<Int, Int> {
        val crlf = s.indexOf("\r\n\r\n")
        val lf = s.indexOf("\n\n")
        return when {
            crlf >= 0 && (lf < 0 || crlf <= lf) -> crlf to crlf + 4
            lf >= 0 -> lf to lf + 2
            // Headers only (a truncated fetch, or BODY[HEADER] without the blank line).
            else -> s.length to s.length + 1
        }
    }

    private fun headersOf(block: String): Headers {
        // Decode the block's 8-bit bytes: UTF-8 if it is valid UTF-8 (RFC 6532), else as-is.
        val text = decodeBytes(block.toByteArray(Charsets.ISO_8859_1), null)
        val out = ArrayList<Pair<String, String>>()
        var name: String? = null
        val value = StringBuilder()
        fun flush() {
            name?.let { out.add(it to decodeHeader(value.toString().trim())) }
            name = null
            value.setLength(0)
        }
        for (line in text.split("\r\n", "\n")) {
            if (line.isEmpty()) continue
            if ((line[0] == ' ' || line[0] == '\t') && name != null) {
                // Unfold: the line break goes, the whitespace stays.
                value.append(' ').append(line.trim())
                continue
            }
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            flush()
            name = line.substring(0, colon).trim()
            value.append(line.substring(colon + 1).trim())
        }
        flush()
        return Headers(out)
    }

    /**
     * The parts between `--boundary` lines.
     *
     * A missing closing delimiter is normal for a capped fetch, so whatever follows the
     * last opening delimiter is still a part.
     */
    fun splitMultipart(body: String, boundary: String): List<String> {
        val delim = "--$boundary"
        val parts = ArrayList<String>()
        var pos = 0
        var start = -1
        while (true) {
            val at = findDelimiter(body, delim, pos) ?: break
            val lineEnd = body.indexOf('\n', at).let { if (it < 0) body.length else it + 1 }
            val isClose = body.startsWith("$delim--", at)
            if (start >= 0) {
                // The CRLF before a delimiter belongs to the delimiter.
                var end = at
                if (end > start && body[end - 1] == '\n') end--
                if (end > start && body[end - 1] == '\r') end--
                parts.add(body.substring(start, end.coerceAtLeast(start)))
            }
            if (isClose) { start = -1; break }
            start = lineEnd
            pos = lineEnd
        }
        if (start in 0..body.length) parts.add(body.substring(start))
        return parts
    }

    /** A delimiter must begin a line. */
    private fun findDelimiter(body: String, delim: String, from: Int): Int? {
        var i = from
        while (true) {
            val at = body.indexOf(delim, i)
            if (at < 0) return null
            if (at == 0 || body[at - 1] == '\n') return at
            i = at + 1
        }
    }

    /* ---------------------------------------------------------------- selection */

    /**
     * Which part is the message.
     *
     * In an `alternative`, the plain rendering wins unless it is empty — some senders
     * ship a blank or "view this in your browser" text part beside the real HTML. In
     * anything else (`mixed`, `related`, unknown), the first readable part is the body
     * and the rest are attachments.
     */
    private fun choose(p: Part, attachments: MutableList<String>): Part? {
        if (!p.isMultipart) {
            if (p.isAttachment) {
                attachments.add(p.filename ?: p.type)
                return null
            }
            return when {
                p.type == "text/plain" || p.type == "text/html" -> p
                p.type == "message/rfc822" -> { attachments.add("forwarded message"); null }
                p.type.startsWith("text/") && p.type != "text/calendar" -> p
                else -> {
                    if (p.type != "text/calendar") p.filename?.let { attachments.add(it) }
                    null
                }
            }
        }
        if (p.type == "multipart/alternative") {
            val plain = p.children.firstOrNull { it.type == "text/plain" && !it.isAttachment }
            if (plain != null && looksReal(textOf(plain))) return plain
            val html = p.children.firstOrNull { it.type == "text/html" && !it.isAttachment }
            if (html != null) return html
            // Nested multiparts (alternative containing related containing html).
            for (c in p.children.reversed()) {
                if (c.isMultipart) choose(c, ArrayList())?.let { return it }
            }
            return plain
        }
        var chosen: Part? = null
        for (c in p.children) {
            val got = if (chosen == null) choose(c, attachments) else {
                // Already have a body; anything else that is a file is an attachment.
                collectAttachments(c, attachments)
                null
            }
            if (chosen == null) chosen = got
        }
        return chosen
    }

    private fun collectAttachments(p: Part, out: MutableList<String>) {
        if (p.isMultipart) p.children.forEach { collectAttachments(it, out) }
        else if (p.isAttachment) out.add(p.filename ?: p.type)
        else if (p.type == "message/rfc822") out.add("forwarded message")
    }

    private fun looksReal(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        // A text part that is only a "view online" line is a placeholder for the HTML.
        return !(t.length < 120 && Regex("(?i)(view|read) (this|it|the email|online|in (your )?browser)").containsMatchIn(t))
    }

    /** Decoded text of a leaf, with its charset and, for flowed text, its soft breaks undone. */
    fun textOf(p: Part, truncated: Boolean = false): String {
        var text = decodeBytes(p.body, p.params["charset"], truncated)
        if (p.type == "text/plain" && p.params["format"].equals("flowed", ignoreCase = true)) {
            text = unflow(text, p.params["delsp"].equals("yes", ignoreCase = true))
        }
        return text.replace("\r\n", "\n").replace('\r', '\n')
    }

    /** RFC 3676: a line ending in a space continues on the next line. */
    fun unflow(text: String, delSp: Boolean): String {
        val lines = text.replace("\r\n", "\n").split('\n')
        val out = StringBuilder()
        var i = 0
        while (i < lines.size) {
            var line = lines[i]
            // Space-stuffing is undone; a signature separator is never flowed.
            if (line.startsWith(" ")) line = line.substring(1)
            if (line.endsWith(" ") && line != "-- " && i < lines.size - 1) {
                out.append(if (delSp) line.dropLast(1) else line)
            } else {
                out.append(line).append('\n')
            }
            i++
        }
        return out.toString().trimEnd('\n')
    }

    /* ----------------------------------------------------------------- encodings */

    fun decodeQuotedPrintable(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(raw.size)
        var i = 0
        while (i < raw.size) {
            val b = raw[i].toInt() and 0xff
            if (b == '='.code) {
                // Soft line break: "=" CRLF, "=" LF, possibly with trailing whitespace.
                var j = i + 1
                while (j < raw.size && (raw[j].toInt() == ' '.code || raw[j].toInt() == '\t'.code)) j++
                if (j < raw.size && raw[j].toInt() == '\r'.code) j++
                if (j < raw.size && raw[j].toInt() == '\n'.code) { i = j + 1; continue }
                if (j >= raw.size) { i = j; continue } // "=" at the very end of a capped fetch
                val h = hex(raw.getOrNull(i + 1))
                val l = hex(raw.getOrNull(i + 2))
                if (h >= 0 && l >= 0) { out.write(h * 16 + l); i += 3; continue }
                out.write(b); i++
            } else {
                out.write(b); i++
            }
        }
        return out.toByteArray()
    }

    private fun hex(b: Byte?): Int {
        if (b == null) return -1
        val c = b.toInt().toChar()
        return when (c) {
            in '0'..'9' -> c - '0'
            in 'A'..'F' -> c - 'A' + 10
            in 'a'..'f' -> c - 'a' + 10
            else -> -1
        }
    }

    /** Lenient: ignores line breaks and junk, and drops an incomplete final quantum. */
    fun decodeBase64(raw: ByteArray): ByteArray {
        val sb = StringBuilder(raw.size)
        for (b in raw) {
            val c = (b.toInt() and 0xff).toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '+' || c == '/') sb.append(c)
            else if (c == '=') break
        }
        val usable = sb.length - sb.length % 4
        val tail = sb.length % 4
        val text = sb.substring(0, usable) + when (tail) {
            2 -> sb.substring(usable) + "=="
            3 -> sb.substring(usable) + "="
            else -> ""
        }
        return runCatching { Base64.getDecoder().decode(text) }.getOrDefault(ByteArray(0))
    }

    /* ------------------------------------------------------------------ charsets */

    private val ALIASES = mapOf(
        "utf8" to "UTF-8",
        "unicode-1-1-utf-8" to "UTF-8",
        "ks_c_5601-1987" to "EUC-KR",
        "iso-8859-8-i" to "ISO-8859-8",
        "x-unknown" to "windows-1252",
        "unknown-8bit" to "windows-1252",
        "ascii" to "US-ASCII",
        "cp1252" to "windows-1252",
        "latin1" to "ISO-8859-1",
        "gb2312" to "GBK",
    )

    fun charsetOf(name: String?): Charset? {
        val n = name?.trim()?.trim('"')?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val mapped = ALIASES[n] ?: n
        return runCatching { Charset.forName(mapped) }.getOrNull()
    }

    /**
     * Bytes to text.
     *
     * A declared charset is believed, except that "us-ascii" with 8-bit bytes in it is a
     * lie told by a lot of software, so those go through the undeclared path: UTF-8 when
     * the bytes are valid UTF-8, Windows-1252 when they are not.
     */
    fun decodeBytes(bytes: ByteArray, declared: String?, truncated: Boolean = false): String {
        val cs = charsetOf(declared)
        val eightBit = bytes.any { it < 0 }
        if (cs != null && !(eightBit && (cs == Charsets.US_ASCII))) {
            if (cs == Charsets.UTF_8) return String(bytes, Charsets.UTF_8)
            return String(bytes, cs)
        }
        if (!eightBit) return String(bytes, Charsets.US_ASCII)
        strictUtf8(bytes, truncated)?.let { return it }
        return String(bytes, charsetOf("windows-1252") ?: Charsets.ISO_8859_1)
    }

    private fun strictUtf8(bytes: ByteArray, truncated: Boolean): String? {
        decodeStrict(bytes)?.let { return it }
        if (!truncated) return null
        // A capped fetch can cut one multi-byte character in half. Forgive exactly that:
        // a lead byte in the last three whose sequence is incomplete.
        for (i in bytes.size - 1 downTo maxOf(0, bytes.size - 3)) {
            val b = bytes[i].toInt() and 0xff
            if (b and 0xC0 == 0x80) continue
            val need = when {
                b and 0xE0 == 0xC0 -> 2
                b and 0xF0 == 0xE0 -> 3
                b and 0xF8 == 0xF0 -> 4
                else -> return null
            }
            if (bytes.size - i >= need) return null
            return decodeStrict(bytes.copyOf(i))
        }
        return null
    }

    private fun decodeStrict(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        null
    }

    /* ------------------------------------------------------------------- headers */

    private val ENCODED_WORD = Regex("""=\?([^?\s]+)\?([bBqQ])\?([^?\s]*)\?=""")

    /**
     * RFC 2047 encoded words, with two rules [Addr.decodeWords] does not apply:
     * whitespace between two adjacent encoded words disappears, and adjacent words in the
     * same charset are joined as bytes before decoding, because encoders split a
     * multi-byte character across two words more often than you would hope.
     */
    fun decodeHeader(value: String): String {
        if (!value.contains("=?")) return value
        val sb = StringBuilder()
        var pos = 0
        var pendingCharset: String? = null
        val pending = ByteArrayOutputStream()
        fun flush() {
            val name = pendingCharset ?: return
            val cs = charsetOf(name.substringBefore('*')) ?: Charsets.UTF_8
            sb.append(String(pending.toByteArray(), cs))
            pending.reset()
            pendingCharset = null
        }
        for (m in ENCODED_WORD.findAll(value)) {
            val between = value.substring(pos, m.range.first)
            if (between.isNotBlank() || pendingCharset == null) {
                flush()
                sb.append(between)
            }
            val charset = m.groupValues[1]
            val bytes = when (m.groupValues[2].lowercase()) {
                "b" -> decodeBase64(m.groupValues[3].toByteArray(Charsets.ISO_8859_1))
                else -> decodeQWord(m.groupValues[3])
            }
            if (pendingCharset != null && !pendingCharset.equals(charset, ignoreCase = true)) flush()
            pendingCharset = charset
            pending.write(bytes)
            pos = m.range.last + 1
        }
        flush()
        sb.append(value.substring(pos))
        return sb.toString()
    }

    private fun decodeQWord(s: String): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '_' -> { out.write(' '.code); i++ }
                c == '=' && i + 2 < s.length -> {
                    val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                    if (v != null) { out.write(v); i += 3 } else { out.write(c.code); i++ }
                }
                else -> { out.write(c.code and 0xff); i++ }
            }
        }
        return out.toByteArray()
    }

    /**
     * `text/plain; charset="utf-8"; format=flowed` -> ("text/plain", {charset, format}).
     *
     * Handles quoted values, and RFC 2231 extended values (`filename*=utf-8''na%C3%AFve.pdf`,
     * and the continued form `name*0*=`, `name*1*=`) because attachment names use them.
     */
    fun parseParams(value: String): Pair<String, Map<String, String>> {
        val pieces = splitParams(value)
        val main = pieces.firstOrNull()?.trim()?.lowercase().orEmpty()
        val simple = LinkedHashMap<String, String>()
        val extended = HashMap<String, MutableMap<Int, Pair<String, Boolean>>>()
        for (p in pieces.drop(1)) {
            val eq = p.indexOf('=')
            if (eq <= 0) continue
            var key = p.substring(0, eq).trim().lowercase()
            var v = p.substring(eq + 1).trim()
            if (v.length >= 2 && v.startsWith('"') && v.endsWith('"')) {
                v = v.substring(1, v.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
            }
            val star = key.indexOf('*')
            if (star > 0) {
                val base = key.substring(0, star)
                val rest = key.substring(star + 1)
                val encoded = key.endsWith("*")
                val index = rest.trimEnd('*').toIntOrNull() ?: 0
                extended.getOrPut(base) { HashMap() }[index] = v to encoded
                continue
            }
            simple[key] = v
        }
        for ((base, segs) in extended) {
            var charset: Charset = Charsets.UTF_8
            val bytes = ByteArrayOutputStream()
            for (i in segs.keys.sorted()) {
                val (raw, enc) = segs.getValue(i)
                var v = raw
                if (enc && i == 0 && v.count { it == '\'' } >= 2) {
                    val cs = v.substringBefore('\'')
                    charset = charsetOf(cs) ?: Charsets.UTF_8
                    v = v.substringAfter('\'').substringAfter('\'')
                }
                if (enc) bytes.write(percentDecode(v)) else bytes.write(v.toByteArray(charset))
            }
            simple[base] = String(bytes.toByteArray(), charset)
        }
        return main to simple
    }

    private fun splitParams(value: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c == '\\' && quoted && i + 1 < value.length -> { sb.append(c).append(value[i + 1]); i += 2; continue }
                c == '"' -> { quoted = !quoted; sb.append(c) }
                c == ';' && !quoted -> { out.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out.filter { it.isNotBlank() }
    }

    private fun percentDecode(s: String): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (v != null) { out.write(v); i += 3; continue }
            }
            out.write(c.code and 0xff)
            i++
        }
        return out.toByteArray()
    }

    private fun latin1(b: ByteArray) = String(b, Charsets.ISO_8859_1)
}
