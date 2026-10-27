package io.embodit.dancesteps.ui.dance.scene

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import com.pico.spatial.ui.foundation.gesture.SpatialTapValue
import io.embodit.dancesteps.domain.model.DanceRules
import io.embodit.dancesteps.domain.model.Foot
import io.embodit.dancesteps.domain.model.Pattern
import io.embodit.dancesteps.domain.model.Quat4
import io.embodit.dancesteps.domain.model.SurfaceKind
import io.embodit.dancesteps.domain.model.Vec3
import io.embodit.dancesteps.domain.usecase.CenterPathUseCase
import io.embodit.dancesteps.domain.usecase.FloorPose
import io.embodit.dancesteps.domain.usecase.PanelFacing
import io.embodit.dancesteps.domain.usecase.PlaceCheck
import io.embodit.dancesteps.domain.usecase.PlaceFloorUseCase
import io.embodit.dancesteps.domain.usecase.TrailSample
import io.embodit.dancesteps.platform.DanceGraph
import io.embodit.dancesteps.platform.DanceLog
import io.embodit.dancesteps.platform.sense.PlaneTracker
import io.embodit.dancesteps.platform.tracking.HeadPose
import io.embodit.dancesteps.ui.dance.DanceEvent
import io.embodit.dancesteps.ui.dance.DanceUiState
import io.embodit.dancesteps.ui.dance.DebugWalk
import io.embodit.dancesteps.ui.dance.MapSnapshot
import io.embodit.dancesteps.ui.dance.Phase
import io.embodit.dancesteps.ui.dance.RunSamples
import io.embodit.dancesteps.ui.dance.debug.DanceProbe
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** The framework instantiates the system itself, so the running scene is reached through this holder. */
internal object ActiveScene {
    @Volatile var scene: DanceScene? = null
}

/** The one per-frame ECS system of the app. */
class DanceFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        ActiveScene.scene?.onFrame(context)
    }
}

/**
 * The 3D side of the app: the dance floor frame, the footprints, the route, your dot and trail, the
 * placement of the pre-built panels, and the beat timeline that lights the footprints from the audio
 * clock. Compose state is rendered through [render] (idempotent diff); per-frame work happens in
 * [onFrame] only and reaches Compose at most ten times a second ([map] snapshot, head samples) or on a
 * beat edge. Everything started in [attach] is stopped in [detach]. Main thread only.
 */
class DanceScene(private val sink: (DanceEvent) -> Unit) : DanceProbe.SceneProbe {

    /** Panel id -> the panel is in view: a pooled panel composes its content only then. */
    val panelShown = mutableStateMapOf<String, Boolean>()

    /** Share of its panel's width that a panel's content fills (snackbar capsule). */
    val panelStretch = mutableStateMapOf<String, Float>()

    /** Bumped to make `SpatialView.update` run again while panel entities are still missing. */
    val bindTick = mutableIntStateOf(0)

    /** What the mirror's map draws; replaced ten times a second (design `container.notes`). */
    val map = mutableStateOf(MapSnapshot())

    private val main = Handler(Looper.getMainLooper())
    private val planeTracker = DanceGraph.planes
    private val headTracker = DanceGraph.head
    private val controller = DanceGraph.controller
    private val beats = DanceGraph.beats
    private val placeRule = PlaceFloorUseCase()

    private var content: SpatialViewContent? = null
    private var stageRoot: Entity? = null
    private var materials: SceneMaterials? = null
    private var shared: SharedResources? = null
    private var colliders: PlaneColliders? = null
    private var rig: PanelRig? = null
    private var floorView: DanceFloorView? = null
    private var patternView: PatternView? = null
    private var youView: YouView? = null
    private var attached = false

    private val mirrorFollower = Follower("mirror", Follower.MIRROR_OFFSET, MIRROR_HALF_EXTENTS)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var buildJob: Job? = null

    private var ui = DanceUiState()
    private var head = HeadPose(Vec3(0f, 1.6f, 0f), Quat4.IDENTITY)
    private var hasHead = false
    private var lastFrameMs = 0L
    private var lastHeadSentMs = 0L
    private var lastMapMs = 0L
    private var lastPlaneCount = -1
    private var lastFloor = false
    private var lastPlanesEmitMs = 0L
    private var lastLost = false
    private var lastBlock: Any? = null
    private var panelPressed = false
    private var lastPanelPressMs = NEVER
    private var dialogPlaced = false
    private var fpsOn = false
    private var frames = 0
    private var fpsStartMs = 0L

    // placing: the floor follows the head; the thumbstick turns it; walls snap it
    private var placing: FloorPose? = null
    private var placingCenter: Vec3? = null
    private var placingYaw = 0f
    private var stickYaw = 0f
    private var snapped = false
    private var lastPlacingSentMs = 0L
    private var lastCheck: PlaceCheck = PlaceCheck.CLEAR

    // the run
    private var fixedMirror: FloorPose? = null
    private var mirrorPos = Vec3.ZERO
    private var mirrorRot = Quat4.IDENTITY
    private var centerPath: CenterPathUseCase? = null
    private var pathFor: Pattern? = null
    private var runKey = ""
    private var lastEdge = ""
    private var lastStep = -2
    private var finished = false
    private var previewDone = false
    private val samples = ArrayList<TrailSample>()
    private val runPoints = ArrayList<Vec3>()
    private var loopSampleStart = 0
    private var loopPointStart = 0
    private var loopPointStartLive = 0
    private var lastLoop = -1
    private var lastSampleMs = 0L
    private var lastSampleBeat = -1f
    private var postureSum = Vec3.ZERO
    private var postureN = 0
    private var posture = 0f to 0f
    private var walkXStartMs = NEVER
    private var debugNoise = 12345

    /** The mirror panel's pointer: a press holds the lazy follow still. */
    fun onPanelPointer(pressed: Boolean) {
        panelPressed = pressed
        lastPanelPressMs = SystemClock.elapsedRealtime()
    }

    // =========================================================================================
    // lifecycle
    // =========================================================================================

    fun attach(content: SpatialViewContent, attachments: SpatialViewAttachments) {
        if (attached || stageRoot != null) detach()
        val t0 = SystemClock.elapsedRealtime()
        this.content = content
        val root = Entity().named("stageRoot")
        content.addEntity(root)
        // An entity added to the SpatialView is not at the Stage origin. Move the root once so that its
        // local space equals Stage space (Spatial Ruler finding).
        val offset = root.convertPositionFrom(Vector3.ZERO, null)
        val rotation = root.convertRotationFrom(Quat.identity(), null)
        root.components[TransformComponent::class.java]?.apply {
            setPosition(offset)
            setQuaternion(rotation)
        }
        stageRoot = root
        DanceLog.i("STAGE root offset=${DanceLog.pos(offset.domain())}")

        val rig = PanelRig(root) { id, shown -> panelShown[id] = shown }
        this.rig = rig
        for (id in PanelSpec.pool) {
            val (w, h) = PanelSpec.size(id)
            rig.world(id, w, h)
        }
        startTrackers()
        beats.start()
        ActiveScene.scene = this
        bindPanels(attachments)
        mirrorFollower.reset()
        buildJob =
            scope.launch {
                try {
                    build(root, t0)
                } catch (t: Throwable) {
                    if (t !is CancellationException) DanceLog.e("SCENE build failed: $t", t)
                    throw t
                }
            }
    }

    private fun startTrackers() {
        headTracker.start()
        controller.start()
        planeTracker.start(
            PlaneTracker.Listener { id, surface, _ ->
                if (surface != null) colliders?.upsert(surface) else colliders?.remove(id)
            }
        )
    }

    /** After the "unavailable" dialog's retry: the trackers start again. */
    fun restartTracking() {
        planeTracker.stop()
        headTracker.stop()
        controller.stop()
        lastBlock = null
        lastLost = false
        lastPlaneCount = -1
        startTrackers()
        colliders?.let { c -> planeTracker.all.forEach(c::upsert) }
        if (attached) sink(DanceEvent.SceneReady)
    }

    private suspend fun build(root: Entity, t0: Long) {
        val sh = SharedResources()
        shared = sh
        val m = SceneMaterials(sh)
        materials = m
        yield()
        colliders =
            PlaneColliders(root).also {
                it.buildBackstop()
                planeTracker.all.forEach(it::upsert)
            }
        yield()
        // every mesh is built here, paced (the idle emulator takes about a second per mesh on the main thread)
        val cube = pacedMesh { sh.keep(MeshFactory.unitCube("cube")) }
        val band = pacedMesh { sh.keep(MeshFactory.unitBand("band")) }
        floorView = DanceFloorView(root, m, sh).also { it.prepare(cube) }
        yield()
        patternView = PatternView(root, m, sh).also { it.prepare(band) }
        yield()
        youView = YouView(root, m, sh).also { it.prepare(band) }
        attached = true
        DanceLog.i("SCENE attached build_ms=${SystemClock.elapsedRealtime() - t0}")
        render(ui, force = true)
        sink(DanceEvent.SceneReady)
    }

    /** Called from `SpatialView(update = ...)`: parents freshly composed panel entities under their holders. */
    fun bindPanels(attachments: SpatialViewAttachments) {
        val rig = rig ?: return
        val complete = rig.bind(attachments, PanelSpec.pool, SystemClock.elapsedRealtime())
        if (!complete) {
            main.removeCallbacks(rebind)
            main.postDelayed(rebind, REBIND_DELAY_MS)
        }
    }

    private val rebind = Runnable { if (stageRoot != null) bindTick.intValue++ }

    fun detach() {
        if (!attached && stageRoot == null) return
        attached = false
        buildJob?.cancel()
        buildJob = null
        if (ActiveScene.scene === this) ActiveScene.scene = null
        main.removeCallbacksAndMessages(null)
        beats.stop()
        planeTracker.stop()
        headTracker.stop()
        controller.stop()
        panelShown.clear()
        panelStretch.clear()
        runCatching { rig?.release() }
        floorView?.destroy()
        patternView?.destroy()
        youView?.destroy()
        colliders?.clear()
        runCatching { stageRoot?.destroy(true) }
        shared?.close()
        shared = null
        stageRoot = null
        content = null
        materials = null
        colliders = null
        rig = null
        floorView = null
        patternView = null
        youView = null
        hasHead = false
        lastPlaneCount = -1
        lastLost = false
        dialogPlaced = false
        fixedMirror = null
        runKey = ""
        DanceLog.i("SCENE detached")
    }

    /** Background: the stream is paused and flushed; the ViewModel has already turned a run into a pause. */
    fun onBackground() {
        beats.stop()
        runKey = ""
    }

    fun onForeground() {
        if (stageRoot != null) beats.start()
    }

    // =========================================================================================
    // model -> entities (called when the UI state changes)
    // =========================================================================================

    fun render(state: DanceUiState, force: Boolean = false) {
        val previous = ui
        ui = state
        if (!attached) return
        val now = SystemClock.elapsedRealtime()
        val floorView = floorView ?: return
        val patternView = patternView ?: return
        val youView = youView ?: return
        val danced = state.danced
        if (pathFor !== danced) {
            pathFor = danced
            centerPath = CenterPathUseCase(danced)
        }
        patternView.setPattern(danced)

        // the floor frame
        val placedFloor = state.floor
        if (state.phase == Phase.PLACING) {
            if (previous.phase != Phase.PLACING || force) {
                placing = null
                placingCenter = null
                stickYaw = 0f
                snapped = false
                fixedMirror = null
            }
            floorView.setStyle(placed = false, alert = state.cramped?.side)
            floorView.setShown(hasHead)
        } else if (placedFloor != null) {
            floorView.setPose(placedFloor, state.floorY)
            floorView.setStyle(placed = true, alert = null)
            floorView.setShown(true)
            patternView.setPose(placedFloor, state.floorY)
            if (fixedMirror !== placedFloor) {
                fixedMirror = placedFloor
                mirrorPos = placedFloor.toStage(0f, DanceRules.MIRROR_AHEAD_M).let { Vec3(it.x, state.headYAtPlace - DanceRules.MIRROR_BELOW_HEAD_M, it.z) }
                mirrorRot = PanelFacing(placedFloor.yaw, Math.toRadians(DanceRules.MIRROR_LEAN_DEG.toDouble()).toFloat()).rotation
                DanceLog.i("MIRROR fixed pos=${DanceLog.pos(mirrorPos)} yaw=${String.format(Locale.US, "%.1f", Math.toDegrees(placedFloor.yaw.toDouble()))}")
            }
        } else {
            floorView.setShown(false)
            fixedMirror = null
        }

        // the pattern on the floor
        val patternShown = placedFloor != null && state.phase != Phase.PICKER && state.phase != Phase.PLACING && state.phase != Phase.BLOCKED
        patternView.setShown(patternShown || state.phase == Phase.PLACING)
        when (state.phase) {
            Phase.PLACING -> {
                patternView.setFeetShown(true)
                patternView.setAllLevel(FootLevel.OUTLINE)
                patternView.setRouteShown(false)
                patternView.setStance(false)
                patternView.hidePulses()
                patternView.hideCues()
            }
            Phase.READY -> {
                patternView.setFeetShown(true)
                if (!state.preview) patternView.setAllLevel(FootLevel.OUTLINE)
                patternView.setRouteShown(true)
                patternView.setStance(true)
                if (!state.preview) {
                    patternView.hidePulses()
                    patternView.hideCues()
                }
            }
            Phase.COUNT_IN -> {
                patternView.setFeetShown(true)
                patternView.setAllLevel(FootLevel.OUTLINE)
                patternView.setRouteShown(true)
                patternView.setStance(false)
            }
            Phase.DANCING -> {
                patternView.setFeetShown(true)
                patternView.setRouteShown(true)
                patternView.setStance(false)
            }
            Phase.PAUSED -> {
                patternView.setFeetShown(true)
                patternView.setRouteShown(true)
                patternView.setStance(true)
                patternView.hidePulses()
                patternView.hideCues()
                // the current footprint goes dim: every spot back to its outline except the done ones
                patternView.setAllLevel(FootLevel.DONE)
            }
            Phase.SUMMARY -> {
                patternView.setFeetShown(false)
                patternView.setRouteShown(true)
                patternView.setStance(false)
                patternView.hidePulses()
                patternView.hideCues()
            }
            else -> {
                patternView.setRouteShown(false)
                patternView.setStance(false)
                patternView.hidePulses()
                patternView.hideCues()
            }
        }

        // the run and its audio
        beats.stepSound = state.settings.stepSound
        val key = if (state.running) "run:${state.run?.serial}:${state.run?.startLoop}:${state.pattern.id}:${state.role}:${state.speed}" else if (state.preview) "preview:${state.pattern.id}:${state.role}" else ""
        if (key != runKey) {
            val wasRun = runKey.startsWith("run:")
            runKey = key
            if (wasRun && (state.phase == Phase.PAUSED)) pauseRun(previous)
            if (key.startsWith("run:")) startRun(state, previous)
            else if (key.startsWith("preview:")) startPreview(state)
            else beats.silence()
        }
        if (state.phase == Phase.SUMMARY && previous.phase != Phase.SUMMARY) {
            youView.showWhole(runPoints)
        }
        if (state.phase == Phase.READY && previous.phase != Phase.READY && previous.phase != Phase.PLACING) {
            // again / faster / restart: a clean floor
            youView.clearTrail()
            runPoints.clear()
            samples.clear()
        }
        if (state.phase == Phase.PICKER || state.phase == Phase.PLACING) {
            if (previous.phase != state.phase) {
                youView.clearTrail()
                runPoints.clear()
                samples.clear()
            }
        }
        youView.setDotShown(placedFloor != null && state.phase != Phase.PICKER && state.phase != Phase.BLOCKED)
        if (state.debugWalkX && !previous.debugWalkX) walkXStartMs = now
        if (!state.debugWalkX) walkXStartMs = NEVER
        syncPanels(state)
    }

    private fun startRun(state: DanceUiState, previous: DanceUiState) {
        val run = state.run ?: return
        val p = state.danced
        val fresh = run.startLoop == 0 && previous.phase != Phase.PAUSED
        if (fresh) {
            samples.clear()
            runPoints.clear()
            youView?.clearTrail()
            posture = 0f to 0f
        }
        postureSum = Vec3.ZERO
        postureN = 0
        loopSampleStart = samples.size
        loopPointStart = runPoints.size
        loopPointStartLive = youView?.pointCount ?: 0
        lastLoop = -1
        lastEdge = ""
        lastStep = -2
        lastSampleBeat = -1f
        finished = false
        beats.arm(p.music, state.bpm, run.countIn, p.beatsPerBar, p.loopBeats, DanceRules.LOOPS - run.startLoop, stepBeats = p.stepBeats())
    }

    private fun startPreview(state: DanceUiState) {
        val p = state.danced
        previewDone = false
        lastEdge = ""
        lastStep = -2
        beats.arm(p.music, DanceRules.tempoBpm(state.pattern, io.embodit.dancesteps.domain.model.Speed.SLOW), 0, p.beatsPerBar, p.loopBeats, 1, metronomeOnly = true, stepBeats = p.stepBeats())
    }

    private fun Pattern.stepBeats(): FloatArray = steps.map { it.beat }.toFloatArray()

    /** A run pauses: the interrupted loop's samples and trail are dropped (design `rules.dance`). */
    private fun pauseRun(previous: DanceUiState) {
        beats.silence()
        while (samples.size > loopSampleStart) samples.removeAt(samples.size - 1)
        while (runPoints.size > loopPointStart) runPoints.removeAt(runPoints.size - 1)
        youView?.truncate(loopPointStartLive)
        val loopsDone = previous.run?.loop ?: 0
        sink(DanceEvent.RunPaused(RunSamples(samples.toList(), posture, loopsDone)))
    }

    private fun syncPanels(state: DanceUiState) {
        val rig = rig ?: return
        if (!state.menuOpen) rig[PanelSpec.MENU]?.hide()
        if (!state.coach) rig[PanelSpec.COACH]?.hide()
        if (state.snack == null) rig[PanelSpec.SNACK]?.hide()
        val dialog = rig[PanelSpec.DIALOG]
        if (state.blocked != null) {
            if (!dialogPlaced) {
                val flat = Vec3(head.forward.x, 0f, head.forward.z).let { if (it.length < 0.1f) Vec3.AHEAD else it.normalized() }
                // taste D8: never behind the mirror; when the mirror stands nearer ahead, the dialog comes in front of it
                val mirrorAhead = (mirrorPos - head.position).dot(flat)
                val distance =
                    if (mirrorAhead > 0f && mirrorAhead < PanelSpec.DIALOG_DISTANCE_M + DIALOG_CLEAR_M) (mirrorAhead - DIALOG_CLEAR_M).coerceAtLeast(DIALOG_MIN_M)
                    else PanelSpec.DIALOG_DISTANCE_M
                dialog?.show(head.position + flat * distance, PanelSpec.DIALOG_SCALE * distance / PanelSpec.DIALOG_DISTANCE_M)
                dialogPlaced = true
            }
        } else {
            dialog?.hide()
            dialogPlaced = false
        }
    }

    /** Width of the snackbar's capsule, dp; called by the panel. */
    fun reportSnack(widthDp: Float) {
        val slot = rig?.get(PanelSpec.SNACK) ?: return
        if (widthDp <= 0f) return
        slot.widthFactor = widthDp / slot.widthDp
        if (panelStretch[PanelSpec.SNACK] != slot.widthFactor) panelStretch[PanelSpec.SNACK] = slot.widthFactor
    }

    // =========================================================================================
    // per frame
    // =========================================================================================

    fun onFrame(context: SceneUpdateContext) {
        val root = stageRoot ?: return
        if (root.scene == null || context.scene != root.scene) return
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastFrameMs == 0L) 0.016f else ((now - lastFrameMs) / 1000f).coerceIn(0.001f, 0.1f)
        lastFrameMs = now
        headTracker.pose()?.let {
            head = it
            hasHead = true
        }
        val rig = rig ?: return
        rig.onFrame(now)
        rig.stowBehind(head.view)
        val state = ui
        countFrame(now)
        if (!attached) return
        pollTracking(now)
        pollPlanes(now)

        // the mirror: lazy follow until the floor is placed, fixed afterwards
        val hold = panelPressed || (lastPanelPressMs != NEVER && now - lastPanelPressMs < PANEL_HOLD_MS) || state.menuOpen || state.blocked != null
        if (state.floor == null) {
            if (hasHead && head.position.length > 1e-3f) mirrorFollower.follow(head, planeTracker.all, planeTracker.count, now, hold)
            mirrorPos = mirrorFollower.pose.position
            mirrorRot = mirrorFollower.rotation
        }
        if (hasHead) rig[PanelSpec.MIRROR]?.place(mirrorPos, mirrorRot, PanelSpec.MIRROR_SCALE)
        placeSatellites(state, rig)

        // the floor under the head while placing
        if (state.phase == Phase.PLACING && hasHead) followFloor(state, now, dt)

        // you
        val floor = state.floor
        var foot: Vec3? = null
        var local: Pair<Float, Float>? = null
        if (hasHead) {
            foot = Vec3(head.position.x, state.floorY, head.position.z)
            if (floor != null) {
                var l = floor.toLocal(foot)
                if (walkXStartMs != NEVER) l = (l.first + 0.5f * ((now - walkXStartMs) / 2000f).coerceIn(0f, 1f)) to l.second
                local = l
            }
        }

        // the beat timeline
        var ghostL: Pair<Float, Float>? = null
        var ghostR: Pair<Float, Float>? = null
        var currentStep: Int? = null
        var nextStep: Int? = null
        var beatInLoop = -1f
        if ((state.running || state.preview) && floor != null) {
            val tl = timeline(state, floor, now, local)
            if (tl != null) {
                ghostL = tl.ghostL
                ghostR = tl.ghostR
                currentStep = tl.current
                nextStep = tl.next
                beatInLoop = tl.beat
                if (tl.synthetic != null) {
                    local = tl.synthetic
                    foot = floor.toStage(tl.synthetic.first, tl.synthetic.second, 0f).let { Vec3(it.x, state.floorY, it.z) }
                }
            }
        }
        patternView?.onFrame(now, if (state.notOnStart) 2.5f else 1f)

        if (foot != null) {
            youView?.place(foot, dt)
            if (state.phase == Phase.DANCING && floor != null && now - lastSampleMs >= DanceRules.TRAIL_SAMPLE_MS && beatInLoop >= 0f) {
                lastSampleMs = now
                val smoothed = youView?.position ?: foot
                youView?.addPoint(smoothed, now)
                runPoints += smoothed
                val (cx, cz) = patternView?.centerOffset() ?: (0f to 0f)
                val l = local ?: floor.toLocal(smoothed)
                if (state.debugWalk != null && patternView != null) {
                    // debug walkers: fill the samples the idle emulator's 1 fps would miss, 100 ms of beat time apart
                    val p = state.danced
                    val stepBeats = DanceRules.TRAIL_SAMPLE_MS / 1000f * state.bpm / 60f
                    var b = if (lastSampleBeat < 0f) beatInLoop else lastSampleBeat + stepBeats
                    var guard = 0
                    while (b <= beatInLoop + (if (beatInLoop < lastSampleBeat) p.loopBeats else 0) + 1e-4f && guard++ < 200) {
                        val bb = b % p.loopBeats
                        val sp = synthetic(state, p, patternView!!, bb) ?: break
                        samples += TrailSample(bb, sp.first + cx, sp.second + cz)
                        b += stepBeats
                    }
                    lastSampleBeat = beatInLoop
                } else {
                    samples += TrailSample(beatInLoop, l.first + cx, l.second + cz)
                }
            }
        }

        if (hasHead && now - lastHeadSentMs >= HEAD_SAMPLE_MS) {
            lastHeadSentMs = now
            val yaw = FloorPose.yawOf(head.forward, placingYaw)
            sink(DanceEvent.HeadSample(local, head.position.x, head.position.z, head.position.y, yaw))
        }
        if (now - lastMapMs >= MAP_MS) {
            lastMapMs = now
            val (cx, cz) = patternView?.centerOffset() ?: (0f to 0f)
            val trail =
                if (state.phase == Phase.SUMMARY) runPoints.map { floor?.toLocal(it) ?: (0f to 0f) }.let { downsample(it, 200) }
                else if (state.phase == Phase.DANCING || state.phase == Phase.PAUSED) runPoints.takeLast(DanceRules.TRAIL_LIVE_SEGMENTS).map { floor?.toLocal(it) ?: (0f to 0f) }
                else emptyList()
            map.value =
                MapSnapshot(
                    you = local?.let { (it.first + cx) to (it.second + cz) },
                    ghostL = ghostL,
                    ghostR = ghostR,
                    currentStep = currentStep,
                    nextStep = nextStep,
                    beatInLoop = beatInLoop,
                    trail = trail.map { (it.first + cx) to (it.second + cz) },
                    weight = if (nextStep != null) centerPath?.at(beatInLoop.coerceAtLeast(0f)) else null,
                )
        }
        if (state.floor == null) rig.faceViewer(head.position)
    }

    private fun downsample(pts: List<Pair<Float, Float>>, max: Int): List<Pair<Float, Float>> {
        if (pts.size <= max) return pts
        val step = pts.size.toFloat() / max
        val out = ArrayList<Pair<Float, Float>>(max + 1)
        var t = 0f
        while (t < pts.size) {
            out += pts[t.toInt()]
            t += step
        }
        if (out.last() !== pts.last()) out += pts.last()
        return out
    }

    /** Menu, snackbar and coachmark hang on the mirror; they follow it every frame. */
    private fun placeSatellites(state: DanceUiState, rig: PanelRig) {
        if (!hasHead) return
        val k = PanelSpec.MIRROR_SCALE
        val (hw, hh) = PanelSpec.halfM(PanelSpec.MIRROR, k)
        val right = mirrorRot.rotate(Vec3.RIGHT)
        val up = mirrorRot.rotate(Vec3.UP)
        val out = mirrorRot.rotate(Vec3(0f, 0f, 1f))
        if (state.snack != null) {
            val (_, sh) = PanelSpec.halfM(PanelSpec.SNACK, k)
            rig[PanelSpec.SNACK]?.place(mirrorPos + up * (hh + SNACK_GAP_M + sh), mirrorRot, k)
        }
        if (state.menuOpen) {
            val (mw, mh) = PanelSpec.halfM(PanelSpec.MENU, k)
            val keyDrop = (16f + 48f + 8f) * PanelRig.DP_M * k
            rig[PanelSpec.MENU]?.place(mirrorPos + right * (hw - mw - 16f * PanelRig.DP_M * k) + up * (hh - keyDrop - mh) + out * 0.012f, mirrorRot, k)
        }
        if (state.coach) {
            val (cw, ch) = PanelSpec.halfM(PanelSpec.COACH, k)
            rig[PanelSpec.COACH]?.place(mirrorPos - right * (hw + COACH_GAP_M + cw) + up * (hh - 90f * PanelRig.DP_M * k - ch), mirrorRot, k)
        }
    }

    /**
     * Placing (design `rules.placeFloor` and taste D1): the floor centre follows the head's floor point
     * and its front the head's heading (0.3 s smoothing); the thumbstick turns it about the vertical;
     * near a wall the heading snaps parallel to it. Frozen while tracking is lost.
     */
    private fun followFloor(state: DanceUiState, now: Long, dt: Float) {
        if (state.lost) return
        val target = Vec3(head.position.x, state.floorY, head.position.z)
        val k = (dt / 0.3f).coerceIn(0f, 1f)
        val c = placingCenter
        val center = if (c == null) target else c + (target - c) * k
        placingCenter = center
        val headYaw = FloorPose.yawOf(head.forward, placingYaw)
        var dYaw = headYaw - placingYaw
        while (dYaw > PI.toFloat()) dYaw -= (2 * PI).toFloat()
        while (dYaw < -PI.toFloat()) dYaw += (2 * PI).toFloat()
        placingYaw += dYaw * k
        // thumbstick: left / right turns the floor about its own vertical axis
        val sample = controller.sample()
        var stick = sample?.stickX ?: 0f
        if (abs(stick) < STICK_DEAD_ZONE) stick = 0f else stick = (abs(stick) - STICK_DEAD_ZONE) / (1f - STICK_DEAD_ZONE) * kotlin.math.sign(stick)
        if (stick != 0f) stickYaw -= stick * STICK_RATE_RAD_S * dt
        var yaw = placingYaw + stickYaw
        // snap parallel to a nearby wall when the stick rests
        var snapNow = false
        if (stick == 0f) {
            val wall = nearestWallYaw(center)
            if (wall != null) {
                var d = yaw - wall
                while (d > (PI / 4).toFloat()) d -= (PI / 2).toFloat()
                while (d < -(PI / 4).toFloat()) d += (PI / 2).toFloat()
                if (abs(d) < SNAP_RAD) {
                    yaw -= d
                    snapNow = true
                }
            }
        }
        if (snapNow != snapped) {
            snapped = snapNow
            DanceLog.i("SNAP ${if (snapNow) "on" else "off"} yaw=${String.format(Locale.US, "%.1f", Math.toDegrees(yaw.toDouble()))}")
        }
        val pose = FloorPose(center, yaw)
        placing = pose
        floorView?.setPose(pose, state.floorY)
        patternView?.setPose(pose, state.floorY)
        if (now - lastPlacingSentMs >= PLACING_EMIT_MS) {
            lastPlacingSentMs = now
            val check = if (state.hasFloor) placeRule.check(pose, planeTracker.all, state.floorY) else PlaceCheck.CLEAR
            if (check != lastCheck) {
                lastCheck = check
                DanceLog.i("PLACE check cramped=${check.cramped} front=${check.frontTooCloseCm}")
            }
            sink(DanceEvent.FloorFollow(pose, head.position.y, check))
        }
    }

    /** Heading along the nearest wall-like vertical plane within 3 m, or null. */
    private fun nearestWallYaw(center: Vec3): Float? {
        var best: Float? = null
        var bestD = 3f
        for (p in planeTracker.all) {
            if (abs(p.normal.y) > 0.5f) continue
            if (p.kind != SurfaceKind.WALL && p.kind != SurfaceKind.DOOR && p.kind != SurfaceKind.WINDOW) continue
            val n = Vec3(p.normal.x, 0f, p.normal.z).normalized()
            val d = abs((center - p.centroid).dot(n))
            if (d < bestD) {
                bestD = d
                // along the wall: rotate the horizontal normal by 90 degrees
                val along = Vec3(-n.z, 0f, n.x)
                best = atan2(-along.x, -along.z)
            }
        }
        return best
    }

    private class Timeline(val beat: Float, val current: Int?, val next: Int?, val ghostL: Pair<Float, Float>?, val ghostR: Pair<Float, Float>?, val synthetic: Pair<Float, Float>?)

    /** Reads the audio clock and lights the floor; sends beat edges, the posture, the finish. Returns null while nothing plays. */
    private fun timeline(state: DanceUiState, @Suppress("UNUSED_PARAMETER") pose: FloorPose, now: Long, local: Pair<Float, Float>?): Timeline? {
        val pos = beats.beatPosition() ?: return null
        val p = state.danced
        val pv = patternView ?: return null
        if (state.preview) {
            val b = pos.toFloat()
            if (b >= p.loopBeats) {
                if (!previewDone) {
                    previewDone = true
                    sink(DanceEvent.PreviewFinished)
                }
                return null
            }
            if (b < 0f) return null
            return drive(p, pv, b, 0, now)
        }
        val run = state.run ?: return null
        val countIn = run.countIn
        if (pos < countIn) {
            val beat = floor(pos).toInt().coerceAtLeast(0)
            val edge = "in:$beat"
            if (edge != lastEdge) {
                lastEdge = edge
                sink(DanceEvent.BeatChanged(true, run.startLoop, beat, null, (beat + 1).toString()))
            }
            // the posture: where the head (or the debug walker) stands over the start stance during the count-in
            val synth = synthetic(state, p, pv, -1f)
            val standing = synth ?: local
            if (standing != null) {
                val (cx, cz) = pv.centerOffset()
                val sx = (p.startL.first + p.startR.first) / 2f
                val sz = (p.startL.second + p.startR.second) / 2f
                postureSum += Vec3(standing.first + cx - sx, 0f, standing.second + cz - sz)
                postureN++
                if (postureN > 0) {
                    var off = postureSum / postureN.toFloat()
                    val len = hypot(off.x, off.z)
                    if (len > DanceRules.POSTURE_CAP_M) off = off * (DanceRules.POSTURE_CAP_M / len)
                    posture = off.x to off.z
                }
            }
            // the first step is the next one: its footprint lit, the arrow and the weight dot ready
            pv.setDance(p, null, 0, -1f)
            pv.setWeight(centerPath?.at(0f))
            return Timeline(-1f, null, 0, null, null, synth)
        }
        val beat = pos - countIn
        val loopOff = floor(beat / p.loopBeats).toInt()
        val loop = run.startLoop + loopOff
        if (loop >= DanceRules.LOOPS) {
            if (!finished) {
                finished = true
                beats.silence()
                DanceLog.i("RUN complete samples=${samples.size}")
                sink(DanceEvent.RunFinished(RunSamples(samples.toList(), posture, DanceRules.LOOPS)))
            }
            return null
        }
        if (loop != lastLoop) {
            lastLoop = loop
            loopSampleStart = samples.size
            loopPointStart = runPoints.size
            loopPointStartLive = youView?.pointCount ?: 0
        }
        val b = (beat - loopOff * p.loopBeats).toFloat()
        val tl = drive(p, pv, b, loop, now)
        return Timeline(tl.beat, tl.current, tl.next, tl.ghostL, tl.ghostR, synthetic(state, p, pv, b))
    }

    /** Lights the footprints for beat [b] of [loop]; sends the beat edge; returns the ghost feet. */
    private fun drive(p: Pattern, pv: PatternView, b: Float, loop: Int, now: Long): Timeline {
        val current = p.stepAt(b)
        val next = p.upcomingStep(b)
        pv.setDance(p, current, next, b)
        pv.setWeight(centerPath?.at(b))
        if (current != null && current != lastStep) {
            pv.pulse(p, current, now)
        }
        lastStep = current ?: -1
        val edge = "d:$loop:${floor(b).toInt()}:${current ?: -1}"
        if (edge != lastEdge) {
            lastEdge = edge
            if (ui.running) sink(DanceEvent.BeatChanged(false, loop, floor(b).toInt(), current, current?.let { p.steps[it].count } ?: "·"))
        }
        // ghost feet: the last landing of each foot at or before b (the loop is closed)
        var gl: Pair<Float, Float>? = null
        var gr: Pair<Float, Float>? = null
        for (s in p.steps) {
            if (s.beat <= b + 1e-4f) {
                if (s.foot == Foot.L) gl = s.x to s.z else gr = s.x to s.z
            }
        }
        if (gl == null) gl = p.steps.lastOrNull { it.foot == Foot.L }?.let { it.x to it.z } ?: p.startL
        if (gr == null) gr = p.steps.lastOrNull { it.foot == Foot.R }?.let { it.x to it.z } ?: p.startR
        return Timeline(b, current, next, gl, gr, null)
    }

    /** Debug walkers (design T10): a synthetic floor point in floor coordinates, or null for the real head. */
    private fun synthetic(state: DanceUiState, p: Pattern, pv: PatternView, b: Float): Pair<Float, Float>? {
        val walk = state.debugWalk ?: return null
        val (cx, cz) = pv.centerOffset()
        val path = centerPath ?: return null
        // during the count-in every walker stands on the start stance (the posture offset must stay zero)
        val stance = ((p.startL.first + p.startR.first) / 2f) to ((p.startL.second + p.startR.second) / 2f)
        if (b < 0f) return (stance.first - cx) to (stance.second - cz)
        val pt =
            when (walk) {
                DebugWalk.STAND_STILL -> stance
                DebugWalk.LATE -> path.at(b - 0.5f)
                DebugWalk.NOISY -> {
                    debugNoise = debugNoise * 1103515245 + 12345
                    val nx = (((debugNoise ushr 8) and 0xFFFF) / 65535f - 0.5f) * 0.16f
                    debugNoise = debugNoise * 1103515245 + 12345
                    val nz = (((debugNoise ushr 8) and 0xFFFF) / 65535f - 0.5f) * 0.16f
                    val c = path.at(b)
                    (c.first + nx) to (c.second + nz)
                }
                DebugWalk.OFFSET -> {
                    val c = path.at(b)
                    (c.first + 0.2f) to c.second
                }
            }
        return (pt.first - cx) to (pt.second - cz)
    }

    private fun pollPlanes(now: Long) {
        val count = planeTracker.count
        val planes = planeTracker.all
        val hasFloor = planes.any { it.kind == SurfaceKind.FLOOR }
        if (count == lastPlaneCount && hasFloor == lastFloor) return
        if (now - lastPlanesEmitMs < PLANES_EMIT_MS) return
        lastPlanesEmitMs = now
        lastPlaneCount = count
        lastFloor = hasFloor
        val floorY = planes.filter { it.kind == SurfaceKind.FLOOR }.map { it.centroid.y }.minOrNull() ?: 0f
        DanceLog.i("PLANES n=$count floor=$hasFloor floorY=${DanceLog.f(floorY)}")
        sink(DanceEvent.PlanesChanged(count, hasFloor, floorY))
    }

    private fun pollTracking(now: Long) {
        val block = headTracker.blockReason()
        if (block != lastBlock) {
            lastBlock = block
            sink(DanceEvent.Blocked(block))
        }
        val lost = headTracker.lost(now)
        if (lost == lastLost) return
        lastLost = lost
        sink(DanceEvent.TrackingChanged(lost))
    }

    private fun countFrame(now: Long) {
        if (!fpsOn) return
        frames++
        if (fpsStartMs == 0L) fpsStartMs = now
        if (now - fpsStartMs >= 1000L) {
            DanceLog.i("FPS app=${frames * 1000f / (now - fpsStartMs)} frames=$frames ms=${now - fpsStartMs}")
            frames = 0
            fpsStartMs = now
        }
    }

    // =========================================================================================
    // system pointer: a pinch anywhere in the room closes the menu
    // =========================================================================================

    fun onSpatialTap(value: SpatialTapValue) {
        val target = value.targetEntity
        val name = runCatching { target?.getName() }.getOrNull() ?: "none"
        DanceLog.i("TAP target=$name kind=${value.interactionKind}")
        if (ui.menuOpen && target != null && !name.startsWith("panel:") && !name.startsWith("holder:")) sink(DanceEvent.MenuDismissed)
    }

    // =========================================================================================
    // debug probe
    // =========================================================================================

    override fun poseLine(): String =
        "POSE hmd pos=${DanceLog.pos5(head.position)} ${DanceLog.quat5(head.rotation)} fwd=${DanceLog.pos5(head.forward)} valid=$hasHead beat=${beats.beatPosition()?.let { DanceLog.f(it.toFloat()) }}"

    override fun panelLines(): List<String> {
        val rig = rig ?: return emptyList()
        return rig.all.filter { it.shown && it.panel != null }.map { slot ->
            String.format(Locale.US, "PANEL id=%s pos=%s %s scale=%.3f size_dp=%.0fx%.0f engine=%s", slot.id, DanceLog.pos5(slot.position), DanceLog.quat5(slot.rotation), slot.scale, slot.widthDp, slot.heightDp, DanceLog.pos5(slot.enginePosition()))
        }
    }

    override fun planesSummary(): List<String> =
        planeTracker.all.map { p -> "PLANE ${p.label} kind=${p.kind} center=${DanceLog.pos(p.centroid)} normal=${DanceLog.pos(p.normal)} verts=${p.vertices.size}" }

    override fun parkPanel(): String {
        mirrorFollower.park(head, planeTracker.all, SystemClock.elapsedRealtime())
        return "FOLLOW mirror park requested"
    }

    override fun setFpsLine(on: Boolean): String {
        fpsOn = on
        frames = 0
        fpsStartMs = 0L
        return "FPS ${if (on) "on" else "off"}"
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE
        private const val REBIND_DELAY_MS = 150L
        private const val PLANES_EMIT_MS = 500L
        private const val HEAD_SAMPLE_MS = 100L
        private const val MAP_MS = 100L
        private const val PLACING_EMIT_MS = 100L
        private const val PANEL_HOLD_MS = 600L
        private const val SNACK_GAP_M = 0.04f
        private const val COACH_GAP_M = 0.03f
        private const val DIALOG_CLEAR_M = 0.2f
        private const val DIALOG_MIN_M = 0.5f
        private const val STICK_DEAD_ZONE = 0.2f
        private const val STICK_RATE_RAD_S = 1.0472f
        private const val SNAP_RAD = 0.14f

        /** Half sizes of the mirror (0.256 x 0.208 m) with a little air, for the surface rule. */
        private val MIRROR_HALF_EXTENTS = listOf(0.27f, 0.22f, 0.01f)
    }
}
