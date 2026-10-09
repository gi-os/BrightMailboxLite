package com.gios.brightmailboxlite.ui

import android.view.KeyEvent
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.gios.brightmailboxlite.data.Mailbox
import com.gios.brightmailboxlite.text.Clean
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

enum class ListKind { NOTICES, EARLIER }

/**
 * Notices, one line each and grouped by day — or, with [ListKind.EARLIER], every Letter
 * not on today's list, in the same shape. Nothing here counts against the ration.
 */
class ListScreen(sealedActivity: SealedLightActivity, private val kind: ListKind) :
    SimpleLightScreen<Unit>(sealedActivity) {

    private val wheel = WheelEvents()

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = wheel.handle(keyCode, event)

    @Composable
    override fun Content() {
        val messages by Mailbox.messages.collectAsState()
        val book by Mailbox.book.collectAsState()

        val shown = when (kind) {
            ListKind.NOTICES -> messages.filter { !it.isLetter }
            ListKind.EARLIER -> {
                val today = Mailbox.day(messages, book).visible.map { it.key }.toSet()
                messages.filter { it.isLetter && it.key !in today }
            }
        }.sortedByDescending { it.receivedAt }
        val keys = shown.map { it.key }

        Page(
            title = if (kind == ListKind.NOTICES) "Notices" else "Earlier Letters",
            onBack = { goBack() },
            wheel = wheel,
        ) {
            if (shown.isEmpty()) {
                LightText(text = if (kind == ListKind.NOTICES) "No Notices." else "Nothing earlier.", variant = LightTextVariant.Copy)
            }
            var lastDay = ""
            for (m in shown) {
                val day = Dates.dayLabel(m.receivedAt)
                if (day != lastDay) {
                    SectionLabel(day)
                    lastDay = day
                }
                val line = m.fromName.ifBlank { m.from } + "  " + Clean.noticeLine(m.subject.ifBlank { "(no subject)" })
                OneLine(
                    line,
                    LightTextVariant.Detail,
                    lighten = m.readDay != 0 || m.seenOnServer,
                    modifier = Modifier.fillMaxWidth()
                        .lightClickable { navigateTo({ ReaderScreen(it, m.key, keys) }) }
                        .padding(vertical = 0.4f.gridUnitsAsDp()),
                )
            }
            Spacer(Modifier.height(1f.gridUnitsAsDp()))
        }
    }
}
