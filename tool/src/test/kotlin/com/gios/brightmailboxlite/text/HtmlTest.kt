package com.gios.brightmailboxlite.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HtmlTest {

    @Test fun `paragraphs become blank-line separated text`() {
        assertEquals("Hello Gio,\n\nSee you Saturday.", Html.toText("<p>Hello Gio,</p><p>See you Saturday.</p>"))
    }

    @Test fun `br is a line break and whitespace collapses like a browser`() {
        assertEquals("one\ntwo three", Html.toText("one<br>two\n\n   three"))
        assertEquals("a\n\nb", Html.toText("a<br/><br/><br/><br/>b"))
    }

    @Test fun `script, style, head and comments are dropped whole`() {
        val html = """
            <html><head><title>Receipt</title><style>p { color: red }</style></head>
            <body><!--[if mso]><table><tr><td>outlook only</td></tr></table><![endif]-->
            <script>var x = "<p>nope</p>";</script><p>Visible</p></body></html>
        """.trimIndent()
        assertEquals("Visible", Html.toText(html))
    }

    @Test fun `entities decode, named and numeric`() {
        assertEquals("Tom & Jerry — café ’ é é <tag>",
            Html.toText("Tom &amp; Jerry &mdash; caf&eacute; &rsquo; &#233; &#xE9; &lt;tag&gt;"))
    }

    @Test fun `unknown entities are left as written`() {
        assertEquals("a &bogus; b & c", Html.toText("a &bogus; b & c"))
    }

    @Test fun `list items get bullets and table rows get lines`() {
        val list = Html.toText("<ul><li>milk</li><li>eggs</li></ul>")
        assertEquals("• milk\n• eggs", list)
        val table = Html.toText("<table><tr><td>Total</td><td>$12.00</td></tr><tr><td>Tax</td><td>$1.00</td></tr></table>")
        assertEquals("Total $12.00\nTax $1.00", table)
    }

    @Test fun `links keep their text, images keep a short alt`() {
        assertEquals("Read the report", Html.toText("""<a href="https://x.com/track?id=1">Read the report</a>"""))
        assertEquals("Logo", Html.toText("""<img src="x.png" alt="Logo">"""))
        assertEquals("", Html.toText("""<img src="pixel.gif" width="1" height="1">"""))
    }

    @Test fun `pre keeps its whitespace`() {
        assertEquals("a  b\n  c", Html.toText("<pre>a  b\n  c</pre>"))
    }

    @Test fun `attributes containing angle brackets do not end the tag`() {
        assertEquals("ok", Html.toText("""<div title="a > b">ok</div>"""))
    }

    @Test fun `a bare less-than sign survives`() {
        assertTrue(Html.toText("1 < 2").contains("1 < 2"))
    }

    @Test fun `unterminated markup never throws`() {
        for (s in listOf("<", "<p", "<a href=\"x", "<!--", "<script>", "&", "&#;", "&#x110000;")) {
            Html.toText(s)
        }
    }

    @Test fun `a typical newsletter reads top to bottom`() {
        val html = """
            <table width="600"><tr><td>
              <h1>Weekly&nbsp;digest</h1>
              <p>Three things happened.</p>
              <div>First</div><div>Second</div>
              <p style="font-size:10px">You are receiving this email because you signed up.
              <a href="https://x/unsub">Unsubscribe</a></p>
            </td></tr></table>
        """.trimIndent()
        val t = Html.toText(html)
        assertTrue(t.startsWith("Weekly digest\n\nThree things happened.\n\nFirst\nSecond"), t)
        assertFalse(t.contains("<"))
        assertFalse(t.contains("\n\n\n"))
    }
}
