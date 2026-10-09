package com.gios.brightmailboxlite.imap

import com.gios.brightmailboxlite.imap.ImapParser.section
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fixtures are real server output, shortened. */
class ImapParserTest {

    private fun p(s: String) = ImapParser.parse(s.toByteArray(Charsets.ISO_8859_1))

    @Test fun `tagged status with a response code`() {
        val r = p("A003 OK [READ-WRITE] SELECT completed\r\n")
        assertEquals("A003", r.tag)
        assertEquals("OK", r.status)
        assertEquals("READ-WRITE", r.code)
        assertEquals("SELECT completed", r.text)
    }

    @Test fun `untagged OK carries UIDVALIDITY`() {
        val r = p("* OK [UIDVALIDITY 3857529045] UIDs valid\r\n")
        assertEquals("*", r.tag)
        assertEquals("UIDVALIDITY 3857529045", r.code)
    }

    @Test fun `numbered untagged data`() {
        val r = p("* 172 EXISTS\r\n")
        assertEquals(172L, r.number)
        assertEquals("EXISTS", r.kind)
    }

    @Test fun `search results`() {
        val r = p("* SEARCH 2 84 882\r\n")
        assertEquals("SEARCH", r.kind)
        assertEquals(listOf("2", "84", "882"), r.values.map { it.text() })
        assertEquals(0, p("* SEARCH\r\n").values.size)
    }

    @Test fun `list with flags, quoted delimiter and quoted name`() {
        val r = p("* LIST (\\HasNoChildren \\Sent) \"/\" \"[Gmail]/Sent Mail\"\r\n")
        assertEquals("LIST", r.kind)
        val flags = assertIs<ImapValue.ListV>(r.values[0]).items.map { it.text() }
        assertEquals(listOf("\\HasNoChildren", "\\Sent"), flags)
        assertEquals("/", r.values[1].text())
        assertEquals("[Gmail]/Sent Mail", r.values[2].text())
    }

    @Test fun `list with NIL delimiter and an atom name`() {
        val r = p("* LIST (\\Noselect) NIL INBOX\r\n")
        assertEquals(ImapValue.Nil, r.values[1])
        assertEquals("INBOX", r.values[2].text())
    }

    @Test fun `fetch with a header literal`() {
        val header = "From: a@b.com\r\nSubject: hi\r\n\r\n"
        val raw = "* 12 FETCH (UID 4827 FLAGS (\\Seen $\\Label) INTERNALDATE \"09-Oct-2026 08:15:00 +0000\" " +
            "BODY[HEADER] {${header.length}}\r\n$header)\r\n"
        val r = p(raw)
        assertEquals(12L, r.number)
        assertEquals("FETCH", r.kind)
        val items = ImapParser.fetchItems(r)
        assertEquals("4827", items["UID"]?.text())
        assertEquals(listOf("\\Seen", "$\\Label"), assertIs<ImapValue.ListV>(items["FLAGS"]).items.map { it.text() })
        assertEquals("09-Oct-2026 08:15:00 +0000", items["INTERNALDATE"]?.text())
        assertContentEquals(header.toByteArray(), assertIs<ImapValue.Bytes>(items.section("BODY[")).bytes)
    }

    @Test fun `section names with spaces and partial origins stay one token`() {
        val raw = "* 3 FETCH (UID 9 BODY[HEADER.FIELDS (TO CC)] {12}\r\nTo: a@b.com\r\n)\r\n"
        val items = ImapParser.fetchItems(p(raw))
        assertTrue(items.containsKey("BODY[HEADER.FIELDS (TO CC)]"))
        val raw2 = "* 3 FETCH (UID 9 BODY[]<0> {5}\r\nhello)\r\n"
        val items2 = ImapParser.fetchItems(p(raw2))
        assertEquals("hello", items2.section("BODY[")?.text())
    }

    @Test fun `quoted strings unescape`() {
        val r = p("* LIST () \"/\" \"a \\\"quoted\\\" \\\\ name\"\r\n")
        assertEquals("a \"quoted\" \\ name", r.values[2].text())
    }

    @Test fun `literal bytes are kept exactly, including 8-bit`() {
        val body = byteArrayOf(0x63, 0x61, 0x66, 0xC3.toByte(), 0xA9.toByte())
        val raw = "* 1 FETCH (UID 1 BODY[] {5}\r\n".toByteArray(Charsets.ISO_8859_1) + body + ")\r\n".toByteArray()
        val items = ImapParser.fetchItems(ImapParser.parse(raw))
        assertContentEquals(body, assertIs<ImapValue.Bytes>(items["BODY[]"]).bytes)
        assertEquals("caf\u00e9", items["BODY[]"]?.text())
    }

    @Test fun `continuation`() {
        val r = p("+ Ready for additional command text\r\n")
        assertTrue(r.isContinuation)
    }

    @Test fun `capability`() {
        val r = p("* CAPABILITY IMAP4rev1 LITERAL+ SASL-IR LOGIN-REFERRALS ID ENABLE IDLE AUTH=PLAIN\r\n")
        assertEquals("CAPABILITY", r.kind)
        assertTrue(r.values.any { it.text() == "IDLE" })
    }

    @Test fun `reader stitches literals into one response`() {
        val wire = "* 1 FETCH (UID 7 BODY[] {4}\r\nab\r\n)\r\nA001 OK done\r\n"
        val reader = ImapReader(ByteArrayInputStream(wire.toByteArray()))
        val first = reader.readResponse()
        assertEquals("* 1 FETCH (UID 7 BODY[] {4}\r\nab\r\n)\r\n", String(first, Charsets.ISO_8859_1))
        val items = ImapParser.fetchItems(ImapParser.parse(first))
        assertEquals("ab\r\n", items["BODY[]"]?.text())
        assertEquals("A001", ImapParser.parse(reader.readResponse()).tag)
    }

    @Test fun `literal announcements are recognised only at the end of a line`() {
        assertEquals(12, ImapReader.literalLength("* x {12}\r\n".toByteArray()))
        assertEquals(3, ImapReader.literalLength("* x {3+}\r\n".toByteArray()))
        assertEquals(-1, ImapReader.literalLength("* OK {12} not at end\r\n".toByteArray()))
        assertEquals(-1, ImapReader.literalLength("* OK {}\r\n".toByteArray()))
    }

    @Test fun `uid sets compress runs`() {
        assertEquals("1:3,7,9:10", ImapClient.uidSet(listOf(3, 1, 2, 7, 10, 9)))
        assertEquals("5", ImapClient.uidSet(listOf(5)))
    }

    @Test fun `internal dates parse`() {
        assertEquals(837596665000L, ImapClient.parseInternalDate("17-Jul-1996 02:44:25 -0700"))
        assertEquals(837596665000L, ImapClient.parseInternalDate(" 17-Jul-1996 02:44:25 -0700"))
        assertNull(ImapClient.parseInternalDate("garbage"))
    }
}
