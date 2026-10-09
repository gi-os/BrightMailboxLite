package com.gios.brightmailboxlite.sort

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RationTest {

    private val today = 20261009

    private fun l(key: String, at: Long, score: Double = 0.5, rationDay: Int = 0, done: Boolean = false) =
        LetterSlot(key, at, score, rationDay, done)

    @Test fun `five unread out of eight, best five, shown in time order`() {
        val letters = (1..8).map { l("m$it", it.toLong(), score = if (it % 2 == 0) 0.9 else 0.1) }
        val d = Rationing.day(letters, Ration.FIVE, today, 0)
        assertEquals(5, d.visible.size)
        assertEquals(3, d.waiting)
        // The four high scorers are all in, then the newest low scorer.
        assertEquals(listOf("m8", "m7", "m6", "m4", "m2"), d.visible.map { it.key })
    }

    @Test fun `reading one does not make another disappear`() {
        val before = (1..6).map { l("m$it", it.toLong()) }
        val d0 = Rationing.day(before, Ration.FIVE, today, 0)
        val read = d0.visible.first().key
        val after = before.map { if (it.key == read) it.copy(rationDay = today) else it }
        val d1 = Rationing.day(after, Ration.FIVE, today, 0)
        assertEquals(d0.visible.map { it.key }.toSet(), d1.visible.map { it.key }.toSet())
        assertEquals(1, d1.readToday)
        assertEquals(4, d1.allowed)
    }

    @Test fun `five read means the day is spent`() {
        val letters = (1..7).map { l("m$it", it.toLong(), rationDay = if (it <= 5) today else 0) }
        val d = Rationing.day(letters, Ration.FIVE, today, 0)
        assertTrue(d.spent)
        assertEquals(5, d.visible.size)
        assertEquals(2, d.waiting)
        val more = Rationing.day(letters, Ration.FIVE, today, 1)
        assertEquals(6, more.visible.size)
    }

    @Test fun `yesterday's reading and mail read elsewhere are not on today's list`() {
        val letters = listOf(
            l("old", 1, rationDay = 20261008),
            l("elsewhere", 2, done = true),
            l("new", 3),
        )
        val d = Rationing.day(letters, Ration.FIVE, today, 0)
        assertEquals(listOf("new"), d.visible.map { it.key })
    }

    @Test fun `unlimited shows everything unread`() {
        val letters = (1..30).map { l("m$it", it.toLong()) }
        val d = Rationing.day(letters, Ration.UNLIMITED, today, 0)
        assertEquals(30, d.visible.size)
        assertEquals(0, d.waiting)
    }
}
