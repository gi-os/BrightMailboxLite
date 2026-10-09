package com.gios.brightmailboxlite.ui

import android.view.KeyEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.gios.brightmailboxlite.SYNC_JOB
import com.gios.brightmailboxlite.data.Mailbox
import com.gios.brightmailboxlite.data.Msg
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightWork
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes

/** Checked while the tool is open; the background job covers the rest of the day. */
private const val OPEN_SYNC_EVERY_MS = 5 * 60_000L

/** Coming back to this screen re-checks only if the last check is older than this. */
private const val RESYNC_AFTER_MS = 2 * 60_000L

/**
 * The day's Letters.
 *
 * One line per Letter here; opening one gives it the whole screen. The header counts the
 * ration spent, not the rows drawn — "2 of 5 read" — and once the five are read the list
 * says so and has nothing more to scroll.
 */
@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {

    private val wheel = WheelEvents()
    private var scheduled = false

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = wheel.handle(keyCode, event)

    @Composable
    override fun Content() {
        val ready by Mailbox.ready.collectAsState()
        val accounts by Mailbox.accounts.collectAsState()
        val messages by Mailbox.messages.collectAsState()
        val book by Mailbox.book.collectAsState()
        val status by Mailbox.status.collectAsState()

        LaunchedEffect(Unit) { Mailbox.load(lightContext) }

        LaunchedEffect(accounts.isNotEmpty()) {
            if (accounts.isEmpty()) return@LaunchedEffect
            if (!scheduled) {
                LightWork.enqueuePeriodic(lightContext, SYNC_JOB, repeatInterval = 15.minutes)
                scheduled = true
            }
            while (true) {
                if (System.currentTimeMillis() - Mailbox.status.value.lastSyncAt > RESYNC_AFTER_MS) {
                    Mailbox.sync(lightContext)
                }
                delay(OPEN_SYNC_EVERY_MS)
            }
        }

        if (!ready) {
            Themed { }
            return
        }

        if (accounts.isEmpty()) {
            Welcome()
            return
        }

        val day = Mailbox.day(messages, book)
        val byKey = messages.associateBy { it.key }
        val visible = day.visible.mapNotNull { byKey[it.key] }
        val visibleKeys = visible.map { it.key }.toSet()
        val earlier = messages.count { it.isLetter && it.key !in visibleKeys }
        val notices = messages.count { !it.isLetter }
        val unlimited = day.allowed == Int.MAX_VALUE

        val header = if (unlimited) "${day.readToday} read" else "${day.readToday} of ${day.readToday + day.allowed} read"

        Page(
            title = header,
            onBack = null,
            wheel = wheel,
            left = LightBarButton.LightIcon(icon = LightIcons.SETTINGS, onClick = { navigateTo({ SettingsScreen(it) }) }),
            right = LightBarButton.LightIcon(
                icon = LightIcons.REFRESH,
                onClick = { if (!status.busy) Mailbox.syncInBackground(lightContext) },
            ),
            bottom = listOf(
                LightBarButton.Text(text = if (notices > 0) "NOTICES  $notices" else "NOTICES", onClick = {
                    navigateTo({ ListScreen(it, ListKind.NOTICES) })
                }),
            ),
        ) {
            status.error?.let { Note(it) }
            if (status.busy && messages.isEmpty()) Note("Checking for mail…")

            if (visible.isEmpty()) {
                Spacer(Modifier.height(2f.gridUnitsAsDp()))
                LightText(
                    text = when {
                        day.spent && day.waiting > 0 -> "That's today's Letters."
                        status.busy -> ""
                        else -> "No Letters."
                    },
                    variant = LightTextVariant.Copy,
                )
            }

            val keys = visible.map { it.key }
            visible.forEach { m -> LetterRow(m, read = m.rationDay != 0) { open(m.key, keys) } }

            Spacer(Modifier.height(1f.gridUnitsAsDp()))
            if (!unlimited && day.spent) {
                Note(
                    if (day.waiting == 0) "That's today's ${day.readToday}. Nothing else is waiting."
                    else "That's today's ${day.readToday}. ${day.waiting} more wait for tomorrow.",
                )
                if (day.waiting > 0) {
                    Link("Read one more", { Mailbox.scope.launch { Mailbox.oneMore(lightContext) } }, LightTextVariant.Detail)
                }
            } else if (day.waiting > 0) {
                Note("${day.waiting} more waiting.")
            }
            if (earlier > 0) {
                Link("Earlier Letters  $earlier", { navigateTo({ ListScreen(it, ListKind.EARLIER) }) }, LightTextVariant.Detail)
            }
            Spacer(Modifier.height(1f.gridUnitsAsDp()))
        }
    }

    private fun open(key: String, keys: List<String>) {
        navigateTo({ ReaderScreen(it, key, keys) })
    }

    @Composable
    private fun LetterRow(m: Msg, read: Boolean, onClick: () -> Unit) {
        Column(
            Modifier.fillMaxWidth().lightClickable(onClick = onClick).padding(vertical = 0.6f.gridUnitsAsDp()),
        ) {
            OneLine(m.fromName.ifBlank { m.from }, LightTextVariant.Copy, lighten = read)
            OneLine(
                (m.subject.ifBlank { "(no subject)" }) + "  ·  " + Dates.short(m.receivedAt),
                LightTextVariant.Detail,
                lighten = true,
            )
        }
    }

    @Composable
    private fun Welcome() {
        Page(
            title = "Mailbox Lite",
            onBack = null,
            wheel = wheel,
            bottom = listOf(
                LightBarButton.Text(text = "ADD GMAIL", onClick = { navigateTo({ AddAccountScreen(it, gmail = true) }) }),
                LightBarButton.Text(text = "OTHER", onClick = { navigateTo({ AddAccountScreen(it, gmail = false) }) }),
            ),
        ) {
            Spacer(Modifier.height(1f.gridUnitsAsDp()))
            LightText(text = "Mail in two piles.", variant = LightTextVariant.Heading)
            Spacer(Modifier.height(1f.gridUnitsAsDp()))
            LightText(
                text = "Letters are what a person wrote to you. They get the whole screen, a few a day.\n\n" +
                    "Notices are everything a machine sent: receipts, codes, newsletters. One line each.\n\n" +
                    "Add a Gmail account with an app password, or any other mailbox by its IMAP server.",
                variant = LightTextVariant.Paragraph,
            )
        }
    }
}
