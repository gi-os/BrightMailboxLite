package com.gios.brightmailboxlite.smtp

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SmtpClientTest {

    private fun client(server: String, sent: ByteArrayOutputStream) =
        SmtpClient(ByteArrayInputStream(server.toByteArray()), sent, "test")

    @Test fun `auth plain and a full send`() {
        val sent = ByteArrayOutputStream()
        val c = client(
            "220 smtp.gmail.com ESMTP\r\n" +
                "250-smtp.gmail.com at your service\r\n250-SIZE 35882577\r\n250-8BITMIME\r\n" +
                "250-AUTH LOGIN PLAIN XOAUTH2\r\n250 SMTPUTF8\r\n" +
                "235 2.7.0 Accepted\r\n" +
                "250 2.1.0 OK\r\n250 2.1.5 OK\r\n354 Go ahead\r\n250 2.0.0 OK queued\r\n221 bye\r\n",
            sent,
        )
        c.greeting()
        c.ehlo()
        c.login("you@gmail.com", "secret")
        c.send("you@gmail.com", listOf("alex@x.com"), "Subject: café\r\n\r\nline\r\n.dot first")
        c.quit()
        val lines = String(sent.toByteArray(), Charsets.UTF_8).split("\r\n")
        assertEquals("EHLO test", lines[0])
        // base64("\u0000you@gmail.com\u0000secret")
        assertEquals("AUTH PLAIN AHlvdUBnbWFpbC5jb20Ac2VjcmV0", lines[1])
        assertEquals("MAIL FROM:<you@gmail.com> BODY=8BITMIME", lines[2])
        assertEquals("RCPT TO:<alex@x.com>", lines[3])
        assertEquals("DATA", lines[4])
        assertEquals("Subject: café", lines[5])
        assertEquals("..dot first", lines[8])
        assertEquals(".", lines[9])
        assertEquals("QUIT", lines[10])
    }

    @Test fun `falls back to AUTH LOGIN when PLAIN is not offered`() {
        val sent = ByteArrayOutputStream()
        val c = client(
            "220 hi\r\n250-mx\r\n250 AUTH LOGIN\r\n334 VXNlcm5hbWU6\r\n334 UGFzc3dvcmQ6\r\n235 ok\r\n",
            sent,
        )
        c.greeting()
        c.ehlo()
        c.login("u", "p")
        val lines = String(sent.toByteArray()).split("\r\n")
        assertEquals(listOf("EHLO test", "AUTH LOGIN", "dQ==", "cA=="), lines.take(4))
    }

    @Test fun `a refused password is an auth failure and the message does not echo it`() {
        val sent = ByteArrayOutputStream()
        val c = client("220 hi\r\n250-mx\r\n250 AUTH PLAIN\r\n535 5.7.8 Username and Password not accepted\r\n", sent)
        c.greeting()
        c.ehlo()
        val e = assertFailsWith<SmtpException> { c.login("u", "hunter2") }
        assertTrue(e.isAuthFailure)
        assertTrue(!e.message!!.contains("hunter2"))
    }

    @Test fun `starttls re-runs ehlo on the new streams`() {
        val sent = ByteArrayOutputStream()
        val c = client("220 hi\r\n250-mx\r\n250 STARTTLS\r\n220 go\r\n", sent)
        c.greeting()
        c.ehlo()
        assertTrue(c.supportsStartTls)
        val after = ByteArrayOutputStream()
        c.startTls { ByteArrayInputStream("250-mx\r\n250 AUTH PLAIN\r\n".toByteArray()) to after }
        assertEquals("EHLO test\r\n", String(after.toByteArray()))
        assertTrue(!c.supportsStartTls)
    }

    @Test fun `dot stuffing and line endings`() {
        assertEquals("a\r\n..b\r\nc\r\n", SmtpClient.dotStuff("a\n.b\r\nc"))
        assertEquals("x\r\n", SmtpClient.dotStuff("x\r\n"))
    }
}
