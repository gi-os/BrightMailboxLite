package com.gios.brightmailboxlite.mail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MimeTest {

    private fun msg(s: String) = s.trimIndent().replace("\n", "\r\n").toByteArray(Charsets.UTF_8)

    @Test fun `a plain message is its own body`() {
        val r = Mime.readable(msg("""
            From: Alex <alex@x.com>
            Subject: hi

            See you at eight.
        """))
        assertEquals("See you at eight.", r.text)
        assertFalse(r.fromHtml)
    }

    @Test fun `folded headers unfold and encoded words decode`() {
        val h = Mime.parseHeaders(msg("""
            Subject: =?UTF-8?Q?R=C3=A9union?=
             =?UTF-8?Q?_de_lundi?=
            From: =?ISO-8859-1?Q?Andr=E9?= <andre@x.fr>
            List-Unsubscribe: <https://x/u>
            List-Unsubscribe: <mailto:u@x>
        """))
        assertEquals("Réunion de lundi", h.get("subject"))
        assertEquals("André <andre@x.fr>", h.get("from"))
        assertEquals("<https://x/u>, <mailto:u@x>", h.toMap()["list-unsubscribe"])
    }

    @Test fun `a multi-byte character split across two encoded words survives`() {
        // "é" is C3 A9; an encoder that wraps by length can put each byte in its own word.
        assertEquals("café!", Mime.decodeHeader("=?UTF-8?Q?caf=C3?= =?UTF-8?Q?=A9!?="))
        assertEquals("café", Mime.decodeHeader("=?UTF-8?B?Y2Fmw6k=?="))
        assertEquals("a b", Mime.decodeHeader("a b"))
    }

    @Test fun `alternative prefers plain text`() {
        val r = Mime.readable(msg("""
            Content-Type: multipart/alternative; boundary="b1"

            --b1
            Content-Type: text/plain; charset=utf-8
            Content-Transfer-Encoding: quoted-printable

            Caf=C3=A9 at nine?=20
            Soft=
             break.
            --b1
            Content-Type: text/html; charset=utf-8

            <p>Caf&eacute; at nine?</p>
            --b1--
        """))
        assertEquals("Café at nine? \nSoft break.", r.text)
        assertFalse(r.fromHtml)
    }

    @Test fun `alternative falls back to html when plain is a placeholder`() {
        val r = Mime.readable(msg("""
            Content-Type: multipart/alternative; boundary=XX

            --XX
            Content-Type: text/plain

            View this email in your browser.
            --XX
            Content-Type: text/html; charset=windows-1252
            Content-Transfer-Encoding: base64

            PHA+SGVsbG8gdGhlcmU8L3A+
            --XX--
        """))
        assertEquals("Hello there", r.text)
        assertTrue(r.fromHtml)
    }

    @Test fun `mixed with an attachment reads the body and names the file`() {
        val r = Mime.readable(msg("""
            Content-Type: multipart/mixed; boundary="m"

            preamble is ignored
            --m
            Content-Type: multipart/alternative; boundary="a"

            --a
            Content-Type: text/plain; charset="us-ascii"

            Deck attached.
            --a
            Content-Type: text/html

            <b>Deck attached.</b>
            --a--
            --m
            Content-Type: application/pdf; name="deck.pdf"
            Content-Disposition: attachment; filename="deck.pdf"
            Content-Transfer-Encoding: base64

            JVBERi0xLjQK
            --m--
        """))
        assertEquals("Deck attached.", r.text)
        assertEquals(listOf("deck.pdf"), r.attachments)
    }

    @Test fun `a body cut off by the fetch cap still reads`() {
        val full = msg("""
            Content-Type: multipart/mixed; boundary="m"

            --m
            Content-Type: text/plain; charset=utf-8
            Content-Transfer-Encoding: base64

            SGVsbG8sIHRoaXMgaXMgYSBsb25nIG1lc3NhZ2U=
        """)
        // Cut mid-quantum, no closing boundary.
        val cut = full.copyOf(full.size - 7)
        val r = Mime.readable(cut, truncated = true)
        assertTrue(r.text.startsWith("Hello, this is a"), r.text)
        // A UTF-8 character cut in half by the cap costs that character, not the charset.
        val utf8 = "Content-Type: text/plain\r\n\r\nd\u00e9j\u00e0 vu \u00e9".toByteArray(Charsets.UTF_8)
        assertEquals("d\u00e9j\u00e0 vu", Mime.readable(utf8.copyOf(utf8.size - 1), truncated = true).text)
    }

    @Test fun `undeclared 8-bit text is UTF-8 when valid, Windows-1252 when not`() {
        assertEquals("café", Mime.decodeBytes("café".toByteArray(Charsets.UTF_8), null))
        assertEquals("café", Mime.decodeBytes(byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte()), null))
        // "us-ascii" with 8-bit bytes in it is a common lie.
        assertEquals("café", Mime.decodeBytes("café".toByteArray(Charsets.UTF_8), "us-ascii"))
        assertEquals("café", Mime.decodeBytes(byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte()), "iso-8859-1"))
        assertEquals("x", Mime.decodeBytes("x".toByteArray(), "no-such-charset"))
    }

    @Test fun `quoted-printable decoding`() {
        fun qp(s: String) = String(Mime.decodeQuotedPrintable(s.toByteArray(Charsets.ISO_8859_1)), Charsets.UTF_8)
        assertEquals("a=b", qp("a=3Db"))
        assertEquals("joined", qp("joi=\r\nned"))
        assertEquals("joined", qp("joi=  \nned"))
        assertEquals("=ZZ", qp("=ZZ"))
        assertEquals("end", qp("end="))
        assertEquals("é", qp("=C3=A9"))
    }

    @Test fun `base64 decoding is lenient`() {
        fun b(s: String) = String(Mime.decodeBase64(s.toByteArray()), Charsets.UTF_8)
        assertEquals("hello", b("aGVs\r\nbG8="))
        assertEquals("hello", b("aGVsbG8"))
        assertEquals("hel", b("aGVsb"))
        assertEquals("", b(""))
    }

    @Test fun `content-type parameters, quoted and RFC 2231`() {
        val (t, p) = Mime.parseParams("Text/Plain; charset=\"UTF-8\"; format=flowed; delsp=yes")
        assertEquals("text/plain", t)
        assertEquals("UTF-8", p["charset"])
        assertEquals("flowed", p["format"])
        val (_, q) = Mime.parseParams("attachment; filename*=utf-8''na%C3%AFve%20plan.pdf")
        assertEquals("naïve plan.pdf", q["filename"])
        val (_, r) = Mime.parseParams("attachment; filename*0=\"long\"; filename*1=\"name.txt\"")
        assertEquals("longname.txt", r["filename"])
        val (_, s) = Mime.parseParams("multipart/mixed; boundary=\"a;b\"")
        assertEquals("a;b", s["boundary"])
    }

    @Test fun `format=flowed unwraps soft breaks`() {
        assertEquals("one two\nthree", Mime.unflow("one \ntwo\nthree", delSp = false))
        assertEquals("onetwo", Mime.unflow("one \ntwo", delSp = true))
        assertEquals("sig\n-- \nGio", Mime.unflow("sig\n-- \nGio", delSp = false))
    }

    @Test fun `a boundary string inside a line is not a delimiter`() {
        val parts = Mime.splitMultipart("--b\r\nfirst x--b\r\n--b\r\nsecond\r\n--b--\r\n", "b")
        assertEquals(listOf("first x--b", "second"), parts)
    }

    @Test fun `html only message is converted`() {
        val r = Mime.readable(msg("""
            Content-Type: text/html; charset=utf-8

            <div>Hi,<br>Thanks!</div>
        """))
        assertEquals("Hi,\nThanks!", r.text)
        assertTrue(r.fromHtml)
    }
}
