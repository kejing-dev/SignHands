package io.embodit.signhands.domain

/** The design package's states (`states[]`); SPIKE is the debug-only recognition page. */
enum class Screen { S00, S01, S02, S03, S04, S05, S06, S07, S08, S09, S10, S11, S12, S13, S14, S15, SPIKE }

enum class CourseMode { LESSON, WORDS, FREE }

/** One letter to learn: [word] is set in lesson 6 (spell words). */
data class Step(val letter: String, val word: String? = null, val posInWord: Int = 0)

data class Course(val mode: CourseMode, val lesson: Int, val steps: List<Step>, val index: Int = 0, val skipped: Set<Int> = emptySet()) {
    val step: Step
        get() = steps[index]

    val letter: String
        get() = step.letter
}

enum class Reveal { RIGHT, WRONG, SELF }

data class Quiz(
    val review: Boolean,
    val lesson: Int,
    val items: List<String>,
    val index: Int = 0,
    /** Wrong first answers, asked once more at the end (not scored again). */
    val retry: List<String> = emptyList(),
    val inRetry: Boolean = false,
    val retryIndex: Int = 0,
    val results: Map<String, Boolean> = emptyMap(),
    val remainingMs: Long = QUIZ_MS,
    val reveal: Reveal? = null,
    /** Hint key of the worst item when a judged answer ran out (S10b "almost: index"). */
    val missKey: String? = null,
) {
    val letter: String
        get() = if (inRetry) retry[retryIndex] else items[index]

    companion object {
        const val QUIZ_MS = 10_000L
    }
}

enum class Mark { OK, MISS, SKIP, SEEN }

data class LessonDone(val lesson: Int, val letters: List<Pair<String, Mark>>, val firstTry: Int, val total: Int, val watchOnly: Boolean, val last: Boolean, val words: Boolean)

data class ReviewDone(val correct: Int, val total: Int, val dueTomorrow: Int)

data class Settings(val lang: Lang = Lang.SYSTEM, val hand: Handed = Handed.RIGHT, val ghost: GhostMode = GhostMode.OVERLAY, val muted: Boolean = false)

/** Sound and storage the flow asks for; the ViewModel carries them out. */
sealed interface Effect {
    data object Ding : Effect

    data object SaveProgress : Effect

    data object SaveSettings : Effect

    data object ClearProgress : Effect

    data class Log(val line: String) : Effect
}

data class UiState(
    val screen: Screen = Screen.S00,
    /** a / b / c / confirm of S02, S03, S10, S14. */
    val variant: String? = null,
    val settings: Settings = Settings(),
    val firstRun: Boolean = true,
    val progress: Progress = Progress(),
    val course: Course? = null,
    val quiz: Quiz? = null,
    val slow: Boolean = false,
    val opposite: Boolean = false,
    /** Bumped by "Replay": the scene restarts the demo. */
    val replay: Int = 0,
    val watchOnly: Boolean = false,
    /** S02: the state to return to when the hand is back. */
    val lostFrom: Screen? = null,
    /** "skip" / "giveup" armed for the second pinch (until [armedUntilMs]). */
    val armed: String? = null,
    val armedUntilMs: Long = 0L,
    /** Hint key shown on the hand label (worst item). */
    val hint: String? = null,
    val done: LessonDone? = null,
    val reviewDone: ReviewDone? = null,
    /** The word just spelled (lesson 6), shown on the hand label for a moment. */
    val wordDone: String? = null,
    val aboutOpen: Boolean = false,
    val handVisible: Boolean = false,
    val enteredMs: Long = 0L,
    /** Debug `hold=1`: the next short state does not move on by itself. */
    val hold: Boolean = false,
    val spikeLetter: String = "A",
    /** v0.1.2: hand tracking as last seen by the scene, shown on the panel. */
    val support: Support = Support.WAIT,
) {
    val inFollow: Boolean
        get() = screen == Screen.S06 || screen == Screen.S07 || screen == Screen.S08

    val inQuiz: Boolean
        get() = screen == Screen.S09 || screen == Screen.S10

    /** The letter on the panel / label now. */
    val letter: String?
        get() =
            when (screen) {
                Screen.S09, Screen.S10 -> quiz?.letter
                Screen.S02 -> if (lostFrom == Screen.S09 || lostFrom == Screen.S10) quiz?.letter else course?.letter
                Screen.SPIKE -> spikeLetter
                else -> course?.letter
            }

    val free: Boolean
        get() = course?.mode == CourseMode.FREE
}

/**
 * The whole state machine (design `states[]`, `interactions[]`, `srs`, `lessons.flow`), pure: every call returns
 * the new state and the effects. Times are passed in (ms, and [today] as an epoch day) so tests drive the clock.
 */
class Flow(private val book: LetterBook, private val random: (Int) -> Int = { n -> (0 until n).random() }) {
    var state = UiState()
        private set
    val effects = ArrayList<Effect>()

    fun drain(): List<Effect> = ArrayList(effects).also { effects.clear() }

    private fun set(s: UiState) {
        if (s.screen != state.screen || s.variant != state.variant) effects += Effect.Log("STATE ${s.screen}${s.variant?.let { "/$it" } ?: ""} letter=${s.letter ?: "-"}")
        state = s
    }

    private fun go(screen: Screen, now: Long, variant: String? = null, s: UiState = state) {
        set(s.copy(screen = screen, variant = variant, enteredMs = now, armed = null, hint = if (screen == s.screen) s.hint else null))
    }

    // ------------------------------------------------------------------------------------------ start

    fun loaded(settings: Settings?, progress: Progress) {
        state = state.copy(settings = settings ?: Settings(), firstRun = settings == null, progress = progress)
    }

    /** The scene is ready (S00 ends) and the support state is known. */
    fun ready(now: Long, support: Support) {
        if (state.screen != Screen.S00) return
        if (state.hold) return
        when (support) {
            Support.OK, Support.EMULATOR -> go(if (state.firstRun) Screen.S01 else Screen.S04, now)
            Support.OFF -> go(Screen.S03, now, "a")
            Support.NO_PERMISSION -> go(Screen.S03, now, "b")
            Support.WAIT -> Unit
        }
    }

    fun chooseHand(h: Handed, now: Long) {
        set(state.copy(settings = state.settings.copy(hand = h), firstRun = false))
        effects += Effect.SaveSettings
        go(Screen.S04, now)
    }

    // ------------------------------------------------------------------------------------------- home

    fun openLesson(n: Int, now: Long) {
        val def = book.lessons.firstOrNull { it.n == n } ?: return
        val course =
            if (def.words.isNotEmpty()) {
                Course(CourseMode.WORDS, n, def.words.flatMap { w -> w.mapIndexed { i, c -> Step(c.toString(), w, i) } })
            } else {
                val p = state.progress
                val first = def.letters.indexOfFirst { it !in p.followed && p.record(it).box == 0 }.let { if (it < 0) 0 else it }
                Course(CourseMode.LESSON, n, def.letters.map { Step(it) }, index = first)
            }
        set(state.copy(course = course, quiz = null, done = null, slow = false, opposite = false, progress = state.progress.copy(lastLesson = n)))
        effects += Effect.SaveProgress
        go(Screen.S05, now)
    }

    fun openLetter(l: String, now: Long) {
        set(state.copy(course = Course(CourseMode.FREE, 0, listOf(Step(l))), quiz = null, slow = false, opposite = false))
        go(Screen.S05, now)
    }

    fun startReview(today: Long, now: Long) {
        if (state.watchOnly) return
        val due = state.progress.due(today)
        if (due.isEmpty()) return
        val items = shuffle(due).take(Srs.REVIEW_MAX)
        set(state.copy(quiz = Quiz(review = true, lesson = 0, items = items), course = null))
        enterJudged(Screen.S09, now)
    }

    fun openAlphabet(now: Long) = go(Screen.S13, now)

    fun openSettings(now: Long) = go(Screen.S14, now)

    fun home(now: Long) {
        set(state.copy(course = null, quiz = null, lostFrom = null, aboutOpen = false))
        go(Screen.S04, now)
    }

    /** "<-" of any page. */
    fun back(now: Long) {
        when (state.screen) {
            Screen.S05, Screen.S06, Screen.S07, Screen.S08 -> if (state.free) go(Screen.S13, now, s = state.copy(course = null)) else home(now)
            Screen.S02 -> if (state.free) go(Screen.S13, now, s = state.copy(course = null, lostFrom = null)) else home(now)
            Screen.S09, Screen.S10 -> home(now)
            Screen.S14 -> if (state.variant == "confirm") go(Screen.S14, now) else home(now)
            else -> home(now)
        }
    }

    // ------------------------------------------------------------------------------------------ watch

    fun toggleSlow() = set(state.copy(slow = !state.slow))

    fun toggleOpposite() = set(state.copy(opposite = !state.opposite))

    fun replay() = set(state.copy(replay = state.replay + 1))

    fun tryIt(now: Long) {
        val course = state.course ?: return
        if (state.watchOnly) {
            advance(now, Mark.SEEN)
            return
        }
        val letter = book[course.letter]
        enterJudged(if (letter.scored) Screen.S06 else Screen.S08, now)
    }

    fun watchAgain(now: Long) {
        if (state.course == null) return
        go(Screen.S05, now, s = state.copy(lostFrom = null))
    }

    // ------------------------------------------------------------------------------------------ follow

    /** The scene's judge passed the 0.8 s window (S06) or the quiz letter (S09). */
    fun matched(now: Long) {
        when (state.screen) {
            Screen.S06 -> {
                val l = state.course?.letter ?: return
                effects += Effect.Ding
                set(state.copy(progress = state.progress.copy(followed = state.progress.followed + l)))
                effects += Effect.SaveProgress
                go(Screen.S07, now)
            }
            Screen.S09 -> {
                effects += Effect.Ding
                val q = state.quiz ?: return
                go(Screen.S10, now, "a", state.copy(quiz = q.copy(reveal = Reveal.RIGHT)))
            }
            else -> Unit
        }
    }

    /** S08 "I made it" (self-check letters while learning). */
    fun selfDone(now: Long) {
        if (state.screen != Screen.S08) return
        val l = state.course?.letter ?: return
        set(state.copy(progress = state.progress.copy(followed = state.progress.followed + l)))
        effects += Effect.SaveProgress
        advance(now, Mark.OK)
    }

    /** v0.1.1 S06 "Next ›": no penalty, the letter counts as followed (judging is only a helper now). */
    fun nextLetter(now: Long) {
        if (state.screen != Screen.S06 && state.screen != Screen.S08) return
        val l = state.course?.letter ?: return
        set(state.copy(progress = state.progress.copy(followed = state.progress.followed + l)))
        effects += Effect.SaveProgress
        advance(now, Mark.OK)
    }

    /** v0.1.1 S09 "Show me": one tap, shows the teacher's hand (S10b). */
    fun showAnswer(now: Long) {
        if (state.screen != Screen.S09) return
        giveUp(now)
    }

    /** "Skip" / "I don't know": the first pinch arms, the second within 2 s confirms (design T11). */
    fun armOrConfirm(what: String, now: Long, today: Long): Boolean {
        if (state.armed == what && now <= state.armedUntilMs) {
            set(state.copy(armed = null))
            when (what) {
                "skip" -> skip(now)
                "giveup" -> giveUp(now)
            }
            return true
        }
        set(state.copy(armed = what, armedUntilMs = now + ARM_MS))
        return false
    }

    private fun skip(now: Long) {
        val c = state.course ?: return
        set(state.copy(course = c.copy(skipped = c.skipped + c.index)))
        advance(now, Mark.SKIP)
    }

    /** Next step of the course after S07 / S08 / skip / watch-only "next". */
    private fun advance(now: Long, mark: Mark) {
        val c = state.course ?: return
        if (c.mode == CourseMode.FREE) {
            go(Screen.S13, now, s = state.copy(course = null))
            return
        }
        if (c.mode == CourseMode.WORDS && mark != Mark.SKIP && !state.watchOnly) {
            val l = c.letter
            val today = todayProvider()
            set(state.copy(progress = state.progress.copy(letters = state.progress.letters + (l to Srs.afterWord(state.progress.record(l), today)))))
            effects += Effect.SaveProgress
        }
        val word = c.step.word
        val wordEnd = c.mode == CourseMode.WORDS && word != null && c.step.posInWord == word.length - 1
        if (c.index + 1 < c.steps.size) {
            set(state.copy(course = c.copy(index = c.index + 1), wordDone = if (wordEnd) word else null, slow = false, opposite = false))
            go(Screen.S05, now)
            return
        }
        // end of the lesson
        val letters = c.steps.mapIndexed { i, s -> i to s.letter }
        if (state.watchOnly || c.mode == CourseMode.WORDS) {
            val marks = letters.distinctBy { it.second }.map { (i, l) -> l to if (i in c.skipped) Mark.SKIP else Mark.SEEN }
            finishLesson(c.lesson, marks, firstTry = 0, total = marks.size, watchOnly = state.watchOnly, words = c.mode == CourseMode.WORDS, now = now)
            return
        }
        val quizLetters = letters.filter { (i, _) -> i !in c.skipped }.map { it.second }
        if (quizLetters.isEmpty()) {
            finishLesson(c.lesson, letters.map { it.second to Mark.SKIP }, 0, letters.size, false, false, now)
            return
        }
        set(state.copy(quiz = Quiz(review = false, lesson = c.lesson, items = shuffle(quizLetters))))
        enterJudged(Screen.S09, now)
    }

    // -------------------------------------------------------------------------------------------- quiz

    /** S09 "I don't know" confirmed. */
    private fun giveUp(now: Long) {
        val q = state.quiz ?: return
        go(Screen.S10, now, "b", state.copy(quiz = q.copy(reveal = Reveal.WRONG, missKey = null)))
    }

    /** S09 "I made it" on a self-check letter. */
    fun quizSelfReady(now: Long) {
        val q = state.quiz ?: return
        if (state.screen != Screen.S09) return
        go(Screen.S10, now, "c", state.copy(quiz = q.copy(reveal = Reveal.SELF)))
    }

    /** S10: "Next" (a / b) or "Right" / "Not quite" (c). */
    fun answer(correct: Boolean?, today: Long, now: Long) {
        val q = state.quiz ?: return
        if (state.screen != Screen.S10) return
        val right =
            when (q.reveal) {
                Reveal.RIGHT -> true
                Reveal.WRONG -> false
                Reveal.SELF -> correct ?: return
                null -> return
            }
        var p = state.progress
        var nq = q
        if (!q.inRetry && q.letter !in q.results) {
            val rec = p.record(q.letter)
            p = p.copy(letters = p.letters + (q.letter to if (q.review) Srs.afterReview(rec, right, today) else Srs.afterQuiz(rec, right, today)))
            nq = nq.copy(results = q.results + (q.letter to right), retry = if (right) q.retry else q.retry + q.letter)
            effects += Effect.SaveProgress
        }
        nq = nq.copy(reveal = null, missKey = null, remainingMs = Quiz.QUIZ_MS)
        when {
            !nq.inRetry && nq.index + 1 < nq.items.size -> nq = nq.copy(index = nq.index + 1)
            !nq.inRetry && nq.retry.isNotEmpty() -> nq = nq.copy(inRetry = true, retryIndex = 0)
            nq.inRetry && nq.retryIndex + 1 < nq.retry.size -> nq = nq.copy(retryIndex = nq.retryIndex + 1)
            else -> {
                set(state.copy(progress = p, quiz = nq))
                finishQuiz(nq, today, now)
                return
            }
        }
        set(state.copy(progress = p, quiz = nq))
        enterJudged(Screen.S09, now)
    }

    private fun finishQuiz(q: Quiz, today: Long, now: Long) {
        val firstTry = q.results.count { it.value }
        if (q.review) {
            val tomorrow = state.progress.due(today + 1).size
            go(Screen.S12, now, s = state.copy(reviewDone = ReviewDone(firstTry, q.items.size, tomorrow), quiz = null))
            return
        }
        val c = state.course
        val letters =
            c?.steps?.mapIndexed { i, s -> s.letter to if (i in c.skipped) Mark.SKIP else if (q.results[s.letter] == true) Mark.OK else Mark.MISS }
                ?: q.items.map { it to if (q.results[it] == true) Mark.OK else Mark.MISS }
        finishLesson(q.lesson, letters, firstTry, q.items.size, false, false, now)
    }

    private fun finishLesson(n: Int, letters: List<Pair<String, Mark>>, firstTry: Int, total: Int, watchOnly: Boolean, words: Boolean, now: Long) {
        var p = state.progress
        if (!watchOnly) {
            p = p.copy(lessons = p.lessons + (n to LessonRecord(true, firstTry)))
            effects += Effect.SaveProgress
        }
        val last = n >= 5 && !words
        go(Screen.S11, now, s = state.copy(progress = p, quiz = null, done = LessonDone(n, letters, firstTry, total, watchOnly, last, words)))
    }

    fun nextLesson(now: Long) {
        val d = state.done ?: return home(now)
        if (d.last || d.words) return home(now)
        openLesson(d.lesson + 1, now)
    }

    // ------------------------------------------------------------------------------------------ hands

    /** The scene's view of the dominant hand (after its 1 s grace), and of the support state. */
    fun handVisible(visible: Boolean, now: Long) {
        if (visible == state.handVisible) return
        set(state.copy(handVisible = visible))
        val s = state
        if (!visible && (s.screen == Screen.S06 || s.screen == Screen.S08 || s.screen == Screen.S09 || s.screen == Screen.S10)) {
            go(Screen.S02, now, "b", s.copy(lostFrom = s.screen))
        } else if (visible && s.screen == Screen.S02 && s.lostFrom != null) {
            val back = s.lostFrom
            go(back, now, if (back == Screen.S10) s.quiz?.reveal?.let { variantOf(it) } else null, s.copy(lostFrom = null))
        }
    }

    private fun variantOf(r: Reveal): String = when (r) { Reveal.RIGHT -> "a"; Reveal.WRONG -> "b"; Reveal.SELF -> "c" }

    /** Entering a state that needs the hand: S02a first when it is not in view. */
    private fun enterJudged(screen: Screen, now: Long) {
        if (!state.handVisible) go(Screen.S02, now, "a", state.copy(lostFrom = screen)) else go(screen, now)
    }

    fun supportLost(now: Long) {
        val s = state
        if (s.screen == Screen.S06 || s.screen == Screen.S08 || s.screen == Screen.S09 || s.screen == Screen.S10) go(Screen.S02, now, "b", s.copy(lostFrom = s.screen))
    }

    /** S03: "Check again" (support now usable) or "Watch only". */
    fun recheck(support: Support, now: Long) {
        if (support == Support.OK || support == Support.EMULATOR) {
            set(state.copy(watchOnly = false))
            go(if (state.firstRun) Screen.S01 else Screen.S04, now)
        } else {
            go(Screen.S03, now, if (support == Support.NO_PERMISSION) "b" else "a")
        }
    }

    fun watchOnly(now: Long) {
        set(state.copy(watchOnly = true))
        go(if (state.firstRun) Screen.S01 else Screen.S04, now)
    }

    /** The scene's support state changed; leaving S03 by itself when tracking becomes usable. */
    fun support(sup: Support, now: Long) {
        if (sup == state.support) return
        set(state.copy(support = sup))
        if (state.screen == Screen.S03 && (sup == Support.OK || sup == Support.EMULATOR)) recheck(sup, now)
    }

    fun hint(key: String?) {
        if (key != state.hint) set(state.copy(hint = key))
    }

    // --------------------------------------------------------------------------------------- settings

    fun setLang(l: Lang) {
        set(state.copy(settings = state.settings.copy(lang = l)))
        effects += Effect.SaveSettings
    }

    fun setHand(h: Handed) {
        set(state.copy(settings = state.settings.copy(hand = h)))
        effects += Effect.SaveSettings
    }

    fun setGhost(g: GhostMode) {
        set(state.copy(settings = state.settings.copy(ghost = g)))
        effects += Effect.SaveSettings
    }

    fun toggleMute() {
        set(state.copy(settings = state.settings.copy(muted = !state.settings.muted)))
        effects += Effect.SaveSettings
    }

    fun askClear(now: Long) = go(Screen.S14, now, "confirm")

    fun confirmClear(now: Long) {
        set(state.copy(progress = Progress()))
        effects += Effect.ClearProgress
        go(Screen.S15, now)
    }

    fun toggleAbout() = set(state.copy(aboutOpen = !state.aboutOpen))

    // ------------------------------------------------------------------------------------------- time

    /** Timers: S07 1.0 s, S15 0.8 s, the quiz countdown (paused in S02), the two-step arm (2 s). */
    fun tick(now: Long, dtMs: Long, today: Long) {
        val s = state
        if (s.armed != null && now > s.armedUntilMs) set(s.copy(armed = null))
        if (s.wordDone != null && now - s.enteredMs > WORD_DONE_MS) set(state.copy(wordDone = null))
        when (s.screen) {
            Screen.S07 -> if (!s.hold && now - s.enteredMs >= MATCH_MS) advance(now, Mark.OK)
            Screen.S15 -> if (!s.hold && now - s.enteredMs >= RESET_MS) home(now)
            // v0.1.1: no countdown in the quiz (no penalty); the user answers or presses "Show me"
            else -> Unit
        }
    }

    // ------------------------------------------------------------------------------------------ debug

    fun debugSet(s: UiState) = set(s)

    fun debugGo(screen: Screen, now: Long, variant: String? = null) = go(screen, now, variant)

    fun debugFinishLesson(n: Int, score: Int, watchOnly: Boolean, now: Long) {
        val def = book.lessons.firstOrNull { it.n == n } ?: return
        val letters = def.letters.mapIndexed { i, l -> l to if (watchOnly) Mark.SEEN else if (i < score) Mark.OK else Mark.MISS }
        finishLesson(n, letters, if (watchOnly) 0 else score, letters.size, watchOnly, def.words.isNotEmpty(), now)
    }

    var todayProvider: () -> Long = { 0L }

    private fun shuffle(list: List<String>): List<String> {
        val a = list.toMutableList()
        for (i in a.size - 1 downTo 1) {
            val j = random(i + 1)
            val t = a[i]
            a[i] = a[j]
            a[j] = t
        }
        return a
    }

    companion object {
        const val ARM_MS = 2000L
        const val MATCH_MS = 1000L
        const val RESET_MS = 800L
        const val WORD_DONE_MS = 1500L
    }
}

/** Hand tracking as the flow sees it (design `container.notes` SupportState mapping). */
enum class Support { OK, WAIT, OFF, NO_PERMISSION, EMULATOR }
