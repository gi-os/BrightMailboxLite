package com.gios.brightmailboxlite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.gios.brightmailboxlite.data.Mailbox
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightThemeTokens
import kotlinx.coroutines.launch

/**
 * A plain-text reply. Returns the address it went to.
 *
 * The text typed here goes first, then the original, quoted. On failure the text stays
 * in the editor so nothing typed on a small keyboard is lost.
 *
 * The on-screen back arrow asks before throwing a reply away. The system back pops the screen
 * without asking it, and tools may not intercept it, so a reply left that way is kept as a
 * draft and comes back the next time this message's reply opens.
 */
class ReplyScreen(sealedActivity: SealedLightActivity, private val key: String) :
    SimpleLightScreen<String>(sealedActivity) {

    private var sending by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var discarding by mutableStateOf(false)
    private var finished = false
    private var gone = false

    // Kept here, not remembered, so the text survives the discard page and can be saved as a draft.
    private val state = TextFieldState(drafts[key] ?: "")

    private fun finish(result: String? = null) {
        finished = true
        goBack(result)
    }

    private fun back() {
        if (state.text.isNotBlank()) discarding = true else finish()
    }

    override fun onScreenDestroy() {
        gone = true
        val text = state.text.toString()
        if (!finished && text.isNotBlank()) drafts[key] = text else drafts.remove(key)
    }

    @Composable
    override fun Content() {
        val msg = Mailbox.find(key)
        val to = msg?.replyTo ?: msg?.from.orEmpty()
        val options = rememberKeyboardOptions()

        if (discarding) {
            Page(
                title = "Discard reply?",
                onBack = { discarding = false },
                wheel = null,
                bottom = listOf(
                    LightBarButton.Text(text = "DISCARD", onClick = { finish() }),
                    LightBarButton.Text(text = "KEEP EDITING", onClick = { discarding = false }),
                ),
            ) { }
            return
        }
        // Sending cannot be taken back, so it shows no way to cancel.
        if (sending && error == null) {
            Busy("Sending to $to…")
            return
        }

        Themed {
            when {
                error != null -> LightFullscreenModal(message = error.orEmpty(), onClose = { error = null })
                else -> LightTextInputEditor(
                    title = "Reply to " + (msg?.fromName?.ifBlank { to } ?: to),
                    state = state,
                    keyboardOptionsFlow = options,
                    submitLabel = "SEND",
                    onSubmit = { text -> send(text.toString(), to) },
                    onBack = ::back,
                    modifier = Modifier.background(LightThemeTokens.colors.background),
                    initialCaps = true,
                )
            }
        }
    }

    private fun send(text: String, to: String) {
        if (text.isBlank() || sending) return
        sending = true
        Mailbox.scope.launch {
            val r = Mailbox.reply(lightContext, key, text)
            sending = false
            r.onSuccess {
                drafts.remove(key)
                // A system back during the send already closed this screen; popping again
                // would close the one underneath.
                if (!gone) finish(to)
            }.onFailure { error = it.message ?: "Sending failed." }
        }
    }

    private companion object {
        /** Unsent replies left by the system back, by message. Lives as long as the tool's process. */
        val drafts = mutableMapOf<String, String>()
    }
}
