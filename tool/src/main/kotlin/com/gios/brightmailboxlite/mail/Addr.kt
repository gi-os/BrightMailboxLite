package com.gios.brightmailboxlite.mail

/**
 * Address and MIME odds and ends. No Android imports — unit-testable.
 */
object Addr {

    /** `"Alex Mercier" <alex@x.com>, bob@y.com` -> the two addresses, lowercased. */
    fun addresses(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return split(raw).mapNotNull { one -> address(one) }
    }

    /** The bare address out of one entry, or null. */
    fun address(entry: String): String? {
        val t = entry.trim()
        if (t.isEmpty()) return null
        val lt = t.lastIndexOf('<')
        val gt = t.lastIndexOf('>')
        val inner = if (lt >= 0 && gt > lt) t.substring(lt + 1, gt) else t
        val a = inner.trim().trim('"', '\'', ',', ';').lowercase()
        return if (a.contains('@') && !a.contains(' ')) a else null
    }

    /** The display name out of one entry, falling back to the local part. */
    fun name(entry: String): String {
        val t = entry.trim()
        val lt = t.lastIndexOf('<')
        if (lt > 0) {
            val n = t.substring(0, lt).trim().trim('"').trim()
            if (n.isNotEmpty()) return n
        }
        val a = address(t) ?: return t
        // "charles.dolige" -> "Charles Dolige". Better than showing an address in a
        // column of human names, and wrong only for people with odd local parts.
        return a.substringBefore('@')
            .split('.', '_', '-')
            .filter { it.isNotBlank() && !it.all { c -> c.isDigit() } }
            .joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
            .ifBlank { a }
    }

    /**
     * Split an address list on commas that are not inside quotes or angle brackets.
     * A naive `split(",")` breaks on `"Doe, Jane" <jane@x.com>`, which is common enough
     * in corporate mail to matter.
     */
    fun split(raw: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQuote = false
        var inAngle = false
        for (c in raw) {
            when {
                c == '"' -> { inQuote = !inQuote; sb.append(c) }
                c == '<' -> { inAngle = true; sb.append(c) }
                c == '>' -> { inAngle = false; sb.append(c) }
                (c == ',' || c == ';') && !inQuote && !inAngle -> {
                    if (sb.isNotBlank()) out.add(sb.toString())
                    sb.setLength(0)
                }
                else -> sb.append(c)
            }
        }
        if (sb.isNotBlank()) out.add(sb.toString())
        return out
    }

    /**
     * RFC 2047 encoded words: `=?UTF-8?B?...?=` and `=?UTF-8?Q?...?=`.
     *
     * Subjects with accents arrive encoded, and "Bozzuto deck =?UTF-8?Q?=E2=80=94?= one
     * note" on the panel is the kind of detail that makes an app feel broken.
     */
    fun decodeWords(s: String): String {
        val re = Regex("""=\?([^?]+)\?([bBqQ])\?([^?]*)\?=""")
        return re.replace(s) { m ->
            val charset = runCatching { charset(m.groupValues[1]) }.getOrNull() ?: Charsets.UTF_8
            val payload = m.groupValues[3]
            runCatching {
                when (m.groupValues[2].lowercase()) {
                    "b" -> String(java.util.Base64.getMimeDecoder().decode(payload), charset)
                    else -> String(decodeQ(payload), charset)
                }
            }.getOrDefault(m.value)
        }.replace(Regex("""\?=\s+=\?"""), "?==?")
    }

    private fun charset(name: String): java.nio.charset.Charset =
        java.nio.charset.Charset.forName(name.substringBefore('*'))

    /** Quoted-printable, with '_' meaning space as RFC 2047 requires. */
    private fun decodeQ(s: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            when {
                s[i] == '_' -> { out.write(' '.code); i++ }
                s[i] == '=' && i + 2 < s.length -> {
                    val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                    if (v != null) { out.write(v); i += 3 } else { out.write(s[i].code); i++ }
                }
                else -> { out.write(s[i].code); i++ }
            }
        }
        return out.toByteArray()
    }

    /**
     * Build an RFC 5322 message.
     *
     * Lite sends one shape only: a plain-text reply. The full app also built
     * multipart/alternative calendar replies and multipart/mixed attachments; neither has
     * a screen here, so neither is built.
     *
     * Headers are encoded as UTF-8 base64 words when they are not plain ASCII, because a
     * bare accented character in a header is not legal and some servers reject it.
     */
    fun rfc5322(from: String, m: Outgoing): String {
        val sb = StringBuilder()
        sb.append("From: ").append(from).append("\r\n")
        sb.append("To: ").append(m.to.joinToString(", ")).append("\r\n")
        if (m.cc.isNotEmpty()) sb.append("Cc: ").append(m.cc.joinToString(", ")).append("\r\n")
        sb.append("Subject: ").append(encodeHeader(m.subject)).append("\r\n")
        m.inReplyTo?.let {
            sb.append("In-Reply-To: ").append(it).append("\r\n")
            sb.append("References: ").append(m.references?.plus(" ")?.plus(it) ?: it).append("\r\n")
        }
        sb.append("MIME-Version: 1.0\r\n")
        val body = m.body.replace("\r\n", "\n").replace("\n", "\r\n")
        sb.append("Content-Type: text/plain; charset=UTF-8\r\n")
        sb.append("Content-Transfer-Encoding: 8bit\r\n")
        sb.append("\r\n")
        sb.append(body)
        return sb.toString()
    }

    fun encodeHeader(s: String): String {
        if (s.all { it.code in 32..126 }) return s
        val b = java.util.Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))
        return "=?UTF-8?B?$b?="
    }

    /** Reply subject, without stacking "Re: Re: Re:". */
    fun replySubject(s: String): String =
        if (s.trimStart().startsWith("re:", ignoreCase = true)) s else "Re: $s"

    /**
     * `Fwd: ` once, and never on top of an existing one.
     *
     * The same rule as [replySubject], for the same reason: a message forwarded three
     * times should not arrive as "Fwd: Fwd: Fwd:". Also recognises "FW:" and the French
     * "TR:", because a forward usually arrives from a client that is not this one.
     */
    fun forwardSubject(s: String): String {
        val t = s.trim()
        val already = Regex("^(fwd?|tr)\\s*:", RegexOption.IGNORE_CASE)
        return if (already.containsMatchIn(t)) t else "Fwd: " + t.ifBlank { "(no subject)" }
    }
}

/** A plain-text message on its way out. */
data class Outgoing(
    val to: List<String>,
    val cc: List<String> = emptyList(),
    val subject: String,
    val body: String,
    /** The Message-ID being answered, angle brackets included. */
    val inReplyTo: String? = null,
    /** The answered message's own References, so the chain accumulates. */
    val references: String? = null,
)
