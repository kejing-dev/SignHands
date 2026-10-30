package io.embodit.signhands.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.compose.runtime.mutableStateOf
import com.pico.spatial.ui.platform.ability.UpperLimbRenderMode
import io.embodit.signhands.domain.Course
import io.embodit.signhands.domain.CourseMode
import io.embodit.signhands.domain.FakeHand
import io.embodit.signhands.domain.GhostMode
import io.embodit.signhands.domain.Handed
import io.embodit.signhands.domain.Lang
import io.embodit.signhands.domain.LetterRecord
import io.embodit.signhands.domain.LessonRecord
import io.embodit.signhands.domain.Quiz
import io.embodit.signhands.domain.Reveal
import io.embodit.signhands.domain.Screen
import io.embodit.signhands.domain.Step
import io.embodit.signhands.domain.Support
import io.embodit.signhands.domain.V3
import io.embodit.signhands.platform.SignGraph
import io.embodit.signhands.platform.SignLog
import io.embodit.signhands.scene.SignScene
import io.embodit.signhands.ui.SignViewModel
import io.embodit.signhands.ui.limbModeOf
import java.util.Locale

/**
 * QA hook (debuggable builds only), design `verification.debugHooks`:
 * `adb shell am broadcast -a io.embodit.signhands.DEBUG -p io.embodit.signhands --es cmd "<command>"`.
 * See AGENTS.md for the command list and the screenshot list.
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        val raw = intent.getStringExtra("cmd") ?: return
        SignLog.i("CMD recv '$raw'")
        DebugHost.run(raw)
    }
}

object DebugHost {
    @Volatile var vm: SignViewModel? = null
    @Volatile var scene: SignScene? = null
    val limbMode = mutableStateOf(UpperLimbRenderMode.Visible)

    fun run(raw: String) {
        val tokens = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return
        val name = tokens.first().lowercase(Locale.US)
        val rest = tokens.drop(1)
        val args = HashMap<String, String>()
        val bare = ArrayList<String>()
        for (t in rest) {
            val i = t.indexOf('=')
            if (i > 0) args[t.substring(0, i).lowercase(Locale.US)] = t.substring(i + 1) else bare += t
        }
        val vm = vm
        val scene = scene
        if (vm == null || scene == null) {
            SignLog.i("CMD $name ignored: the Stage is not running")
            return
        }
        try {
            if (args["hold"] == "1") vm.debug { _, _ -> debugSet(state.copy(hold = true)) }
            when (name) {
                "fakehand" -> {
                    scene.noHand = false
                    scene.fake =
                        FakeHand(
                            letter = (args["letter"] ?: "OPEN").uppercase(Locale.US),
                            off = args["off"]?.lowercase(Locale.US),
                            offset = args["pos"]?.split(',')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 3 }?.let { V3(it[0], it[1], it[2]) } ?: V3.ZERO,
                            bone = args["bone"]?.toFloatOrNull() ?: 1f,
                            jitter = args["jitter"]?.toFloatOrNull() ?: 0f,
                        )
                }
                "nohand" -> {
                    scene.fake = null
                    scene.noHand = true
                }
                "lost" -> scene.fake = null
                "letter" -> {
                    val l = (bare.firstOrNull() ?: args["l"] ?: "A").uppercase(Locale.US)
                    val free = args["free"] == "1"
                    vm.debug { now, _ ->
                        val lesson = vm.book.lessons.firstOrNull { l in it.letters }
                        val course =
                            if (free || lesson == null) Course(CourseMode.FREE, 0, listOf(Step(l)))
                            else Course(CourseMode.LESSON, lesson.n, lesson.letters.map { Step(it) }, index = lesson.letters.indexOf(l))
                        debugSet(state.copy(course = course, quiz = null, slow = false, opposite = false))
                        if (args["stage"] == "follow") {
                            debugGo(Screen.S05, now)
                            tryIt(now)
                        } else {
                            debugGo(Screen.S05, now)
                        }
                    }
                }
                "watch" -> vm.debug { _, _ ->
                    debugSet(state.copy(slow = args["slow"]?.let { it == "1" } ?: state.slow, opposite = args["opposite"]?.let { it == "1" } ?: state.opposite))
                    args["angle"]?.toFloatOrNull()?.let { scene.onRingDrag(it / 0.35f) }
                }
                "quiz" -> {
                    val n = args["lesson"]?.toIntOrNull() ?: 1
                    val index = (args["index"]?.toIntOrNull() ?: 1) - 1
                    val def = vm.book.lesson(n)
                    vm.debug { now, _ ->
                        debugSet(state.copy(course = Course(CourseMode.LESSON, n, def.letters.map { Step(it) }, index = def.letters.size - 1), quiz = Quiz(false, n, def.letters, index = index.coerceIn(0, def.letters.size - 1))))
                        debugGo(if (state.handVisible) Screen.S09 else Screen.S02, now, if (state.handVisible) null else "a")
                        if (!state.handVisible) debugSet(state.copy(lostFrom = Screen.S09))
                    }
                }
                "review" -> vm.debug { now, today ->
                    if (state.progress.due(today).isEmpty()) seed(this, today)
                    startReview(today, now)
                }
                "finishlesson" -> {
                    val n = bare.firstOrNull()?.toIntOrNull() ?: args["n"]?.toIntOrNull() ?: 1
                    vm.debug { now, _ -> debugFinishLesson(n, args["score"]?.toIntOrNull() ?: 5, args["watchonly"] == "1", now) }
                }
                "seedprogress" -> vm.debug { _, today -> seed(this, today) }.also { vm.debugSaveProgress() }
                "cleardue" -> vm.debug { _, today ->
                    val p = state.progress
                    debugSet(state.copy(progress = p.copy(letters = p.letters.mapValues { (_, r) -> if (r.box > 0 && r.due <= today) r.copy(due = today + 1) else r })))
                }.also { vm.debugSaveProgress() }
                "cleardata" -> {
                    scene.fake = null
                    vm.debugClearData()
                }
                "clock" -> {
                    val d = (bare.firstOrNull() ?: "+0d").trim().removeSuffix("d").toLongOrNull() ?: 0L
                    vm.dayOffset += d
                    vm.debug { _, _ -> debugSet(state.copy()) }
                    SignLog.i("CMD clock offset=${vm.dayOffset}d today=${vm.today}")
                }
                "set" -> {
                    args["hand"]?.let { vm.setHand(if (it.lowercase(Locale.US) == "left") Handed.LEFT else Handed.RIGHT) }
                    args["ghostmode"]?.let { vm.setGhost(if (it.lowercase(Locale.US) == "side") GhostMode.SIDE else GhostMode.OVERLAY) }
                    args["lang"]?.let { vm.setLang(when (it.lowercase(Locale.US)) { "en" -> Lang.EN; "zh" -> Lang.ZH; else -> Lang.SYSTEM }) }
                    args["muted"]?.let { if ((it == "1") != vm.state.value.settings.muted) vm.toggleMute() }
                }
                "watchonly" -> vm.debug { now, _ -> if ((args["on"] ?: bare.firstOrNull() ?: "1") == "1") watchOnly(now) else debugSet(state.copy(watchOnly = false)) }
                "support" -> {
                    val s = bare.firstOrNull() ?: args["s"]
                    SignGraph.hands.forced =
                        when (s?.uppercase(Locale.US)) {
                            "SUPPORTED", "OK" -> Support.OK
                            "DEVICE_NOT_SUPPORTED", "NONE", "UNKNOWN", "OFF" -> Support.OFF
                            "WITHOUT_PERMISSION" -> Support.NO_PERMISSION
                            "NOT_IN_FULL_SPACE" -> Support.WAIT
                            else -> null
                        }
                }
                "state" -> {
                    val id = (bare.firstOrNull() ?: "S04").uppercase(Locale.US)
                    val screen = runCatching { Screen.valueOf(id) }.getOrNull() ?: return
                    val variant = args["variant"]
                    vm.debug { now, today ->
                        when (screen) {
                            Screen.S10 -> {
                                val def = vm.book.lesson(1)
                                val reveal = when (variant) { "b" -> Reveal.WRONG; "c" -> Reveal.SELF; else -> Reveal.RIGHT }
                                val items = if (reveal == Reveal.SELF) listOf("E") + def.letters.filter { it != "E" } else def.letters
                                debugSet(state.copy(course = Course(CourseMode.LESSON, 1, def.letters.map { Step(it) }, index = 4), quiz = Quiz(false, 1, items, reveal = reveal, missKey = if (reveal == Reveal.WRONG) "finger:index:straight" else null)))
                            }
                            Screen.S12 -> debugSet(state.copy(reviewDone = io.embodit.signhands.domain.ReviewDone(3, 3, 2)))
                            Screen.SPIKE -> debugSet(state.copy(spikeLetter = args["letter"]?.uppercase(Locale.US) ?: state.spikeLetter))
                            else -> Unit
                        }
                        debugGo(screen, now, variant)
                        if (screen == Screen.S02) debugSet(state.copy(lostFrom = if (variant == "b") Screen.S06 else Screen.S06))
                    }
                }
                "recall" -> scene.recall()
                "dragpanel" -> {
                    // dragpanel dx=<px> dy=<px>: the same path as pinching the bar under the panel
                    scene.onDragStart(scene.panelHandle)
                    scene.onDrag(args["dx"]?.toFloatOrNull() ?: 0f, args["dy"]?.toFloatOrNull() ?: 0f)
                    scene.onDragEnd()
                }
                "limbs" -> limbModeOf(bare.firstOrNull())?.let { limbMode.value = it }
                "dump" -> {
                    SignLog.i(scene.dumpLine())
                    val s = vm.state.value
                    SignLog.i("DUMP state=${s.screen}/${s.variant} letter=${s.letter} settings=${s.settings} watchOnly=${s.watchOnly} hint=${s.hint} today=${vm.today}")
                    SignLog.i("DUMP progress=${s.progress.toJson()}")
                }
                "tap" -> {
                    // panel actions without a pointer (QA fallback): tap <action>
                    when (bare.firstOrNull()?.lowercase(Locale.US)) {
                        "tryit" -> vm.tryIt()
                        "skip", "nextletter" -> vm.nextLetter()
                        "giveup", "showanswer" -> vm.showAnswer()
                        "recall" -> vm.recall()
                        "self" -> vm.selfDone()
                        "ready" -> vm.quizReady()
                        "next" -> vm.answer(null)
                        "yes" -> vm.answer(true)
                        "no" -> vm.answer(false)
                        "back" -> vm.back()
                        "home" -> vm.home()
                        "review" -> vm.startReview()
                        "alphabet" -> vm.openAlphabet()
                        "settings" -> vm.openSettings()
                        "mute" -> vm.toggleMute()
                        "lesson" -> vm.openLesson(args["n"]?.toIntOrNull() ?: 1)
                        "nextlesson" -> vm.nextLesson()
                        "watchagain" -> vm.watchAgain()
                        "right" -> vm.chooseHand(Handed.RIGHT)
                        "left" -> vm.chooseHand(Handed.LEFT)
                        "clear" -> vm.askClear()
                        "confirm" -> vm.confirmClear()
                        "slow" -> vm.toggleSlow()
                        "opposite" -> vm.toggleOpposite()
                        "replay" -> vm.replay()
                        "about" -> vm.toggleAbout()
                        else -> SignLog.i("CMD tap: unknown action")
                    }
                }
                "release" -> vm.debug { _, _ -> debugSet(state.copy(hold = false)) }
                else -> SignLog.i("CMD unknown '$name'")
            }
            SignLog.i("CMD $name done state=${vm.state.value.screen}/${vm.state.value.variant}")
        } catch (t: Throwable) {
            SignLog.e("CMD $name failed: $t", t)
        }
    }

    /** Lesson 1 done, three letters due today, the rest of lesson 1 tomorrow. */
    private fun seed(flow: io.embodit.signhands.domain.Flow, today: Long) {
        val def = flow.state.let { it }
        val letters = listOf("A", "B", "C", "D", "E")
        val records = letters.mapIndexed { i, l -> l to LetterRecord(box = 1 + (i % 2), due = if (i < 3) today else today + 1, seen = 1, correct = 1) }.toMap()
        flow.debugSet(def.copy(progress = def.progress.copy(letters = def.progress.letters + records, lessons = def.progress.lessons + (1 to LessonRecord(true, 4)), followed = def.progress.followed + letters, lastLesson = 1)))
    }
}
