package io.embodit.signhands

import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.dsl.DefaultStage
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import io.embodit.signhands.domain.MainWindowLayout
import io.embodit.signhands.ui.SignScreen
import io.embodit.signhands.ui.window.PANEL_ID
import io.embodit.signhands.ui.window.SignWindow

/**
 * App entry. One Mixed Stage (AndroidManifest: `pico.spatial.stage.style=1`): hand tracking only reports joints
 * in Full Space, and the real hands stay visible in passthrough (design `container`, archetype D).
 * v0.1.4 (profile D8 / D21, factory native-window-move): all 2D lesson content is one system Planar window opened from
 * the Stage; the system moves / closes / minimises it, its pages are switched by the SpatialUI TabBar.
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultStage {
            SignScreen()
        }
        WindowContainer(
            PANEL_ID,
            form = Form.Planar,
            defaultSize = WindowContainerSize(width = MainWindowLayout.W_DP.dp, height = MainWindowLayout.H_DP.dp),
        ) { SignWindow() }
    }
