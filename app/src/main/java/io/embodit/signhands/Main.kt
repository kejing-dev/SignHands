package io.embodit.signhands

import com.pico.spatial.ui.foundation.dsl.DefaultStage
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import io.embodit.signhands.ui.SignScreen

/**
 * App entry. One Mixed Stage (AndroidManifest: `pico.spatial.stage.style=1`): hand tracking only reports joints
 * in Full Space, and the real hands stay visible in passthrough (design `container`, archetype D).
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultStage {
            SignScreen()
        }
    }
