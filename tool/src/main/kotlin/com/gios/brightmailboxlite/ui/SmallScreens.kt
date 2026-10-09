package com.gios.brightmailboxlite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.LightQrCodeScanner
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp

/** Full-screen keyboard for one value. Returns the text, or nothing if backed out of. */
class EditorScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val initial: String,
    private val caps: Boolean = false,
) : SimpleLightScreen<String>(sealedActivity) {

    @Composable
    override fun Content() {
        val options = rememberKeyboardOptions()
        val state = rememberTextFieldState(initial)
        Themed {
            LightTextInputEditor(
                title = title,
                state = state,
                keyboardOptionsFlow = options,
                submitLabel = "DONE",
                singleLine = true,
                initialCaps = caps,
                onSubmit = { goBack(it.toString().trim()) },
                onBack = { goBack() },
                modifier = Modifier.background(LightThemeTokens.colors.background),
            )
        }
    }
}

/** The SDK's QR scanner. Returns the decoded text. */
class ScanScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<String>(sealedActivity) {

    @Composable
    override fun Content() {
        var scanned by remember { mutableStateOf<String?>(null) }
        Themed {
            LightQrCodeScanner(
                title = "Scan the sign-in code",
                onScanned = { if (scanned == null) scanned = it },
                onBack = { goBack() },
                modifier = Modifier.background(LightThemeTokens.colors.background),
            )
        }
        LaunchedEffect(scanned) {
            scanned?.let { goBack(it) }
        }
    }
}

/** A yes-or-back question. Returns true only on yes. */
class ConfirmScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val question: String,
    private val yes: String,
) : SimpleLightScreen<Boolean>(sealedActivity) {

    @Composable
    override fun Content() {
        Page(
            title = title,
            onBack = { goBack() },
            wheel = null,
            bottom = listOf(LightBarButton.Text(text = yes.uppercase(), onClick = { goBack(true) })),
        ) {
            Spacer(Modifier.height(2f.gridUnitsAsDp()))
            LightText(text = question, variant = LightTextVariant.Copy)
        }
    }
}
