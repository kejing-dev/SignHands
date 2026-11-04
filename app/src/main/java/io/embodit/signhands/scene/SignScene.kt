package io.embodit.signhands.scene

import android.os.SystemClock
import androidx.compose.runtime.mutableStateMapOf
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
import io.embodit.signhands.domain.LevelHintPlacement
import io.embodit.signhands.domain.MainWindowLayout
import io.embodit.signhands.domain.Letter
import io.embodit.signhands.domain.Motion
import io.embodit.signhands.domain.Performer
import io.embodit.signhands.domain.Placement
import io.embodit.signhands.domain.Sign
import io.embodit.signhands.domain.Target
import io.embodit.signhands.domain.PracticeFrame
import io.embodit.signhands.domain.Q4
import io.embodit.signhands.domain.Reveal
import io.embodit.signhands.domain.Screen
import io.embodit.signhands.domain.Support
import io.embodit.signhands.domain.Tint
import io.embodit.signhands.domain.UiState
import io.embodit.signhands.domain.V3
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
import io.embodit.signhands.ui.window.WindowCtl
import io.embodit.signhands.ui.window.WindowSpike
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * Ids of the Stage's attachment panels (declared once in SignScreen with fixed sizes): the hand label and the
 * "打开面板 / 平视前方" chip. v0.1.4: the lesson panel is the system main window, not a Stage panel.
 */
object PanelIds {
    const val LABEL = "label"
    const val OPEN = "open"
    val ALL = listOf(LABEL, OPEN)
}

/**
 * The 3D side of the app, driven once per frame by [SignFrameSystem] (never through Compose): reads the head and
 * the dominant hand, judges the letter (S06 / S09), poses and places the teacher's hand, the watch model and its
 * turntable, the hint ring / turn-palm arrow, the J / Z trail, the hand label and the window chip; steps the main
 * window's level gate (D9) and keeps the 3D content out of the window's way (D8).
 * The ViewModel only gets discrete events and a hint key at most 10 times a second.
 */
class SignScene(private val vm: SignViewModel, private val head: HeadTracker, private val hands: HandTracker, private val scope: CoroutineScope) {
    val panelShown = mutableStateMapOf<String, Boolean>()

    /** The debug page (SPIKE) reads this a few times a second. */
    val spikeText = WindowSpike.text

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
    /** v0.1.3 word demos: an outline of the signer's head and shoulders, so "at the chin" / "on the chest" reads. */
    private var headOutline: ModelEntity? = null
    private var shoulders: ModelEntity? = null
    private var lastLoosen = -1L

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

    // the main window's estimated pose (the station) and the Stage panels
    private var stationSet = false
    private var stationPos = V3.ZERO
    /** Heading the window faces away from (the user stood at stationYaw + 180 when it was opened). */
    private var stationYaw = 0f
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
            slots[id] = Slot(id, holder, if (id == PanelIds.OPEN) OPEN_SCALE else LABEL_SCALE).also { it.hide() }
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
        // v0.1.3: also called by settings > Exit before the Stage is disposed; the second call does nothing
        if (!attached && root == null) return
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
        stationSet = false
        windowEpoch = -1
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
        work += "body" to {
            val mat = unlit(shared, SignColors.Ring, BlendingMode.TRANSPARENT, opacity = 0.5f, twoSided = true, depthWrite = false)
            headOutline = ModelEntity(Meshes.ring(HEAD_R - 0.004f, HEAD_R, "headOutline", segments = 64), mat).also {
                root.addChild(it)
                it.setVisible(false)
            }
            shoulders = ModelEntity(Meshes.unitCube("shoulders"), mat).also {
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

    /**
     * v0.1.4: the station is where the main window is (estimated: the system opens it straight ahead of a level head,
     * [MainWindowLayout.estimate]); the demo hand stands beside it. Heading only, upright (D9).
     */
    private fun placeStation(headPos: V3, headYaw: Float) {
        val placed = MainWindowLayout.estimate(headPos, headYaw)
        stationPos = placed.position
        stationYaw = placed.yaw
        stationSet = true
    }

    private val window: Placement.Placed
        get() = Placement.Placed(stationPos, stationYaw)


    private var windowEpoch = -1

    /**
     * v0.1.2 lesson (headset: "打开什么都没有"): nothing is placed from the head until its pose is real. v0.1.4: until
     * the window first opens the station follows the head; every time the system (re)creates the window (first open,
     * "叫回面前", the chip after x) the station is re-estimated from the head at that moment, where the system put it.
     */
    private fun settleStation(headPos: V3, headYaw: Float) {
        val epoch = WindowCtl.createdEpoch
        if (epoch != windowEpoch) {
            windowEpoch = epoch
            placeStation(headPos, headYaw)
            if (epoch > 0) SignLog.i("WINDOW estimate epoch=$epoch at ${fmt(stationPos)} yaw=${stationYaw.toInt()} head=${fmt(headPos)} pitch=${head.pose()?.pitch?.toInt()}")
        } else if (epoch == 0) {
            placeStation(headPos, headYaw)
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
        val g = ghost[s.settings.hand]
        val w = watch[s.settings.hand]
        SignLog.i(
            "DIAG t=${diagAt[diagNext] / 1000}s state=${s.screen}/${s.variant} head=${fmt(headPos)} yaw=${headYaw.toInt()} support=${hands.support()} " +
                "| window ${WindowCtl.panelState} est ${rel(stationPos)} level=${WindowCtl.levelHint} " +
                "| watch visible=${w?.visible} at ${w?.let { rel(watchCentre()) }} | ghost visible=${g?.visible} | models=${ghost.keys}/${watch.keys}"
        )
        diagNext++
    }

    private fun fmt(v: V3) = "(%.2f,%.2f,%.2f)".format(v.x, v.y, v.z)

    /** v0.1.4 (D8): the demo hand stands beside the window on the dominant side, nearer than it, never in front of it. */
    private fun watchCentre(): V3 = MainWindowLayout.demoSpot(window, vm.state.value.settings.hand)

    /** The demo faces the eye it was placed for (it stands to the side now). */
    private fun demoYaw(): Float {
        val d = watchCentre() - MainWindowLayout.eyeOf(window)
        return yawOf(V3(d.x, 0f, d.z))
    }

    // ------------------------------------------------------------------------------------------ frame

    fun onFrame(deltaTime: Float) {
        if (!attached) return
        val dt = if (deltaTime.isFinite() && deltaTime > 0f) deltaTime.coerceAtMost(0.1f) else 0.016f
        frame++
        val now = SystemClock.elapsedRealtime()
        runWork(if (deltaTime.isFinite() && deltaTime > 0f) deltaTime else 1f, now)
        attachments?.let { if (slots.values.any { s -> s.panel == null }) bind(it) }
        // v0.1.4 (D9): the main window opens only while the head is level; null until the head pose is real
        WindowCtl.stepGate(now, head.pose()?.pitch)
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
                    val fk = fake!!
                    val target = fk.target(book)
                    if (target != null) {
                        // v0.1.3: `fakeHand sign=<id>` / `move=1` performs the move (path judging on the emulator)
                        Performer.joints(book, target, handed, headPos, headYawSmooth, now - fk.startMs, fk.sloppy())
                    } else {
                        val spot = headPractice.toSpace(Layout.sided(Layout.TEACHING_SPOT, handed) + fk.offset)
                        val p = PracticeFrame(headPos, yawOf(V3(spot.x - headPos.x, 0f, spot.z - headPos.z)))
                        fk.joints(book, p, handed, frame)
                    }
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
        judgeFrame(s, features, joints, headPractice, handed, now)
        val letter = s.letter?.let { book.letters[it] }
        updateGhost(s, letter ?: s.sign?.let { book.letters[it.shape] }, joints, frameOfHand, practice, headPractice, handed, dt, now)
        val sign = s.sign
        if (sign != null) updateSignWatch(s, sign, handed, dt) else updateWatch(s, letter, pose.yaw, headPos, handed, dt)
        updateMarkers(s, letter, joints, headPos)
        updatePanels(s, pose, headPos, joints, headPractice, handed, dt, now)

        if (now - lastTickMs >= TICK_MS) {
            vm.tick(if (lastTickMs == 0L) 0L else now - lastTickMs)
            lastTickMs = now
        }
    }

    // ----------------------------------------------------------------------------------------- judging

    private fun judgeFrame(s: UiState, f: Features?, joints: List<V3>?, headPractice: PracticeFrame, handed: Handed, now: Long) {
        val l = s.letter?.let { book.letters[it] }
        val sign = s.sign
        val target = sign?.let { Target.of(it, book) } ?: l?.takeIf { it.scored }?.let { Target.of(it) }
        val active = target != null && (s.screen == Screen.S06 || s.screen == Screen.S09 || s.screen == Screen.SPIKE)
        val key = if (active) "${s.screen}:${target!!.key}:${s.enteredMs}:${s.quiz?.index}:${s.quiz?.retryIndex}" else ""
        if (key != judgeKey) {
            autolog(now)
            judgeKey = key
            judge = if (active) Judge(target!!) else null
            matchSent = false
            hintHolder.reset()
            verdict = null
            featSum = null
            featN = 0
            lastLoosen = -1L
        }
        val j = judge ?: run {
            if (s.hint != null && s.screen != Screen.S10) vm.hint(null)
            return
        }
        if (f == null || joints == null) return
        val v = j.frame(f)
        verdict = v
        // moving targets (J, Z, word signs) follow a point of the hand in the head frame (right-hand terms)
        val point = if (j.target.moving) HandMath.trackPoint(joints, j.target.track, headPractice, handed) else null
        val passed = j.push(now, v, point)
        accumulate(f)
        if (passed && !matchSent && s.screen != Screen.SPIKE) {
            matchSent = true
            SignLog.i("MATCH target=${j.target.key} tier=${j.tier} share=${"%.2f".format(j.share())} move=${"%.2f".format(j.moveRatio)} ${f.line()}")
            autolog(now)
            vm.matched()
            return
        }
        if (j.loosenedAt != lastLoosen) {
            // D27: two almost-tries in a row loosened the target; the label says so for a moment
            lastLoosen = j.loosenedAt
            SignLog.i("LOOSEN target=${j.target.key} tier=${j.tier}")
            hintHolder.force("loosen", now)
        }
        val worst = v.worst
        val shapeKey = worst?.let { HintHolder.keyOf(it, j.letter) }
        val pathKey = j.moveHint?.takeIf { it != "shape" && v.pass }
        val hintKey = hintHolder.offer(pathKey ?: shapeKey, now)
        if (now - lastHintMs >= HINT_MS) {
            lastHintMs = now
            vm.hint(hintKey)
            if (s.screen == Screen.SPIKE) {
                spikeText.value = "${j.target.key}  ${(j.share() * 100).toInt()}% tier=${j.tier}" + (if (j.target.moving) " move=${"%.2f".format(j.moveRatio)} ${j.moveHint ?: ""}" else "") + "\n" +
                    v.items.joinToString("  ") { "${it.item.key}=${"%.2f".format(it.ratio)}" } + "\n" + f.line()
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
            "AUTOLOG target=${j.target.key} tier=${j.tier} matched=$matchSent share=${"%.2f".format(j.share())} frames=$featN flex=${m.subList(0, 4).joinToString(",") { it.toInt().toString() }} " +
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
            rotation = p.rotation * HandMath.handedOrient(s.sign?.orient ?: l.orient, handed)
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
        headOutline?.setVisible(false)
        shoulders?.setVisible(false)
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
                // v0.1.1 (D21): the demo hand stands at a fixed spot beside the window (moves when the window is re-opened)
                userTurn = 0f
                turn = defaultTurn(l.l)
            }
            watchKey = key
            lastReplay = s.replay
            watchT = 0f
        }
        view!!.setVisible(true)
        watchT += dt * if (s.slow) 0.5f else 1f
        val motionSec = Motion.seconds(l.motion)
        val cycle = MORPH + motionSec + WATCH_HOLD + 0.3f
        if (watchT > cycle) watchT = 0f
        val u = (watchT / MORPH).coerceIn(0f, 1f)
        view.pose(l.clipStart + book.morphSec * u)
        // turn: default 3/4 angle, + drag, + "from the front" (180), eased
        val target = defaultTurn(l.l) + userTurn + if (s.opposite) 180f else 0f
        val step = 180f / 0.4f * dt
        val d = target - turn
        turn = if (abs(d) <= step) target else turn + if (d > 0) step else -step
        val dy = demoYaw()
        val frameP = PracticeFrame(watchCentre() - Q4.yaw(dy).rotate(Layout.WATCH_SPOT), dy)
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

    /**
     * v0.1.3 word demo (S05 of a word sign): the big demo hand morphs into the sign's handshape, then moves its palm along
     * the sign's path round an outline of the signer's head and shoulders (so chin / forehead / chest read), with the
     * path drawn behind it. Two-handed signs show the other hand resting in its spot. Same turntable, slow and "from the
     * front" as the letters.
     */
    private fun updateSignWatch(s: UiState, sign: Sign, handed: Handed, dt: Float) {
        val other = if (handed == Handed.RIGHT) Handed.LEFT else Handed.RIGHT
        val view = watch[handed]
        val shape = book.letters[sign.shape]
        if (s.screen != Screen.S05 || view == null || shape == null) {
            view?.setVisible(false)
            watch[other]?.setVisible(false)
            headOutline?.setVisible(false)
            shoulders?.setVisible(false)
            ring?.setVisible(false)
            ringHit?.setLocal(position = STOW)
            trail.forEach { it.setVisible(false) }
            watchKey = ""
            return
        }
        val key = "${sign.id}:${s.enteredMs}:${s.course?.index}"
        if (key != watchKey || s.replay != lastReplay) {
            if (key != watchKey) {
                userTurn = 0f
                turn = SIGN_TURN
            }
            watchKey = key
            lastReplay = s.replay
            watchT = 0f
        }
        view.setVisible(true)
        watchT += dt * if (s.slow) 0.5f else 1f
        val moveSec = Performer.SIGN_MS / 1000f
        val cycle = MORPH + moveSec + WATCH_HOLD + 0.3f
        if (watchT > cycle) watchT = 0f
        val u = (watchT / MORPH).coerceIn(0f, 1f)
        view.pose(shape.clipStart + book.morphSec * u)
        val target = SIGN_TURN + userTurn + if (s.opposite) 180f else 0f
        val step = 180f / 0.4f * dt
        val d = target - turn
        turn = if (abs(d) <= step) target else turn + if (d > 0) step else -step
        val spin = Q4.yaw(if (handed == Handed.RIGHT) turn else -turn)
        val frameRot = Q4.yaw(demoYaw()) * spin
        val k = SIGN_SCALE
        // the signer's head: the demo's centre of interest (chest height) sits on the watch spot
        val origin = watchCentre() - frameRot.rotate(Layout.sided(SIGN_CENTRE, handed) * k)
        fun toSpace(p: V3): V3 = origin + frameRot.rotate(Layout.sided(p, handed) * k)
        val m = ((watchT - MORPH) / moveSec).coerceIn(0f, 1f)
        val palm = toSpace(sign.palmAt(m))
        val rotation = frameRot * HandMath.handedOrient(Q4.IDENTITY.slerp(sign.orient, smoothstep(u)), handed)
        val modelScale = k * HandMath.DEFAULT_HAND_LENGTH / book.modelHandLength
        val centre = shape.joints.getValue("ok")[J.prox(1)] * 0.5f
        view.place(palm - rotation.rotate((if (handed == Handed.LEFT) centre.mirrorX() else centre) * k), rotation, modelScale)
        // the other hand of a two-handed sign
        val base = sign.base
        val otherView = watch[other]
        val baseShape = base?.let { book.letters[it.shape] }
        if (base != null && otherView != null && baseShape != null) {
            otherView.setVisible(true)
            otherView.pose(baseShape.clipStart + HOLD_AT)
            val baseRot = frameRot * HandMath.handedOrient(base.orient, other)
            val bc = baseShape.joints.getValue("ok")[J.prox(1)] * 0.5f
            otherView.place(toSpace(base.at) - baseRot.rotate((if (other == Handed.LEFT) bc.mirrorX() else bc) * k), baseRot, modelScale)
        } else {
            otherView?.setVisible(false)
        }
        // head and shoulders outline
        headOutline?.let {
            it.setVisible(true)
            it.setLocal(position = origin, rotation = frameRot * Q4.pitch(90f), scale = V3(k, k, k))
        }
        shoulders?.let {
            it.setVisible(true)
            it.setLocal(position = origin + frameRot.rotate(V3(0f, -SHOULDER_DROP * k, 0.02f * k)), rotation = frameRot, scale = V3(SHOULDER_W * k, 0.008f, 0.008f))
        }
        // the path, drawn as the palm moves
        if (trail.size == TRAIL_DOTS) {
            for (i in 0 until TRAIL_DOTS) {
                val f = i / (TRAIL_DOTS - 1f)
                val on = watchT > MORPH && f <= m
                trail[i].setVisible(on)
                if (on) trail[i].setLocal(position = toSpace(sign.palmAt(f)))
            }
        }
        val ringPos = watchCentre() + V3(0f, -SIGN_RING_BELOW, 0f)
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

    private fun updatePanels(s: UiState, pose: io.embodit.signhands.platform.HeadPose, headPos: V3, joints: List<V3>?, practice: PracticeFrame, handed: Handed, dt: Float, now: Long) {
        settleStation(headPos, pose.yaw)
        placeChip(pose, headPos, now)

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
        // v0.1.4 (D8): never in front of or behind the window (pushed below it or out past its side)
        val lp = MainWindowLayout.clearOf(headPos, window, labelPos!!, LABEL_R, handed)
        slot.place(lp, faceHead(lp, headPos))
    }

    private var chipPos: V3? = null
    private var chipYaw = 0f
    private var chipWhat = ""

    /**
     * The Stage's chip: "平视前方" on the line of sight (pitch clamped, upright, re-posed every frame) while an open
     * waits for a level head; "打开面板" posed once in front of the head (upright) while the window is closed.
     */
    private fun placeChip(pose: io.embodit.signhands.platform.HeadPose, headPos: V3, now: Long) {
        val slot = slots[PanelIds.OPEN] ?: return
        val what =
            when {
                WindowCtl.levelHint -> "level"
                WindowCtl.chipWanted(now) && vm.state.value.screen != Screen.S00 -> "open"
                else -> ""
            }
        if (what != chipWhat) {
            chipWhat = what
            chipPos = null
            if (what.isNotEmpty()) SignLog.i("CHIP $what pitch=${pose.pitch.toInt()} window=${WindowCtl.panelState}")
        }
        when (what) {
            "level" -> {
                val h = LevelHintPlacement.place(headPos, pose.yaw, pose.pitch)
                slot.place(h.position, Q4.yaw(h.yaw))
            }
            "open" -> {
                val c =
                    chipPos ?: Placement.placeUpright(headPos, pose.yaw, vm.state.value.settings.hand).let {
                        chipPos = it.position
                        chipYaw = it.yaw
                        it.position
                    }
                slot.place(c, Q4.yaw(chipYaw))
            }
            else -> slot.hide()
        }
    }

    /** `dump`: one log line with the head, the hand and the panels. */
    fun dumpLine(): String {
        val sb = StringBuilder("DUMP")
        head.pose()?.let { sb.append(" head=(${"%.2f".format(it.position.x)},${"%.2f".format(it.position.y)},${"%.2f".format(it.position.z)}) yaw=${it.yaw.toInt()}") }
        sb.append(" station=${fmt(stationPos)} yaw=${stationYaw.toInt()}")
        sb.append(" handLen=${"%.3f".format(handLength)} visible=$reportedVisible fake=$fake models=${ghost.keys}/${watch.keys}")
        judge?.let { sb.append(" judge=${it.target.key} tier=${it.tier} move=${"%.2f".format(it.moveRatio)} hint=${it.moveHint}") }
        verdict?.let { v -> sb.append(" verdict pass=${v.pass} ").append(v.items.joinToString(" ") { "${it.item.key}=${"%.2f".format(it.ratio)}${if (it.inside) "" else "!"}" }) }
        for (slot in slots.values) sb.append(" | ").append(slot.id).append(" shown=").append(slot.shown).append(" bound=").append(slot.panel != null)
        return sb.toString()
    }

    companion object {
        const val LIGHT_LUX = 1600f
        const val LABEL_SCALE = 0.5f
        const val OPEN_SCALE = 1.0f
        /** The hand label as a ball for the window check: 260 x 88 dp x 0.5 on the Stage (0.8 mm / dp). */
        const val LABEL_R = 0.06f
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
        const val DEMO_MS = 4000L
        private val DEMO_LETTERS = listOf("A", "B", "C", "D", "L", "Y")
        const val LABEL_MOVE_SEC = 0.3f
        const val MOTION_LOOP_MS = 2400L
        /** Word demos: scale of hand and body, default turntable angle, the outline. */
        const val SIGN_SCALE = 1.3f
        const val SIGN_TURN = 40f
        const val HEAD_R = 0.085f
        const val SHOULDER_DROP = 0.24f
        const val SHOULDER_W = 0.36f
        const val SIGN_RING_BELOW = 0.42f
        /** The point of the signer's body (head frame) that sits on the watch spot. */
        private val SIGN_CENTRE = V3(0.02f, -0.14f, -0.18f)
        const val SHOW_FRAMES = 2L
        const val FAST_FRAME_SEC = 0.04f
        const val SLOW_GAP_MS = 1500L
        const val PARK = 0.001f
        private val STOW = V3(0f, -40f, 0f)
        private val THREE_QUARTER = setOf("A", "B", "E", "M", "N", "S", "T")
        private val LABEL_SCREENS = setOf(Screen.S02, Screen.S06, Screen.S07, Screen.S08, Screen.S09, Screen.S10)
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

