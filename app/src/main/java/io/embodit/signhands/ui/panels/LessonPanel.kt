package io.embodit.signhands.ui.panels

import androidx.compose.foundation.background
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.IconButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentControlDefaults
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Text
import io.embodit.signhands.R
import io.embodit.signhands.domain.CourseMode
import io.embodit.signhands.domain.GhostMode
import io.embodit.signhands.domain.Handed
import io.embodit.signhands.domain.Lang
import io.embodit.signhands.domain.LetterBook
import io.embodit.signhands.domain.Mark
import io.embodit.signhands.domain.Reveal
import io.embodit.signhands.domain.Screen
import io.embodit.signhands.domain.Sign
import io.embodit.signhands.domain.Signs
import io.embodit.signhands.domain.Theme
import io.embodit.signhands.domain.Srs
import io.embodit.signhands.domain.Support
import io.embodit.signhands.domain.UiState
import io.embodit.signhands.ui.Copy
import io.embodit.signhands.ui.SignViewModel
import io.embodit.signhands.ui.theme.LocalCopy
import io.embodit.signhands.ui.theme.SignColors
import android.content.res.Configuration
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.Locale
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import com.pico.spatial.ui.design.Slider
import kotlin.math.roundToInt


@Composable
fun PrimaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.then(NoFocus), colors = ButtonDefaults.buttonColors(containerColor = SignColors.ButtonPrimary, contentColor = SignColors.OnButtonPrimary)) {
        Text(label, maxLines = 1)
    }
}

@Composable
fun SecondaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false) {
    val c = if (selected) ButtonDefaults.buttonColors(containerColor = SignColors.Selected, contentColor = SignColors.OnSelected) else ButtonDefaults.buttonColors(containerColor = SignColors.ButtonSecondary, contentColor = SignColors.OnButtonSecondary)
    Button(onClick = onClick, modifier = modifier.then(NoFocus), colors = c) { Text(label, maxLines = 1) }
}

/** Unselected segments in dark ink on the light glass (QA q1: the default grey measured ~3:1), selected = night pill. */
@Composable
private fun segmentColors() =
    SegmentControlDefaults.colors(
        itemContentColor = SignColors.OnButtonPrimary,
        selectedItemBackgroundColor = SignColors.ButtonSecondary,
        selectedItemContentColor = SignColors.OnButtonSecondary,
    )

@Composable
private fun iconColors() = IconButtonDefaults.iconButtonColors(containerColor = SignColors.ButtonSecondary, contentColor = SignColors.OnButtonSecondary)

@Composable
private fun RoundIcon(icon: Int, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, colors = iconColors(), modifier = Modifier.size(48.dp).then(NoFocus)) {
        Icon(painter = painterResource(icon), contentDescription = description, modifier = Modifier.size(22.dp))
    }
}

/**
 * The lesson panel, v0.1.4 the content of the system main window (design `panels.lesson`; the window draws the glass
 * and the 32 dp corners, the TabBar switches 课程 / 字母表 / 常用词 / 设置): a title row (back, title, mute), then the
 * page of the current state. The clear and exit confirmations replace the settings page (no second panel).
 */
@Composable
fun LessonPanel(state: UiState, vm: SignViewModel, book: LetterBook, spike: String) {
    val c = LocalCopy.current
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TitleRow(state, vm, c)
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (state.screen) {
                    Screen.S00 -> Centered(c.loading)
                    Screen.S01 -> Welcome(vm, c)
                    Screen.S02 -> HandNeeded(state, vm, c)
                    Screen.S03 -> TrackingOff(state, vm, c)
                    Screen.S04 -> Home(state, vm, c, book)
                    Screen.S05 -> Watch(state, vm, c, book)
                    Screen.S06, Screen.S07 -> Follow(state, vm, c, book)
                    Screen.S08 -> SelfCheck(state, vm, c, book)
                    Screen.S09 -> QuizPage(state, vm, c, book)
                    Screen.S10 -> RevealPage(state, vm, c)
                    Screen.S11 -> LessonDonePage(state, vm, c)
                    Screen.S12 -> ReviewDonePage(state, vm, c)
                    Screen.S13 -> Alphabet(state, vm, c, book)
                    Screen.S14 -> SettingsPage(state, vm, c)
                    Screen.S15 -> Centered(c.cleared)
                    Screen.S16 -> WordsPage(state, vm, c)
                    Screen.SPIKE -> SpikePage(state, vm, spike, book)
                }
            }
        }
    }
}

@Composable
private fun TitleRow(state: UiState, vm: SignViewModel, c: Copy) {
    val s = state.screen
    val backShown = s !in setOf(Screen.S00, Screen.S01, Screen.S03, Screen.S04, Screen.S11, Screen.S12, Screen.S15)
    val muteShown = s in setOf(Screen.S04, Screen.S05, Screen.S06, Screen.S07, Screen.S08, Screen.S09, Screen.S10, Screen.S11, Screen.S12, Screen.S02)
    Row(modifier = Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (backShown) RoundIcon(R.drawable.ic_back, c.back) { vm.back() }
        Text(title(state, c), style = PicoTheme.typography.titleMedium, maxLines = 1)
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { if (s in DOT_SCREENS) Dots(state) }
        if (muteShown) RoundIcon(if (state.settings.muted) R.drawable.ic_sound_off else R.drawable.ic_sound_on, if (state.settings.muted) c.unmute else c.mute) { vm.toggleMute() }
    }
}

private val DOT_SCREENS = setOf(Screen.S05, Screen.S06, Screen.S07, Screen.S08)

private fun title(s: UiState, c: Copy): String {
    val course = s.course
    val letter = s.letter ?: ""
    return when (s.screen) {
        Screen.S05, Screen.S06, Screen.S07, Screen.S08, Screen.S02 ->
            if (s.lostFrom == Screen.S09 || s.lostFrom == Screen.S10) quizTitle(s, c)
            else if (course == null) c.appName
            else when (course.mode) {
                CourseMode.FREE -> c.freeTitle(letter)
                CourseMode.WORDS -> c.wordTitle(course.step.word ?: "", course.step.posInWord)
                CourseMode.LESSON -> c.lessonTitle(course.lesson, letter)
                CourseMode.SIGNS -> s.sign?.let { c.wordsTitle(it.theme) } ?: c.words
            }
        Screen.S09, Screen.S10 -> quizTitle(s, c)
        Screen.S13 -> c.alphabet
        Screen.S16 -> c.words
        Screen.S14 -> c.settings
        Screen.SPIKE -> "SPIKE"
        else -> c.appName
    }
}

private fun quizTitle(s: UiState, c: Copy): String {
    val q = s.quiz ?: return c.appName
    return when {
        q.inRetry -> c.retryTitle(q.retryIndex + 1, q.retry.size)
        q.review -> c.reviewTitle(q.index + 1, q.items.size)
        else -> c.quizTitle(q.index + 1, q.items.size)
    }
}

@Composable
private fun Centered(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text, style = PicoTheme.typography.bodyLarge) }
}

@Composable
private fun Small(text: String, color: Color = PicoTheme.colorScheme.labelSecondary) {
    Text(text, style = PicoTheme.typography.bodyMedium, color = color, maxLines = 2)
}

@Composable
private fun ButtonsRow(content: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) { content() }
}

// ------------------------------------------------------------------------------------------- S01 - S03

@Composable
private fun Welcome(vm: SignViewModel, c: Copy) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(c.welcome, style = PicoTheme.typography.bodyLargeMultiline, maxLines = 3)
        Small(c.notChinese)
        Spacer(modifier = Modifier.weight(1f))
        Text(c.whichHand, style = PicoTheme.typography.titleMedium)
        ButtonsRow {
            PrimaryButton(c.rightHand, { vm.chooseHand(Handed.RIGHT) }, Modifier.weight(1f))
            PrimaryButton(c.leftHand, { vm.chooseHand(Handed.LEFT) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun HandNeeded(state: UiState, vm: SignViewModel, c: Copy) {
    val fromQuiz = state.lostFrom == Screen.S09 || state.lostFrom == Screen.S10
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(c.raiseHand(state.settings.hand, state.variant == "b"), style = PicoTheme.typography.bodyLargeMultiline, maxLines = 3)
        Spacer(modifier = Modifier.weight(1f))
        ButtonsRow {
            if (fromQuiz) SecondaryButton(c.backHome, { vm.home() }) else SecondaryButton(c.watchFirst, { vm.watchAgain() })
        }
    }
}

@Composable
private fun TrackingOff(state: UiState, vm: SignViewModel, c: Copy) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(if (state.variant == "b") c.trackingOffB else c.trackingOffA, style = PicoTheme.typography.bodyLargeMultiline, maxLines = 3)
        Text(c.trackingStatus(state.support), style = PicoTheme.typography.bodyMedium, color = SignColors.Alert, maxLines = 2)
        Small(c.demoHint)
        Spacer(modifier = Modifier.weight(1f))
        ButtonsRow {
            PrimaryButton(c.checkAgain, { vm.recheck(io.embodit.signhands.platform.SignGraph.hands.support()) })
            SecondaryButton(c.watchOnly, { vm.watchOnly() })
        }
    }
}

// ----------------------------------------------------------------------------------------------- S04

/**
 * v0.3.0 (polish M2; reviewers: "交互和UI需要优化，目前像是个demo" / "界面过于单一"): the home page is "today": two progress
 * rings (letters, words), the streak and what is due, one big "next step" button (review, else the next lesson, else
 * the words), the five lessons + spelling, the alphabet and the words.
 */
@Composable
private fun Home(state: UiState, vm: SignViewModel, c: Copy, book: LetterBook) {
    val due = state.progress.due(vm.today).size
    val next = (1..6).firstOrNull { state.progress.lessons[it]?.done != true } ?: 0
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (state.watchOnly) {
            Small(c.trackingStatus(state.support))
            Button(onClick = { vm.recheck(io.embodit.signhands.platform.SignGraph.hands.support()) }, modifier = Modifier.fillMaxWidth().then(NoFocus), colors = ButtonDefaults.buttonColors(containerColor = SignColors.ButtonSecondary, contentColor = SignColors.OnButtonSecondary)) {
                Text(c.watchOnlyBanner, maxLines = 1)
            }
        } else {
            TodayCard(state, vm, c, due)
        }
        when {
            due > 0 && !state.watchOnly -> PrimaryButton(c.reviewToday(due.coerceAtMost(Srs.REVIEW_MAX)), { vm.startReview() }, Modifier.fillMaxWidth())
            next > 0 -> {
                val def = book.lessons.firstOrNull { it.n == next }
                val what = if (def == null || def.words.isNotEmpty()) c.spellWords else def.letters.joinToString(" ")
                PrimaryButton(c.continueLesson(next, what), { vm.openLesson(next) }, Modifier.fillMaxWidth())
            }
            else -> PrimaryButton(c.allLessonsDone, { vm.openWords() }, Modifier.fillMaxWidth())
        }
        for (row in 0 until 2) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for (col in 0 until 3) {
                    val n = row * 3 + col + 1
                    LessonCell(n, state, book, c, highlighted = false, modifier = Modifier.weight(1f)) { vm.openLesson(n) }
                }
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SecondaryButton(c.alphabet, { vm.openAlphabet() }, Modifier.weight(1f))
            SecondaryButton(c.words + " · " + c.wordCount(Signs.all.size), { vm.openWords() }, Modifier.weight(1f))
        }
    }
}

/** v0.3.0: the two rings and the streak / due lines. */
@Composable
private fun TodayCard(state: UiState, vm: SignViewModel, c: Copy, due: Int) {
    Row(modifier = Modifier.fillMaxWidth().height(96.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        ProgressRing(state.progress.lettersLearned, 26, c.lettersRing)
        ProgressRing(state.progress.wordsLearned, Signs.all.size, c.wordsRing)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(c.today, style = PicoTheme.typography.titleMedium, maxLines = 1)
            Text(c.streak(state.progress.streak(vm.today)), style = PicoTheme.typography.bodyMedium, color = if (state.progress.streak(vm.today) > 0) SignColors.Jade else PicoTheme.colorScheme.labelSecondary, maxLines = 1)
            Text(c.dueLine(due), style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1)
        }
    }
}

/** A ring that fills with [k] of [n] (Compose foundation Canvas, no Material), the count inside, [label] under it. */
@Composable
private fun ProgressRing(k: Int, n: Int, label: String) {
    val frac = if (n <= 0) 0f else (k.toFloat() / n).coerceIn(0f, 1f)
    val track = PicoTheme.colorScheme.labelTertiary
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(modifier = Modifier.size(70.dp), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(70.dp)) {
                val w = 7.dp.toPx()
                val inset = w / 2f
                val arc = androidx.compose.ui.geometry.Size(size.width - w, size.height - w)
                val tl = androidx.compose.ui.geometry.Offset(inset, inset)
                drawArc(track, 0f, 360f, false, topLeft = tl, size = arc, style = Stroke(width = w))
                if (frac > 0f) drawArc(SignColors.Jade, -90f, 360f * frac, false, topLeft = tl, size = arc, style = Stroke(width = w, cap = StrokeCap.Round))
            }
            Text("$k", style = PicoTheme.typography.titleMedium, maxLines = 1)
        }
        Text("$label · $n", style = PicoTheme.typography.labelMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1)
    }
}

@Composable
private fun LessonCell(n: Int, state: UiState, book: LetterBook, c: Copy, highlighted: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val def = book.lessons.firstOrNull { it.n == n } ?: return
    val done = state.progress.lessons[n]?.done == true
    val label = (if (def.words.isNotEmpty()) "${c.lesson(n)} · ${c.spellWords}" else "${c.lesson(n)}  ${def.letters.joinToString(" ")}") + if (done) "  ✓" else ""
    val colors =
        if (highlighted) ButtonDefaults.buttonColors(containerColor = SignColors.ButtonPrimary, contentColor = SignColors.OnButtonPrimary)
        else ButtonDefaults.buttonColors(containerColor = SignColors.ButtonSecondary, contentColor = SignColors.OnButtonSecondary)
    Button(onClick = onClick, modifier = modifier.then(NoFocus), colors = colors) { Text(label, maxLines = 1, style = PicoTheme.typography.labelLarge) }
}

// ----------------------------------------------------------------------------------------- S05 - S08

@Composable
private fun Dots(state: UiState) {
    val course = state.course ?: return
    if (course.mode == CourseMode.FREE) return
    if (course.mode == CourseMode.SIGNS) {
        Text("${course.index + 1} / ${course.steps.size}", style = PicoTheme.typography.labelMedium, color = PicoTheme.colorScheme.labelSecondary)
        return
    }
    val steps = if (course.mode == CourseMode.WORDS) course.steps.filter { it.word == course.step.word } else course.steps
    val currentInList = if (course.mode == CourseMode.WORDS) course.step.posInWord else course.index
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        steps.forEachIndexed { i, st ->
            val learned = i < currentInList
            val current = i == currentInList
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(st.letter, style = PicoTheme.typography.labelMedium, color = if (current) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelSecondary)
                Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(if (learned) SignColors.Jade else if (current) SignColors.TeacherWhite else Color(0x55FFFFFF)))
            }
        }
    }
}

@Composable
private fun BigLetter(letter: String, tip: String) {
    Row(modifier = Modifier.height(72.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(letter, fontSize = 56.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(72.dp), textAlign = TextAlign.Center)
        Text(tip, style = PicoTheme.typography.bodyLargeMultiline, maxLines = 3)
    }
}

/** v0.1.3: a word sign's name (English big, Chinese beside) and how it is made. */
@Composable
private fun BigWord(sign: Sign, c: Copy) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FitText(sign.en, PicoTheme.typography.titleLarge.copy(fontSize = 34.sp, fontWeight = FontWeight.SemiBold), PicoTheme.colorScheme.labelPrimary, id = "lesson/word", modifier = Modifier.weight(1f, fill = false))
            if (!c.en) Text(sign.zh, style = PicoTheme.typography.titleMedium, maxLines = 1)
        }
        Text(sign.tip(if (c.en) Lang.EN else Lang.ZH), style = PicoTheme.typography.bodyLargeMultiline, maxLines = 2)
    }
}

@Composable
private fun Watch(state: UiState, vm: SignViewModel, c: Copy, book: LetterBook) {
    val sign = state.sign
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (sign != null) {
            BigWord(sign, c)
            Small(if (state.opposite) c.oppositeNote else c.signDemoNote)
        } else {
            val l = book[state.letter ?: return@Column]
            BigLetter(l.l, l.tip(if (c.en) Lang.EN else Lang.ZH))
            Small(if (state.opposite) c.oppositeNote else c.dragToTurn)
        }
        Spacer(modifier = Modifier.weight(1f))
        TurnSlider(state, vm, c)
        ButtonsRow {
            SecondaryButton((if (state.slow) "✓ " else "") + c.slow, { vm.toggleSlow() }, selected = state.slow)
            SecondaryButton((if (state.opposite) "✓ " else "") + c.opposite, { vm.toggleOpposite() }, selected = state.opposite)
            SecondaryButton(c.replay, { vm.replay() })
        }
        PrimaryButton(if (state.watchOnly) c.next else c.tryIt, { vm.tryIt() }, Modifier.fillMaxWidth())
    }
}

/**
 * v0.1.7 (user on the headset: the ring under the demo hand "应该用原生的 bar 组件"): the SpatialUI Slider turns the demo
 * hand -180..180 degrees from its default angle (0 in the middle); a new demo starts in the middle again.
 */
@Composable
private fun TurnSlider(state: UiState, vm: SignViewModel, c: Copy) {
    var local by remember(state.enteredMs) { mutableFloatStateOf(state.turn) }
    // follow a turn set from elsewhere (debug `watch angle=`); the slider's own changes land here unchanged
    if (state.turn != local && !state.turn.isNaN()) local = state.turn
    Row(modifier = Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(c.turnLabel, style = PicoTheme.typography.bodyMedium, modifier = Modifier.width(if (c.en) 48.dp else 40.dp), maxLines = 1)
        Slider(
            value = (local + 180f) / 360f,
            onValueChange = { v ->
                local = v * 360f - 180f
                vm.setTurn(local)
            },
            modifier = Modifier.weight(1f).height(48.dp).then(NoFocus),
        )
        Text("${local.roundToInt()}°", style = PicoTheme.typography.labelLarge, modifier = Modifier.padding(start = 12.dp).width(52.dp), textAlign = TextAlign.End, maxLines = 1)
    }
}

@Composable
private fun Follow(state: UiState, vm: SignViewModel, c: Copy, book: LetterBook) {
    val sign = state.sign
    val moving = state.letter?.let { book.letters[it]?.isMotion } == true
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (sign != null) {
            BigWord(sign, c)
            Small(c.signFit)
        } else {
            Text(if (moving) c.moveFit else c.fitHint, style = PicoTheme.typography.bodyLarge, maxLines = 2)
        }
        // v0.3.0: which finger is off, live (thumb .. pinky, palm); the combo once two in a row are right
        if (state.screen == Screen.S06) FingerChips(state.fingers, c)
        if (state.combo >= 2) Text(c.combo(state.combo), style = PicoTheme.typography.titleMedium, color = SignColors.Jade, maxLines = 1)
        Spacer(modifier = Modifier.weight(1f))
        ButtonsRow {
            SecondaryButton(c.watchAgain, { vm.watchAgain() }, Modifier.weight(1f))
            PrimaryButton(c.next, { vm.nextLetter() })
        }
    }
}

/** v0.3.0 live finger chips: jade = right, coral = off, grey = not judged / no hand yet. */
@Composable
private fun FingerChips(code: String?, c: Copy) {
    Row(modifier = Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { // gate:allow button-gap status chips, not buttons
        c.fingerShort.forEachIndexed { i, name ->
            val s = code?.getOrNull(i) ?: '-'
            val bg = when (s) { 'o' -> SignColors.Jade; 'x' -> SignColors.Coral; else -> Color(0x33000000) }
            val ink = when (s) { 'o', 'x' -> Color(0xFF14121A); else -> PicoTheme.colorScheme.labelSecondary }
            Box(modifier = Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(18.dp)).background(bg), contentAlignment = Alignment.Center) {
                Text(name, style = PicoTheme.typography.labelLarge, color = ink, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SelfCheck(state: UiState, vm: SignViewModel, c: Copy, book: LetterBook) {
    val l = book[state.letter ?: return]
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(if (l.isMotion) c.selfMotion else c.selfCrossed, style = PicoTheme.typography.bodyLargeMultiline, maxLines = 2)
        Spacer(modifier = Modifier.weight(1f))
        ButtonsRow {
            PrimaryButton(c.iMadeIt, { vm.selfDone() }, Modifier.weight(1f))
            SecondaryButton(c.watchAgain, { vm.watchAgain() })
        }
    }
}

// ----------------------------------------------------------------------------------------- S09 - S12

@Composable
private fun QuizPage(state: UiState, vm: SignViewModel, c: Copy, book: LetterBook) {
    val l = book[state.letter ?: return]
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(c.quizLine, style = PicoTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.weight(1f))
        ButtonsRow {
            PrimaryButton(c.ready, { vm.quizReady() }, Modifier.weight(1f))
            SecondaryButton(c.dontKnow, { vm.showAnswer() })
        }
    }
}

@Composable
private fun RevealPage(state: UiState, vm: SignViewModel, c: Copy) {
    val q = state.quiz ?: return
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when (q.reveal) {
            Reveal.RIGHT -> Text(c.right, style = PicoTheme.typography.titleMedium, color = SignColors.Jade)
            Reveal.WRONG -> Text(c.missPart(q.missKey)?.let { c.almostPart(it) } ?: c.likeThis, style = PicoTheme.typography.titleMedium, color = SignColors.Coral)
            Reveal.SELF, null -> Text(c.compare, style = PicoTheme.typography.titleMedium)
        }
        Spacer(modifier = Modifier.weight(1f))
        ButtonsRow {
            if (q.reveal == Reveal.SELF) {
                PrimaryButton(c.yes, { vm.answer(true) }, Modifier.weight(1f))
                SecondaryButton(c.no, { vm.answer(false) }, Modifier.weight(1f))
            } else {
                PrimaryButton(c.nextQuestion, { vm.answer(null) }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun LessonDonePage(state: UiState, vm: SignViewModel, c: Copy) {
    val d = state.done ?: return
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            when {
                d.words -> c.wordsDone
                d.watchOnly -> c.lessonSeen(d.lesson)
                d.last -> c.allDone
                else -> c.lessonDone(d.lesson)
            },
            style = PicoTheme.typography.titleLarge,
        )
        if (!d.watchOnly && !d.words) Text(c.firstTry(d.firstTry, d.total), style = PicoTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            for ((l, m) in d.letters.take(8)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(l, style = PicoTheme.typography.titleMedium, color = if (m == Mark.SKIP) PicoTheme.colorScheme.labelTertiary else PicoTheme.colorScheme.labelPrimary)
                    when (m) {
                        Mark.OK -> Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(SignColors.Jade))
                        Mark.MISS -> Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0x55FFFFFF)))
                        else -> Unit
                    }
                }
            }
        }
        if (d.letters.any { it.second == Mark.SKIP }) Small(c.skipped + "：" + d.letters.filter { it.second == Mark.SKIP }.joinToString(" ") { it.first })
        Small(if (d.watchOnly) c.turnOnTracking else c.comeBack)
        Spacer(modifier = Modifier.weight(1f))
        ButtonsRow {
            if (!d.last && !d.words) PrimaryButton(c.nextLesson, { vm.nextLesson() }, Modifier.weight(1f))
            SecondaryButton(c.backHome, { vm.home() })
        }
    }
}

@Composable
private fun ReviewDonePage(state: UiState, vm: SignViewModel, c: Copy) {
    val d = state.reviewDone ?: return
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(c.reviewDone, style = PicoTheme.typography.titleLarge)
        Text(c.rightCount(d.correct, d.total), style = PicoTheme.typography.bodyLarge)
        Small(c.tomorrow(d.dueTomorrow))
        Spacer(modifier = Modifier.weight(1f))
        PrimaryButton(c.backHome, { vm.home() }, Modifier.fillMaxWidth())
    }
}

// ----------------------------------------------------------------------------------------------- S13

@Composable
private fun Alphabet(state: UiState, vm: SignViewModel, c: Copy, book: LetterBook) {
    val letters = book.alphabet
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        for (row in 0 until 5) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for (col in 0 until 6) {
                    val i = row * 6 + col
                    if (i < letters.size) {
                        val l = letters[i]
                        val dots = Srs.dots(state.progress.record(l))
                        Button(
                            onClick = { vm.openLetter(l) },
                            modifier = Modifier.weight(1f).height(52.dp).then(NoFocus),
                            colors = ButtonDefaults.buttonColors(containerColor = SignColors.ButtonSecondary, contentColor = SignColors.OnButtonSecondary),
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(l, style = PicoTheme.typography.titleMedium)
                                    if (!book[l].scored) Icon(painter = painterResource(R.drawable.ic_eye), contentDescription = null, modifier = Modifier.padding(start = 2.dp).size(12.dp))
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    repeat(5) { k -> Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(if (k < dots) SignColors.Jade else Color(0x44FFFFFF))) }
                                }
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------------------------------------- S16

/**
 * v0.1.3 (profile B18): everyday word signs by theme. "‹ theme ›" pages through the nine themes; each word is a cell
 * (English, Chinese, ✓ once done) that opens its demo; the theme's words then follow one another.
 */
@Composable
private fun WordsPage(state: UiState, vm: SignViewModel, c: Copy) {
    val themes = Theme.entries
    val i = themes.indexOf(state.theme)
    val words = Signs.inTheme(state.theme)
    // v0.5.0 (M4): 21 themes - the theme name opens a picker instead of paging through them one by one
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        ThemePicker(state, vm, c) { picking = false }
        return
    }
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            RoundIcon(R.drawable.ic_back, c.back) { vm.setTheme(themes[(i - 1 + themes.size) % themes.size]) }
            Column(modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).then(NoFocus).clickable { picking = true }, horizontalAlignment = Alignment.CenterHorizontally) {
                Text((if (c.en) state.theme.en else state.theme.zh) + "  ▾", style = PicoTheme.typography.titleMedium, maxLines = 1)
                val learned = words.count { it.key in state.progress.followed }
                Text("${i + 1} / ${themes.size} · ${c.learnedOf(learned, words.size)}", style = PicoTheme.typography.labelMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1)
                // v0.3.0: the theme's progress bar
                Box(modifier = Modifier.padding(top = 4.dp).width(160.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(PicoTheme.colorScheme.labelTertiary)) {
                    Box(modifier = Modifier.fillMaxWidth(if (words.isEmpty()) 0f else learned / words.size.toFloat()).height(4.dp).background(SignColors.Jade))
                }
            }
            Box(modifier = Modifier.graphicsLayer(scaleX = -1f)) { RoundIcon(R.drawable.ic_back, c.next) { vm.setTheme(themes[(i + 1) % themes.size]) } }
        }
        for (row in words.chunked(3)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for (w in row) {
                    val done = w.key in state.progress.followed
                    Button(
                        onClick = { vm.openSign(w.id) },
                        modifier = Modifier.weight(1f).height(52.dp).then(NoFocus),
                        contentPadding = PaddingValues(horizontal = 6.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SignColors.ButtonSecondary, contentColor = SignColors.OnButtonSecondary),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            FitText((if (done) "✓ " else "") + w.en, PicoTheme.typography.labelLarge, SignColors.OnButtonSecondary, id = "lesson/words/${w.id}", align = TextAlign.Center)
                            if (!c.en) FitText(w.zh, PicoTheme.typography.labelMedium, SignColors.OnButtonSecondary, id = "lesson/words/${w.id}/zh", align = TextAlign.Center)
                        }
                    }
                }
                repeat(3 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
        if (words.size <= 6) {
            Spacer(modifier = Modifier.weight(1f))
            Small(c.wordsNote)
        }
    }
}

/** v0.5.0: all themes at once (4 x 6 grid), each with learned / total; tapping one opens it. */
@Composable
private fun ThemePicker(state: UiState, vm: SignViewModel, c: Copy, close: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            RoundIcon(R.drawable.ic_back, c.back) { close() }
            Text(c.pickTheme, style = PicoTheme.typography.titleMedium, maxLines = 1)
        }
        for (row in Theme.entries.chunked(4)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for (th in row) {
                    val ws = Signs.inTheme(th)
                    val learned = ws.count { it.key in state.progress.followed }
                    val colors =
                        if (th == state.theme) ButtonDefaults.buttonColors(containerColor = SignColors.Selected, contentColor = SignColors.OnSelected)
                        else ButtonDefaults.buttonColors(containerColor = SignColors.ButtonSecondary, contentColor = SignColors.OnButtonSecondary)
                    Button(
                        onClick = {
                            vm.setTheme(th)
                            close()
                        },
                        modifier = Modifier.weight(1f).height(40.dp).then(NoFocus),
                        contentPadding = PaddingValues(horizontal = 6.dp),
                        colors = colors,
                    ) {
                        FitText((if (c.en) th.en else th.zh) + " ${learned}/${ws.size}", PicoTheme.typography.labelMedium, if (th == state.theme) SignColors.OnSelected else SignColors.OnButtonSecondary, id = "lesson/themes/${th.name}", align = TextAlign.Center)
                    }
                }
                repeat(4 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

// ----------------------------------------------------------------------------------------------- S14

@Composable
private fun SettingsPage(state: UiState, vm: SignViewModel, c: Copy) {
    if (state.variant == "exit") {
        // v0.1.3 (profile D26): exit asks once more
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(c.exitAsk, style = PicoTheme.typography.bodyLargeMultiline, maxLines = 3)
            Spacer(modifier = Modifier.weight(1f))
            ButtonsRow {
                SecondaryButton(c.cancel, { vm.cancelExit() }, Modifier.weight(1f))
                Button(onClick = { vm.confirmExit() }, modifier = Modifier.weight(1f).then(NoFocus), colors = ButtonDefaults.buttonColors(containerColor = SignColors.Coral, contentColor = Color(0xFF2A0B05))) {
                    Text(c.exit, maxLines = 1)
                }
            }
        }
        return
    }
    if (state.variant == "confirm") {
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(c.clearAsk, style = PicoTheme.typography.bodyLargeMultiline, maxLines = 3)
            Spacer(modifier = Modifier.weight(1f))
            ButtonsRow {
                SecondaryButton(c.cancel, { vm.cancelClear() }, Modifier.weight(1f))
                Button(onClick = { vm.confirmClear() }, modifier = Modifier.weight(1f).then(NoFocus), colors = ButtonDefaults.buttonColors(containerColor = SignColors.Coral, contentColor = Color(0xFF2A0B05))) {
                    Text(c.clear, maxLines = 1)
                }
            }
        }
        return
    }
    val s = state.settings
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (state.aboutOpen) {
            Text(c.version(vm.version) + "\n" + c.aboutBody, style = PicoTheme.typography.labelMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 6, modifier = Modifier.weight(1f))
            ButtonsRow { SecondaryButton(c.about, { vm.toggleAbout() }, Modifier.weight(1f), selected = true) }
            SecondaryButton(c.exitApp, { vm.askExit() }, Modifier.fillMaxWidth())
            return@Column
        }
        if (state.howToOpen) {
            HowTo(c, Modifier.weight(1f))
            ButtonsRow { SecondaryButton(c.howTo, { vm.toggleHowTo() }, Modifier.weight(1f), selected = true) }
            return@Column
        }
        SettingRow(c.language) {
            SegmentControl {
            for (l in Lang.entries) {
                SegmentItem(colors = segmentColors(), selected = s.lang == l, onClick = { vm.setLang(l) }, modifier = Modifier.width(88.dp), title = {
                    Text(when (l) { Lang.SYSTEM -> c.followSystem; Lang.ZH -> "中文"; Lang.EN -> "English" }, maxLines = 1)
                })
            }
            }
        }
        SettingRow(c.dominantHand) {
            SegmentControl {
            for (h in Handed.entries) {
                SegmentItem(colors = segmentColors(), selected = s.hand == h, onClick = { vm.setHand(h) }, modifier = Modifier.width(104.dp), title = { Text(if (h == Handed.RIGHT) c.rightHand else c.leftHand, maxLines = 1) })
            }
            }
        }
        SettingRow(c.teacherHand) {
            SegmentControl {
            for (g in GhostMode.entries) {
                SegmentItem(colors = segmentColors(), selected = s.ghost == g, onClick = { vm.setGhost(g) }, modifier = Modifier.width(104.dp), title = { Text(if (g == GhostMode.OVERLAY) c.onHand else c.besideHand, maxLines = 1) })
            }
            }
        }
        ButtonsRow {
            // v0.1.4 (D21): the system moves the window; this puts it back in front of you, the demo hand beside it
            SecondaryButton(c.recall, { vm.recall() }, Modifier.weight(1f))
            // v0.1.6 (profile B19): how to use, gesture and controller per main action
            SecondaryButton(c.howTo, { vm.toggleHowTo() }, Modifier.weight(1f))
        }
        ButtonsRow {
            SecondaryButton(c.clearProgress, { vm.askClear() }, Modifier.weight(1f))
            SecondaryButton(c.about, { vm.toggleAbout() }, Modifier.weight(1f), selected = state.aboutOpen)
        }
        Spacer(modifier = Modifier.weight(1f))
        // v0.1.3 (profile D26): the last row of settings
        SecondaryButton(c.exitApp, { vm.askExit() }, Modifier.fillMaxWidth())
    }
}

/**
 * v0.1.6 (profile B19): Settings > "怎么操作": one row per main action, an icon, what to do with the hand, what to do
 * with the controller. Strings are `how_to_*` resources, read in the app's own language (settings may differ from the
 * system's).
 */
@Composable
private fun HowTo(c: Copy, modifier: Modifier) {
    val ctx = LocalContext.current
    val res =
        remember(c.en, ctx) {
            val conf = Configuration(ctx.resources.configuration)
            conf.setLocale(if (c.en) Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE)
            ctx.createConfigurationContext(conf).resources
        }
    val rows =
        listOf(
            Triple(R.drawable.ic_eye, R.string.how_to_watch, R.string.how_to_watch_hand to R.string.how_to_watch_pad),
            Triple(R.drawable.ic_hand, R.string.how_to_follow, R.string.how_to_follow_hand to R.string.how_to_follow_pad),
            Triple(R.drawable.ic_touch, R.string.how_to_tap, R.string.how_to_tap_hand to R.string.how_to_tap_pad),
            Triple(R.drawable.ic_window, R.string.how_to_panel, R.string.how_to_panel_hand to R.string.how_to_panel_pad),
            Triple(R.drawable.ic_back, R.string.how_to_back, R.string.how_to_back_hand to R.string.how_to_back_pad),
        )
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((icon, name, how) in rows) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Icon(painter = painterResource(icon), contentDescription = null, tint = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.padding(top = 2.dp).size(22.dp))
                Text(res.getString(name), style = PicoTheme.typography.titleSmall, modifier = Modifier.padding(start = 10.dp).width(if (c.en) 80.dp else 64.dp), maxLines = 1)
                Column(modifier = Modifier.weight(1f)) {
                    FitText(res.getString(how.first), PicoTheme.typography.bodyMedium, PicoTheme.colorScheme.labelPrimary, id = "settings/howto/${res.getResourceEntryName(name)}", maxLines = 2)
                    FitText(c.padPrefix + res.getString(how.second), PicoTheme.typography.labelMedium, PicoTheme.colorScheme.labelSecondary, id = "settings/howto/${res.getResourceEntryName(name)}/pad")
                }
            }
        }
    }
}

@Composable
private fun SettingRow(label: String, items: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = PicoTheme.typography.bodyMedium, modifier = Modifier.width(108.dp), maxLines = 2)
        items()
    }
}

// ---------------------------------------------------------------------------------------------- SPIKE

@Composable
private fun SpikePage(state: UiState, vm: SignViewModel, spike: String, book: LetterBook) {
    val scored = book.letters.values.filter { it.scored }.map { it.l }
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(spike.ifEmpty { "${state.spikeLetter}: no hand" }, style = PicoTheme.typography.labelMedium, maxLines = 6)
        Spacer(modifier = Modifier.weight(1f))
        ButtonsRow {
            PrimaryButton("记一次 Log", { vm.spikeLog(spike) })
            SecondaryButton("Next letter", { vm.spikeNext(scored) })
        }
    }
}

