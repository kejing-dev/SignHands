package io.embodit.signhands.ui.window

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import android.view.View
import android.view.ViewParent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.windows.TabBar
import com.pico.spatial.ui.platform.ViewPoint
import io.embodit.signhands.platform.BackKeys
import io.embodit.signhands.platform.SignLog
import io.embodit.signhands.ui.panels.eatPadKeys

/*
 * 主窗口模板（taste D8「合成一个窗口模板」+ D21 拖动交给系统 + D26 设置里退出应用），产线 prompts/snippets/
 * MainWindowTemplate.kt 的本应用版（和 drone-sight v0.1.3 的改法一样）。和产线版的差别，结构不变：
 *  - 当前页由应用的状态机决定（[current] + [onSelect]），不是模板自己 rememberSaveable：调试广播和窗口按钮走同一套事件；
 *  - 页面内容（含标题行、返回键、静音键）由应用的 LessonPanel 按状态画，一页里有课程 → 示范 → 跟做 → 测验的流程；
 *  - 「设置」页是最后一个页签，「退出应用」和二次确认在设置页里（S14 / S14 exit），中英双语的字由应用传进来。
 * 规矩照旧：应用全部 2D 内容都在这一个系统窗口里；切页只用 SpatialUI 原生 TabBar（系统挂在窗口外的独立小窗，
 * 模拟器上从正面看在窗口上方居中，只显示图标）；拖动 / 关闭 / 最小化是系统窗口控件（窗口下方的小圆点），这里不写任何拖动代码。
 */

/** One TabBar item of the main window. [icon]: a Material Symbols / Material Icons vector (taste D23). */
class MainWindowTab(val id: String, val label: String, @DrawableRes val icon: Int)

/**
 * The app's single main window: [content] fills it; the system TabBar lists [tabs] (at most 7, settings last) with
 * [current] selected, or is left out while [showTabs] is false (first run, before a hand is chosen).
 */
@Composable
fun MainWindow(tabs: List<MainWindowTab>, current: String, showTabs: Boolean, onSelect: (String) -> Unit, content: @Composable () -> Unit) {
    require(tabs.size in 1..7) { "TabBar holds at most 7 items" }
    Box(Modifier.fillMaxSize().eatPadKeys("window")) { content() }

    if (!showTabs) return
    // system TabBar: its own small window outside this one (emulator 6.1.0, seen from the front: centred above the window)
    TabBar(followViewpoints = setOf(ViewPoint.Front, ViewPoint.Left)) {
        tabs.forEach { t ->
            item(
                selected = current == t.id,
                onClick = {
                    SignLog.i("TAB ${t.id}")
                    // the TabBar is a window of its own: hook its root so BACK / B pressed after a tab tap still arrives
                    BackKeys.sweep("tab")
                    onSelect(t.id)
                },
                mainContent = {
                    TabKeyGuard()
                    Icon(painter = painterResource(t.icon), contentDescription = t.label)
                },
                supportContent = { Text(t.label) },
            )
        }
    }
}

/**
 * v0.1.6 (profile D60): the TabBar lives in a Dialog window of its own, where the focused tab clicks on A / X / ENTER /
 * DPAD_CENTER and BACK never reaches the activity. From inside a tab, walk up to the [DialogWindowProvider] and wrap
 * that window's callback ([BackKeys.wrap]): unassigned pad keys are eaten, BACK / B go to the app's one back handler.
 */
@Composable
internal fun TabKeyGuard() {
    val view = LocalView.current
    LaunchedEffect(view) {
        var v: Any? = view
        while (v != null) {
            if (v is DialogWindowProvider) {
                BackKeys.wrap(v.window, "TabBar")
                return@LaunchedEffect
            }
            v = (v as? View)?.parent ?: (v as? ViewParent)?.parent
        }
        SignLog.w("KEY TabBar window not found from ${view.javaClass.simpleName}")
    }
}
