package io.embodit.signhands.scene

import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System

/** The one per-frame system of the app (registered while the Stage screen is composed). */
class SignFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        ActiveScene.scene?.onFrame(context.deltaTime)
    }
}

object ActiveScene {
    @Volatile var scene: SignScene? = null
}
