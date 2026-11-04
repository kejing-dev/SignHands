package io.embodit.signhands.ui

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.pico.spatial.ui.platform.containers.SpatialNavigator
import io.embodit.signhands.ui.panels.PrimaryButton
import io.embodit.signhands.ui.theme.SignColors
import io.embodit.signhands.ui.window.WindowCtl
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialDragGesture
import com.pico.spatial.ui.platform.LocalStageUpperLimbRenderModelManager
import com.pico.spatial.ui.platform.ability.UpperLimbRenderMode
import io.embodit.signhands.debug.DebugHost
import io.embodit.signhands.platform.SignGraph
import io.embodit.signhands.platform.SignLog
import io.embodit.signhands.scene.PanelIds
import io.embodit.signhands.scene.SignFrameSystem
import io.embodit.signhands.scene.SignScene
import io.embodit.signhands.ui.panels.HandLabel
import io.embodit.signhands.ui.theme.SignTheme

/** Sizes of the Stage's two small attachment panels, dp (the lesson panel is the system window, v0.1.4). */
object PanelSizes {
    val LABEL = IntSize(260, 88)
    val OPEN = IntSize(300, 120)
}

/**
 * The Stage content: one full-size SpatialView carrying the 3D scene, the hand label and the "打开面板 / 平视前方" chip.
 * v0.1.4 (profile D8 / D21): the lesson panel is the system main window ([SignWindow]), opened from here once the head
 * is level (§7.9); the system moves / closes it, the app has no drag code for it. The turntable drag is the only 3D
 * gesture (the teacher's hand and the watch model carry no input, so a pinch made while signing never selects them).
 */
@Composable
fun SignScreen() {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val vm = remember(application) { SignViewModel.shared(application) }
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val scene = remember(vm) { SignScene(vm, SignGraph.head, SignGraph.hands, scope) }
    val navigator = LocalSpatialNavigator.current

    DisposableEffect(scene, navigator) {
        registerSystem<SignFrameSystem>()
        WindowCtl.navigator = navigator
        DebugHost.vm = vm
        DebugHost.scene = scene
        vm.onRecall = { WindowCtl.recall("bring here") }
        vm.onExit = { exitApp(context, scene, navigator) }
        SignLog.i("STAGE enter")
        onDispose {
            DebugHost.vm = null
            DebugHost.scene = null
            vm.onRecall = null
            vm.onExit = null
            if (WindowCtl.navigator === navigator) WindowCtl.navigator = null
            unregisterSystem<SignFrameSystem>()
            scene.detach()
            SignLog.i("STAGE disposed")
        }
    }
    // the main window opens once the head is level (D9, factory native-window-move §7.9)
    LaunchedEffect(Unit) { WindowCtl.openWindow("stage") }

    // real hands stay visible in passthrough under the teacher's hand (design container.upperLimbRenderMode)
    val limbs = LocalStageUpperLimbRenderModelManager.current
    LaunchedEffect(limbs, DebugHost.limbMode.value) {
        val mode = DebugHost.limbMode.value
        val r = runCatching { limbs.setUpperLimbRenderMode(mode) }
        SignLog.i("LIMBS mode=$mode result=${r.getOrNull() ?: r.exceptionOrNull()}")
    }

    val copy = remember(state.settings.lang) { Copy(state.settings.lang) }
    SignTheme(copy) {
        SpatialView(
            modifier =
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        // the watch model's turntable (D21 allows the 3D object's own drag); nothing else on the Stage drags
                        detectSpatialDragGesture(
                            context = context,
                            targetedToEntity = TargetEntity.any { e -> scene.ringHit?.let { it === e } == true },
                            onDragEnd = { _ -> scene.onRingDragEnd() },
                            onDragCancel = { _ -> scene.onRingDragEnd() },
                        ) { value ->
                            scene.onRingDrag(value.dragAmount.x)
                        }
                    },
            attachments = {
                AttachmentPanel(PanelIds.LABEL) {
                    Gate(scene, PanelIds.LABEL, PanelSizes.LABEL) { HandLabel(state, vm.book) }
                }
                AttachmentPanel(PanelIds.OPEN) {
                    Gate(scene, PanelIds.OPEN, PanelSizes.OPEN) { OpenChip(copy) }
                }
            },
            update = { _, attachments -> scene.bind(attachments) },
            initial = { content, attachments -> scene.attach(content, attachments) },
        )
    }
}

/**
 * The Stage's one chip: "平视前方 / 面板就出来" while the window waits for a level head, else "打开面板" while the window
 * is closed (system x) or minimised. The scene shows it only then.
 */
@Composable
private fun OpenChip(copy: Copy) {
    Box(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)).background(Color(0xCC14121A)), contentAlignment = Alignment.Center) {
        if (WindowCtl.levelHint) {
            Text(copy.levelHint, style = PicoTheme.typography.titleMedium, color = SignColors.LabelInk, textAlign = TextAlign.Center, maxLines = 2)
        } else {
            PrimaryButton(copy.openPanel, { WindowCtl.reopen("chip") })
        }
    }
}

/** A panel's content is composed only once the scene has shown it (a panel drawn while parked stays broken). */
@Composable
private fun Gate(scene: SignScene, id: String, size: IntSize, content: @Composable () -> Unit) {
    val shown = scene.panelShown[id] == true
    Box(modifier = Modifier.size(size.width.dp, size.height.dp)) {
        if (shown) content()
    }
}

/**
 * v0.1.3 (profile D26): settings > Exit, confirmed. Tracking and the scene are released first (the hand and head
 * providers stop, entities and panels go), v0.1.4 the window and the Stage are closed, then every task of the app is
 * finished and removed, then the process ends.
 */
private fun exitApp(context: Context, scene: SignScene, navigator: SpatialNavigator) {
    SignLog.i("EXIT confirmed: releasing tracking and the scene, closing the window and the Stage")
    runCatching { scene.detach() }.onFailure { SignLog.e("EXIT detach failed: $it", it) }
    // v0.1.4: the window would otherwise outlive the Stage in the Shared Space (native-window-move §7.7)
    WindowCtl.closeForQuit()
    // a scope of its own: closing the Stage disposes the Stage's composition (and its scope) half-way
    MainScope().launch {
        runCatching { navigator.closeStage() }.onFailure { SignLog.w("EXIT closeStage: $it") }
        finishTasks(context)
    }
}

private fun finishTasks(context: Context) {
    var activity: Context? = context
    while (activity is ContextWrapper && activity !is Activity) activity = activity.baseContext
    runCatching { (activity as? Activity)?.finishAndRemoveTask() }
    runCatching { context.getSystemService(ActivityManager::class.java)?.appTasks?.forEach { it.finishAndRemoveTask() } }
    SignLog.i("EXIT tasks finished, ending the process")
    Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, EXIT_DELAY_MS)
}

private const val EXIT_DELAY_MS = 300L

/** For the debug receiver: the upper-limb mode (Visible by default; SPIKE page / `limbs` command switches it). */
internal fun limbModeOf(name: String?): UpperLimbRenderMode? = UpperLimbRenderMode.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
