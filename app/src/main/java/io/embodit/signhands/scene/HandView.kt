package io.embodit.signhands.scene

import androidx.compose.ui.graphics.Color
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.animation.AnimationPlayConfig
import com.pico.spatial.core.ecs.animation.AnimationPlaybackController
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import io.embodit.signhands.domain.Finger
import io.embodit.signhands.domain.Q4
import io.embodit.signhands.domain.Tint
import io.embodit.signhands.domain.V3
import io.embodit.signhands.platform.SignLog
import io.embodit.signhands.ui.theme.SignColors

/**
 * One instance of the WebXR generic-hand (assets/hands/hand_<side>.glb, baked by tools/bake_hands.py): the
 * model's root is the app's hand frame (wrist at the origin, fingers +Y, back +Z). Its six primitives (thumb,
 * index, middle, ring, pinky, palm) get one material each, so a finger can be tinted on its own. The pose is
 * the clip "poses" scrubbed with setTime while paused: letter k sits at [2k, 2k + 2) s.
 *
 * [ghost] = the teacher's hand (translucent warm white, glowing), otherwise the watch model (soft skin).
 */
internal class HandView(val name: String, model: Entity, private val shared: SharedResources, val ghost: Boolean) {
    /** Placement: wrist position, hand-frame rotation, uniform scale. */
    val root: Entity = Entity().also { runCatching { it.setName("hand:$name") } }
    private val materials: List<Material>
    private var controller: AnimationPlaybackController? = null
    private var lastTime = Float.NaN
    private var tints: Map<Finger, Tint> = emptyMap()
    private var flash = 0f
    private var opacity = -1f
    var visible = false
        private set

    init {
        root.addChild(model)
        val holders = ArrayList<ModelComponent>()
        collectModels(model, holders)
        val slots = holders.maxOfOrNull { it.materials.size } ?: 0
        materials = List(GROUPS) { i -> makeMaterial(i) }
        for (mc in holders) {
            for (i in 0 until mc.materials.size) runCatching { mc.materials[i] = materials[i.coerceAtMost(GROUPS - 1)] }
        }
        val carrier = runCatching { model.findSkeletonRootEntity().firstOrNull() }.getOrNull() ?: runCatching { model.findSkinnedMeshEntity().firstOrNull() }.getOrNull() ?: model
        val clips = runCatching { carrier.getAnimationResources().toList() }.getOrNull().orEmpty().ifEmpty { runCatching { model.getAnimationResources().toList() }.getOrNull().orEmpty() }
        val clip = clips.firstOrNull { runCatching { it.getName() }.getOrNull()?.endsWith("poses") == true } ?: clips.firstOrNull()
        controller =
            clip?.let { c ->
                runCatching { carrier.playAnimation(c, AnimationPlayConfig(transitionDuration = 0f)) }
                    .onFailure { SignLog.e("HAND $name play failed: $it", it) }
                    .getOrNull()
            }
        runCatching { controller?.pause() }
        SignLog.i(
            "HAND $name ready models=${holders.size} slots=$slots clips=${clips.map { runCatching { it.getName() }.getOrDefault("?") }} " +
                "duration=${runCatching { controller?.getDuration() }.getOrNull()} carrier=${runCatching { carrier.getName() }.getOrNull()}"
        )
        setVisible(false)
    }

    val animated: Boolean
        get() = controller != null

    private fun collectModels(e: Entity, out: MutableList<ModelComponent>) {
        e.components[ModelComponent::class.java]?.let { out += it }
        runCatching { e.getChildren() }.getOrNull()?.forEach { collectModels(it, out) }
    }

    /**
     * Ghost: transparent unlit ("translucent glowing teacher's hand"); a transparent PBR material on the skinned mesh
     * rendered opaque on the emulator, and so did the entity's OpacityControllerComponent.
     */
    private fun makeMaterial(group: Int): Material =
        shared.keep(
            if (ghost) {
                UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
                    setBaseColor(SignColors.TeacherWhite.color4())
                    setOpacity(GHOST_OPACITY)
                    runCatching { setApplyToneMapping(false) }
                    setDepthWrite(false)
                    setCullingMode(MaterialCullingMode.BACK)
                }
            } else {
                PhysicallyBasedMaterial.create(BlendingMode.OPAQUE).apply {
                    setBaseColor((if (group == GROUPS - 1) SignColors.Skin else SignColors.Skin).color4())
                    setRoughness(0.58f)
                    setMetallic(0f)
                }
            }
        )

    fun setVisible(v: Boolean) {
        if (v == visible) return
        visible = v
        root.setLocal(position = if (v) null else STOW)
        setEnabledRecursive(root, v)
    }

    private fun setEnabledRecursive(e: Entity, v: Boolean) {
        e.components[ModelComponent::class.java]?.isVisible = v
        runCatching { e.getChildren() }.getOrNull()?.forEach { setEnabledRecursive(it, v) }
    }

    private var entityOpacity = -1f

    /** Whole-hand opacity, written to every material slot. */
    private fun applyEntityOpacity(k: Float) {
        if (!ghost || k == entityOpacity) return
        entityOpacity = k
        materials.forEach { opacityOf(it, k) }
    }

    private fun opacityOf(m: Material, k: Float) {
        when (m) {
            is UnlitMaterial -> m.setOpacity(k)
            is PhysicallyBasedMaterial -> m.setOpacity(k)
        }
    }

    private fun colorOf(m: Material, c: Color) {
        when (m) {
            is UnlitMaterial -> m.setBaseColor(c.color4())
            is PhysicallyBasedMaterial -> m.setBaseColor(c.color4())
        }
    }

    fun place(wrist: V3, rotation: Q4, scale: Float) {
        if (!visible) return
        root.setLocal(position = wrist, rotation = rotation, scale = V3(scale, scale, scale))
    }

    /** Clip time in seconds (letter segment + morph progress). */
    fun pose(time: Float) {
        if (time == lastTime) return
        lastTime = time
        controller?.let { c -> runCatching { c.setTime(time) }.onFailure { SignLog.w("HAND $name setTime failed: $it") } }
    }

    /** Per-finger tint (jade / coral), [flashJade] 0..1 tints the whole hand jade (S07), [breath] 0..1 for the coral pulse. */
    fun tint(map: Map<Finger, Tint>, flashJade: Float, breath: Float) {
        if (!ghost) return
        applyEntityOpacity(if (flashJade > 0f) 0.6f else GHOST_OPACITY * fade)
        if (map == tints && flashJade == flash && map.values.none { it == Tint.CORAL }) return
        tints = map
        flash = flashJade
        for (i in 0 until GROUPS) {
            val finger = if (i < 5) Finger.entries[i] else null
            val t = finger?.let { map[it] }
            val m = materials[i]
            val c =
                when {
                    flashJade > 0f -> mix(SignColors.TeacherWhite, SignColors.Jade, flashJade)
                    t == Tint.JADE -> SignColors.Jade
                    t == Tint.CORAL -> mix(SignColors.Coral, SignColors.TeacherWhite, 0.25f * breath)
                    else -> SignColors.TeacherWhite
                }
            colorOf(m, c)
        }
    }

    private var fade = 1f

    private fun opacityFactor(): Float = fade

    /** Overall fade (S02 breathing, S07 fade-out) for the untinted parts. */
    fun fade(k: Float) {
        if (!ghost || k == opacity) return
        opacity = k
        fade = k
        applyEntityOpacity(GHOST_OPACITY * k)
    }

    private fun mix(a: Color, b: Color, t: Float) = Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f)

    fun destroy() {
        runCatching { controller?.stop() }
        runCatching { controller?.close() }
        controller = null
        runCatching { root.destroy(true) }
    }

    companion object {
        const val GROUPS = 6
        const val GHOST_OPACITY = 0.45f
        val STOW = V3(0f, -40f, 0f)
    }
}
