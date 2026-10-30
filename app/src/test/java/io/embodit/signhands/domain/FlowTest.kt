package io.embodit.signhands.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowTest {
    private val book = TestBook.book
    private var now = 0L
    private val today = 20000L

    private fun flow(first: Boolean = true): Flow {
        val f = Flow(book) { 0 }
        f.todayProvider = { today }
        f.loaded(if (first) null else Settings(), Progress())
        f.ready(now, Support.OK)
        return f
    }

    private fun Flow.wait(ms: Long, day: Long = today) {
        var left = ms
        while (left > 0) {
            now += 100
            left -= 100
            tick(now, 100, day)
        }
    }

    @Test
    fun firstRunChoosesTheHand() {
        val f = flow()
        assertEquals(Screen.S01, f.state.screen)
        f.chooseHand(Handed.LEFT, now)
        assertEquals(Screen.S04, f.state.screen)
        assertEquals(Handed.LEFT, f.state.settings.hand)
        assertTrue(Effect.SaveSettings in f.drain())
    }

    @Test
    fun trackingOffVariants() {
        val f = Flow(book)
        f.loaded(Settings(), Progress())
        f.ready(0, Support.NO_PERMISSION)
        assertEquals(Screen.S03, f.state.screen)
        assertEquals("b", f.state.variant)
        f.recheck(Support.OFF, 0)
        assertEquals("a", f.state.variant)
        f.recheck(Support.OK, 0)
        assertEquals(Screen.S04, f.state.screen)
    }

    @Test
    fun lessonOneWithQuizAndReview() {
        val f = flow(first = false)
        f.handVisible(true, now)
        f.openLesson(1, now)
        assertEquals(Screen.S05, f.state.screen)
        for (l in listOf("A", "B", "C", "D")) {
            assertEquals(l, f.state.letter)
            f.tryIt(now)
            assertEquals(Screen.S06, f.state.screen)
            f.matched(now)
            assertEquals(Screen.S07, f.state.screen)
            f.wait(1000)
        }
        // E is a self-check letter
        assertEquals("E", f.state.letter)
        f.tryIt(now)
        assertEquals(Screen.S08, f.state.screen)
        f.selfDone(now)
        assertEquals(Screen.S09, f.state.screen)
        assertEquals(5, f.state.quiz!!.items.size)
        var wrongOnce = false
        var guard = 0
        while (f.state.screen == Screen.S09 && guard++ < 20) {
            val letter = book[f.state.letter!!]
            if (!letter.scored) {
                f.quizSelfReady(now)
                assertEquals("c", f.state.variant)
                f.answer(true, today, now)
            } else if (!wrongOnce) {
                wrongOnce = true
                f.wait(15_000)
                assertEquals("no countdown in v0.1.1", Screen.S09, f.state.screen)
                f.showAnswer(now)
                assertEquals(Screen.S10, f.state.screen)
                assertEquals("b", f.state.variant)
                f.answer(null, today, now)
            } else {
                f.matched(now)
                assertEquals("a", f.state.variant)
                f.answer(null, today, now)
            }
        }
        // the wrong one came back once ("again 1 / 1") before the end
        assertEquals(Screen.S11, f.state.screen)
        val done = f.state.done!!
        assertEquals(4, done.firstTry)
        assertEquals(5, done.total)
        assertTrue(f.state.progress.lessons[1]!!.done)
        assertTrue(f.state.progress.letters.values.all { it.box == 1 && it.due == today + 1 })
        assertEquals(5, f.state.progress.due(today + 1).size)
        f.home(now)
        f.startReview(today + 1, now)
        assertEquals(Screen.S09, f.state.screen)
        guard = 0
        while (f.state.screen != Screen.S12 && guard++ < 20) {
            if (f.state.screen == Screen.S09) {
                if (book[f.state.letter!!].scored) f.matched(now) else f.quizSelfReady(now)
            }
            f.answer(true, today + 1, now)
        }
        assertEquals(Screen.S12, f.state.screen)
        assertEquals(5, f.state.reviewDone!!.correct)
        assertTrue(f.state.progress.letters.values.all { it.box == 2 })
    }

    @Test
    fun skipNeedsTwoPinchesAndLeavesTheLetterOut() {
        val f = flow(first = false)
        f.handVisible(true, now)
        f.openLesson(2, now)
        f.tryIt(now)
        assertEquals(Screen.S06, f.state.screen)
        assertFalse(f.armOrConfirm("skip", now, today))
        assertEquals("skip", f.state.armed)
        now += 2500
        f.tick(now, 100, today)
        assertNull(f.state.armed)
        f.armOrConfirm("skip", now, today)
        assertTrue(f.armOrConfirm("skip", now + 500, today))
        assertEquals(Screen.S05, f.state.screen)
        assertEquals("G", f.state.letter)
        assertEquals(setOf(0), f.state.course!!.skipped)
    }

    @Test
    fun nextLetterWithoutPenalty() {
        val f = flow(first = false)
        f.handVisible(true, now)
        f.openLesson(1, now)
        f.tryIt(now)
        assertEquals(Screen.S06, f.state.screen)
        f.nextLetter(now)
        assertEquals(Screen.S05, f.state.screen)
        assertEquals("B", f.state.letter)
        assertTrue("A" in f.state.progress.followed)
        assertTrue(f.state.course!!.skipped.isEmpty())
    }

    @Test
    fun handLostPausesTheQuiz() {
        val f = flow(first = false)
        f.handVisible(true, now)
        f.debugSet(f.state.copy(quiz = Quiz(false, 1, listOf("B", "A"))))
        f.debugGo(Screen.S09, now)
        f.wait(3000)
        f.handVisible(false, now)
        assertEquals(Screen.S02, f.state.screen)
        assertEquals("b", f.state.variant)
        f.wait(5000)
        assertEquals(Screen.S02, f.state.screen)
        f.handVisible(true, now)
        assertEquals(Screen.S09, f.state.screen)
    }

    @Test
    fun enteringFollowWithoutAHandAsksForIt() {
        val f = flow(first = false)
        f.openLesson(1, now)
        f.tryIt(now)
        assertEquals(Screen.S02, f.state.screen)
        assertEquals("a", f.state.variant)
        f.handVisible(true, now)
        assertEquals(Screen.S06, f.state.screen)
    }

    @Test
    fun watchOnlyWalksALesson() {
        val f = flow(first = false)
        f.watchOnly(now)
        f.openLesson(3, now)
        repeat(5) { f.tryIt(now) }
        assertEquals(Screen.S11, f.state.screen)
        assertTrue(f.state.done!!.watchOnly)
        assertEquals(0, f.state.progress.letters.size)
    }

    @Test
    fun clearKeepsSettings() {
        val f = flow()
        f.chooseHand(Handed.LEFT, now)
        f.debugFinishLesson(1, 3, false, now)
        f.openSettings(now)
        f.askClear(now)
        assertEquals("confirm", f.state.variant)
        f.confirmClear(now)
        assertEquals(Screen.S15, f.state.screen)
        f.wait(900)
        assertEquals(Screen.S04, f.state.screen)
        assertEquals(Handed.LEFT, f.state.settings.hand)
        assertTrue(f.state.progress.lessons.isEmpty())
    }

    @Test
    fun freeModeReturnsToTheAlphabet() {
        val f = flow(first = false)
        f.handVisible(true, now)
        f.openAlphabet(now)
        f.openLetter("G", now)
        f.tryIt(now)
        f.matched(now)
        f.wait(1000)
        assertEquals(Screen.S13, f.state.screen)
        assertEquals(0, f.state.progress.record("G").box)
    }

    @Test
    fun wordsLessonSpellsAndCounts() {
        val f = flow(first = false)
        f.handVisible(true, now)
        f.openLesson(6, now)
        assertEquals("H", f.state.letter)
        f.tryIt(now)
        f.matched(now)
        f.wait(1000)
        assertEquals("I", f.state.letter)
        f.tryIt(now)
        f.matched(now)
        now += 1000
        f.tick(now, 1000, today)
        assertEquals("N", f.state.letter)
        assertEquals("HI", f.state.wordDone)
        assertEquals(1, f.state.progress.record("H").box)
    }

    @Test
    fun allLessonsLetterSkipGoesToDone() {
        val f = flow(first = false)
        f.handVisible(true, now)
        f.openLesson(1, now)
        repeat(5) {
            f.tryIt(now)
            if (f.state.screen == Screen.S06) {
                f.armOrConfirm("skip", now, today)
                f.armOrConfirm("skip", now, today)
            } else {
                f.watchAgain(now)
                f.debugSet(f.state.copy(course = f.state.course!!.copy(skipped = f.state.course!!.skipped + f.state.course!!.index)))
                f.debugSet(f.state.copy(course = f.state.course!!.copy(index = f.state.course!!.index)))
                f.tryIt(now)
                f.selfDone(now)
            }
        }
        assertEquals(Screen.S11, f.state.screen)
    }
}
