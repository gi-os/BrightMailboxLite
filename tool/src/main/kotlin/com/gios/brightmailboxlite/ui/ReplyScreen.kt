package com.gios.brightmailboxlite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.gios.brightmailboxlite.data.Mailbox
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightThemeTokens
import kotlinx.coroutines.launch

/**
 * A plain-text reply. Returns the address it went to.
 *
 * The text typed here goes first, then the original, quoted. On failure the text stays
 * in the editor so nothing typed on a small keyboard is lost.
 */
class ReplyScreen(sealedActivity: SealedLightActivity, private val key: String) :
    SimpleLightScreen<String>(sealedActivity) {

    private var sending by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)

    @Composable
    override fun Content() {
        val msg = Mailbox.find(key)
        val to = msg?.replyTo ?: msg?.from.orEmpty()
        val options = rememberKeyboardOptions()
        val state = rememberTextFieldState("")

        Themed {
            when {
                error != null -> LightFullscreenModal(message = error.orEmpty(), onClose = { error = null })
                sending -> LightFullscreenModal(message = "Sending to $to…", onClose = {})
                else -> LightTextInputEditor(
                    title = "Reply to " + (msg?.fromName?.ifBlank { to } ?: to),
                    state = state,
                    keyboardOptionsFlow = options,
                    submitLabel = "SEND",
                    onSubmit = { text -> send(text.toString(), to) },
                    onBack = { goBack() },
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
            r.onSuccess { goBack(to) }.onFailure { error = it.message ?: "Sending failed." }
        }
    }
}
