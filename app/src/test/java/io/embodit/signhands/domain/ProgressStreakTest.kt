package io.embodit.signhands.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** v0.3.0 home page: streak, learned counts, and the active days survive a save. */
class ProgressStreakTest {
    @Test
    fun streakCountsDaysInARowEndingTodayOrYesterday() {
        val p = Progress(activeDays = setOf(10L, 11L, 12L, 20L))
        assertEquals(3, p.streak(12L))
        assertEquals(3, p.streak(13L))
        assertEquals(0, p.streak(14L))
        assertEquals(1, p.streak(20L))
        assertEquals(1, p.practised(21L).streak(21L) - p.streak(21L))
    }

    @Test
    fun learnedCountsAndJsonRoundTrip() {
        val p = Progress(followed = setOf("A", "B", "w:HELLO"), activeDays = setOf(5L, 6L))
        assertEquals(2, p.lettersLearned)
        assertEquals(1, p.wordsLearned)
        val q = Progress.fromJson(p.toJson())
        assertEquals(p.activeDays, q.activeDays)
        assertEquals(p.followed, q.followed)
    }
}
