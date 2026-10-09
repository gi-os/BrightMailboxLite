package com.gios.brightmailboxlite.data

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gios.brightmailboxlite.mail.Addr
import com.gios.brightmailboxlite.mail.Mime
import com.gios.brightmailboxlite.mail.Outgoing
import com.gios.brightmailboxlite.sort.Day
import com.gios.brightmailboxlite.sort.Headers
import com.gios.brightmailboxlite.sort.Learner
import com.gios.brightmailboxlite.sort.LetterSlot
import com.gios.brightmailboxlite.sort.Lessons
import com.gios.brightmailboxlite.sort.Pile
import com.gios.brightmailboxlite.sort.Ration
import com.gios.brightmailboxlite.sort.Rationing
import com.gios.brightmailboxlite.sort.Sorter
import com.gios.brightmailboxlite.text.Clean
import com.thelightphone.sdk.SealedLightContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Calendar
import java.util.UUID

/** What the screens show about syncing. */
data class SyncStatus(
    val busy: Boolean = false,
    /** One sentence, or null when the last sync worked. */
    val error: String? = null,
    val lastSyncAt: Long = 0,
)

/**
 * The tool's single source of truth: accounts, messages, the user's decisions, and every
 * operation on them.
 *
 * One object for the whole process, because the screens and the background job share it.
 * Messages and decisions are JSON files in the tool's private storage; account details
 * and encrypted passwords are in the SDK's DataStore.
 */
object Mailbox {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    /** Main-thread scope for work started from screens; heavy parts switch dispatchers inside. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val stateLock = Mutex()
    private val syncLock = Mutex()
    private val loadLock = Mutex()
    @Volatile private var loaded = false
    private lateinit var dir: File

    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    val accounts: StateFlow<List<Account>> = _accounts

    private val _messages = MutableStateFlow<List<Msg>>(emptyList())
    val messages: StateFlow<List<Msg>> = _messages

    private val _book = MutableStateFlow(Book())
    val book: StateFlow<Book> = _book

    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status

    /** True once the first load from disk is done, so screens do not flash "no accounts". */
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    private val ACCOUNTS_KEY = stringPreferencesKey("accounts")
    private fun secretKey(id: String) = stringPreferencesKey("secret_$id")

    /* -------------------------------------------------------------------- loading */

    suspend fun load(ctx: SealedLightContext) {
        if (loaded) return
        loadLock.withLock {
            if (loaded) return
            withContext(Dispatchers.IO) {
                dir = File(ctx.filesDir, "mailbox").apply { mkdirs() }
                File(dir, "bodies").mkdirs()
                val prefs = ctx.dataStore.data.first()
                _accounts.value = prefs[ACCOUNTS_KEY]?.let {
                    runCatching { json.decodeFromString(ListSerializer(Account.serializer()), it) }.getOrNull()
                }.orEmpty()
                _messages.value = readFile("messages.json")?.let {
                    runCatching { json.decodeFromString(ListSerializer(Msg.serializer()), it) }.getOrNull()
                }.orEmpty()
                _book.value = readFile("book.json")?.let {
                    runCatching { json.decodeFromString(Book.serializer(), it) }.getOrNull()
                } ?: Book()
            }
            loaded = true
            _ready.value = true
        }
    }

    private fun readFile(name: String): String? = File(dir, name).takeIf { it.exists() }?.readText()

    private fun writeFile(name: String, text: String) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(File(dir, name))) {
            File(dir, name).writeText(text)
            tmp.delete()
        }
    }

    private suspend fun persist() = withContext(Dispatchers.IO) {
        writeFile("messages.json", json.encodeToString(ListSerializer(Msg.serializer()), _messages.value))
        writeFile("book.json", json.encodeToString(Book.serializer(), _book.value))
    }

    /* ------------------------------------------------------------------- accounts */

    suspend fun password(ctx: SealedLightContext, id: String): String? {
        val blob = ctx.dataStore.data.first()[secretKey(id)] ?: return null
        return runCatching { Secrets.decrypt(blob) }.getOrNull()
    }

    /**
     * Try the credential against the server, and keep it only if it works.
     *
     * The full app does the same: a wrong character is an error while the user is still
     * on the sign-in screen, not a sync that quietly never runs.
     */
    suspend fun addAccount(ctx: SealedLightContext, draft: Account, password: String): Result<Account> {
        load(ctx)
        val acc = draft.copy(id = draft.id.ifBlank { UUID.randomUUID().toString().take(8) })
        val check = withContext(Dispatchers.IO) { runCatching { MailNet.verify(acc, password) } }
        check.exceptionOrNull()?.let { return Result.failure(Exception(MailNet.friendly(it))) }
        val blob = runCatching { Secrets.encrypt(password) }.getOrElse {
            return Result.failure(Exception("Could not store the password securely: ${it.message}"))
        }
        val list = _accounts.value.filterNot { it.email.equals(acc.email, ignoreCase = true) } + acc
        ctx.dataStore.edit {
            it[secretKey(acc.id)] = blob
            it[ACCOUNTS_KEY] = json.encodeToString(ListSerializer(Account.serializer()), list)
        }
        _accounts.value = list
        return Result.success(acc)
    }

    suspend fun removeAccount(ctx: SealedLightContext, id: String) {
        load(ctx)
        val list = _accounts.value.filterNot { it.id == id }
        ctx.dataStore.edit {
            it.remove(secretKey(id))
            it[ACCOUNTS_KEY] = json.encodeToString(ListSerializer(Account.serializer()), list)
        }
        _accounts.value = list
        stateLock.withLock {
            val gone = _messages.value.filter { it.accountId == id }
            _messages.value = _messages.value.filterNot { it.accountId == id }
            val b = _book.value
            _book.value = b.copy(sync = b.sync - id, pendingSeen = b.pendingSeen - id)
            withContext(Dispatchers.IO) { gone.forEach { bodyFile(it.key).delete() } }
            persist()
        }
    }

    private fun mine(): Set<String> = _accounts.value.map { it.email.lowercase() }.toSet()

    /* ---------------------------------------------------------------------- sync */

    /** Start a sync unless one is running. Safe to call from anywhere. */
    fun syncInBackground(ctx: SealedLightContext) {
        scope.launch { sync(ctx) }
    }

    /**
     * Fetch new mail for every account, then sort everything again.
     *
     * @return false if any account failed in a way worth retrying later.
     */
    suspend fun sync(ctx: SealedLightContext): Boolean {
        load(ctx)
        if (!syncLock.tryLock()) return true
        try {
            _status.value = _status.value.copy(busy = true)
            val errors = ArrayList<String>()
            for (acc in _accounts.value) {
                val pw = password(ctx, acc.id)
                if (pw == null) {
                    errors.add("${acc.email}: the stored password could not be read; add the account again.")
                    continue
                }
                val book = _book.value
                val state = book.sync[acc.id] ?: SyncState()
                val pending = book.pendingSeen[acc.id].orEmpty()
                val result = withContext(Dispatchers.IO) {
                    runCatching { MailNet.sync(acc, pw, state, pending, mine()) }
                }
                val fetched = result.getOrNull()
                if (fetched == null) {
                    errors.add("${acc.email}: ${MailNet.friendly(result.exceptionOrNull() ?: Exception())}")
                    continue
                }
                stateLock.withLock { apply(acc, fetched) }
            }
            stateLock.withLock {
                resort()
                prune()
                persist()
            }
            _status.value = SyncStatus(busy = false, error = errors.firstOrNull(), lastSyncAt = System.currentTimeMillis())
            return errors.isEmpty()
        } finally {
            if (_status.value.busy) _status.value = _status.value.copy(busy = false)
            syncLock.unlock()
        }
    }

    private suspend fun apply(acc: Account, f: MailNet.Fetched) {
        var msgs = _messages.value
        if (f.reset) {
            val gone = msgs.filter { it.accountId == acc.id }
            withContext(Dispatchers.IO) { gone.forEach { bodyFile(it.key).delete() } }
            msgs = msgs.filterNot { it.accountId == acc.id }
        }
        val known = msgs.mapTo(HashSet()) { it.key }
        msgs = msgs + f.messages.filter { it.key !in known }
        _messages.value = msgs

        val b = _book.value
        val replies = if (f.sentCounts != null) {
            // Merge: counts from the Sent folder, never lowering a count the app raised.
            val merged = HashMap(b.replies)
            for ((a, n) in f.sentCounts) merged[a] = maxOf(merged[a] ?: 0, n)
            merged
        } else b.replies
        val pending = b.pendingSeen[acc.id].orEmpty() - f.seenDelivered.toSet()
        _book.value = b.copy(
            replies = replies,
            sync = b.sync + (acc.id to f.state),
            pendingSeen = if (pending.isEmpty()) b.pendingSeen - acc.id else b.pendingSeen + (acc.id to pending),
        )
    }

    /** Keep six weeks, and at most 1500 messages. Bodies of dropped messages go too. */
    private suspend fun prune() {
        val cutoff = System.currentTimeMillis() - 42L * 86_400_000L
        val all = _messages.value.sortedByDescending { it.receivedAt }
        val keep = all.filter { it.receivedAt >= cutoff }.take(1500)
        if (keep.size == all.size) return
        val kept = keep.mapTo(HashSet()) { it.key }
        withContext(Dispatchers.IO) { all.filter { it.key !in kept }.forEach { bodyFile(it.key).delete() } }
        _messages.value = keep
    }

    /**
     * Both tiers, over everything stored.
     *
     * The learned model is retrained from scratch each time, from the evidence [Lessons]
     * weighs: who you have written to, what you opened here, and Tier 0's verdict as the
     * floor. It is a second's work for a phone's worth of mail and it means the model is
     * never out of step with a decision the user just made.
     */
    private suspend fun resort() = withContext(Dispatchers.Default) {
        val b = _book.value
        val mine = mine()
        val overrides = b.overrides.mapNotNull { (k, v) -> runCatching { Pile.valueOf(v) }.getOrNull()?.let { k to it } }.toMap()
        val msgs = _messages.value
        val learner = Learner()
        learner.fitWeighted(
            msgs.map { m ->
                val e = m.envelope(mine)
                val tier0 = Headers.classify(e, b.replies[m.from] ?: 0, overrides[m.from])
                val lesson = Lessons.of(
                    starred = false,
                    wroteToSender = (b.replies[m.from] ?: 0) > 0,
                    readHere = m.readDay != 0,
                    archived = false,
                    tier0SaysLetter = tier0.pile == Pile.LETTER,
                )
                Triple(e, lesson.worthReading, lesson.weight)
            },
        )
        val sorter = Sorter(learner, overrides, b.replies)
        _messages.value = msgs.map { m ->
            val s = sorter.sort(m.envelope(mine))
            m.copy(pile = s.verdict.pile.name, reason = s.verdict.reason, rule = s.verdict.rule, score = s.importance)
        }
    }

    /* -------------------------------------------------------------------- the day */

    fun today(): Int {
        val c = Calendar.getInstance()
        return Rationing.dayNumber(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    fun ration(b: Book = _book.value): Ration = Ration.of(b.ration)

    fun extraToday(b: Book = _book.value): Int = if (b.extraDay == today()) b.extra else 0

    /** Today's Letters under the ration. */
    fun day(msgs: List<Msg>, b: Book): Day =
        Rationing.day(
            msgs.filter { it.isLetter }.map {
                LetterSlot(it.key, it.receivedAt, it.score, it.rationDay, done = it.seenOnServer || it.readDay != 0)
            },
            ration(b),
            today(),
            extraToday(b),
        )

    suspend fun setRation(ctx: SealedLightContext, r: Ration) = edit(ctx) { it.copy(ration = r.key) }

    /** The one override of the ration: one more Letter today. */
    suspend fun oneMore(ctx: SealedLightContext) = edit(ctx) {
        val t = today()
        it.copy(extra = (if (it.extraDay == t) it.extra else 0) + 1, extraDay = t)
    }

    private suspend fun edit(ctx: SealedLightContext, f: (Book) -> Book) {
        load(ctx)
        stateLock.withLock {
            _book.value = f(_book.value)
            persist()
        }
    }

    /* -------------------------------------------------------------- user choices */

    /** Move every message from [address] to [pile], now and in future. Reversible in Settings. */
    suspend fun moveSender(ctx: SealedLightContext, address: String, pile: Pile) {
        load(ctx)
        stateLock.withLock {
            _book.value = _book.value.let { it.copy(overrides = it.overrides + (address to pile.name)) }
            resort()
            persist()
        }
    }

    suspend fun forgetOverride(ctx: SealedLightContext, address: String) {
        load(ctx)
        stateLock.withLock {
            _book.value = _book.value.let { it.copy(overrides = it.overrides - address) }
            resort()
            persist()
        }
    }

    /* ------------------------------------------------------------------- reading */

    private fun bodyFile(key: String) = File(File(dir, "bodies"), key.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    fun find(key: String): Msg? = _messages.value.firstOrNull { it.key == key }

    /**
     * Open a message: charge the ration if it is one of today's fresh Letters, fetch its
     * body (or read the cached one), and mark it read on the server.
     */
    suspend fun open(ctx: SealedLightContext, key: String): Result<Body> {
        load(ctx)
        val msg = find(key) ?: return Result.failure(Exception("That message is gone."))
        val t = today()
        stateLock.withLock {
            _messages.value = _messages.value.map {
                if (it.key != key) it
                else it.copy(
                    readDay = if (it.readDay == 0) t else it.readDay,
                    // Only a Letter that was waiting is charged; re-reading is free.
                    rationDay = if (it.isLetter && it.rationDay == 0 && it.readDay == 0 && !it.seenOnServer) t else it.rationDay,
                )
            }
            persist()
        }
        val cached = withContext(Dispatchers.IO) {
            bodyFile(key).takeIf { it.exists() }?.let { f ->
                runCatching { json.decodeFromString(Body.serializer(), f.readText()) }.getOrNull()
            }
        }
        val needsSeen = !msg.seenOnServer
        if (cached != null) {
            if (needsSeen) markSeenLater(ctx, msg)
            return Result.success(cached)
        }
        val acc = _accounts.value.firstOrNull { it.id == msg.accountId }
            ?: return Result.failure(Exception("The account for this message was removed."))
        val pw = password(ctx, acc.id) ?: return Result.failure(Exception("The stored password could not be read."))
        val validity = msg.key.split(':').getOrNull(1)?.toLongOrNull() ?: 0L
        val raw = withContext(Dispatchers.IO) {
            runCatching { MailNet.open(acc, pw, validity, msg.uid, markSeen = needsSeen) }
        }.getOrElse { return Result.failure(Exception(MailNet.friendly(it))) }
            ?: return Result.failure(Exception("The server no longer has this message."))
        val body = withContext(Dispatchers.Default) {
            val truncated = raw.size >= MailNet.BODY_CAP
            val r = Mime.readable(raw, truncated)
            val clean = Clean.body(r.text)
            Body(
                text = clean.text.ifBlank { r.text }.ifBlank { "(This message has no text.)" },
                attachments = r.attachments,
                fromHtml = r.fromHtml,
                quoted = clean.quotedMessages,
                truncated = truncated,
            )
        }
        withContext(Dispatchers.IO) { runCatching { bodyFile(key).writeText(json.encodeToString(Body.serializer(), body)) } }
        if (needsSeen) {
            stateLock.withLock {
                _messages.value = _messages.value.map { if (it.key == key) it.copy(seenOnServer = true) else it }
                persist()
            }
        }
        return Result.success(body)
    }

    /** Queue a \Seen for the next sync, and try it now. */
    private suspend fun markSeenLater(ctx: SealedLightContext, msg: Msg) {
        stateLock.withLock {
            val b = _book.value
            val list = (b.pendingSeen[msg.accountId].orEmpty() + msg.uid).distinct()
            _book.value = b.copy(pendingSeen = b.pendingSeen + (msg.accountId to list))
            _messages.value = _messages.value.map { if (it.key == msg.key) it.copy(seenOnServer = true) else it }
            persist()
        }
        syncInBackground(ctx)
    }

    /* ------------------------------------------------------------------- replying */

    /**
     * Send a plain-text reply.
     *
     * A successful reply counts as writing to that address, which is the strongest
     * Letter signal there is: their next message lands in Letters whatever its headers say.
     */
    suspend fun reply(ctx: SealedLightContext, key: String, text: String): Result<Unit> {
        load(ctx)
        val msg = find(key) ?: return Result.failure(Exception("That message is gone."))
        val acc = _accounts.value.firstOrNull { it.id == msg.accountId }
            ?: return Result.failure(Exception("The account for this message was removed."))
        val pw = password(ctx, acc.id) ?: return Result.failure(Exception("The stored password could not be read."))
        val to = msg.replyTo ?: msg.from
        val quote = buildString {
            append(text.trimEnd())
            append("\n\n")
            append("On ").append(DateFormat.getDateTimeInstance().format(Date(msg.receivedAt)))
            append(", ").append(msg.fromName.ifBlank { msg.from }).append(" wrote:\n")
        }
        val out = Outgoing(
            to = listOf(to),
            subject = Addr.replySubject(msg.subject),
            body = quote + quotedBody(key),
            inReplyTo = msg.messageId,
            references = msg.references,
        )
        val sent = withContext(Dispatchers.IO) { runCatching { MailNet.send(acc, pw, out) } }
        sent.exceptionOrNull()?.let { return Result.failure(Exception(MailNet.friendly(it))) }
        stateLock.withLock {
            val b = _book.value
            _book.value = b.copy(replies = b.replies + (to to ((b.replies[to] ?: 0) + 1)))
            resort()
            persist()
        }
        return Result.success(Unit)
    }

    /** The cached body, quoted with "> ", for the bottom of a reply. */
    private suspend fun quotedBody(key: String): String = withContext(Dispatchers.IO) {
        val body = bodyFile(key).takeIf { it.exists() }?.let {
            runCatching { json.decodeFromString(Body.serializer(), it.readText()) }.getOrNull()
        } ?: return@withContext ""
        body.text.lines().joinToString("\n") { if (it.isEmpty()) ">" else "> $it" }
    }
}
