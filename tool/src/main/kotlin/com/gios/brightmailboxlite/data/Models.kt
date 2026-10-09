package com.gios.brightmailboxlite.data

import com.gios.brightmailboxlite.auth.Servers
import com.gios.brightmailboxlite.auth.Service
import com.gios.brightmailboxlite.sort.Envelope
import com.gios.brightmailboxlite.sort.Pile
import kotlinx.serialization.Serializable

/** One mailbox the phone signs in to. The password is stored apart, encrypted ([Secrets]). */
@Serializable
data class Account(
    val id: String,
    /** [Service.key]: "google" or "imap". */
    val service: String,
    val email: String,
    /** The IMAP/SMTP user name; the address for Gmail and most providers. */
    val user: String,
    val imapHost: String,
    val imapPort: Int,
    val smtpHost: String,
    val smtpPort: Int,
    val smtpSsl: Boolean,
) {
    val servers: Servers get() = Servers(imapHost, imapPort, smtpHost, smtpPort, smtpSsl)
    val label: String get() = Service.of(service)?.label ?: "Mail"
}

/**
 * One message, as much of it as the lists and the sorter need.
 *
 * Bodies are not here; they are fetched when a message is opened and cached as [Body].
 */
@Serializable
data class Msg(
    /** `accountId:uidValidity:uid` — unique and stable while the server keeps its UIDs. */
    val key: String,
    val accountId: String,
    val uid: Long,
    val from: String,
    val fromName: String,
    val subject: String,
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val receivedAt: Long,
    val messageId: String? = null,
    val references: String? = null,
    val replyTo: String? = null,
    /** Only the headers the sorter reads, lowercased names. */
    val headers: Map<String, String> = emptyMap(),
    /** Already \Seen on the server when it reached the phone: read somewhere else. */
    val seenOnServer: Boolean = false,
    val pile: String = Pile.LETTER.name,
    val reason: String = "",
    val rule: String = "",
    val score: Double = 0.5,
    /** yyyymmdd it was opened here and charged to the ration; 0 if never. */
    val rationDay: Int = 0,
    /** yyyymmdd it was first opened here; 0 if never. */
    val readDay: Int = 0,
) {
    val isLetter: Boolean get() = pile == Pile.LETTER.name

    fun envelope(mine: Set<String>): Envelope =
        Envelope(from = from, fromName = fromName, subject = subject, headers = headers, to = to, cc = cc, mine = mine)
}

@Serializable
data class SyncState(
    val uidValidity: Long = 0,
    val lastUid: Long = 0,
    /** The Sent folder has been read once for the "you've replied to this address" rule. */
    val sentScanned: Boolean = false,
)

/** Everything small that is not a message: the user's decisions and the sync cursor. */
@Serializable
data class Book(
    /** Address -> Pile name. A per-sender decision, which always wins. */
    val overrides: Map<String, String> = emptyMap(),
    /** Address -> times the user has written to it. */
    val replies: Map<String, Int> = emptyMap(),
    val sync: Map<String, SyncState> = emptyMap(),
    /** Account id -> UIDs opened here whose \Seen has not reached the server yet. */
    val pendingSeen: Map<String, List<Long>> = emptyMap(),
    val ration: String = "five",
    /** Extra Letters unlocked today ("one more"), and the day that was. */
    val extra: Int = 0,
    val extraDay: Int = 0,
)

/** A message opened once and cached as text. */
@Serializable
data class Body(
    val text: String,
    val attachments: List<String> = emptyList(),
    val fromHtml: Boolean = false,
    /** Earlier messages quoted below the reply and cut, as the full app does. */
    val quoted: Int = 0,
    /** The message was larger than the fetch cap and is shown in part. */
    val truncated: Boolean = false,
)
