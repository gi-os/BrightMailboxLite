package com.gios.brightmailboxlite.ui

import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.gios.brightmailboxlite.data.Body
import com.gios.brightmailboxlite.data.Mailbox
import com.gios.brightmailboxlite.sort.Pile
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.launch

/**
 * One message, given the whole screen and set like a page: the subject, who and when,
 * then the text with quoting and signatures already cut. The wheel turns the page.
 *
 * At the bottom, in plain words, why it landed in its pile, and the one action that
 * changes that for everything from the same sender.
 */
class ReaderScreen(
    sealedActivity: SealedLightActivity,
    startKey: String,
    /** The list it was opened from, so NEXT can walk it. */
    private val keys: List<String>,
) : SimpleLightScreen<Unit>(sealedActivity) {

    private val wheel = WheelEvents()
    private var key by mutableStateOf(startKey)
    private var body by mutableStateOf<Body?>(null)
    private var error by mutableStateOf<String?>(null)
    private var modal by mutableStateOf<String?>(null)
    private var attempt by mutableIntStateOf(0)

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = wheel.handle(keyCode, event)

    private val next: String?
        get() = keys.indexOf(key).takeIf { it >= 0 && it + 1 < keys.size }?.let { keys[it + 1] }

    @Composable
    override fun Content() {
        val messages by Mailbox.messages.collectAsState()
        val msg = messages.firstOrNull { it.key == key }

        LaunchedEffect(key, attempt) {
            body = null
            error = null
            val r = Mailbox.open(lightContext, key)
            r.onSuccess { body = it }.onFailure { error = it.message ?: "Could not open this message." }
        }

        modal?.let {
            Themed { LightFullscreenModal(message = it, onClose = { modal = null }) }
            return
        }

        val bottom = buildList {
            if (msg != null) add(LightBarButton.LightIcon(icon = LightIcons.PENCIL, contentDescription = "reply", onClick = { reply() }))
            next?.let { n -> add(LightBarButton.LightIcon(icon = LightIcons.ARROW_RIGHT, contentDescription = "next", onClick = { key = n })) }
        }

        androidx.compose.runtime.key(key) {
            Page(
                title = msg?.let { it.fromName.ifBlank { it.from } } ?: "",
                onBack = { goBack() },
                wheel = wheel,
                bottom = bottom,
            ) {
                if (msg == null) {
                    LightText(text = "That message is gone.", variant = LightTextVariant.Copy)
                    return@Page
                }
                LightText(text = msg.subject.ifBlank { "(no subject)" }, variant = LightTextVariant.Subheading)
                Note(msg.from + "  ·  " + Dates.full(msg.receivedAt))
                Spacer(Modifier.height(1f.gridUnitsAsDp()))

                val b = body
                when {
                    error != null -> {
                        LightText(text = error.orEmpty(), variant = LightTextVariant.Copy)
                        Link("Try again", { attempt++ })
                    }
                    b == null -> Note("Opening…")
                    else -> {
                        LightText(text = b.text, variant = LightTextVariant.Paragraph)
                        Spacer(Modifier.height(1f.gridUnitsAsDp()))
                        if (b.quoted > 0) {
                            Note(if (b.quoted == 1) "1 earlier message is quoted below this one and not shown." else "${b.quoted} earlier messages are quoted below this one and not shown.")
                        }
                        if (b.attachments.isNotEmpty()) {
                            Note("Attached: " + b.attachments.joinToString(", ") + ". Open attachments on a computer.")
                        }
                        if (b.truncated) Note("This message is long; only the first part is shown.")
                        if (b.fromHtml) Note("Shown as text. Images and layout are left out.")
                    }
                }

                Spacer(Modifier.height(1f.gridUnitsAsDp()))
                SectionLabel(if (msg.isLetter) "A Letter because it" else "A Notice because it")
                Note(msg.reason.ifBlank { "was sorted before the rules ran" } + ".")
                val target = if (msg.isLetter) Pile.NOTICE else Pile.LETTER
                Link(
                    if (target == Pile.NOTICE) "Send ${msg.from} to Notices" else "Bring ${msg.from} to Letters",
                    {
                        val from = msg.from
                        Mailbox.scope.launch {
                            Mailbox.moveSender(lightContext, from, target)
                            modal = if (target == Pile.NOTICE) "Mail from $from goes to Notices from now on. Undo it in Settings."
                            else "Mail from $from goes to Letters from now on. Undo it in Settings."
                        }
                    },
                    LightTextVariant.Detail,
                )
                Box(Modifier.height(2f.gridUnitsAsDp()))
            }
        }
    }

    private fun reply() {
        val k = key
        navigateTo({ ReplyScreen(it, k) }) { sentTo -> modal = "Sent to $sentTo." }
    }
}
