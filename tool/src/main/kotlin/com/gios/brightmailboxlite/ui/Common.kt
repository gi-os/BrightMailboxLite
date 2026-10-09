package com.gios.brightmailboxlite.ui

import android.view.KeyEvent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The LPIII's scroll wheel.
 *
 * It is an optical sensor that sends one key press per notch. LightOS labels the codes
 * `WHEEL_CW` / `WHEEL_CCW`; those are Light's additions, so their numbers are resolved by
 * name at runtime, with the raw scan codes from the hardware (20 and 19) as a fallback
 * when the event comes from the wheel's own input device. D-pad up/down also work, for
 * the emulator. This mirrors the full app's `LightKeys`.
 */
object Wheel {
    private val cw = code("WHEEL_CW")
    private val ccw = code("WHEEL_CCW")
    private const val SCAN_DOWN = 20
    private const val SCAN_UP = 19
    private const val WHEEL_DEVICE = "Pixart pat9126ja"

    private fun code(label: String): Int =
        runCatching { KeyEvent.keyCodeFromString(label) }.getOrDefault(KeyEvent.KEYCODE_UNKNOWN)

    /** +1 for a notch towards the bottom, -1 towards the top, 0 if this key is not the wheel. */
    fun step(keyCode: Int, event: KeyEvent): Int {
        if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
            if (keyCode == cw) return 1
            if (keyCode == ccw) return -1
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return 1
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return -1
        if (event.device?.name == WHEEL_DEVICE) {
            if (event.scanCode == SCAN_DOWN) return 1
            if (event.scanCode == SCAN_UP) return -1
        }
        return 0
    }
}

/** A screen's wheel notches, consumed by whatever scroll view it shows. */
class WheelEvents {
    private val flow = MutableSharedFlow<Int>(extraBufferCapacity = 32)
    val notches: SharedFlow<Int> = flow

    fun handle(keyCode: Int, event: KeyEvent): Boolean {
        val s = Wheel.step(keyCode, event)
        if (s == 0) return false
        flow.tryEmit(s)
        return true
    }
}

/** Scroll [state] by about three lines per notch. */
@Composable
fun FollowWheel(wheel: WheelEvents, state: ScrollState) {
    val step = with(LocalDensity.current) { 96.dp.toPx() }
    LaunchedEffect(wheel, state) {
        wheel.notches.collect { state.animateScrollBy(it * step) }
    }
}

/** The tool's theme around a screen, with the LightOS background. */
@Composable
fun Themed(content: @Composable () -> Unit) {
    val colors by LightThemeController.colors.collectAsState()
    LightTheme(colors = colors) {
        Box(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) { content() }
    }
}

/**
 * The common page: a top bar, a scrolling middle, and an optional bottom bar.
 */
@Composable
fun Page(
    title: String,
    onBack: (() -> Unit)?,
    wheel: WheelEvents?,
    left: LightBarButton? = null,
    right: LightBarButton? = null,
    bottom: List<LightBarButton?> = emptyList(),
    scroll: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Themed {
        Column(Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = left ?: onBack?.let { LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = it) },
                center = LightTopBarCenter.Text(title),
                rightButton = right,
                modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
            )
            if (wheel != null) FollowWheel(wheel, scroll)
            LightScrollView(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp()),
                scrollState = scroll,
                content = content,
            )
            if (bottom.isNotEmpty()) LightBottomBar(bottom)
        }
    }
}

/** A tappable line of text. */
@Composable
fun Link(text: String, onClick: () -> Unit, variant: LightTextVariant = LightTextVariant.Copy) {
    LightText(
        text = text,
        variant = variant,
        underline = true,
        modifier = Modifier.fillMaxWidth().lightClickable(onClick = onClick).padding(vertical = 0.5f.gridUnitsAsDp()),
    )
}

@Composable
fun Note(text: String, modifier: Modifier = Modifier) {
    LightText(text = text, variant = LightTextVariant.Detail, lighten = true, modifier = modifier.padding(vertical = 0.25f.gridUnitsAsDp()))
}

@Composable
fun SectionLabel(text: String) {
    LightText(
        text = text.uppercase(),
        variant = LightTextVariant.Fine,
        lighten = true,
        modifier = Modifier.padding(top = 1f.gridUnitsAsDp(), bottom = 0.25f.gridUnitsAsDp()),
    )
}

/** One line, ellipsised. */
@Composable
fun OneLine(text: String, variant: LightTextVariant, lighten: Boolean = false, modifier: Modifier = Modifier) {
    LightText(text = text, variant = variant, lighten = lighten, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

object Dates {
    private fun cal(ms: Long) = Calendar.getInstance().apply { timeInMillis = ms }

    private fun sameDay(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    /** "Today", "Yesterday", "Mon 6 Oct". */
    fun dayLabel(ms: Long): String {
        val now = Calendar.getInstance()
        val c = cal(ms)
        if (sameDay(now, c)) return "Today"
        now.add(Calendar.DAY_OF_YEAR, -1)
        if (sameDay(now, c)) return "Yesterday"
        return SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(ms))
    }

    /** "14:02" today, "Mon 6 Oct" otherwise. */
    fun short(ms: Long): String =
        if (sameDay(Calendar.getInstance(), cal(ms))) SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
        else SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(ms))

    fun full(ms: Long): String = SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(ms))

    fun time(ms: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
}
