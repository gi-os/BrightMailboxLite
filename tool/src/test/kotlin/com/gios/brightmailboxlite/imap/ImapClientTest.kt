package com.gios.brightmailboxlite.imap

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The client against a scripted server. Tags are deterministic (A001, A002...), so a
 * whole conversation can be written down in advance and the commands checked after.
 */
class ImapClientTest {

    private class Script(server: String) {
        val sent = ByteArrayOutputStream()
        val client = ImapClient(ByteArrayInputStream(server.toByteArray(Charsets.ISO_8859_1)), sent)
        fun sentLines() = String(sent.toByteArray(), Charsets.UTF_8).split("\r\n").filter { it.isNotEmpty() }
    }

    @Test fun `login, select, search, fetch, store`() {
        val header = "From: Alex <alex@x.com>\r\nSubject: hi\r\n\r\n"
        val s = Script(
            "* OK Gimap ready\r\n" +
                "A001 OK user authenticated\r\n" +
                "* FLAGS (\\Answered \\Seen)\r\n* 3 EXISTS\r\n* OK [UIDVALIDITY 11] UIDs valid.\r\n" +
                "* OK [UIDNEXT 40] Predicted next UID.\r\nA002 OK [READ-WRITE] INBOX selected.\r\n" +
                "* SEARCH 31 38 39\r\nA003 OK SEARCH completed\r\n" +
                "* 2 FETCH (UID 38 FLAGS () INTERNALDATE \"09-Oct-2026 08:00:00 +0000\" BODY[HEADER] {${header.length}}\r\n$header)\r\n" +
                "* 3 FETCH (UID 39 FLAGS (\\Seen) INTERNALDATE \"09-Oct-2026 09:00:00 +0000\" BODY[HEADER] {${header.length}}\r\n$header)\r\n" +
                "A004 OK Success\r\n" +
                "A005 OK Success\r\n",
        )
        s.client.greeting()
        s.client.login("you@gmail.com", "abcd efgh")
        val info = s.client.select("INBOX")
        assertEquals(11L, info.uidValidity)
        assertEquals(3L, info.exists)
        assertEquals(40L, info.uidNext)
        assertEquals(listOf(31L, 38L, 39L), s.client.uidSearch("UID 31:*"))
        val h = s.client.fetchHeaders(listOf(38, 39))
        assertEquals(listOf(38L, 39L), h.map { it.uid })
        assertTrue("\\Seen" in h[1].flags)
        assertContentEquals(header.toByteArray(), h[0].header)
        s.client.markSeen(listOf(38))

        val lines = s.sentLines()
        assertEquals("A001 LOGIN \"you@gmail.com\" \"abcd efgh\"", lines[0])
        assertEquals("A002 SELECT \"INBOX\"", lines[1])
        assertEquals("A003 UID SEARCH UID 31:*", lines[2])
        assertEquals("A004 UID FETCH 38:39 (UID FLAGS INTERNALDATE BODY.PEEK[HEADER])", lines[3])
        assertEquals("A005 UID STORE 38 +FLAGS.SILENT (\\Seen)", lines[4])
    }

    @Test fun `a password with quotes is escaped, a non-ascii one goes as a literal`() {
        val s = Script("* OK hi\r\nA001 OK\r\n+ go ahead\r\nA002 OK\r\n")
        s.client.greeting()
        s.client.login("a@b.c", "p\"w\\d")
        s.client.login("a@b.c", "pässword")
        val text = String(s.sent.toByteArray(), Charsets.UTF_8)
        assertTrue(text.startsWith("A001 LOGIN \"a@b.c\" \"p\\\"w\\\\d\"\r\n"), text)
        assertTrue(text.contains("A002 LOGIN \"a@b.c\" {9}\r\npässword\r\n"), text)
    }

    @Test fun `a refused login is reported as an auth failure`() {
        val s = Script("* OK hi\r\nA001 NO [AUTHENTICATIONFAILED] Invalid credentials (Failure)\r\n")
        s.client.greeting()
        val e = assertFailsWith<ImapCommandException> { s.client.login("a@b.c", "wrong") }
        assertTrue(e.isAuthFailure)
        assertEquals("Invalid credentials (Failure)", e.message)
    }

    @Test fun `list finds the sent folder by its special-use flag`() {
        val s = Script(
            "* OK hi\r\n" +
                "* LIST (\\HasNoChildren) \"/\" \"INBOX\"\r\n" +
                "* LIST (\\All \\HasNoChildren) \"/\" \"[Gmail]/All Mail\"\r\n" +
                "* LIST (\\HasNoChildren \\Sent) \"/\" \"[Gmail]/Sent Mail\"\r\n" +
                "A001 OK Success\r\n",
        )
        s.client.greeting()
        val boxes = s.client.list()
        assertEquals("[Gmail]/Sent Mail", boxes.first { "\\sent" in it.flags }.name)
    }

    @Test fun `a partial body fetch returns the bytes`() {
        val body = "Subject: x\r\n\r\nhello"
        val s = Script("* OK hi\r\n* 1 FETCH (UID 5 BODY[]<0> {${body.length}}\r\n$body)\r\nA001 OK\r\n")
        s.client.greeting()
        assertEquals(body, String(s.client.fetchBody(5, 1000)!!, Charsets.ISO_8859_1))
        assertEquals("A001 UID FETCH 5 (UID BODY.PEEK[]<0.1000>)", s.sentLines()[0])
    }

    @Test fun `a BYE greeting is refused`() {
        val s = Script("* BYE too many connections\r\n")
        assertFailsWith<ImapException> { s.client.greeting() }
    }
}
