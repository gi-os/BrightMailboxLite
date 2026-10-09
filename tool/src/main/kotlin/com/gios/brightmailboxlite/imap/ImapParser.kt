package com.gios.brightmailboxlite.imap

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream

/** One value in an IMAP response (RFC 3501 section 4). */
sealed interface ImapValue {
    /** An atom, a number, a flag like `\Seen`, or a section like `BODY[HEADER]`. */
    data class Atom(val text: String) : ImapValue

    /** A quoted string or a literal, kept as bytes because mail is not always UTF-8. */
    class Bytes(val bytes: ByteArray) : ImapValue {
        fun string(): String = String(bytes, Charsets.UTF_8)
        override fun toString(): String = "Bytes(${String(bytes, Charsets.ISO_8859_1)})"
    }

    data object Nil : ImapValue

    data class ListV(val items: List<ImapValue>) : ImapValue
}

/** Text of an atom or string value, or null for NIL and lists. */
fun ImapValue.text(): String? = when (this) {
    is ImapValue.Atom -> text
    is ImapValue.Bytes -> string()
    else -> null
}

/**
 * One server response.
 *
 *  - Tagged:       `A3 OK [READ-WRITE] SELECT completed`  -> tag "A3", status "OK"
 *  - Untagged:     `* 18 EXISTS`                          -> tag "*", number 18, kind "EXISTS"
 *                  `* SEARCH 4 9 12`                      -> kind "SEARCH", values [4, 9, 12]
 *                  `* OK [UIDVALIDITY 3857529045] UIDs`   -> status "OK", code "UIDVALIDITY 3857529045"
 *  - Continuation: `+ Ready for literal data`             -> tag "+"
 */
data class ImapResponse(
    val tag: String,
    val status: String? = null,
    /** The bracketed response code, without brackets, if any. */
    val code: String? = null,
    /** Human text after a status. */
    val text: String = "",
    val number: Long? = null,
    val kind: String? = null,
    val values: List<ImapValue> = emptyList(),
) {
    val isContinuation: Boolean get() = tag == "+"
    val isUntagged: Boolean get() = tag == "*"
}

/**
 * Reads whole responses off the wire.
 *
 * A response is a line, unless that line ends in a literal announcement `{n}`, in which
 * case exactly n bytes follow and then the line continues. The bytes are returned exactly
 * as received — CRLFs, announcements and all — and [ImapParser] does the rest. Keeping the
 * wire format intact means the parser can be tested on captured server output.
 */
class ImapReader(private val input: InputStream, private val maxBytes: Int = 8 * 1024 * 1024) {

    fun readResponse(): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val line = readLine()
            out.write(line)
            val n = literalLength(line)
            if (n < 0) return out.toByteArray()
            if (out.size() + n > maxBytes) throw ImapException("response too large ($n bytes)")
            val buf = ByteArray(n)
            var read = 0
            while (read < n) {
                val r = input.read(buf, read, n - read)
                if (r < 0) throw EOFException("connection closed inside a literal")
                read += r
            }
            out.write(buf)
        }
    }

    /** One line including its CRLF (or LF). */
    private fun readLine(): ByteArray {
        val out = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (out.size() == 0) throw EOFException("connection closed")
                return out.toByteArray()
            }
            out.write(b)
            if (b == '\n'.code) return out.toByteArray()
            if (out.size() > maxBytes) throw ImapException("line too long")
        }
    }

    companion object {
        /** `... {123}\r\n` or `... {123+}\r\n` -> 123; otherwise -1. */
        fun literalLength(line: ByteArray): Int {
            var end = line.size
            while (end > 0 && (line[end - 1] == '\n'.code.toByte() || line[end - 1] == '\r'.code.toByte())) end--
            if (end < 3 || line[end - 1] != '}'.code.toByte()) return -1
            var i = end - 2
            if (line[i] == '+'.code.toByte()) i--
            val digitsEnd = i + 1
            while (i >= 0 && line[i] in '0'.code.toByte()..'9'.code.toByte()) i--
            if (i < 0 || line[i] != '{'.code.toByte() || i + 1 == digitsEnd) return -1
            return String(line, i + 1, digitsEnd - i - 1, Charsets.ISO_8859_1).toIntOrNull() ?: -1
        }
    }
}

class ImapException(message: String) : Exception(message)

/** Pure parsing of a response's bytes. No I/O. */
object ImapParser {

    private val STATUS = setOf("OK", "NO", "BAD", "BYE", "PREAUTH")

    fun parse(raw: ByteArray): ImapResponse {
        val s = String(raw, Charsets.ISO_8859_1)
        val lex = Lexer(s)
        val tag = lex.word()
        if (tag == "+") return ImapResponse("+", text = lex.restOfLine())
        lex.skipSpaces()
        val first = lex.word()
        if (first.uppercase() in STATUS) {
            return status(tag, first.uppercase(), lex.restOfLine())
        }
        if (tag != "*") {
            // A tagged response is always a status; anything else is a protocol error.
            return ImapResponse(tag, status = "BAD", text = lex.restOfLine())
        }
        val number = first.toLongOrNull()
        val kind: String
        if (number != null) {
            lex.skipSpaces()
            kind = lex.word().uppercase()
        } else {
            kind = first.uppercase()
        }
        val values = ArrayList<ImapValue>()
        while (true) {
            lex.skipSpaces()
            if (lex.atEnd()) break
            val v = lex.value() ?: break
            values.add(v)
        }
        return ImapResponse(tag, number = number, kind = kind, values = values)
    }

    private fun status(tag: String, status: String, rest: String): ImapResponse {
        val t = rest.trim()
        if (t.startsWith("[")) {
            val close = t.indexOf(']')
            if (close > 0) {
                return ImapResponse(tag, status = status, code = t.substring(1, close), text = t.substring(close + 1).trim())
            }
        }
        return ImapResponse(tag, status = status, text = t)
    }

    private class Lexer(val s: String) {
        var i = 0

        fun atEnd(): Boolean = i >= s.length || s[i] == '\r' || s[i] == '\n'

        fun skipSpaces() { while (i < s.length && s[i] == ' ') i++ }

        fun word(): String {
            val start = i
            while (i < s.length && s[i] != ' ' && s[i] != '\r' && s[i] != '\n') i++
            return s.substring(start, i)
        }

        fun restOfLine(): String {
            skipSpaces()
            val start = i
            while (i < s.length && s[i] != '\r' && s[i] != '\n') i++
            // Status text is human-readable; decode as UTF-8 for display.
            return String(s.substring(start, i).toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
        }

        fun value(): ImapValue? {
            if (i >= s.length) return null
            return when (val c = s[i]) {
                '(' -> {
                    i++
                    val items = ArrayList<ImapValue>()
                    while (true) {
                        skipSpacesAndBreaks()
                        if (i >= s.length) break
                        if (s[i] == ')') { i++; break }
                        val v = value() ?: break
                        items.add(v)
                    }
                    ImapValue.ListV(items)
                }
                ')' -> null
                '"' -> quoted()
                '{' -> literal() ?: atom()
                else -> if (c == '\r' || c == '\n') null else atom()
            }
        }

        private fun skipSpacesAndBreaks() {
            // A list never legally spans a bare line break, but a literal's trailing data
            // continues on the "same" line after its bytes; spaces are all we expect here.
            while (i < s.length && s[i] == ' ') i++
        }

        private fun quoted(): ImapValue {
            i++ // opening quote
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) { sb.append(s[i + 1]); i += 2; continue }
                if (c == '"') { i++; break }
                sb.append(c)
                i++
            }
            return ImapValue.Bytes(sb.toString().toByteArray(Charsets.ISO_8859_1))
        }

        private fun literal(): ImapValue? {
            val close = s.indexOf('}', i)
            if (close < 0) return null
            val n = s.substring(i + 1, close).trimEnd('+').toIntOrNull() ?: return null
            var p = close + 1
            if (p < s.length && s[p] == '\r') p++
            if (p < s.length && s[p] == '\n') p++
            val end = (p + n).coerceAtMost(s.length)
            val bytes = s.substring(p, end).toByteArray(Charsets.ISO_8859_1)
            i = end
            return ImapValue.Bytes(bytes)
        }

        /**
         * An atom, with one extension: a `[` opens a section that runs to its matching
         * `]` whatever it contains, so `BODY[HEADER.FIELDS (TO CC)]<0>` is one token.
         */
        private fun atom(): ImapValue {
            val start = i
            var depth = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '[') depth++
                else if (c == ']') depth = (depth - 1).coerceAtLeast(0)
                else if (depth == 0 && (c == ' ' || c == '(' || c == ')' || c == '\r' || c == '\n')) break
                i++
            }
            val text = s.substring(start, i)
            return if (text.equals("NIL", ignoreCase = true)) ImapValue.Nil else ImapValue.Atom(text)
        }
    }

    /**
     * The pairs inside a FETCH response, keyed by uppercased item name.
     *
     * `* 12 FETCH (UID 5 FLAGS (\Seen) BODY[HEADER] {342}...)` ->
     * {UID: 5, FLAGS: (\Seen), BODY[HEADER]: <bytes>}
     */
    fun fetchItems(r: ImapResponse): Map<String, ImapValue> {
        val list = r.values.firstOrNull() as? ImapValue.ListV ?: return emptyMap()
        val out = LinkedHashMap<String, ImapValue>()
        var k = 0
        while (k + 1 < list.items.size) {
            val key = (list.items[k] as? ImapValue.Atom)?.text?.uppercase()
            if (key != null) out[key] = list.items[k + 1]
            k += 2
        }
        return out
    }

    /** The first FETCH item whose name starts with [prefix], e.g. "BODY[" matches `BODY[]<0>`. */
    fun Map<String, ImapValue>.section(prefix: String): ImapValue? =
        entries.firstOrNull { it.key.startsWith(prefix) }?.value
}
