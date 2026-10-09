package com.gios.brightmailboxlite.ui

import android.view.KeyEvent
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.gios.brightmailboxlite.auth.QrSignIn
import com.gios.brightmailboxlite.auth.Servers
import com.gios.brightmailboxlite.auth.Service
import com.gios.brightmailboxlite.data.Account
import com.gios.brightmailboxlite.data.Mailbox
import com.gios.brightmailboxlite.mail.Addr
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.launch

/**
 * Sign in, by typing or by scanning the full app's sign-in QR.
 *
 * Gmail needs an address and a sixteen-letter app password. Anything else needs its IMAP
 * server too; the SMTP server is guessed from it and can be corrected. Either way the
 * credential is tried against the server before it is kept.
 */
class AddAccountScreen(sealedActivity: SealedLightActivity, private val gmail: Boolean) :
    SimpleLightScreen<Unit>(sealedActivity) {

    private val wheel = WheelEvents()

    private var email by mutableStateOf("")
    private var password by mutableStateOf("")
    private var user by mutableStateOf("")
    private var imapHost by mutableStateOf("")
    private var imapPort by mutableStateOf("993")
    private var smtpHost by mutableStateOf("")
    private var smtpPort by mutableStateOf("465")
    private var busy by mutableStateOf(false)
    private var modal by mutableStateOf<String?>(null)
    private var done by mutableStateOf(false)

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = wheel.handle(keyCode, event)

    private fun edit(title: String, value: String, set: (String) -> Unit) {
        navigateTo({ EditorScreen(it, title, value) }) { set(it) }
    }

    @Composable
    override fun Content() {
        modal?.let { m ->
            Themed {
                LightFullscreenModal(message = m, onClose = {
                    modal = null
                    if (done) goBack()
                })
            }
            return
        }
        if (busy) {
            Themed { LightFullscreenModal(message = "Checking with the server…", onClose = {}) }
            return
        }

        val bottom = buildList {
            if (gmail) add(LightBarButton.Text(text = "SCAN", onClick = { scan() }))
            add(LightBarButton.Text(text = "SIGN IN", onClick = { signIn() }))
        }

        Page(title = if (gmail) "Add Gmail" else "Add mail", onBack = { goBack() }, wheel = wheel, bottom = bottom) {
            if (gmail) {
                Note("Make an app password at myaccount.google.com/apppasswords (2-Step Verification must be on). Or open gi-os.github.io/BrightMailbox on a computer and scan its code.")
            } else {
                Note("Any mailbox with IMAP. Most providers want an app password here, not your account password.")
            }
            LightTextField(label = "Email", value = email, placeholder = "you@example.com", onClick = {
                edit("Email", email) { email = it.trim() }
            })
            if (!gmail) {
                LightTextField(label = "User name", value = user, placeholder = "same as email", onClick = {
                    edit("User name", user) { user = it.trim() }
                })
            }
            LightTextField(
                label = if (gmail) "App password" else "Password",
                value = if (password.isEmpty()) "" else "•".repeat(password.length.coerceAtMost(16)),
                placeholder = if (gmail) "16 letters" else "password",
                onClick = { edit(if (gmail) "App password" else "Password", password) { password = it } },
            )
            if (!gmail) {
                LightTextField(label = "IMAP server", value = imapHost, placeholder = "imap.example.com", onClick = {
                    edit("IMAP server", imapHost) { h ->
                        imapHost = h.trim().lowercase()
                        if (smtpHost.isBlank()) smtpHost = Servers.guess(imapHost).smtpHost
                    }
                })
                LightTextField(label = "IMAP port", value = imapPort, placeholder = "993", onClick = {
                    edit("IMAP port", imapPort) { imapPort = it.filter(Char::isDigit) }
                })
                LightTextField(label = "SMTP server", value = smtpHost, placeholder = "smtp.example.com", onClick = {
                    edit("SMTP server", smtpHost) { smtpHost = it.trim().lowercase() }
                })
                LightTextField(label = "SMTP port", value = smtpPort, placeholder = "465 or 587", onClick = {
                    edit("SMTP port", smtpPort) { smtpPort = it.filter(Char::isDigit) }
                })
                Note("IMAP uses TLS from the first byte (usually 993). SMTP uses TLS on 465 and STARTTLS on 587.")
            }
            Spacer(Modifier.height(2f.gridUnitsAsDp()))
        }
    }

    private fun scan() {
        navigateTo({ ScanScreen(it) }) { raw ->
            when (val r = QrSignIn.parse(raw)) {
                is QrSignIn.Result.Bad -> modal = r.why
                is QrSignIn.Result.Ok -> {
                    email = r.email
                    password = r.password
                    if (r.service == Service.GOOGLE) signIn()
                }
            }
        }
    }

    private fun signIn() {
        val address = Addr.address(email.trim())
        if (address == null) { modal = "That email address does not look right."; return }
        val pw = if (gmail) password.filterNot { it.isWhitespace() } else password
        if (pw.isEmpty()) { modal = "The password is empty."; return }
        val servers = if (gmail) Service.GMAIL_SERVERS else Servers(
            imapHost = imapHost.trim(),
            imapPort = imapPort.toIntOrNull() ?: 993,
            smtpHost = smtpHost.trim().ifBlank { Servers.guess(imapHost).smtpHost },
            smtpPort = smtpPort.toIntOrNull() ?: 465,
            smtpSsl = Servers.sslForPort(smtpPort.toIntOrNull() ?: 465),
        )
        if (!servers.valid) { modal = "The IMAP server is missing."; return }
        val draft = Account(
            id = "",
            service = if (gmail) Service.GOOGLE.key else Service.IMAP.key,
            email = address,
            user = if (gmail) address else user.trim().ifBlank { address },
            imapHost = servers.imapHost,
            imapPort = servers.imapPort,
            smtpHost = servers.smtpHost,
            smtpPort = servers.smtpPort,
            smtpSsl = servers.smtpSsl,
        )
        busy = true
        Mailbox.scope.launch {
            val r = Mailbox.addAccount(lightContext, draft, pw)
            busy = false
            r.onSuccess {
                done = true
                modal = "Signed in as ${it.email}. The first check reads the last two weeks of mail and your Sent folder, so it can take a minute."
                Mailbox.syncInBackground(lightContext)
            }.onFailure { modal = it.message ?: "Sign-in failed." }
        }
    }
}
