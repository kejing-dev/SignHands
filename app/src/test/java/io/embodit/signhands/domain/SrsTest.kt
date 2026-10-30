package io.embodit.signhands.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class SrsTest {
    @Test
    fun leitnerBoxes() {
        var r = Srs.afterQuiz(LetterRecord(), correct = false, today = 100)
        assertEquals(1, r.box)
        assertEquals(101, r.due)
        r = Srs.afterReview(r, true, 101)
        assertEquals(2, r.box)
        assertEquals(104, r.due)
        r = Srs.afterReview(r, true, 104)
        r = Srs.afterReview(r, true, 111)
        r = Srs.afterReview(r, true, 125)
        assertEquals(5, r.box)
        assertEquals(155, r.due)
        r = Srs.afterReview(r, true, 155)
        assertEquals(5, r.box)
        r = Srs.afterReview(r, false, 185)
        assertEquals(1, r.box)
        assertEquals(186, r.due)
        assertEquals(5, r.correct)
        assertEquals(2, r.wrong)
    }

    @Test
    fun progressJsonRoundTrip() {
        val p = Progress(mapOf("A" to LetterRecord(2, 20000, 3, 2, 1)), mapOf(1 to LessonRecord(true, 4)), 2, setOf("A", "B"))
        assertEquals(p, Progress.fromJson(p.toJson()))
        assertEquals(listOf("A"), p.due(20000))
        assertEquals(emptyList<String>(), p.due(19999))
    }
}
