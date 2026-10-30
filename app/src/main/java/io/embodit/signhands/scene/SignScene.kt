package io.embodit.signhands.scene

import android.os.SystemClock
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.DirectionalLightComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.SphereOption
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import io.embodit.signhands.domain.FakeHand
import io.embodit.signhands.domain.Features
import io.embodit.signhands.domain.Finger
import io.embodit.signhands.domain.FrameVerdict
import io.embodit.signhands.domain.GhostMode
import io.embodit.signhands.domain.HandFrame
import io.embodit.signhands.domain.HandMath
import io.embodit.signhands.domain.Handed
import io.embodit.signhands.domain.HintHolder
import io.embodit.signhands.domain.ItemKind
import io.embodit.signhands.domain.J
import io.embodit.signhands.domain.Judge
import io.embodit.signhands.domain.Layout
import io.embodit.signhands.domain.Letter
import io.embodit.signhands.domain.PracticeFrame
import io.embodit.signhands.domain.Q4
import io.embodit.signhands.domain.Reveal
import io.embodit.signhands.domain.Screen
import io.embodit.signhands.domain.Support
import io.embodit.signhands.domain.Tint
import io.embodit.signhands.domain.UiState
import io.embodit.signhands.domain.V3
import io.embodit.signhands.domain.dirOf
import io.embodit.signhands.domain.faceHead
import io.embodit.signhands.domain.mirrorX
import io.embodit.signhands.domain.smoothstep
import io.embodit.signhands.domain.wrap180
import io.embodit.signhands.domain.yawOf
import io.embodit.signhands.platform.HandTracker
import io.embodit.signhands.platform.HeadTracker
import io.embodit.signhands.platform.SignLog
import io.embodit.signhands.ui.SignViewModel
import io.embodit.signhands.ui.theme.SignColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/** Ids of the two attachment panels (declared once in SignScreen with fixed sizes). */
object PanelIds {
    const val LESSON = "lesson"
    const val LABEL = "label"
    val ALL = listOf(LESSON, LABEL)
}

/**
 * The 3D side of the app, driven once per frame by [SignFrameSystem] (never through Compose): reads the head and
 * the dominant hand, judges the letter (S06 / S09), poses and places the teacher's hand, the watch model and its
 * turntable, the hint ring / turn-palm arrow, the J / Z trail, the lazy-follow lesson panel and the hand label.
 * The ViewModel only gets discrete events and a hint key at most 10 times a second.
 */
class SignScene(private val vm: SignViewModel, private val head: HeadTracker, private val hands: HandTracker, private val scope: CoroutineScope) {
    val panelShown = mutableStateMapOf<String, Boolean>()

    /** The debug page (SPIKE) reads this a few times a second. */
    val spikeText = mutableStateOf("")

    /** The turntable's collider: drag target. */
    @Volatile var ringHit: Entity? = null
        private set

    // --- debug hooks
    @Volatile var fake: FakeHand? = null
    @Volatile var noHand = false

    private var root: Entity? = null
    private var attachments: SpatialViewAttachments? = null
    private val shared = SharedResources()
    private var attached = false
    private val work = ArrayDeque<Pair<String, () -> Unit>>()
    private var lastWorkMs = 0L
    private val jobs = ArrayList<Job>()
    private val book = vm.book

    private val ghost = HashMap<Handed, HandView>()
    private val watch = HashMap<Handed, HandView>()
    private var modelsDone = false
    private var ring: ModelEntity? = null
    private var hintRing: ModelEntity? = null
    private var arrow: ModelEntity? = null
    private val trail = ArrayList<ModelEntity>()

    private inner class Slot(val id: String, val holder: Entity, val scale: Float) {
        var panel: Entity? = null
        var shown = false
        var shownFrame = 0L

        fun place(position: V3, rotation: Q4) {
            holder.setLocal(position = position, rotation = rotation, scale = V3(scale, scale, scale))
            if (!shown) {
                shown = true
                shownFrame = frame
            }
            val visible = frame - shownFrame >= SHOW_FRAMES
            if (panelShown[id] != visible) panelShown[id] = visible
        }

        fun hide() {
            if (shown || frame == 0L) {
                shown = false
                panelShown[id] = false
                holder.setLocal(position = STOW, scale = V3(PARK, PARK, PARK))
            }
        }
    }

    private val slots = LinkedHashMap<String, Slot>()

    // --- per-frame state
    private var frame = 0L
    private var attachMs = 0L
    private var readySent = false
    private var headYawSmooth = Float.NaN
    private var lastSeenMs = -1L
    private var reportedVisible = false
    private val lengths = ArrayList<Float>()
    private var firstHandMs = -1L
    private var handLength = HandMath.DEFAULT_HAND_LENGTH
    private var lengthLocked = false
    private val wristFilter = OneEuro()
    private var judge: Judge? = null
    private var judgeKey = ""
    private var matchSent = false
    private val hintHolder = HintHolder()
    private var lastHintMs = 0L
    private var lastTickMs = 0L
    private var verdict: FrameVerdict? = null
    private var supportWasOk = true
    private var featLogMs = 0L
    private var featSum: FloatArray? = null
    private var featN = 0
    private var lastWrist: V3? = null
    private var jitterSum = 0f
    private var jitterN = 0

    // watch model
    private var watchKey = ""
    private var watchT = 0f
    private var turn = 0f
    private var userTurn = 0f
    private var lastReplay = -1

    // panels
    private var stationSet = false
    private var stationPos = V3.ZERO
    /** Heading the panel faces away from (the user stood at stationYaw + 180 when it was placed). */
    private var stationYaw = 0f
    @Volatile var panelHandle: Entity? = null
        private set
    private var labelPos: V3? = null
    private var labelFrom = V3.ZERO
    private var labelTo = V3.ZERO
    private var labelT = 1f
    private var breath = 0f

    // ------------------------------------------------------------------------------------------ setup

    fun attach(content: SpatialViewContent, attachments: SpatialViewAttachments) {
        if (attached) detach()
        val t0 = SystemClock.elapsedRealtime()
        attachMs = t0
        this.attachments = attachments
        val root = Entity()
        runCatching { root.setName("signRoot") }
        content.addEntity(root)
        // an entity added to the SpatialView is not at the Stage origin: move the root once so its local space is Stage space
        val offset = root.convertPositionFrom(Vector3.ZERO, null)
        val rotation = root.convertRotationFrom(Quat.identity(), null)
        root.components[TransformComponent::class.java]?.apply {
            setPosition(offset)
            setQuaternion(rotation)
        }
        this.root = root
        Entity().also {
            it.components.set(DirectionalLightComponent(Color4(1f, 0.97f, 0.92f, 1f), LIGHT_LUX, false))
            it.setLocal(rotation = Q4.pitch(-40f) * Q4.yaw(20f))
            root.addChild(it)
        }
        for (id in PanelIds.ALL) {
            val holder = Entity()
            runCatching { holder.setName("holder:$id") }
            root.addChild(holder)
            slots[id] = Slot(id, holder, if (id == PanelIds.LESSON) LESSON_SCALE else LABEL_SCALE).also { it.hide() }
        }
        bind(attachments)
        queueBuild(root)
        loadModels(root)
        head.start()
        hands.start()
        ActiveScene.scene = this
        attached = true
        SignLog.i("SCENE attached setup_ms=${SystemClock.elapsedRealtime() - t0} emulator=${hands.isEmulator}")
    }

    fun bind(attachments: SpatialViewAttachments): Boolean {
        var complete = true
        for (slot in slots.values) {
            if (slot.panel != null) continue
            val panel = attachments.entity(slot.id)
            if (panel == null) {
                complete = false
                continue
            }
            slot.holder.addChild(panel)
            runCatching { panel.setName("panel:${slot.id}") }
            slot.panel = panel
            SignLog.i("PANEL bound id=${slot.id}")
        }
        return complete
    }

    fun detach() {
        ActiveScene.scene = null
        attached = false
        work.clear()
        jobs.forEach { it.cancel() }
        jobs.clear()
        hands.stop()
        head.stop()
        (ghost.values + watch.values).forEach { it.destroy() }
        ghost.clear()
        watch.clear()
        trail.clear()
        for (slot in slots.values) {
            runCatching { slot.holder.removeAllChildren() }
            slot.panel = null
        }
        slots.clear()
        panelShown.clear()
        ringHit = null
        panelHandle = null
        stationSet = false
        runCatching { root?.destroy(true) }
        root = null
        shared.close()
        readySent = false
        SignLog.i("SCENE detached")
    }

    private fun loadModels(root: Entity) {
        jobs +=
            scope.launch {
                for (side in Handed.entries) {
                    val file = if (side == Handed.RIGHT) "hands/hand_right.glb" else "hands/hand_left.glb"
                    val t0 = SystemClock.elapsedRealtime()
                    val model = runCatching { Entity.loadSuspend("asset://$file") }.onFailure { SignLog.e("HAND load $file failed: $it", it) }.getOrNull()
                    if (model == null || !attached) continue
                    val copy = runCatching { model.clone(Entity.CloneOptions(recursive = true, shouldShareMaterialInstance = false)) }.getOrNull()
                    val g = HandView("ghost:${side.name.lowercase()}", model, shared, ghost = true)
                    root.addChild(g.root)
                    ghost[side] = g
                    if (copy != null) {
                        val w = HandView("watch:${side.name.lowercase()}", copy, shared, ghost = false)
                        root.addChild(w.root)
                        watch[side] = w
                    }
                    SignLog.i("HAND loaded $file ms=${SystemClock.elapsedRealtime() - t0} animated=${g.animated}")
                }
                modelsDone = true
            }
    }

    private fun queueBuild(root: Entity) {
        work += "ring" to {
            val mat = unlit(shared, SignColors.Ring, BlendingMode.TRANSPARENT, opacity = 0.55f, twoSided = true, depthWrite = false)
            ring = ModelEntity(Meshes.ring(RING_R - 0.004f, RING_R, "turntable", segments = 72), mat).also {
                root.addChild(it)
                it.setVisible(false)
            }
            ringHit = Entity().also {
                runCatching { it.setName("ringHit") }
                runCatching { it.components.set(CollisionComponent(listOf(ShapeResource.createBox(Vector3(RING_R * 2.2f, 0.05f, RING_R * 2.2f))), PhysicsMaterialResource())) }
                it.setLocal(position = STOW)
                root.addChild(it)
            }
        }
        work += "handle" to {
            val mat = unlit(shared, SignColors.TeacherWhite, BlendingMode.TRANSPARENT, opacity = 0.75f, twoSided = true, depthWrite = false)
            val bar = ModelEntity(Meshes.unitCube("panelHandle"), mat)
            bar.setLocal(scale = V3(HANDLE_W, HANDLE_H, 0.008f))
            panelHandle = Entity().also {
                runCatching { it.setName("panelHandle") }
                runCatching { it.components.set(CollisionComponent(listOf(ShapeResource.createBox(Vector3(HANDLE_W + 0.06f, HANDLE_H + 0.05f, 0.05f))), PhysicsMaterialResource())) }
                it.addChild(bar)
                it.setLocal(position = STOW)
                root.addChild(it)
            }
        }
        work += "hint" to {
            hintRing = ModelEntity(Meshes.ring(0.004f, 0.006f, "hintRing", segments = 32), unlit(shared, SignColors.Coral, twoSided = true, depthWrite = false)).also {
                root.addChild(it)
                it.setVisible(false)
            }
            arrow = ModelEntity(Meshes.bentArrow(0.05f, "palmArrow"), unlit(shared, SignColors.Coral, twoSided = true, depthWrite = false)).also {
                root.addChild(it)
                it.setVisible(false)
            }
        }
        work += "trail" to {
            val dot = shared.keep(MeshResource.createSphere(SphereOption(1f).setName("trailDot")))
            val mat: UnlitMaterial = unlit(shared, SignColors.TrailAmber)
            repeat(TRAIL_DOTS) {
                trail += ModelEntity(dot, mat).also { e ->
                    e.setUniformScale(0.004f)
                    root.addChild(e)
                    e.setVisible(false)
                }
            }
        }
    }

    private fun runWork(dt: Float, now: Long) {
        if (work.isEmpty()) return
        val n = if (dt < FAST_FRAME_SEC) 2 else if (now - lastWorkMs >= SLOW_GAP_MS) 1 else 0
        repeat(n) {
            val (name, w) = work.removeFirstOrNull() ?: return
            runCatching { w() }.onFailure { SignLog.e("SCENE build '$name' failed: $it", it) }
            lastWorkMs = SystemClock.elapsedRealtime()
        }
    }

    // ---------------------------------------------------------------------------------------- gestures

    /** Turntable drag: px of horizontal drag -> degrees (about 6 degrees per cm at arm's length). */
    fun onRingDrag(dxPx: Float) {
        userTurn += dxPx * DEG_PER_PX
    }

    fun onRingDragEnd() = SignLog.i("WATCH turn=${userTurn.toInt()}")

    /** Which drag is running: the turntable or the panel's bar. */
    @Volatile var dragging: String? = null

    fun onDragStart(target: Entity?) {
        dragging = if (target != null && target === panelHandle) "panel" else "ring"
        SignLog.i("DRAG start ${dragging}")
    }

    fun onDrag(dxPx: Float, dyPx: Float) {
        if (dragging == "panel") movePanel(dxPx, dyPx) else onRingDrag(dxPx)
    }

    fun onDragEnd() {
        if (dragging == "panel") {
            // D21: stays where it was let go, and turns to face the user again
            head.pose()?.let { stationYaw = yawOf(V3(headPosOf(it).x - stationPos.x, 0f, headPosOf(it).z - stationPos.z)) + 180f }
            SignLog.i("PANEL dropped at ${fmt(stationPos)} yaw=${stationYaw.toInt()}")
        } else {
            onRingDragEnd()
        }
        dragging = null
    }

    /** Moves the panel in the plane facing the user: drag right = right, drag up = up (px scaled by distance). */
    fun movePanel(dxPx: Float, dyPx: Float) {
        val pose = head.pose() ?: return
        val hp = headPosOf(pose)
        val dist = (stationPos - hp).length.coerceAtLeast(0.3f)
        val k = PX_M * (0.67f + 1.186f * dist)
        val toPanel = V3(stationPos.x - hp.x, 0f, stationPos.z - hp.z).normalized()
        val right = toPanel.cross(V3.UP).normalized()
        stationPos = stationPos + right * (dxPx * k) + V3(0f, -dyPx * k, 0f)
    }

    /** "Bring here" (D21): panel straight ahead and a little to the non-dominant side, 15 degrees down, 0.9 m, facing you; then locked. */
    fun recall() {
        val pose = head.pose() ?: return
        placeStation(headPosOf(pose), pose.yaw, vm.state.value.settings.hand)
        SignLog.i("PANEL recalled to ${fmt(stationPos)} yaw=${stationYaw.toInt()}")
    }

    private fun placeStation(headPos: V3, headYaw: Float, handed: Handed) {
        val side = if (handed == Handed.RIGHT) STATION_SIDE_DEG else -STATION_SIDE_DEG
        val dir = dirOf(headYaw + side)
        stationPos = headPos + dir * (STATION_DIST * kotlin.math.cos(STATION_DOWN_DEG * PI.toFloat() / 180f)) +
            V3(0f, -STATION_DIST * sin(STATION_DOWN_DEG * PI.toFloat() / 180f), 0f)
        stationYaw = headYaw + side
        stationSet = true
    }

    private fun headPosOf(p: io.embodit.signhands.platform.HeadPose): V3 = p.position

    private var firstPoseMs = -1L
    private var stationAuto = true

    /**
     * v0.1.2 (headset: "打开什么都没有"): the panel is placed from the head only once the head pose is real, keeps being
     * re-placed in front of the head for the first STATION_SETTLE_MS, then locks (D21). Until the user has moved it, a
     * panel that ends up far away or out of view (> 70 degrees) is put back in front once.
     */
    private fun settleStation(headPos: V3, headYaw: Float, handed: Handed) {
        val now = SystemClock.elapsedRealtime()
        if (firstPoseMs < 0) firstPoseMs = now
        if (!stationSet || (stationAuto && now - firstPoseMs < STATION_SETTLE_MS)) {
            placeStation(headPos, headYaw, handed)
            return
        }
        if (stationAuto) {
            val d = stationPos - headPos
            val off = abs(wrap180(yawOf(V3(d.x, 0f, d.z)) - headYaw))
            if (d.length > 3f || d.length < 0.3f || off > 70f) {
                placeStation(headPos, headYaw, handed)
                SignLog.i("PANEL re-placed: was dist=${"%.2f".format(d.length)} off=${off.toInt()}")
            }
            stationAuto = false
        }
    }

    private val diagAt = longArrayOf(1000L, 3000L, 5000L)
    private var diagNext = 0

    /** v0.1.2 diagnostics: 1 / 3 / 5 s after attach, where the panel and the hands are relative to the head. */
    private fun diagnostics(now: Long, headPos: V3, headYaw: Float, s: UiState) {
        if (diagNext >= diagAt.size || now - attachMs < diagAt[diagNext]) return
        fun rel(p: V3): String {
            val d = p - headPos
            val dir = wrap180(yawOf(V3(d.x, 0f, d.z)) - headYaw)
            val elev = Math.toDegrees(kotlin.math.atan2(d.y.toDouble(), kotlin.math.sqrt((d.x * d.x + d.z * d.z).toDouble()))).toInt()
            return "${fmt(p)} dist=${"%.2f".format(d.length)} dir=${dir.toInt()} elev=$elev"
        }
        val lessonShown = slots[PanelIds.LESSON]?.shown
        val g = ghost[s.settings.hand]
        val w = watch[s.settings.hand]
        SignLog.i(
            "DIAG t=${diagAt[diagNext] / 1000}s state=${s.screen}/${s.variant} head=${fmt(headPos)} yaw=${headYaw.toInt()} support=${hands.support()} " +
                "| panel ${rel(stationPos)} shown=$lessonShown bound=${slots[PanelIds.LESSON]?.panel != null} composed=${panelShown[PanelIds.LESSON]} " +
                "| watch visible=${w?.visible} at ${w?.let { rel(watchCentre()) }} | ghost visible=${g?.visible} | models=${ghost.keys}/${watch.keys}"
        )
        diagNext++
    }

    private fun fmt(v: V3) = "(%.2f,%.2f,%.2f)".format(v.x, v.y, v.z)

    /** The demo hand stands to the panel's dominant side, a little closer to the user. */
    private fun watchCentre(): V3 {
        val handed = vm.state.value.settings.hand
        val toUser = dirOf(stationYaw + 180f)
        val right = dirOf(stationYaw - 90f) * (if (handed == Handed.RIGHT) 1f else -1f)
        return stationPos + right * WATCH_BESIDE + toUser * WATCH_FORWARD + V3(0f, WATCH_UP, 0f)
    }

    // ------------------------------------------------------------------------------------------ frame

    fun onFrame(deltaTime: Float) {
        if (!attached) return
        val dt = if (deltaTime.isFinite() && deltaTime > 0f) deltaTime.coerceAtMost(0.1f) else 0.016f
        frame++
        val now = SystemClock.elapsedRealtime()
        runWork(if (deltaTime.isFinite() && deltaTime > 0f) deltaTime else 1f, now)
        attachments?.let { if (slots.values.any { s -> s.panel == null }) bind(it) }
        val pose = head.pose() ?: return
        val s = vm.state.value
        val handed = s.settings.hand
        val headPos = pose.position
        breath = (0.5f + 0.5f * sin(now / 1000f * 2f * PI.toFloat() / 1.2f))

        // smoothed head heading (saccades over 25 degrees are ignored for a while)
        val hy = pose.yaw
        headYawSmooth = if (headYawSmooth.isNaN()) hy else headYawSmooth + wrap180(hy - headYawSmooth) * (1f - exp(-dt / YAW_TAU))
        val headPractice = PracticeFrame(headPos, headYawSmooth)

        // start-up: S00 ends once the models are in and the support state is known (3 s at most)
        val support = hands.support()
        if (!readySent && s.screen == Screen.S00 && modelsDone && work.isEmpty()) {
            val waited = now - attachMs > SUPPORT_WAIT_MS
            if (support != Support.WAIT || waited) {
                readySent = true
                vm.ready(if (support == Support.WAIT) Support.OFF else support)
            }
        }

        // the dominant hand
        val joints: List<V3>? =
            when {
                noHand -> null
                fake != null -> {
                    val spot = headPractice.toSpace(Layout.sided(Layout.TEACHING_SPOT, handed) + fake!!.offset)
                    val p = PracticeFrame(headPos, yawOf(V3(spot.x - headPos.x, 0f, spot.z - headPos.z)))
                    fake!!.joints(book, p, handed, frame)
                }
                else -> hands.joints(handed)
            }
        val practice =
            if (joints != null) {
                val w = joints[J.WRIST]
                PracticeFrame(headPos, yawOf(V3(w.x - headPos.x, 0f, w.z - headPos.z)))
            } else {
                headPractice
            }
        if (joints != null) lastSeenMs = now
        val visible = joints != null || (lastSeenMs > 0 && now - lastSeenMs < LOST_GRACE_MS)
        if (visible != reportedVisible) {
            reportedVisible = visible
            vm.handVisible(visible)
        }
        vm.support(support)
        val supportOk = support == Support.OK || support == Support.EMULATOR
        if (supportWasOk && !supportOk && s.screen != Screen.S00) vm.supportLost()
        supportWasOk = supportOk

        // hand length: median of the first 1.5 s of good frames (design followGhost.scale)
        val frameOfHand = joints?.let { HandFrame.of(it, handed) }
        if (joints != null && frameOfHand != null && !lengthLocked) {
            if (firstHandMs < 0) firstHandMs = now
            lengths += HandMath.handLength(HandMath.rightLocal(joints, frameOfHand, handed))
            if (now - firstHandMs > LENGTH_SAMPLE_MS && lengths.size >= 5) {
                val m = lengths.sorted()[lengths.size / 2]
                handLength = m.coerceIn(HandMath.DEFAULT_HAND_LENGTH * 0.85f, HandMath.DEFAULT_HAND_LENGTH * 1.15f)
                lengthLocked = true
                SignLog.i("HAND length median=${"%.3f".format(m)} used=${"%.3f".format(handLength)} n=${lengths.size}")
            }
        }
        val features = joints?.let { HandMath.features(it, handed, practice, handLength) }
        if (joints != null) {
            val w = joints[J.WRIST]
            lastWrist?.let { jitterSum += (w - it).length; jitterN++ }
            lastWrist = w
        } else {
            lastWrist = null
        }

        diagnostics(now, headPos, pose.yaw, s)
        judgeFrame(s, features, now)
        val letter = s.letter?.let { book.letters[it] }
        updateGhost(s, letter, joints, frameOfHand, practice, headPractice, handed, dt, now)
        updateWatch(s, letter, pose.yaw, headPos, handed, dt)
        updateMarkers(s, letter, joints, headPos)
        updatePanels(s, pose.yaw, headPos, joints, headPractice, handed, dt)

        if (now - lastTickMs >= TICK_MS) {
            vm.tick(if (lastTickMs == 0L) 0L else now - lastTickMs)
            lastTickMs = now
        }
    }

    // ----------------------------------------------------------------------------------------- judging

    private fun judgeFrame(s: UiState, f: Features?, now: Long) {
        val l = s.letter?.let { book.letters[it] }
        val active = l != null && l.scored && (s.screen == Screen.S06 || s.screen == Screen.S09 || s.screen == Screen.SPIKE)
        val key = if (active) "${s.screen}:${l!!.l}:${s.enteredMs}:${s.quiz?.index}:${s.quiz?.retryIndex}" else ""
        if (key != judgeKey) {
            autolog(now)
            judgeKey = key
            judge = if (active) Judge(l!!) else null
            matchSent = false
            hintHolder.reset()
            verdict = null
            featSum = null
            featN = 0
        }
        val j = judge ?: run {
            if (s.hint != null && s.screen != Screen.S10) vm.hint(null)
            return
        }
        if (f == null) return
        val v = j.frame(f)
        verdict = v
        val passed = j.push(now, v)
        accumulate(f)
        if (passed && !matchSent && s.screen != Screen.SPIKE) {
            matchSent = true
            SignLog.i("MATCH letter=${j.letter.l} share=${"%.2f".format(j.share())} ${f.line()}")
            autolog(now)
            vm.matched()
            return
        }
        val worst = v.worst
        val hintKey = hintHolder.offer(worst?.let { HintHolder.keyOf(it, j.letter) }, now)
        if (now - lastHintMs >= HINT_MS) {
            lastHintMs = now
            vm.hint(hintKey)
            if (s.screen == Screen.SPIKE) {
                spikeText.value = "${j.letter.l}  ${(j.share() * 100).toInt()}%\n" + v.items.joinToString("  ") { "${it.item.key}=${"%.2f".format(it.ratio)}" } + "\n" + f.line()
            }
        }
    }

    private fun accumulate(f: Features) {
        val a = featSum ?: FloatArray(10).also { featSum = it }
        for (i in 0 until 4) a[i] += f.flex[i]
        for (i in 0 until 3) a[4 + i] += if (f.spread[i].isNaN()) 0f else f.spread[i]
        a[7] += f.thumbTip.x
        a[8] += f.thumbTip.y
        a[9] += f.thumbTip.z
        featN++
    }

    /** design `preconditions.autoLog`: per judged letter, the window share and the mean features (judging templates). */
    private fun autolog(now: Long) {
        val j = judge ?: return
        val a = featSum ?: return
        if (featN == 0) return
        val m = a.map { it / featN }
        val jit = if (jitterN > 0) jitterSum / jitterN * 1000f else 0f
        SignLog.i(
            "AUTOLOG letter=${j.letter.l} matched=$matchSent share=${"%.2f".format(j.share())} frames=$featN flex=${m.subList(0, 4).joinToString(",") { it.toInt().toString() }} " +
                "spread=${m.subList(4, 7).joinToString(",") { it.toInt().toString() }} thumb=${m.subList(7, 10).joinToString(",") { "%.2f".format(it) }} wristStepMm=${"%.2f".format(jit)} len=${"%.3f".format(handLength)}"
        )
        featSum = null
        featN = 0
        jitterSum = 0f
        jitterN = 0
    }

    // --------------------------------------------------------------------------------- teacher's hand

    private fun updateGhost(
        s: UiState,
        letter: Letter?,
        joints: List<V3>?,
        frameOfHand: HandFrame?,
        practice: PracticeFrame,
        headPractice: PracticeFrame,
        handed: Handed,
        dt: Float,
        now: Long,
    ) {
        val other = if (handed == Handed.RIGHT) Handed.LEFT else Handed.RIGHT
        ghost[other]?.setVisible(false)
        val view = ghost[handed] ?: return
        val shown =
            when (s.screen) {
                Screen.S01 -> true
                Screen.S02, Screen.S06, Screen.S07, Screen.S08 -> letter != null
                Screen.S10 -> letter != null
                else -> false
            }
        view.setVisible(shown)
        if (!shown) return
        val l = if (s.screen == Screen.S01) book["OPEN"] else letter ?: return
        view.pose(l.clipStart + HOLD_AT)
        val onHand = joints != null && frameOfHand != null && s.screen != Screen.S01 && s.screen != Screen.S02
        val modelScale: Float
        var wrist: V3
        val rotation: Q4
        if (onHand) {
            wrist = wristFilter.filter(frameOfHand!!.wrist, dt)
            if (s.settings.ghost == GhostMode.SIDE) wrist += practice.toSpaceDir(Layout.sided(V3(Layout.SIDE_OFFSET, 0f, 0f), handed))
            rotation = frameOfHand.rotation
            modelScale = handLength / book.modelHandLength
        } else {
            wristFilter.reset()
            val p = headPractice
            wrist = p.toSpace(Layout.sided(Layout.TEACHING_SPOT, handed))
            rotation = p.rotation * HandMath.handedOrient(l.orient, handed)
            modelScale = HandMath.DEFAULT_HAND_LENGTH / book.modelHandLength
        }
        // J / Z in S08: the teacher's hand loops the move (no trail)
        if (s.screen == Screen.S08 && l.motion != null) {
            val cycle = (now % MOTION_LOOP_MS) / MOTION_LOOP_MS.toFloat()
            wrist += headPractice.toSpaceDir(Layout.sided(Motion.offset(l.motion, (cycle * 1.4f).coerceAtMost(1f)), handed))
        }
        view.place(wrist, rotation, modelScale)
        // tints and fades
        val sinceEnter = (now - s.enteredMs) / 1000f
        when (s.screen) {
            Screen.S06 -> {
                view.tint(verdict?.tints() ?: emptyMap(), 0f, breath)
                view.fade(1f)
            }
            Screen.S07 -> {
                view.tint(emptyMap(), if (sinceEnter < 0.4f) 1f else 0f, breath)
                view.fade(if (sinceEnter < 0.4f) 1f else (1f - (sinceEnter - 0.4f) / 0.6f).coerceIn(0f, 1f))
            }
            Screen.S10 -> {
                val miss = s.quiz?.missKey
                val finger = miss?.let { fingerOfKey(it) }
                view.tint(if (s.quiz?.reveal == Reveal.WRONG && finger != null) mapOf(finger to Tint.CORAL) else emptyMap(), 0f, breath)
                view.fade(1f)
            }
            Screen.S02 -> {
                view.tint(emptyMap(), 0f, breath)
                view.fade(0.45f + 0.55f * breath)
            }
            else -> {
                view.tint(emptyMap(), 0f, breath)
                view.fade(1f)
            }
        }
    }

    private fun fingerOfKey(key: String): Finger? {
        val p = key.split(':')
        return when (p[0]) {
            "finger" -> runCatching { Finger.valueOf(p[1].uppercase()) }.getOrNull()
            "thumb" -> Finger.THUMB
            else -> null
        }
    }

    // ------------------------------------------------------------------------------------ watch model

    private fun updateWatch(s: UiState, letter: Letter?, headYaw: Float, headPos: V3, handed: Handed, dt: Float) {
        val other = if (handed == Handed.RIGHT) Handed.LEFT else Handed.RIGHT
        watch[other]?.setVisible(false)
        val view = watch[handed]
        val demo = s.screen == Screen.S03
        val shownLetter = if (demo) book[DEMO_LETTERS[((SystemClock.elapsedRealtime() / DEMO_MS) % DEMO_LETTERS.size).toInt()]] else letter
        val shown = (s.screen == Screen.S05 || demo) && shownLetter != null && view != null
        if (!shown) {
            view?.setVisible(false)
            ring?.setVisible(false)
            ringHit?.setLocal(position = STOW)
            trail.forEach { it.setVisible(false) }
            watchKey = ""
            return
        }
        val l = shownLetter!!
        val key = "${l.l}:${s.enteredMs}:${s.course?.index}"
        if (key != watchKey || s.replay != lastReplay) {
            if (key != watchKey) {
                // v0.1.1 (D21): the demo hand stands at a fixed spot beside the panel (moves only with the panel)
                userTurn = 0f
                turn = defaultTurn(l.l)
            }
            watchKey = key
            lastReplay = s.replay
            watchT = 0f
        }
        view!!.setVisible(true)
        watchT += dt * if (s.slow) 0.5f else 1f
        val motionSec = when (l.motion) { "J" -> 1.2f; "Z" -> 1.5f; else -> 0f }
        val cycle = MORPH + motionSec + WATCH_HOLD + 0.3f
        if (watchT > cycle) watchT = 0f
        val u = (watchT / MORPH).coerceIn(0f, 1f)
        view.pose(l.clipStart + book.morphSec * u)
        // turn: default 3/4 angle, + drag, + "from the front" (180), eased
        val target = defaultTurn(l.l) + userTurn + if (s.opposite) 180f else 0f
        val step = 180f / 0.4f * dt
        val d = target - turn
        turn = if (abs(d) <= step) target else turn + if (d > 0) step else -step
        val frameP = PracticeFrame(watchCentre() - Q4.yaw(stationYaw).rotate(Layout.WATCH_SPOT), stationYaw)
        val orient = Q4.IDENTITY.slerp(l.orient, smoothstep(u))
        val spin = Q4.yaw(if (handed == Handed.RIGHT) turn else -turn)
        val rotation = frameP.rotation * spin * HandMath.handedOrient(orient, handed)
        val scale = Layout.WATCH_SCALE * HandMath.DEFAULT_HAND_LENGTH / book.modelHandLength
        val centre = frameP.toSpace(Layout.WATCH_SPOT)
        var wrist = centre - rotation.rotate(V3(0f, CENTRE_UP, 0f) * scale)
        // J / Z: the move after the morph, with a trail drawn behind the fingertip
        val m = if (motionSec > 0f) ((watchT - MORPH) / motionSec).coerceIn(0f, 1f) else 0f
        val showTrail = motionSec > 0f && watchT > MORPH
        val motionDir: (V3) -> V3 = { v -> frameP.rotation.rotate(spin.rotate(Layout.sided(v, handed))) * Layout.WATCH_SCALE }
        if (l.motion != null && watchT > MORPH) wrist += motionDir(Motion.offset(l.motion, m))
        view.place(wrist, rotation, scale)
        if (l.motion != null && trail.size == TRAIL_DOTS) {
            val tipIndex = if (l.motion == "J") J.tip(3) else J.tip(0)
            val tipLocal = l.joints.getValue("ok")[tipIndex].let { if (handed == Handed.LEFT) it.mirrorX() else it }
            val wrist0 = centre - rotation.rotate(V3(0f, CENTRE_UP, 0f) * scale)
            val tip0 = wrist0 + rotation.rotate(tipLocal * scale)
            for (i in 0 until TRAIL_DOTS) {
                val k = i / (TRAIL_DOTS - 1f)
                val on = showTrail && k <= m
                trail[i].setVisible(on)
                if (on) trail[i].setLocal(position = tip0 + motionDir(Motion.offset(l.motion, k)))
            }
        } else {
            trail.forEach { it.setVisible(false) }
        }
        // the turntable under the model
        val ringPos = centre + V3(0f, -RING_BELOW, 0f)
        ring?.setVisible(true)
        ring?.setLocal(position = ringPos)
        ringHit?.setLocal(position = ringPos)
    }

    /** design `watchModel.defaultAngle`: palm-forward letters with a hidden thumb start 35 degrees round. */
    private fun defaultTurn(l: String): Float = if (l in THREE_QUARTER) 35f else 0f

    // -------------------------------------------------------------------------------- hint markers

    private fun updateMarkers(s: UiState, letter: Letter?, joints: List<V3>?, headPos: V3) {
        val worst = if (s.screen == Screen.S06) verdict?.worst else null
        val hr = hintRing
        val ar = arrow
        if (worst == null || joints == null) {
            hr?.setVisible(false)
            ar?.setVisible(false)
            return
        }
        when (worst.item.kind) {
            ItemKind.PALM, ItemKind.POINT -> {
                hr?.setVisible(false)
                val w = joints[J.WRIST]
                ar?.setVisible(true)
                ar?.setLocal(position = w + V3(0f, 0.03f, 0f), rotation = faceHead(w, headPos), scale = V3(0.9f + 0.2f * breath, 0.9f + 0.2f * breath, 1f))
            }
            else -> {
                ar?.setVisible(false)
                val f = worst.item.finger ?: return
                val tip = if (f == Finger.THUMB) joints[J.THUMB_TIP] else joints[J.tip(f.ordinal - 1)]
                hr?.setVisible(true)
                val k = 1f + 0.25f * breath
                hr?.setLocal(position = tip, rotation = faceHead(tip, headPos) * Q4.pitch(90f), scale = V3(k, k, k))
            }
        }
    }

    // ------------------------------------------------------------------------------------------ panels

    private fun updatePanels(s: UiState, headYaw: Float, headPos: V3, joints: List<V3>?, practice: PracticeFrame, handed: Handed, dt: Float) {
        // lesson panel (D21): placed once in front of you, then fixed in the world; moves only by its bar or "Bring here"
        settleStation(headPos, headYaw, handed)
        val pRot = Q4.yaw(stationYaw)
        slots[PanelIds.LESSON]?.place(stationPos, pRot)
        panelHandle?.setLocal(position = stationPos + V3(0f, -PANEL_HALF_H - 0.03f, 0f), rotation = pRot)

        // hand label: beside the wrist (non-dominant side 0.12 m, up 0.08 m), moves only when 8 cm off
        val labelShown = s.screen in LABEL_SCREENS || s.wordDone != null
        val slot = slots[PanelIds.LABEL] ?: return
        if (!labelShown) {
            slot.hide()
            labelPos = null
            return
        }
        val wrist = joints?.get(J.WRIST) ?: practice.toSpace(Layout.sided(Layout.TEACHING_SPOT, handed))
        val anchor = wrist + practice.toSpaceDir(Layout.sided(V3(-Layout.LABEL_SIDE, Layout.LABEL_UP, 0f), handed))
        val cur = labelPos
        if (cur == null) {
            labelPos = anchor
            labelTo = anchor
            labelT = 1f
        } else if (labelT >= 1f && (anchor - cur).length > Layout.LABEL_MOVE_AT) {
            labelFrom = cur
            labelTo = anchor
            labelT = 0f
        }
        if (labelT < 1f) {
            labelT = (labelT + dt / LABEL_MOVE_SEC).coerceAtMost(1f)
            labelPos = labelFrom + (labelTo - labelFrom) * smoothstep(labelT)
        }
        val lp = labelPos!!
        slot.place(lp, faceHead(lp, headPos))
    }

    /** `dump`: one log line with the head, the hand and the panels. */
    fun dumpLine(): String {
        val sb = StringBuilder("DUMP")
        head.pose()?.let { sb.append(" head=(${"%.2f".format(it.position.x)},${"%.2f".format(it.position.y)},${"%.2f".format(it.position.z)}) yaw=${it.yaw.toInt()}") }
        sb.append(" station=${fmt(stationPos)} yaw=${stationYaw.toInt()}")
        sb.append(" handLen=${"%.3f".format(handLength)} visible=$reportedVisible fake=$fake models=${ghost.keys}/${watch.keys}")
        verdict?.let { v -> sb.append(" verdict pass=${v.pass} ").append(v.items.joinToString(" ") { "${it.item.key}=${"%.2f".format(it.ratio)}${if (it.inside) "" else "!"}" }) }
        for (slot in slots.values) sb.append(" | ").append(slot.id).append(" shown=").append(slot.shown).append(" bound=").append(slot.panel != null)
        return sb.toString()
    }

    companion object {
        const val LIGHT_LUX = 1600f
        const val LESSON_SCALE = 0.9f
        const val LABEL_SCALE = 0.5f
        const val HOLD_AT = 1.5f
        const val MORPH = 1.2f
        const val WATCH_HOLD = 1.5f
        const val CENTRE_UP = 0.09f
        const val RING_R = 0.13f
        const val RING_BELOW = 0.27f
        const val TRAIL_DOTS = 24
        const val DEG_PER_PX = 0.35f
        const val YAW_TAU = 0.8f
        const val LOST_GRACE_MS = 1000L
        const val LENGTH_SAMPLE_MS = 1500L
        const val SUPPORT_WAIT_MS = 3000L
        const val TICK_MS = 100L
        const val HINT_MS = 100L
        const val STATION_DIST = 0.9f
        const val STATION_SETTLE_MS = 2000L
        const val DEMO_MS = 4000L
        private val DEMO_LETTERS = listOf("A", "B", "C", "D", "L", "Y")
        const val STATION_DOWN_DEG = 15f
        const val STATION_SIDE_DEG = 14f
        const val PANEL_HALF_H = 0.2f
        const val HANDLE_W = 0.16f
        const val HANDLE_H = 0.016f
        const val PX_M = 0.0004f
        const val WATCH_BESIDE = 0.42f
        const val WATCH_FORWARD = 0.22f
        const val WATCH_UP = 0.06f
        const val LABEL_MOVE_SEC = 0.3f
        const val MOTION_LOOP_MS = 2400L
        const val SHOW_FRAMES = 2L
        const val FAST_FRAME_SEC = 0.04f
        const val SLOW_GAP_MS = 1500L
        const val PARK = 0.001f
        private val STOW = V3(0f, -40f, 0f)
        private val THREE_QUARTER = setOf("A", "B", "E", "M", "N", "S", "T")
        private val LABEL_SCREENS = setOf(Screen.S02, Screen.S06, Screen.S07, Screen.S08, Screen.S09, Screen.S10)
    }
}

/** J and Z paths of the fingertip (design `letters.table` motion), practice frame, right hand, metres. */
object Motion {
    fun offset(motion: String, t: Float): V3 {
        val u = t.coerceIn(0f, 1f)
        return when (motion) {
            "J" -> {
                // down 6 cm, then towards the non-dominant side and a little towards the body (4 cm)
                if (u < 0.55f) {
                    V3(0f, -0.06f * smoothstep(u / 0.55f), 0f)
                } else {
                    val k = smoothstep((u - 0.55f) / 0.45f)
                    V3(-0.04f * k, -0.06f + 0.015f * sin(k * PI.toFloat()), 0.015f * k)
                }
            }
            "Z" -> {
                // top left -> top right -> bottom left -> bottom right, as you see it
                val pts = listOf(V3(-0.03f, 0.03f, 0f), V3(0.03f, 0.03f, 0f), V3(-0.03f, -0.03f, 0f), V3(0.03f, -0.03f, 0f))
                val seg = (u * 3f).coerceAtMost(2.9999f)
                val i = seg.toInt()
                val k = seg - i
                val p = pts[i] + (pts[i + 1] - pts[i]) * k
                p - pts[0]
            }
            else -> V3.ZERO
        }
    }
}

/** One-Euro filter for the wrist (design `followGhost.position`: minCutoff 1.0 Hz, beta 0.02). */
class OneEuro(private val minCutoff: Float = 1.0f, private val beta: Float = 0.02f, private val dCutoff: Float = 1.0f) {
    private var x: V3? = null
    private var dx = V3.ZERO

    fun reset() {
        x = null
        dx = V3.ZERO
    }

    fun filter(v: V3, dt: Float): V3 {
        val prev = x ?: return v.also { x = it }
        val d = (v - prev) * (1f / dt.coerceAtLeast(1e-3f))
        val ad = alpha(dCutoff, dt)
        dx = dx + (d - dx) * ad
        val cutoff = minCutoff + beta * dx.length * 1000f
        val a = alpha(cutoff, dt)
        val out = prev + (v - prev) * a
        x = out
        return out
    }

    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt.coerceAtLeast(1e-3f))
    }
}

