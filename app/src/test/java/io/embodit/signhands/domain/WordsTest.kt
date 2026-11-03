package io.embodit.signhands.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.1.3: the everyday words (profile B18) and the settings exit (profile D26). */
class WordsTest {
    private val book = TestBook.book
    private var now = 0L

    @Test
    fun atLeastFiftyEverydayWordsInEveryTheme() {
        assertTrue("${Signs.all.size} words", Signs.all.size >= 50)
        assertEquals(Signs.all.size, Signs.all.map { it.id }.toSet().size)
        for (t in Theme.entries) assertTrue("$t has ${Signs.inTheme(t).size}", Signs.inTheme(t).size >= 4)
        for (s in Signs.all) {
            assertTrue("${s.id} shape ${s.shape}", book.letters.containsKey(s.shape))
            s.base?.let { assertTrue("${s.id} base ${it.shape}", book.letters.containsKey(it.shape)) }
            assertTrue("${s.id} path", s.path.size >= 2 && s.path.first() == V3.ZERO && Trajectory.extent(s.path) >= 0.03f)
            assertTrue("${s.id} tips", s.tipZh.isNotBlank() && s.tipEn.isNotBlank() && s.zh.isNotBlank())
            // every word is judged: a path and a hand shape
            val t = Target.of(s, book)
            assertTrue(t.moving && t.template.size == Trajectory.N && CareMask.items(t).isNotEmpty())
        }
    }

    private fun flow(): Flow {
        val f = Flow(book) { 0 }
        f.loaded(Settings(), Progress())
        f.ready(now, Support.OK)
        f.handVisible(true, now)
        return f
    }

    @Test
    fun aWordIsWatchedFollowedAndTheThemeGoesOn() {
        val f = flow()
        f.openWords(now)
        assertEquals(Screen.S16, f.state.screen)
        f.setTheme(Theme.FAMILY)
        f.openSign("MOTHER", now)
        assertEquals(Screen.S05, f.state.screen)
        assertEquals("MOTHER", f.state.sign?.id)
        assertNull(f.state.letter)
        f.tryIt(now)
        assertEquals(Screen.S06, f.state.screen)
        f.matched(now)
        assertEquals(Screen.S07, f.state.screen)
        assertTrue("w:MOTHER" in f.state.progress.followed)
        now += 1100
        f.tick(now, 100, 0)
        assertEquals(Screen.S05, f.state.screen)
        assertEquals("FATHER", f.state.sign?.id)
        // "Next ›" is always there
        f.tryIt(now)
        f.nextLetter(now)
        assertEquals("SISTER", f.state.sign?.id)
        // back from a word goes to the words page on the same theme
        f.back(now)
        assertEquals(Screen.S16, f.state.screen)
        assertEquals(Theme.FAMILY, f.state.theme)
        f.back(now)
        assertEquals(Screen.S04, f.state.screen)
    }

    @Test
    fun theLastWordOfAThemeReturnsToTheWordsPage() {
        val f = flow()
        val last = Signs.inTheme(Theme.QUESTION).last()
        f.openSign(last.id, now)
        f.tryIt(now)
        f.nextLetter(now)
        assertEquals(Screen.S16, f.state.screen)
        assertNull(f.state.course)
    }

    @Test
    fun exitAsksFirst() {
        val f = flow()
        f.openSettings(now)
        f.askExit(now)
        assertEquals(Screen.S14, f.state.screen)
        assertEquals("exit", f.state.variant)
        assertFalse(Effect.Exit in f.drain())
        // cancel = back to settings, nothing happens
        f.back(now)
        assertEquals(Screen.S14, f.state.screen)
        assertNull(f.state.variant)
        f.confirmExit()
        assertFalse("exit without the question", Effect.Exit in f.drain())
        f.askExit(now)
        f.confirmExit()
        assertTrue(Effect.Exit in f.drain())
    }

    @Test
    fun lettersAreAllJudgedNow() {
        // M, N, J and Z used to be "judge it yourself"; now trying them opens the judged follow page
        val f = flow()
        for (l in listOf("M", "N", "J", "Z", "E", "T")) {
            f.openLetter(l, now)
            f.tryIt(now)
            assertEquals(l, Screen.S06, f.state.screen)
        }
    }
}
