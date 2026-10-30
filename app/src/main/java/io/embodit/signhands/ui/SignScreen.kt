package io.embodit.signhands.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
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
import androidx.lifecycle.viewmodel.compose.viewModel
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
import io.embodit.signhands.ui.panels.LessonPanel
import io.embodit.signhands.ui.theme.SignTheme

/** Sizes of the two attachment panels, dp (design `panels[]`; the lesson panel at its max height). */
object PanelSizes {
    val LESSON = IntSize(460, 380)
    val LABEL = IntSize(260, 88)
}

/**
 * The Stage content: one full-size SpatialView carrying the 3D scene and both panels. Panels are declared once with a
 * fixed size; the scene parents them under its holders and moves them. The turntable drag is the only 3D gesture
 * (the teacher's hand and the watch model carry no input, so a pinch made while signing never selects them).
 */
@Composable
fun SignScreen() {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val vm: SignViewModel = viewModel(factory = SignViewModel.Factory(application))
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val scene = remember(vm) { SignScene(vm, SignGraph.head, SignGraph.hands, scope) }

    DisposableEffect(scene) {
        registerSystem<SignFrameSystem>()
        DebugHost.vm = vm
        DebugHost.scene = scene
        vm.onRecall = { scene.recall() }
        SignLog.i("STAGE enter")
        onDispose {
            DebugHost.vm = null
            DebugHost.scene = null
            vm.onRecall = null
            unregisterSystem<SignFrameSystem>()
            scene.detach()
            SignLog.i("STAGE disposed")
        }
    }

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
                        detectSpatialDragGesture(
                            context = context,
                            targetedToEntity = TargetEntity.any { e -> scene.ringHit?.let { it === e } == true || scene.panelHandle?.let { it === e } == true },
                            onDragStart = { _, value -> scene.onDragStart(value.targetEntity) },
                            onDragEnd = { _ -> scene.onDragEnd() },
                            onDragCancel = { _ -> scene.onDragEnd() },
                        ) { value ->
                            scene.onDrag(value.dragAmount.x, value.dragAmount.y)
                        }
                    },
            attachments = {
                AttachmentPanel(PanelIds.LESSON) {
                    Gate(scene, PanelIds.LESSON, PanelSizes.LESSON) { LessonPanel(state, vm, vm.book, scene.spikeText.value) }
                }
                AttachmentPanel(PanelIds.LABEL) {
                    Gate(scene, PanelIds.LABEL, PanelSizes.LABEL) { HandLabel(state, vm.book) }
                }
            },
            update = { _, attachments -> scene.bind(attachments) },
            initial = { content, attachments -> scene.attach(content, attachments) },
        )
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

/** For the debug receiver: the upper-limb mode (Visible by default; SPIKE page / `limbs` command switches it). */
internal fun limbModeOf(name: String?): UpperLimbRenderMode? = UpperLimbRenderMode.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
