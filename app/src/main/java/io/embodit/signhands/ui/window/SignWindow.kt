package io.embodit.signhands.ui.window

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.platform.LengthUnit
import com.pico.spatial.ui.platform.LocalPhysicalLengthConverter
import io.embodit.signhands.R
import io.embodit.signhands.domain.MainWindowLayout
import io.embodit.signhands.domain.Tab
import io.embodit.signhands.platform.SignLog
import io.embodit.signhands.ui.Copy
import io.embodit.signhands.ui.SignViewModel
import io.embodit.signhands.ui.panels.LessonPanel
import io.embodit.signhands.ui.theme.SignTheme
import java.util.Locale

/**
 * v0.1.4 (profile D8 / D21, factory native-window-move §3.6): the lesson panel is the one system Planar window.
 * Pages 课程 / 字母表 / 常用词 / 设置 are switched by the SpatialUI TabBar; the lesson flow (watch, follow, quiz)
 * runs inside the page it was started from. The system moves, closes and minimises the window.
 */
@Composable
fun SignWindow() {
    val context = LocalContext.current
    val vm = remember(context) { SignViewModel.shared(context.applicationContext as Application) }
    val state by vm.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> WindowCtl.onLifecycle(e.name) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val converter = LocalPhysicalLengthConverter.current
    LaunchedEffect(converter) {
        if (WindowCtl.dpLogged) return@LaunchedEffect
        WindowCtl.dpLogged = true
        runCatching { converter.dpToLength(1000.dp, LengthUnit.Meters) / 1000f }.getOrNull()?.let {
            SignLog.i(String.format(Locale.US, "WINDOW dp=%.5f m (%.2f x %.2f m, layout assumes %.5f)", it, MainWindowLayout.W_DP * it, MainWindowLayout.H_DP * it, MainWindowLayout.DP_M))
        }
    }
    val copy = remember(state.settings.lang) { Copy(state.settings.lang) }
    val tabs =
        listOf(
            MainWindowTab(Tab.LESSONS.name, copy.lessons, R.drawable.ic_school),
            MainWindowTab(Tab.ALPHABET.name, copy.alphabet, R.drawable.ic_sort_by_alpha),
            MainWindowTab(Tab.WORDS.name, copy.words, R.drawable.ic_menu_book),
            MainWindowTab(Tab.SETTINGS.name, copy.settings, R.drawable.ic_settings),
        )
    val current = Tab.of(state)
    SignTheme(copy) {
        MainWindow(
            tabs = tabs,
            current = current.name,
            showTabs = Tab.shown(state),
            onSelect = { id ->
                // the page already shown keeps its lesson (a stray tap must not end it); use "‹" inside the page
                if (id != current.name) {
                    when (Tab.valueOf(id)) {
                        Tab.LESSONS -> vm.home()
                        Tab.ALPHABET -> vm.openAlphabet()
                        Tab.WORDS -> vm.openWords()
                        Tab.SETTINGS -> vm.openSettings()
                    }
                }
            },
        ) {
            LessonPanel(state, vm, vm.book, WindowSpike.text.value)
        }
    }
}

/** The SPIKE page's recognition line, written by the scene a few times a second (the scene lives in the Stage). */
object WindowSpike {
    val text = androidx.compose.runtime.mutableStateOf("")
}
