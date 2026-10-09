package com.gios.brightmailboxlite.smtp

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64

/** A reply from an SMTP server: the code and every text line. */
data class SmtpReply(val code: Int, val lines: List<String>) {
    val ok: Boolean get() = code in 200..399
    val text: String get() = lines.joinToString(" ").trim()
}

class SmtpException(val reply: SmtpReply?, message: String) : Exception(message) {
    val isAuthFailure: Boolean get() = reply?.code == 535 || reply?.code == 534
}

/**
 * Just enough SMTP submission to send one plain-text reply (RFC 5321, 4954, 3207).
 *
 * EHLO, optional STARTTLS, AUTH PLAIN or AUTH LOGIN, MAIL, RCPT, DATA, QUIT. No
 * pipelining, no CHUNKING, no attachments. Like [com.gios.brightmailboxlite.imap.ImapClient]
 * it runs over any pair of streams, so tests drive it with a scripted server.
 */
class SmtpClient(
    private var input: InputStream,
    private var output: OutputStream,
    private val clientName: String = "lightphone",
) {
    private var extensions: Set<String> = emptySet()
    private var authMechanisms: Set<String> = emptySet()

    fun greeting() {
        val r = read()
        if (r.code != 220) throw SmtpException(r, "server refused the connection: ${r.text}")
    }

    fun ehlo() {
        val r = expect("EHLO $clientName", 250)
        // First line is the server's name; the rest are extensions.
        val ext = r.lines.drop(1).map { it.trim().uppercase() }
        extensions = ext.map { it.substringBefore(' ') }.toSet()
        authMechanisms = ext.filter { it.startsWith("AUTH ") || it.startsWith("AUTH=") }
            .flatMap { it.substring(5).split(' ') }
            .filter { it.isNotBlank() }
            .toSet()
    }

    val supportsStartTls: Boolean get() = "STARTTLS" in extensions

    /**
     * STARTTLS, then swap in the encrypted streams and EHLO again — RFC 3207 says
     * everything learned before the upgrade is to be forgotten.
     */
    fun startTls(upgrade: () -> Pair<InputStream, OutputStream>) {
        expect("STARTTLS", 220)
        val (i, o) = upgrade()
        input = i
        output = o
        ehlo()
    }

    /** PLAIN when offered (or when nothing is advertised), else LOGIN. */
    fun login(user: String, password: String) {
        if ("PLAIN" in authMechanisms || authMechanisms.isEmpty()) {
            val token = b64("\u0000$user\u0000$password".toByteArray(Charsets.UTF_8))
            val r = command("AUTH PLAIN $token", sensitive = true)
            if (r.code == 235) return
            if ("LOGIN" !in authMechanisms) throw SmtpException(r, "sign-in refused: ${r.text}")
        }
        if ("LOGIN" in authMechanisms) {
            expect("AUTH LOGIN", 334)
            expect(b64(user.toByteArray(Charsets.UTF_8)), 334, sensitive = true)
            expect(b64(password.toByteArray(Charsets.UTF_8)), 235, sensitive = true)
            return
        }
        throw SmtpException(null, "the server offers no sign-in this app can use")
    }

    fun send(from: String, recipients: List<String>, message: String) {
        val eightBit = message.any { it.code > 127 }
        val bodyParam = if (eightBit && "8BITMIME" in extensions) " BODY=8BITMIME" else ""
        expect("MAIL FROM:<$from>$bodyParam", 250)
        for (r in recipients) expect("RCPT TO:<$r>", 250, 251)
        expect("DATA", 354)
        output.write(dotStuff(message).toByteArray(Charsets.UTF_8))
        output.write(".\r\n".toByteArray(Charsets.US_ASCII))
        output.flush()
        val r = read()
        if (r.code != 250) throw SmtpException(r, "message refused: ${r.text}")
    }

    fun quit() {
        runCatching { command("QUIT") }
    }

    private fun command(line: String, sensitive: Boolean = false): SmtpReply {
        output.write("$line\r\n".toByteArray(Charsets.UTF_8))
        output.flush()
        return read()
    }

    private fun expect(line: String, vararg codes: Int, sensitive: Boolean = false): SmtpReply {
        val r = command(line, sensitive)
        if (r.code !in codes) {
            val what = if (sensitive) line.substringBefore(' ').take(4) else line.substringBefore(':').substringBefore(' ')
            val text = r.text
            throw SmtpException(r, if (r.code == 535 || r.code == 534) "sign-in refused: $text" else "$what failed: ${r.code} $text")
        }
        return r
    }

    /** One reply, which may span lines: `250-first`, `250-second`, `250 last`. */
    private fun read(): SmtpReply {
        val lines = ArrayList<String>()
        while (true) {
            val line = readLine()
            if (line.length < 3) throw SmtpException(null, "malformed reply: $line")
            val code = line.substring(0, 3).toIntOrNull() ?: throw SmtpException(null, "malformed reply: $line")
            lines.add(if (line.length > 4) line.substring(4) else "")
            if (line.length == 3 || line[3] != '-') return SmtpReply(code, lines)
        }
    }

    private fun readLine(): String {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (out.size() == 0) throw EOFException("connection closed")
                break
            }
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    companion object {
        private fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)

        /**
         * CRLF line endings, a leading "." doubled on every line (RFC 5321 4.5.2), and
         * a final CRLF so the terminating "." sits on its own line.
         */
        fun dotStuff(message: String): String {
            val normalized = message.replace("\r\n", "\n").replace('\r', '\n')
            val sb = StringBuilder()
            for (line in normalized.removeSuffix("\n").split('\n')) {
                if (line.startsWith(".")) sb.append('.')
                sb.append(line).append("\r\n")
            }
            return sb.toString()
        }
    }
}
