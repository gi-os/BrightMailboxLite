package com.gios.brightmailboxlite.sort

/** How many Letters a day. Two states, as in the full app. */
enum class Ration(val key: String, val label: String, val perDay: Int) {
    FIVE("five", "Five a day", 5),
    UNLIMITED("unlimited", "Unlimited", Int.MAX_VALUE);

    companion object {
        fun of(k: String?) = entries.firstOrNull { it.key == k } ?: FIVE
    }
}

/** What the Letters screen needs to know about one Letter. */
data class LetterSlot(
    val key: String,
    val receivedAt: Long,
    /** The learned score, 0..1. Picks which ones; never orders the screen. */
    val score: Double,
    /** The day (yyyymmdd) it was opened here and charged to the ration, or 0. */
    val rationDay: Int,
    /** Read already: here on an earlier day, or elsewhere before it ever reached the phone. */
    val done: Boolean,
)

data class Day(
    /** On screen, newest first. */
    val visible: List<LetterSlot>,
    /** Read today against the ration. */
    val readToday: Int,
    /** Still allowed today; [Int.MAX_VALUE] when unlimited. */
    val allowed: Int,
    /** Unread Letters held back for another day. */
    val waiting: Int,
) {
    /** The ration is spent and nothing more will appear today. */
    val spent: Boolean get() = allowed == 0
}

/**
 * The daily ration, as pure arithmetic.
 *
 * Ported from the full app's `visibleLetters`. Letters read today stay on the screen
 * (reading one must never make another disappear), and the rest of the day's allowance is
 * filled from the unread ones. The model chooses WHICH unread ones, in coarse score
 * buckets so the pick does not reshuffle on every sync; the screen then shows them in time
 * order, because five letters sorted by an invisible number look shuffled.
 */
object Rationing {

    fun day(letters: List<LetterSlot>, ration: Ration, today: Int, extra: Int): Day {
        val held = letters.filter { it.rationDay == today }
        val fresh = letters.filter { it.rationDay == 0 && !it.done }
        if (ration == Ration.UNLIMITED) {
            return Day((held + fresh).sortedByDescending { it.receivedAt }, held.size, Int.MAX_VALUE, 0)
        }
        val allowed = (ration.perDay + extra - held.size).coerceAtLeast(0)
        val picked = fresh.sortedWith(
            compareByDescending<LetterSlot> { (it.score * 20).toInt() }.thenByDescending { it.receivedAt },
        ).take(allowed)
        return Day(
            visible = (held + picked).sortedByDescending { it.receivedAt },
            readToday = held.size,
            allowed = allowed,
            waiting = fresh.size - picked.size,
        )
    }

    /** `yyyymmdd` from calendar fields. */
    fun dayNumber(year: Int, month1: Int, dayOfMonth: Int): Int = year * 10000 + month1 * 100 + dayOfMonth
}
