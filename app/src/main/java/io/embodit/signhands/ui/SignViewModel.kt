package io.embodit.signhands.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.embodit.signhands.data.Store
import io.embodit.signhands.domain.Effect
import io.embodit.signhands.domain.Flow
import io.embodit.signhands.domain.GhostMode
import io.embodit.signhands.domain.Handed
import io.embodit.signhands.domain.Lang
import io.embodit.signhands.domain.LetterBook
import io.embodit.signhands.domain.Screen
import io.embodit.signhands.domain.Support
import io.embodit.signhands.domain.Theme
import io.embodit.signhands.domain.UiState
import io.embodit.signhands.platform.Chime
import io.embodit.signhands.platform.SignLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

/**
 * Wraps the pure [Flow]: the panels call the actions below, the scene calls [tick], [matched], [handVisible] and
 * [hint] (at most ~10 times a second); effects (sound, files, log lines) are carried out here.
 */
class SignViewModel(application: Application) : AndroidViewModel(application) {
    private val store = Store(application)
    private val chime = Chime()

    val book: LetterBook = LetterBook.parse(application.assets.open("asl_letters.json").bufferedReader().use { it.readText() })
    private val flow = Flow(book)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Debug `clock +3d`. */
    @Volatile var dayOffset = 0L

    val today: Long
        get() = LocalDate.now().toEpochDay() + dayOffset

    val version: String = runCatching { application.packageManager.getPackageInfo(application.packageName, 0).versionName ?: "" }.getOrDefault("")

    init {
        flow.todayProvider = { today }
        flow.loaded(store.loadSettings(), store.loadProgress())
        publish()
        SignLog.i("VM loaded firstRun=${flow.state.firstRun} settings=${flow.state.settings} letters=${book.letters.size}")
    }

    private fun now() = SystemClock.elapsedRealtime()

    private fun publish() {
        for (e in flow.drain()) {
            when (e) {
                Effect.Ding -> if (!flow.state.settings.muted) chime.ding()
                Effect.SaveProgress -> store.saveProgress(flow.state.progress)
                Effect.SaveSettings -> store.saveSettings(flow.state.settings)
                Effect.ClearProgress -> store.clearProgress()
                is Effect.Log -> SignLog.i(e.line)
                Effect.Exit -> onExit?.invoke()
            }
        }
        _state.value = flow.state
    }

    private inline fun act(block: Flow.() -> Unit) {
        flow.block()
        publish()
    }

    // ------------------------------------------------------------------------------------ scene side

    fun ready(support: Support) = act { ready(now(), support) }

    fun tick(dtMs: Long) = act { tick(now(), dtMs, today) }

    fun matched() = act { matched(now()) }

    fun handVisible(v: Boolean) = act { handVisible(v, now()) }

    fun supportLost() = act { supportLost(now()) }

    fun support(sup: Support) {
        if (sup == flow.state.support) return
        act { support(sup, now()) }
    }

    fun hint(key: String?) {
        if (key == flow.state.hint) return
        act { hint(key) }
    }

    // ------------------------------------------------------------------------------------ panel side

    fun chooseHand(h: Handed) = act { chooseHand(h, now()) }

    fun openLesson(n: Int) = act { openLesson(n, now()) }

    fun openLetter(l: String) = act { openLetter(l, now()) }

    fun startReview() = act { startReview(today, now()) }

    fun openAlphabet() = act { openAlphabet(now()) }

    fun openWords() = act { openWords(now()) }

    fun setTheme(t: Theme) = act { setTheme(t) }

    fun openSign(id: String) = act { openSign(id, now()) }

    fun openSettings() = act { openSettings(now()) }

    fun home() = act { home(now()) }

    fun back() = act { back(now()) }

    fun toggleSlow() = act { toggleSlow() }

    fun toggleOpposite() = act { toggleOpposite() }

    fun replay() = act { replay() }

    fun tryIt() = act { tryIt(now()) }

    fun watchAgain() = act { watchAgain(now()) }

    fun selfDone() = act { selfDone(now()) }

    fun arm(what: String) = act { armOrConfirm(what, now(), today) }

    fun quizReady() = act { quizSelfReady(now()) }

    fun nextLetter() = act { nextLetter(now()) }

    fun showAnswer() = act { showAnswer(now()) }

    /** "Bring here": set by the screen to the scene's recall (panel + demo hand in front of the head, then locked). */
    var onRecall: (() -> Unit)? = null

    fun recall() {
        SignLog.i("PANEL recall")
        onRecall?.invoke()
    }

    fun answer(correct: Boolean?) = act { answer(correct, today, now()) }

    fun nextLesson() = act { nextLesson(now()) }

    fun recheck(support: Support) = act { recheck(support, now()) }

    fun watchOnly() = act { watchOnly(now()) }

    fun setLang(l: Lang) = act { setLang(l) }

    fun setHand(h: Handed) = act { setHand(h) }

    fun setGhost(g: GhostMode) = act { setGhost(g) }

    fun toggleMute() = act { toggleMute() }

    fun askClear() = act { askClear(now()) }

    fun confirmClear() = act { confirmClear(now()) }

    fun cancelClear() = act { debugGo(Screen.S14, now()) }

    fun toggleAbout() = act { toggleAbout() }

    /** v0.1.3 (profile D26): settings > Exit asks first; the confirmed exit is carried out by the screen ([onExit]). */
    fun askExit() = act { askExit(now()) }

    fun confirmExit() = act { confirmExit() }

    fun cancelExit() = act { debugGo(Screen.S14, now()) }

    /** Set by the screen: release the scene's tracking, finish the task, end the process. */
    var onExit: (() -> Unit)? = null

    // ------------------------------------------------------------------------------------------ debug

    fun debug(block: Flow.(now: Long, today: Long) -> Unit) = act { block(now(), today) }

    fun debugClearData() {
        store.clearAll()
        act { debugSet(UiState(screen = Screen.S00).copy(firstRun = true)) }
        act { loaded(null, store.loadProgress()) }
        act { ready(now(), Support.OK) }
    }

    /** SPIKE page: "log once" writes the current recognition line; "next letter" cycles the scored letters. */
    fun spikeLog(line: String) = SignLog.i("SPIKE letter=${flow.state.spikeLetter} ${line.replace('\n', ' ')}")

    fun spikeNext(scored: List<String>) = act {
        val i = scored.indexOf(state.spikeLetter)
        debugSet(state.copy(spikeLetter = scored[(i + 1) % scored.size]))
    }

    fun debugSaveProgress() = store.saveProgress(flow.state.progress)

    override fun onCleared() {
        chime.release()
        super.onCleared()
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SignViewModel(application) as T
    }
}
