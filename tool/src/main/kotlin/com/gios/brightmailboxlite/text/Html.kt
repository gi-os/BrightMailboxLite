package com.gios.brightmailboxlite.text

/**
 * HTML to readable plain text, without a parser library.
 *
 * Mail HTML is the worst HTML there is: nested layout tables, inline styles, comments
 * carrying Outlook conditionals, tracking pixels, and whitespace that only means
 * something because a browser collapses it. This does what a browser would do with the
 * text and nothing with the layout:
 *
 *  - `script`, `style`, `head`, `title` and comments are dropped whole.
 *  - Whitespace collapses to one space, except inside `pre`.
 *  - Block elements break lines; paragraphs and headings leave a blank line.
 *  - Table cells are separated by a space, rows by a line.
 *  - List items get a bullet.
 *  - Links keep their text. A link whose text is itself an address is kept as is.
 *  - Images keep a short alt text, if they have one; tracking pixels have none.
 *  - Named and numeric character references are decoded.
 *
 * Plain Kotlin, no Android imports, so it runs in unit tests on a JVM.
 */
object Html {

    private val DROP = setOf("script", "style", "head", "title", "noscript", "template", "svg", "xml")

    private val PARAGRAPH = setOf(
        "p", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "table", "ul", "ol", "dl", "pre", "hr",
    )

    private val LINE = setOf(
        "div", "tr", "li", "dt", "dd", "section", "article", "header", "footer", "center",
        "address", "form", "fieldset", "tbody", "thead", "tfoot", "main", "nav", "aside", "figure",
    )

    fun toText(html: String): String {
        val out = Out()
        var i = 0
        val n = html.length
        var pre = 0
        while (i < n) {
            val c = html[i]
            if (c != '<') {
                val next = html.indexOf('<', i).let { if (it < 0) n else it }
                out.text(decodeEntities(html.substring(i, next)), pre > 0)
                i = next
                continue
            }
            // Comment, including Outlook's <!--[if mso]> blocks.
            if (html.startsWith("<!--", i)) {
                val end = html.indexOf("-->", i + 4)
                i = if (end < 0) n else end + 3
                continue
            }
            // Doctype, CDATA, processing instructions.
            if (i + 1 < n && (html[i + 1] == '!' || html[i + 1] == '?')) {
                val end = html.indexOf('>', i)
                i = if (end < 0) n else end + 1
                continue
            }
            val tag = readTag(html, i)
            if (tag == null) {
                // A bare '<' in text, e.g. "a < b".
                out.text("<", pre > 0)
                i++
                continue
            }
            i = tag.end
            val name = tag.name
            if (!tag.closing && name in DROP) {
                val close = indexOfIgnoreCase(html, "</$name", i)
                i = if (close < 0) n else (html.indexOf('>', close).let { if (it < 0) n else it + 1 })
                continue
            }
            when {
                name == "br" -> out.newline()
                name == "pre" -> {
                    out.block(2)
                    pre = if (tag.closing) (pre - 1).coerceAtLeast(0) else pre + 1
                }
                name in PARAGRAPH -> out.block(2)
                name in LINE -> {
                    out.block(1)
                    if (name == "li" && !tag.closing) out.raw("• ")
                }
                name == "td" || name == "th" -> if (tag.closing) out.space()
                name == "img" -> {
                    val alt = tag.attrs["alt"]?.let { decodeEntities(it).trim() }.orEmpty()
                    if (alt.isNotEmpty() && alt.length <= 60) out.text(alt, false)
                }
                else -> Unit
            }
        }
        return out.finish()
    }

    private class Tag(val name: String, val closing: Boolean, val attrs: Map<String, String>, val end: Int)

    /** Read one tag starting at [start] (which is '<'), or null if it is not a tag. */
    private fun readTag(s: String, start: Int): Tag? {
        var i = start + 1
        val closing = i < s.length && s[i] == '/'
        if (closing) i++
        val nameStart = i
        while (i < s.length && (s[i].isLetterOrDigit() || s[i] == ':' || s[i] == '-')) i++
        if (i == nameStart) return null
        val name = s.substring(nameStart, i).lowercase()
        val attrs = HashMap<String, String>()
        while (i < s.length) {
            while (i < s.length && s[i].isWhitespace()) i++
            if (i >= s.length) break
            if (s[i] == '>') return Tag(name, closing, attrs, i + 1)
            if (s[i] == '/') { i++; continue }
            val aStart = i
            while (i < s.length && !s[i].isWhitespace() && s[i] != '=' && s[i] != '>' && s[i] != '/') i++
            val key = s.substring(aStart, i).lowercase()
            while (i < s.length && s[i].isWhitespace()) i++
            var value = ""
            if (i < s.length && s[i] == '=') {
                i++
                while (i < s.length && s[i].isWhitespace()) i++
                if (i < s.length && (s[i] == '"' || s[i] == '\'')) {
                    val q = s[i]
                    val end = s.indexOf(q, i + 1)
                    if (end < 0) return Tag(name, closing, attrs, s.length)
                    value = s.substring(i + 1, end)
                    i = end + 1
                } else {
                    val vStart = i
                    while (i < s.length && !s[i].isWhitespace() && s[i] != '>') i++
                    value = s.substring(vStart, i)
                }
            }
            if (key.isNotEmpty()) attrs[key] = value
            if (i == aStart) i++ // never stall on a stray character
        }
        return Tag(name, closing, attrs, s.length)
    }

    private fun indexOfIgnoreCase(s: String, needle: String, from: Int): Int =
        s.indexOf(needle, from, ignoreCase = true)

    /** Accumulates text with browser-like whitespace and at most one blank line in a row. */
    private class Out {
        private val sb = StringBuilder()
        private var pendingSpace = false
        /** Newlines owed before the next text: 0, 1 or 2. */
        private var pendingBreak = 0

        fun text(t: String, preformatted: Boolean) {
            if (preformatted) {
                flushBreak()
                if (pendingSpace) { sb.append(' '); pendingSpace = false }
                sb.append(t.replace("\r\n", "\n"))
                return
            }
            for (ch in t) {
                if (ch.isWhitespace()) {
                    if (sb.isNotEmpty() && pendingBreak == 0) pendingSpace = true
                } else {
                    flushBreak()
                    if (pendingSpace) { sb.append(' '); pendingSpace = false }
                    sb.append(ch)
                }
            }
        }

        fun raw(t: String) {
            flushBreak()
            pendingSpace = false
            sb.append(t)
        }

        fun space() {
            if (sb.isNotEmpty() && pendingBreak == 0) pendingSpace = true
        }

        fun newline() {
            // <br> is a hard break even when repeated, but never more than a blank line.
            pendingSpace = false
            if (pendingBreak < 2) {
                if (pendingBreak == 0 && sb.isNotEmpty()) pendingBreak = 1 else if (sb.isNotEmpty()) pendingBreak = 2
            }
        }

        fun block(lines: Int) {
            pendingSpace = false
            if (sb.isNotEmpty() && lines > pendingBreak) pendingBreak = lines
        }

        private fun flushBreak() {
            if (pendingBreak > 0 && sb.isNotEmpty()) {
                // Trim trailing spaces before the break.
                while (sb.isNotEmpty() && sb[sb.length - 1] == ' ') sb.setLength(sb.length - 1)
                repeat(pendingBreak) { sb.append('\n') }
            }
            pendingBreak = 0
        }

        fun finish(): String =
            sb.toString()
                .replace(' ', ' ')
                .lines()
                .joinToString("\n") { it.trimEnd() }
                .replace(Regex("\n{3,}"), "\n\n")
                .trim()
    }

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“",
        "sbquo" to "‚", "bdquo" to "„",
        "mdash" to "—", "ndash" to "–", "hellip" to "…", "bull" to "•",
        "middot" to "·", "copy" to "©", "reg" to "®", "trade" to "™",
        "euro" to "€", "pound" to "£", "yen" to "¥", "cent" to "¢",
        "deg" to "°", "times" to "×", "divide" to "÷", "laquo" to "«", "raquo" to "»",
        "zwnj" to "", "zwj" to "", "shy" to "", "thinsp" to " ", "ensp" to " ", "emsp" to " ",
        "eacute" to "é", "egrave" to "è", "ecirc" to "ê", "euml" to "ë",
        "aacute" to "á", "agrave" to "à", "acirc" to "â", "auml" to "ä", "aring" to "å", "atilde" to "ã",
        "iacute" to "í", "igrave" to "ì", "icirc" to "î", "iuml" to "ï",
        "oacute" to "ó", "ograve" to "ò", "ocirc" to "ô", "ouml" to "ö", "otilde" to "õ", "oslash" to "ø",
        "uacute" to "ú", "ugrave" to "ù", "ucirc" to "û", "uuml" to "ü",
        "ccedil" to "ç", "ntilde" to "ñ", "szlig" to "ß", "aelig" to "æ", "oelig" to "œ",
        "Eacute" to "É", "Egrave" to "È", "Agrave" to "À", "Ccedil" to "Ç", "Ouml" to "Ö", "Uuml" to "Ü", "Auml" to "Ä",
        "iexcl" to "¡", "iquest" to "¿", "sect" to "§", "para" to "¶", "frac12" to "½", "frac14" to "¼",
    )

    /** `&amp;`, `&#233;`, `&#xE9;`; anything unrecognised is left exactly as written. */
    fun decodeEntities(s: String): String {
        if (s.indexOf('&') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '&') {
                val semi = s.indexOf(';', i + 1)
                if (semi > i + 1 && semi - i <= 12) {
                    val body = s.substring(i + 1, semi)
                    val decoded = when {
                        body.startsWith("#x") || body.startsWith("#X") ->
                            body.substring(2).toIntOrNull(16)?.let(::codePoint)
                        body.startsWith("#") -> body.substring(1).toIntOrNull()?.let(::codePoint)
                        else -> NAMED[body] ?: NAMED[body.lowercase()]
                    }
                    if (decoded != null) {
                        sb.append(decoded)
                        i = semi + 1
                        continue
                    }
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun codePoint(cp: Int): String? =
        if (cp in 1..0x10FFFF && cp !in 0xD800..0xDFFF) String(Character.toChars(cp)) else null
}
