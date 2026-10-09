package com.gios.brightmailboxlite.data

import com.gios.brightmailboxlite.imap.FetchedHeader
import com.gios.brightmailboxlite.imap.ImapClient
import com.gios.brightmailboxlite.imap.ImapCommandException
import com.gios.brightmailboxlite.imap.ImapException
import com.gios.brightmailboxlite.imap.Tls
import com.gios.brightmailboxlite.mail.Addr
import com.gios.brightmailboxlite.mail.Mime
import com.gios.brightmailboxlite.mail.Outgoing
import com.gios.brightmailboxlite.smtp.SmtpClient
import com.gios.brightmailboxlite.smtp.SmtpException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.net.ssl.SSLException

/**
 * Everything that talks to a mail server. Blocking; call it from Dispatchers.IO.
 *
 * No Android imports: the state it reads and returns is plain data, and [Mailbox] owns
 * where that data lives.
 */
object MailNet {

    /** Most of a message is its first quarter megabyte; the rest is usually attachments. */
    const val BODY_CAP = 256 * 1024

    /** How far back the first sync of an account reads. */
    private const val FIRST_SYNC_DAYS = 14

    /** The most messages one sync takes from one account. */
    private const val MAX_PER_SYNC = 300

    private const val HEADER_BATCH = 40

    private val SENT_NAMES = listOf("Sent", "Sent Items", "Sent Messages", "Sent Mail", "INBOX.Sent", "INBOX/Sent")

    /** Headers the sorter reads. Everything else is dropped before storage. */
    private val KEEP = setOf(
        "list-unsubscribe", "list-id", "list-post", "x-auto-response-suppress", "feedback-id",
        "x-campaign-id", "x-mailchimp-id", "x-ses-outgoing", "x-sg-eid", "x-pm-message-id",
        "x-mandrill-user", "precedence", "auto-submitted", "return-path", "content-class", "content-type",
    )

    fun <T> withImap(acc: Account, password: String, block: (ImapClient) -> T): T {
        val client = Tls.imap(acc.imapHost, acc.imapPort)
        try {
            client.login(acc.user, password)
            val result = block(client)
            client.logout()
            return result
        } finally {
            client.close()
        }
    }

    /** Sign in and open the inbox, so a wrong password is caught while the user is still there. */
    fun verify(acc: Account, password: String) {
        withImap(acc, password) { it.examine("INBOX") }
    }

    class Fetched(
        val state: SyncState,
        val messages: List<Msg>,
        /** The server renumbered the mailbox; everything stored for this account is stale. */
        val reset: Boolean,
        /** Address -> times written to, from the Sent folder; null when not scanned this time. */
        val sentCounts: Map<String, Int>?,
        /** UIDs whose \Seen was delivered this time. */
        val seenDelivered: List<Long>,
    )

    fun sync(acc: Account, password: String, state: SyncState, pendingSeen: List<Long>, mine: Set<String>): Fetched =
        withImap(acc, password) { c ->
            val sentCounts = if (!state.sentScanned) runCatching { scanSent(c, mine) }.getOrNull() else null

            val info = c.select("INBOX")
            val reset = state.uidValidity != 0L && info.uidValidity != state.uidValidity
            val lastUid = if (reset) 0L else state.lastUid

            var delivered = emptyList<Long>()
            if (!reset && pendingSeen.isNotEmpty()) {
                runCatching { c.markSeen(pendingSeen) }.onSuccess { delivered = pendingSeen }
            }

            val uids = if (lastUid == 0L) {
                c.uidSearchSince(Date(System.currentTimeMillis() - FIRST_SYNC_DAYS * 86_400_000L))
            } else {
                c.uidSearch("UID ${lastUid + 1}:*").filter { it > lastUid }
            }.takeLast(MAX_PER_SYNC)

            val messages = ArrayList<Msg>()
            for (batch in uids.chunked(HEADER_BATCH)) {
                c.fetchHeaders(batch).forEach { messages.add(msgOf(acc, info.uidValidity, it)) }
            }
            val newLast = maxOf(lastUid, uids.maxOrNull() ?: 0L)
            Fetched(
                state = SyncState(info.uidValidity, newLast, state.sentScanned || sentCounts != null),
                messages = messages,
                reset = reset,
                sentCounts = sentCounts,
                seenDelivered = delivered,
            )
        }

    /**
     * Who the user writes to, from the last year of the Sent folder.
     *
     * The strongest signal the sorter has — someone you have written to is a person — and
     * one the app could never have caused, which is why the full app reads it once at
     * setup. Lite does the same, capped at the newest 1000 sent messages.
     */
    private fun scanSent(c: ImapClient, mine: Set<String>): Map<String, Int> {
        val boxes = c.list()
        val sent = boxes.firstOrNull { "\\sent" in it.flags }
            ?: boxes.firstOrNull { b -> SENT_NAMES.any { it.equals(b.name, ignoreCase = true) } }
            ?: return emptyMap()
        c.examine(sent.name)
        val uids = c.uidSearchSince(Date(System.currentTimeMillis() - 365L * 86_400_000L)).takeLast(1000)
        val counts = HashMap<String, Int>()
        for (batch in uids.chunked(200)) {
            for ((_, bytes) in c.fetchHeaderFields(batch, "TO CC")) {
                val h = Mime.parseHeaders(bytes)
                val rcpts = (Addr.addresses(h.get("to")) + Addr.addresses(h.get("cc"))).toSet()
                for (a in rcpts) if (a !in mine) counts[a] = (counts[a] ?: 0) + 1
            }
        }
        return counts
    }

    fun msgOf(acc: Account, uidValidity: Long, f: FetchedHeader): Msg {
        val h = Mime.parseHeaders(f.header)
        val fromRaw = h.get("from").orEmpty()
        val fromEntry = Addr.split(fromRaw).firstOrNull() ?: fromRaw
        val from = Addr.address(fromEntry) ?: fromRaw.trim().lowercase()
        val received = ImapClient.parseInternalDate(f.internalDate)
            ?: parseRfc5322Date(h.get("date"))
            ?: System.currentTimeMillis()
        val kept = h.toMap().filter { (k, _) -> k in KEEP || k.startsWith("x-microsoft-cdo") }
            .mapValues { it.value.take(300) }
        return Msg(
            key = "${acc.id}:$uidValidity:${f.uid}",
            accountId = acc.id,
            uid = f.uid,
            from = from,
            fromName = if (fromEntry.isNotBlank()) Addr.name(fromEntry) else from,
            subject = h.get("subject").orEmpty().trim(),
            to = Addr.addresses(h.get("to")),
            cc = Addr.addresses(h.get("cc")),
            receivedAt = received,
            messageId = h.get("message-id")?.trim(),
            references = h.get("references")?.trim(),
            replyTo = Addr.addresses(h.get("reply-to")).firstOrNull(),
            headers = kept,
            seenOnServer = f.flags.any { it.equals("\\Seen", ignoreCase = true) },
        )
    }

    private fun parseRfc5322Date(s: String?): Long? {
        if (s == null) return null
        val t = s.substringBefore('(').trim()
        for (p in listOf("EEE, d MMM yyyy HH:mm:ss Z", "d MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm Z")) {
            runCatching { SimpleDateFormat(p, Locale.US).parse(t)?.time }.getOrNull()?.let { return it }
        }
        return null
    }

    /** The raw message, capped, or null if the server no longer has it. */
    fun fetchBody(acc: Account, password: String, uidValidity: Long, uid: Long): ByteArray? =
        withImap(acc, password) { c ->
            val info = c.examine("INBOX")
            if (info.uidValidity != uidValidity) return@withImap null
            c.fetchBody(uid, BODY_CAP)
        }

    /**
     * Fetch a body and, in the same session, mark it read. Returns null if the server
     * renumbered the mailbox or no longer has the message.
     */
    fun open(acc: Account, password: String, uidValidity: Long, uid: Long, markSeen: Boolean): ByteArray? =
        withImap(acc, password) { c ->
            val info = c.select("INBOX")
            if (info.uidValidity != uidValidity) return@withImap null
            val body = c.fetchBody(uid, BODY_CAP)
            if (markSeen && body != null) runCatching { c.markSeen(listOf(uid)) }
            body
        }

    fun markSeen(acc: Account, password: String, uids: List<Long>) {
        withImap(acc, password) { c ->
            c.select("INBOX")
            c.markSeen(uids)
        }
    }

    /** Build and send one plain-text message. Returns nothing; throws with a readable message. */
    fun send(acc: Account, password: String, out: Outgoing) {
        val domain = acc.email.substringAfter('@', "localhost")
        val header = "Date: " + SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z", Locale.US).format(Date()) + "\r\n" +
            "Message-ID: <" + UUID.randomUUID().toString() + "@" + domain + ">\r\n"
        val raw = header + Addr.rfc5322(acc.email, out)
        val recipients = (out.to + out.cc).distinct()

        if (acc.smtpSsl) {
            val socket = Tls.connect(acc.smtpHost, acc.smtpPort)
            socket.use {
                val c = SmtpClient(it.getInputStream().buffered(), it.getOutputStream().buffered())
                c.greeting()
                c.ehlo()
                c.login(acc.user, password)
                c.send(acc.email, recipients, raw)
                c.quit()
            }
        } else {
            val plain = Tls.connectPlain(acc.smtpHost, acc.smtpPort)
            plain.use { raw0 ->
                val c = SmtpClient(raw0.getInputStream().buffered(), raw0.getOutputStream().buffered())
                c.greeting()
                c.ehlo()
                // Never send a password over an unencrypted connection.
                if (!c.supportsStartTls) throw IOException("${acc.smtpHost} does not offer encryption on port ${acc.smtpPort}")
                c.startTls {
                    val tls = Tls.wrap(raw0, acc.smtpHost, acc.smtpPort)
                    tls.getInputStream().buffered() to tls.getOutputStream().buffered()
                }
                c.login(acc.user, password)
                c.send(acc.email, recipients, raw)
                c.quit()
            }
        }
    }

    /** One sentence for the screen. */
    fun friendly(e: Throwable): String = when (e) {
        is ImapCommandException ->
            if (e.isAuthFailure) "The server refused the password. For Gmail, use an app password, not your account password."
            else "The server said: ${e.message}"
        is SmtpException ->
            if (e.isAuthFailure) "The mail server refused the password for sending."
            else e.message ?: "Sending failed."
        is UnknownHostException -> "Could not find ${e.message ?: "the server"}. Check the host name and the connection."
        is SocketTimeoutException -> "The server took too long to answer."
        is SSLException -> "Could not make a secure connection: ${e.message}"
        is ImapException -> "Mail server error: ${e.message}"
        is IOException -> "Connection problem: ${e.message ?: "no network"}"
        else -> e.message ?: "Something went wrong."
    }
}
