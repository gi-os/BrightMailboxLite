package com.gios.brightmailboxlite.ui

import android.view.KeyEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.gios.brightmailboxlite.data.Mailbox
import com.gios.brightmailboxlite.sort.Ration
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.launch

/**
 * Accounts, the ration, and every sender the user has moved — each listed so it can be
 * undone. Automation you cannot see or undo is not worth shipping.
 */
class SettingsScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {

    private val wheel = WheelEvents()

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = wheel.handle(keyCode, event)

    @Composable
    override fun Content() {
        val accounts by Mailbox.accounts.collectAsState()
        val book by Mailbox.book.collectAsState()
        val status by Mailbox.status.collectAsState()
        val ration = Mailbox.ration(book)

        Page(title = "Settings", onBack = { goBack() }, wheel = wheel) {
            SectionLabel("Accounts")
            for (a in accounts) {
                Column(
                    Modifier.fillMaxWidth()
                        .lightClickable {
                            navigateTo({
                                ConfirmScreen(it, "Remove", "Remove ${a.email} from this phone? Its mail and password are deleted here; nothing changes on the server.", "remove")
                            }) { yes ->
                                if (yes) Mailbox.scope.launch { Mailbox.removeAccount(lightContext, a.id) }
                            }
                        }
                        .padding(vertical = 0.4f.gridUnitsAsDp()),
                ) {
                    OneLine(a.email, LightTextVariant.Copy)
                    OneLine("${a.label}  ·  ${a.imapHost}  ·  tap to remove", LightTextVariant.Fine, lighten = true)
                }
            }
            Link("Add Gmail", { navigateTo({ AddAccountScreen(it, gmail = true) }) })
            Link("Add other mail (IMAP)", { navigateTo({ AddAccountScreen(it, gmail = false) }) })

            SectionLabel("Letters a day")
            for (r in Ration.entries) {
                val on = r == ration
                LightText(
                    text = (if (on) "● " else "○ ") + r.label,
                    variant = LightTextVariant.Copy,
                    lighten = !on,
                    modifier = Modifier.fillMaxWidth()
                        .lightClickable { Mailbox.scope.launch { Mailbox.setRation(lightContext, r) } }
                        .padding(vertical = 0.4f.gridUnitsAsDp()),
                )
            }

            SectionLabel("Senders you moved")
            if (book.overrides.isEmpty()) Note("None. Open a message and use the line at the bottom to move its sender.")
            for ((addr, pile) in book.overrides.toSortedMap()) {
                Column(
                    Modifier.fillMaxWidth()
                        .lightClickable { Mailbox.scope.launch { Mailbox.forgetOverride(lightContext, addr) } }
                        .padding(vertical = 0.4f.gridUnitsAsDp()),
                ) {
                    OneLine(addr, LightTextVariant.Detail)
                    OneLine((if (pile == "NOTICE") "to Notices" else "to Letters") + "  ·  tap to undo", LightTextVariant.Fine, lighten = true)
                }
            }

            SectionLabel("Checking")
            Note(
                "Every 5 minutes while Mailbox is open, and about every 15 minutes in the background." +
                    if (status.lastSyncAt > 0) " Last checked at ${Dates.time(status.lastSyncAt)}." else "",
            )
            status.error?.let { Note(it) }
            Note("Senders you have written to: ${book.replies.size}. They always land in Letters.")
            Spacer(Modifier.height(2f.gridUnitsAsDp()))
        }
    }
}
