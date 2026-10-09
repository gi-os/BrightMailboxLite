package com.gios.brightmailboxlite.imap

import com.gios.brightmailboxlite.imap.ImapParser.section
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One mailbox from LIST. [name] is exactly as the server sent it, ready to SELECT. */
data class ImapMailbox(val name: String, val flags: Set<String>)

data class SelectInfo(val uidValidity: Long, val exists: Long, val uidNext: Long?)

/** What a header fetch returns for one message. */
class FetchedHeader(
    val uid: Long,
    val flags: Set<String>,
    /** `17-Jul-1996 02:44:25 -0700`, or null if the server left it out. */
    val internalDate: String?,
    val header: ByteArray,
)

/**
 * A minimal IMAP4rev1 client: the eight commands a reader needs and nothing else.
 *
 * LOGIN, CAPABILITY, LIST, SELECT/EXAMINE, UID SEARCH, UID FETCH, UID STORE, LOGOUT.
 * Every FETCH uses BODY.PEEK so reading on the phone never marks anything read by
 * accident; marking \Seen is a separate, explicit STORE.
 *
 * The transport is any pair of streams, so tests drive it with a scripted server and the
 * app hands it a TLS socket (see [Tls]).
 */
class ImapClient(input: InputStream, private val output: OutputStream, private val onClose: () -> Unit = {}) : Closeable {

    private val reader = ImapReader(input)
    private var tagCounter = 0

    /** Every untagged response seen by the last command, for callers that want them. */
    var lastUntagged: List<ImapResponse> = emptyList()
        private set

    /** Read the server greeting. Must be called once, first. */
    fun greeting(): ImapResponse {
        val r = ImapParser.parse(reader.readResponse())
        if (r.status == "BYE") throw ImapException("server refused the connection: ${r.text}")
        if (r.status != "OK" && r.status != "PREAUTH") throw ImapException("unexpected greeting")
        return r
    }

    /** A command argument: either sent as written, or as an IMAP string. */
    sealed interface Arg {
        data class Raw(val text: String) : Arg
        data class Str(val text: String) : Arg
    }

    /**
     * Send one command and collect its untagged responses.
     *
     * Strings that are plain printable ASCII go as quoted strings; anything else (a
     * password with a non-ASCII character, say) goes as a synchronizing literal, which is
     * the only form that can carry arbitrary bytes.
     */
    fun command(vararg args: Arg): List<ImapResponse> {
        val tag = "A" + (++tagCounter).toString().padStart(3, '0')
        val untagged = ArrayList<ImapResponse>()
        val line = StringBuilder(tag)
        fun send(s: String) = output.write(s.toByteArray(Charsets.ISO_8859_1))
        for (a in args) {
            line.append(' ')
            when (a) {
                is Arg.Raw -> line.append(a.text)
                is Arg.Str -> {
                    if (isQuotable(a.text)) {
                        line.append('"').append(a.text.replace("\\", "\\\\").replace("\"", "\\\"")).append('"')
                    } else {
                        val bytes = a.text.toByteArray(Charsets.UTF_8)
                        line.append('{').append(bytes.size).append("}\r\n")
                        send(line.toString())
                        output.flush()
                        line.setLength(0)
                        // Wait for the go-ahead; untagged data may arrive first.
                        while (true) {
                            val r = ImapParser.parse(reader.readResponse())
                            if (r.isContinuation) break
                            if (r.tag == tag) throw ImapException(r.text.ifBlank { "command refused" })
                            untagged.add(r)
                        }
                        output.write(bytes)
                    }
                }
            }
        }
        line.append("\r\n")
        send(line.toString())
        output.flush()
        while (true) {
            val r = ImapParser.parse(reader.readResponse())
            when {
                r.tag == tag -> {
                    lastUntagged = untagged
                    if (r.status != "OK") {
                        throw ImapCommandException(r.status ?: "BAD", r.code, r.text)
                    }
                    return untagged
                }
                r.isContinuation -> Unit // not expected outside a literal; ignore
                r.isUntagged && r.status == "BYE" -> {
                    untagged.add(r)
                    // The tagged reply may still follow (LOGOUT); if not, the read throws.
                }
                else -> untagged.add(r)
            }
        }
    }

    private fun isQuotable(s: String): Boolean = s.all { it.code in 0x20..0x7e }

    fun login(user: String, password: String) {
        command(Arg.Raw("LOGIN"), Arg.Str(user), Arg.Str(password))
    }

    fun capabilities(): Set<String> =
        command(Arg.Raw("CAPABILITY"))
            .filter { it.kind == "CAPABILITY" }
            .flatMap { r -> r.values.mapNotNull { it.text()?.uppercase() } }
            .toSet()

    fun list(): List<ImapMailbox> =
        command(Arg.Raw("LIST"), Arg.Str(""), Arg.Str("*"))
            .filter { it.kind == "LIST" }
            .mapNotNull { r ->
                val flags = (r.values.getOrNull(0) as? ImapValue.ListV)?.items
                    ?.mapNotNull { it.text()?.lowercase() }?.toSet().orEmpty()
                val name = when (val n = r.values.getOrNull(2)) {
                    is ImapValue.Bytes -> String(n.bytes, Charsets.ISO_8859_1)
                    is ImapValue.Atom -> n.text
                    else -> null
                } ?: return@mapNotNull null
                ImapMailbox(name, flags)
            }

    fun select(mailbox: String): SelectInfo = open("SELECT", mailbox)

    /** Read-only open. Nothing done in an EXAMINEd mailbox can change a flag. */
    fun examine(mailbox: String): SelectInfo = open("EXAMINE", mailbox)

    private fun open(verb: String, mailbox: String): SelectInfo {
        val rs = command(Arg.Raw(verb), Arg.Str(mailbox))
        var validity = 0L
        var exists = 0L
        var next: Long? = null
        for (r in rs) {
            if (r.kind == "EXISTS") exists = r.number ?: 0
            val code = r.code ?: continue
            val parts = code.split(' ')
            when (parts[0].uppercase()) {
                "UIDVALIDITY" -> validity = parts.getOrNull(1)?.toLongOrNull() ?: 0
                "UIDNEXT" -> next = parts.getOrNull(1)?.toLongOrNull()
            }
        }
        return SelectInfo(validity, exists, next)
    }

    /** `UID SEARCH <criteria>` -> UIDs, ascending. */
    fun uidSearch(criteria: String): List<Long> =
        command(Arg.Raw("UID SEARCH $criteria"))
            .filter { it.kind == "SEARCH" }
            .flatMap { r -> r.values.mapNotNull { it.text()?.toLongOrNull() } }
            .distinct()
            .sorted()

    /** Every message that arrived on or after [day]. */
    fun uidSearchSince(day: Date): List<Long> = uidSearch("SINCE ${imapDate(day)}")

    /** Headers, flags and arrival time for [uids]. */
    fun fetchHeaders(uids: List<Long>): List<FetchedHeader> {
        if (uids.isEmpty()) return emptyList()
        return command(Arg.Raw("UID FETCH ${uidSet(uids)} (UID FLAGS INTERNALDATE BODY.PEEK[HEADER])"))
            .filter { it.kind == "FETCH" }
            .mapNotNull { r ->
                val items = ImapParser.fetchItems(r)
                val uid = items["UID"]?.text()?.toLongOrNull() ?: return@mapNotNull null
                val flags = (items["FLAGS"] as? ImapValue.ListV)?.items?.mapNotNull { it.text() }?.toSet().orEmpty()
                val header = (items.section("BODY[") as? ImapValue.Bytes)?.bytes ?: ByteArray(0)
                FetchedHeader(uid, flags, items["INTERNALDATE"]?.text(), header)
            }
    }

    /** Just some header fields, e.g. `TO CC` for counting who the user writes to. */
    fun fetchHeaderFields(uids: List<Long>, fields: String): Map<Long, ByteArray> {
        if (uids.isEmpty()) return emptyMap()
        val out = HashMap<Long, ByteArray>()
        command(Arg.Raw("UID FETCH ${uidSet(uids)} (UID BODY.PEEK[HEADER.FIELDS ($fields)])"))
            .filter { it.kind == "FETCH" }
            .forEach { r ->
                val items = ImapParser.fetchItems(r)
                val uid = items["UID"]?.text()?.toLongOrNull() ?: return@forEach
                val bytes = (items.section("BODY[") as? ImapValue.Bytes)?.bytes ?: return@forEach
                out[uid] = bytes
            }
        return out
    }

    /**
     * The raw message, at most [maxBytes] of it.
     *
     * A partial fetch (`BODY.PEEK[]<0.n>`) so one message with a 20 MB attachment cannot
     * fill the phone's memory; the MIME parser is written to cope with the cut.
     */
    fun fetchBody(uid: Long, maxBytes: Int): ByteArray? =
        command(Arg.Raw("UID FETCH $uid (UID BODY.PEEK[]<0.$maxBytes>)"))
            .filter { it.kind == "FETCH" }
            .firstNotNullOfOrNull { r ->
                val items = ImapParser.fetchItems(r)
                if (items["UID"]?.text()?.toLongOrNull() != uid) return@firstNotNullOfOrNull null
                (items.section("BODY[") as? ImapValue.Bytes)?.bytes
            }

    fun markSeen(uids: List<Long>) {
        if (uids.isEmpty()) return
        command(Arg.Raw("UID STORE ${uidSet(uids)} +FLAGS.SILENT (\\Seen)"))
    }

    fun logout() {
        runCatching { command(Arg.Raw("LOGOUT")) }
    }

    override fun close() {
        onClose()
    }

    companion object {
        /** `1,2,3,7` -> `1:3,7`. Shorter commands, same meaning. */
        fun uidSet(uids: List<Long>): String {
            val sorted = uids.distinct().sorted()
            val sb = StringBuilder()
            var i = 0
            while (i < sorted.size) {
                var j = i
                while (j + 1 < sorted.size && sorted[j + 1] == sorted[j] + 1) j++
                if (sb.isNotEmpty()) sb.append(',')
                sb.append(sorted[i])
                if (j > i) sb.append(':').append(sorted[j])
                i = j + 1
            }
            return sb.toString()
        }

        /** RFC 3501 date: `9-Oct-2026`. Month names are English whatever the locale. */
        fun imapDate(d: Date): String = SimpleDateFormat("d-MMM-yyyy", Locale.US).format(d)

        /** INTERNALDATE -> epoch millis. */
        fun parseInternalDate(s: String?): Long? {
            if (s == null) return null
            return runCatching {
                SimpleDateFormat("d-MMM-yyyy HH:mm:ss Z", Locale.US).parse(s.trim())?.time
            }.getOrNull()
        }
    }
}

class ImapCommandException(val status: String, val code: String?, text: String) :
    Exception(text.ifBlank { "server said $status" }) {
    /** LOGIN refused for bad credentials, as opposed to a network or server fault. */
    val isAuthFailure: Boolean
        get() = status == "NO" && (code?.uppercase()?.startsWith("AUTHENTICATIONFAILED") == true ||
            code?.uppercase()?.startsWith("AUTHORIZATIONFAILED") == true ||
            message.orEmpty().contains("invalid credentials", ignoreCase = true) ||
            message.orEmpty().contains("authentication failed", ignoreCase = true) ||
            message.orEmpty().contains("login failed", ignoreCase = true))
}
