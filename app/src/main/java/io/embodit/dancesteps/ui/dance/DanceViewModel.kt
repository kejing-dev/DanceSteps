package io.embodit.dancesteps.ui.dance

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.embodit.dancesteps.data.DanceRepository
import io.embodit.dancesteps.domain.model.BlockReason
import io.embodit.dancesteps.domain.model.DanceRules
import io.embodit.dancesteps.domain.model.LastResult
import io.embodit.dancesteps.domain.model.LanguagePref
import io.embodit.dancesteps.domain.model.Patterns
import io.embodit.dancesteps.domain.model.Role
import io.embodit.dancesteps.domain.model.Settings
import io.embodit.dancesteps.domain.model.Speed
import io.embodit.dancesteps.domain.model.Verdict
import io.embodit.dancesteps.domain.usecase.FloorPose
import io.embodit.dancesteps.domain.usecase.PlaceCheck
import io.embodit.dancesteps.domain.usecase.PlaceFloorUseCase
import io.embodit.dancesteps.domain.usecase.ScoreUseCase
import io.embodit.dancesteps.domain.model.Vec3
import io.embodit.dancesteps.platform.DanceGraph
import io.embodit.dancesteps.platform.DanceLog
import java.util.Locale
import kotlin.math.hypot
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The whole state machine of the app (design `states[]`, `rules`). Events come from the panels, the
 * scene (planes, tracking, head samples, beat edges, run results) and the debug receiver; the state is
 * rendered by the panels and by `DanceScene.render`. Per-frame work never passes through here.
 */
class DanceViewModel(private val repository: DanceRepository) : ViewModel() {

    private val _state = MutableStateFlow(DanceUiState())
    val state: StateFlow<DanceUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<DanceUiEffect>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val effects: SharedFlow<DanceUiEffect> = _effects.asSharedFlow()

    private val score = ScoreUseCase()
    private var sceneReady = false
    private var settingsLoaded = false
    private var latestPlacing: FloorPose? = null
    private var latestHeadY = 1.6f
    private var latestHeadStage: Pair<Float, Float>? = null
    private var latestHeadYaw = 0f
    private var youLocal: Pair<Float, Float>? = null
    private var offFloorSinceMs = NEVER
    private var paused: RunSamples? = null
    private var runSerial = 0
    private var realHasFloor = false

    /** Debug: a forced overlay (cramped, not-on-start, off-floor, scanning) is not cleared by live sensing until the next `state`. */
    private var debugHold = false
    private var lastStateName = ""

    init {
        viewModelScope.launch {
            val s = repository.load()
            settingsLoaded = true
            update { it.copy(settings = s, role = s.role, language = s.language, pattern = Patterns.byId(s.lastPatternId) ?: Patterns.waltzBox) }
            update { it.copy(speed = it.settings.speedOf(it.pattern.id)) }
            maybeStart()
        }
    }

    private inline fun update(f: (DanceUiState) -> DanceUiState) {
        val before = _state.value
        val after = f(before)
        if (after !== before) {
            _state.value = after
            val name = after.qaName
            if (name != lastStateName) {
                lastStateName = name
                DanceLog.i("STATE $name phase=${after.phase} pattern=${after.pattern.id} role=${after.role.tag} speed=${after.speed.tag} bpm=${after.bpm} loop=${after.run?.loop ?: -1}")
            }
        }
    }

    private fun maybeStart() {
        if (sceneReady && settingsLoaded && _state.value.phase == Phase.STARTING) update { it.copy(phase = Phase.PICKER) }
    }

    fun onEvent(event: DanceEvent) {
        if (event !is DanceEvent.HeadSample && event !is DanceEvent.FloorFollow && event !is DanceEvent.BeatChanged) DanceLog.i("EVENT ${event::class.simpleName}")
        when (event) {
            DanceEvent.SceneReady -> {
                sceneReady = true
                maybeStart()
            }
            is DanceEvent.PlanesChanged -> {
                realHasFloor = event.hasFloor
                update { it.copy(planeCount = event.count, hasFloor = if (debugHold) it.hasFloor else event.hasFloor, floorY = event.floorY) }
            }
            is DanceEvent.TrackingChanged -> setLost(event.lost)
            is DanceEvent.Blocked -> {
                if (event.reason == BlockReason.WITHOUT_PERMISSION || event.reason == BlockReason.DEVICE_NOT_SUPPORTED) {
                    update { it.copy(phase = Phase.BLOCKED, blocked = event.reason, run = null, preview = false, menuOpen = false) }
                }
            }
            is DanceEvent.FloorFollow -> {
                latestPlacing = event.pose
                latestHeadY = event.headY
                if (!debugHold && _state.value.phase == Phase.PLACING && event.check != _state.value.placeCheck) update { it.copy(placeCheck = event.check) }
            }
            is DanceEvent.HeadSample -> onHead(event)
            is DanceEvent.BeatChanged -> onBeat(event)
            is DanceEvent.RunFinished -> finish(event.samples)
            is DanceEvent.RunPaused -> paused = event.samples
            DanceEvent.PreviewFinished -> update { it.copy(preview = false) }

            is DanceEvent.PatternClicked -> pickPattern(event.id)
            is DanceEvent.RoleSelected -> {
                update { it.copy(role = event.role) }
                saveSettings { it.copy(role = event.role) }
            }
            is DanceEvent.SpeedSelected -> if (_state.value.phase == Phase.READY) setSpeed(event.speed)
            DanceEvent.PlaceHereClicked -> placeHere()
            DanceEvent.BackClicked -> back()
            DanceEvent.MoreClicked -> update { it.copy(menuOpen = !it.menuOpen) }
            DanceEvent.MenuDismissed -> update { it.copy(menuOpen = false) }
            is DanceEvent.LanguageSelected -> {
                update { it.copy(language = event.language) }
                saveSettings { it.copy(language = event.language) }
            }
            DanceEvent.ReplaceFloorClicked -> update { it.copy(menuOpen = false, floor = null, run = null, summary = null, preview = false, notOnStart = false, offFloor = false, phase = Phase.PLACING) }
            DanceEvent.ExitClicked -> _effects.tryEmit(DanceUiEffect.ExitApp)

            DanceEvent.StartClicked -> if (_state.value.phase == Phase.READY && !_state.value.preview) startRun(0)
            DanceEvent.PreviewClicked -> if (_state.value.phase == Phase.READY) update { it.copy(preview = !it.preview) }
            DanceEvent.PauseClicked -> pause()
            DanceEvent.ResumeClicked -> if (_state.value.phase == Phase.PAUSED) startRun(_state.value.run?.loop ?: 0)
            DanceEvent.RestartClicked -> if (_state.value.phase == Phase.PAUSED || _state.value.running) update { it.copy(phase = Phase.READY, run = null, menuOpen = false, notOnStart = false, offFloor = false) }
            DanceEvent.EndEarlyClicked -> if (_state.value.canEndEarly) paused?.let { finish(it) }
            DanceEvent.AgainClicked -> update { it.copy(phase = Phase.READY, summary = null, run = null, menuOpen = false) }
            DanceEvent.FasterClicked -> {
                val faster = _state.value.speed.faster ?: return
                setSpeed(faster)
                update { it.copy(phase = Phase.READY, summary = null, run = null, menuOpen = false) }
            }
            DanceEvent.ChangePatternClicked -> update { it.copy(phase = Phase.PICKER, summary = null, run = null, menuOpen = false) }
            DanceEvent.CoachDismissed -> {
                update { it.copy(coach = false) }
                saveSettings { it.copy(coachSeen = true) }
                DanceLog.i("COACH dismissed")
            }
            DanceEvent.RetryClicked -> {
                update { it.copy(phase = Phase.STARTING, blocked = null) }
                sceneReady = false
                _effects.tryEmit(DanceUiEffect.RetryTracking)
            }

            DanceEvent.WentBackground -> if (_state.value.running) pause()
            DanceEvent.CameForeground -> Unit

            is DanceEvent.DebugState -> debugState(event.name)
            DanceEvent.DebugPlace -> debugPlace()
            is DanceEvent.DebugWalkSet -> {
                update { it.copy(debugWalk = event.walk) }
                if (event.walk != null) {
                    if (_state.value.floor == null) debugPlace()
                    update { it.copy(phase = Phase.READY, run = null, summary = null, preview = false, notOnStart = false) }
                    startRun(0, force = true)
                }
            }
            DanceEvent.DebugWalkX -> {
                update { it.copy(debugWalkX = true) }
                viewModelScope.launch {
                    kotlinx.coroutines.delay(2500)
                    update { it.copy(debugWalkX = false) }
                }
            }
            is DanceEvent.DebugTracking -> {
                DanceGraph.head.forceLost = event.lost == true
                if (event.lost != null) setLost(event.lost)
            }
            DanceEvent.DebugCoachReset -> {
                saveSettings { it.copy(coachSeen = false) }
                update { it.copy(coach = it.phase == Phase.READY) }
            }
            is DanceEvent.DebugSummary -> update { it.copy(phase = Phase.SUMMARY, run = null, preview = false, summary = SummaryUi(event.verdict, event.devCm, it.speed, DanceRules.LOOPS)) }
        }
    }

    // =========================================================================================
    // picker, placing, ready
    // =========================================================================================

    private fun pickPattern(id: String) {
        val pattern = Patterns.byId(id) ?: return
        update {
            val speed = it.settings.speedOf(id)
            val next = if (it.floor != null) Phase.READY else Phase.PLACING
            it.copy(pattern = pattern, speed = speed, phase = next, run = null, summary = null, preview = false, menuOpen = false, notOnStart = false, coach = next == Phase.READY && !it.settings.coachSeen)
        }
        saveSettings { it.copy(lastPatternId = id) }
    }

    private fun placeHere() {
        val s = _state.value
        if (s.phase != Phase.PLACING || !s.canPlace) return
        val pose = latestPlacing ?: return
        DanceLog.i("FLOOR placed center=${DanceLog.pos(pose.center)} yaw=${String.format(Locale.US, "%.1f", Math.toDegrees(pose.yaw.toDouble()))} headY=${DanceLog.f(latestHeadY)}")
        update { it.copy(floor = pose, headYAtPlace = latestHeadY, phase = Phase.READY, coach = !it.settings.coachSeen, notOnStart = false) }
    }

    private fun back() {
        val s = _state.value
        when (s.phase) {
            Phase.PLACING, Phase.READY, Phase.PAUSED, Phase.SUMMARY -> update { it.copy(phase = Phase.PICKER, run = null, summary = null, preview = false, menuOpen = false, notOnStart = false, offFloor = false, coach = false) }
            else -> Unit
        }
    }

    private fun setSpeed(speed: Speed) {
        update { it.copy(speed = speed) }
        val id = _state.value.pattern.id
        saveSettings { it.copy(speeds = it.speeds + (id to speed)) }
    }

    /** Start / resume: the dancer must stand on the start stance (design `rules.ready`). */
    private fun startRun(startLoop: Int, force: Boolean = false) {
        val s = _state.value
        if (s.lost && !force) return
        if (!force && !onStart()) {
            update { it.copy(notOnStart = true) }
            DanceLog.i("START refused: not on the start stance you=$youLocal")
            return
        }
        paused = null
        update {
            it.copy(
                phase = Phase.COUNT_IN,
                run = RunUi(startLoop = startLoop, countIn = it.pattern.beatsPerBar, loop = startLoop, countText = "", serial = ++runSerial),
                notOnStart = false,
                offFloor = false,
                preview = false,
                menuOpen = false,
                coach = false,
            )
        }
        DanceLog.i("RUN start loop=$startLoop pattern=${s.pattern.id} speed=${s.speed.tag} bpm=${s.bpm}")
    }

    private fun onStart(): Boolean {
        val you = youLocal ?: return _state.value.debugWalk != null
        val p = _state.value.danced
        val cx = (p.startL.first + p.startR.first) / 2f
        val cz = (p.startL.second + p.startR.second) / 2f
        return hypot(you.first - cx, you.second - cz) <= DanceRules.START_RADIUS_M
    }

    private fun pause() {
        val s = _state.value
        if (!s.running) return
        update { it.copy(phase = Phase.PAUSED, offFloor = false) }
        DanceLog.i("RUN pause loop=${s.run?.loop}")
    }

    private fun onBeat(e: DanceEvent.BeatChanged) {
        val s = _state.value
        if (!s.running) return
        val run = s.run ?: return
        val phase = if (e.countingIn) Phase.COUNT_IN else Phase.DANCING
        update { it.copy(phase = phase, run = run.copy(countingIn = e.countingIn, loop = e.loop, beatInLoop = e.beatInLoop, step = e.step, countText = e.countText)) }
    }

    private fun finish(samples: RunSamples) {
        val s = _state.value
        if (s.phase != Phase.DANCING && s.phase != Phase.COUNT_IN && s.phase != Phase.PAUSED) return
        val result = score.score(s.danced, samples.samples, samples.posture)
        val verdict = result?.verdict ?: Verdict.BIGGER
        val dev = result?.devCm ?: 0
        val loops = samples.loopsDone.coerceAtLeast(1)
        DanceLog.i("RUN finish loops=$loops verdict=${verdict.tag} dev=${dev}cm ratio=${result?.moveRatio?.let { DanceLog.f(it) }} samples=${samples.samples.size}")
        update { it.copy(phase = Phase.SUMMARY, run = null, offFloor = false, menuOpen = false, summary = SummaryUi(verdict, dev, it.speed, loops)) }
        val key = s.pattern.id + ":" + s.speed.tag
        saveSettings { it.copy(results = it.results + (key to LastResult(verdict, dev, loops))) }
        paused = null
    }

    private fun onHead(e: DanceEvent.HeadSample) {
        latestHeadStage = e.stageX to e.stageZ
        latestHeadY = e.headY
        latestHeadYaw = e.yaw
        youLocal = e.local
        val s = _state.value
        if (debugHold) return
        if (s.notOnStart && onStart()) update { it.copy(notOnStart = false) }
        if (s.phase == Phase.DANCING && e.local != null) {
            val now = SystemClock.elapsedRealtime()
            if (PlaceFloorUseCase.offFloor(e.local)) {
                if (offFloorSinceMs == NEVER) offFloorSinceMs = now
                if (!s.offFloor && now - offFloorSinceMs >= DanceRules.OFF_FLOOR_AFTER_MS) update { it.copy(offFloor = true) }
            } else {
                offFloorSinceMs = NEVER
                if (s.offFloor) update { it.copy(offFloor = false) }
            }
        } else {
            offFloorSinceMs = NEVER
        }
    }

    private fun setLost(lost: Boolean) {
        val s = _state.value
        if (lost == s.lost) return
        update { it.copy(lost = lost) }
        if (lost && s.running) pause()
        DanceLog.i("TRACK lost=$lost")
    }

    private fun saveSettings(f: (Settings) -> Settings) {
        val next = f(_state.value.settings)
        update { it.copy(settings = next) }
        viewModelScope.launch { runCatching { repository.save(next) }.onFailure { DanceLog.w("PREFS save failed: $it") } }
    }

    // =========================================================================================
    // debug (design qaHooks): straight to a named state
    // =========================================================================================

    private fun debugPlace() {
        val head = latestHeadStage ?: (0f to 0f)
        val pose = FloorPose(Vec3(head.first, _state.value.floorY, head.second), latestHeadYaw)
        update { it.copy(floor = pose, headYAtPlace = latestHeadY) }
        DanceLog.i("FLOOR placed by debug center=${DanceLog.pos(pose.center)}")
    }

    private fun debugState(raw: String) {
        val name = raw.trim()
        val base = name.substringBefore('+').substringBefore(':')
        val overlays = name.substringAfter('+', "").substringBefore(':').split('+').filter { it.isNotEmpty() }
        val follower = name.substringAfter(':', "") == "follower"
        debugHold = false
        update { it.copy(role = if (follower) Role.FOLLOWER else Role.LEADER, hasFloor = realHasFloor) }
        val needsFloor = base in setOf("ready", "countIn", "dancing", "paused", "summary")
        if (needsFloor && _state.value.floor == null) debugPlace()
        update { it.copy(menuOpen = false, coach = false, preview = false, notOnStart = false, offFloor = false, blocked = null) }
        when (base) {
            "starting" -> update { it.copy(phase = Phase.STARTING) }
            "picker" -> update { it.copy(phase = Phase.PICKER, run = null, summary = null) }
            "placing" -> update { it.copy(phase = Phase.PLACING, floor = null, run = null, summary = null) }
            "ready" -> update { it.copy(phase = Phase.READY, run = null, summary = null) }
            "countIn" -> {
                // the count-in bar is over in a few seconds: for the screenshot the clicks go on for 60 beats
                update { it.copy(phase = Phase.READY, run = null, summary = null) }
                startRun(0, force = true)
                update { it.copy(run = it.run?.copy(countIn = 60)) }
            }
            "dancing" -> {
                update { it.copy(phase = Phase.READY, run = null, summary = null) }
                startRun(0, force = true)
            }
            "paused" -> {
                update { it.copy(phase = Phase.READY, run = null, summary = null) }
                startRun(0, force = true)
                // the pause lands on the third loop so that "finish & see result" is available
                update { it.copy(phase = Phase.PAUSED, run = it.run?.copy(countingIn = false, loop = 2, beatInLoop = 1, step = 1, countText = it.danced.steps.getOrNull(1)?.count ?: "")) }
            }
            "summary" -> update { it.copy(phase = Phase.SUMMARY, run = null, summary = SummaryUi(Verdict.STEADY, 5, it.speed, DanceRules.LOOPS)) }
            "blocked" -> update { it.copy(phase = Phase.BLOCKED, blocked = BlockReason.WITHOUT_PERMISSION, run = null) }
            else -> DanceLog.i("CMD state unknown '$base'")
        }
        for (o in overlays) {
            when (o) {
                "scanning" -> {
                    debugHold = true
                    update { it.copy(hasFloor = false) }
                }
                "cramped" -> {
                    debugHold = true
                    update { it.copy(placeCheck = PlaceCheck(io.embodit.dancesteps.domain.usecase.Cramped(io.embodit.dancesteps.domain.usecase.Side.RIGHT, io.embodit.dancesteps.domain.model.SurfaceKind.CHAIR, 30), null)) }
                }
                "front" -> {
                    debugHold = true
                    update { it.copy(placeCheck = PlaceCheck(null, 40)) }
                }
                "notOnStart" -> {
                    debugHold = true
                    update { it.copy(notOnStart = true) }
                }
                "preview" -> update { it.copy(preview = true) }
                "offFloor" -> {
                    debugHold = true
                    update { it.copy(offFloor = true) }
                }
                "lost" -> {
                    DanceGraph.head.forceLost = true
                    update { it.copy(lost = true) }
                }
                "menu" -> update { it.copy(menuOpen = true) }
                "coach" -> update { it.copy(coach = true) }
                else -> DanceLog.i("CMD state unknown overlay '$o'")
            }
        }
    }

    /** The whole model as one JSON line (debug `dump`). */
    fun dumpJson(): String {
        val s = _state.value
        val o = JSONObject()
        o.put("state", s.qaName)
        o.put("phase", s.phase.name)
        o.put("pattern", s.pattern.id)
        o.put("role", s.role.tag)
        o.put("speed", s.speed.tag)
        o.put("bpm", s.bpm)
        o.put("hasFloor", s.hasFloor)
        o.put("planes", s.planeCount)
        o.put("floorY", s.floorY.toDouble())
        s.floor?.let { f -> o.put("floor", JSONObject().put("x", f.center.x.toDouble()).put("y", f.center.y.toDouble()).put("z", f.center.z.toDouble()).put("yawDeg", Math.toDegrees(f.yaw.toDouble()))) }
        s.run?.let { r -> o.put("run", JSONObject().put("loop", r.loop).put("beat", r.beatInLoop).put("step", r.step ?: -1).put("count", r.countText).put("countingIn", r.countingIn)) }
        s.summary?.let { r -> o.put("summary", JSONObject().put("verdict", r.verdict.tag).put("devCm", r.devCm).put("loops", r.loops)) }
        youLocal?.let { o.put("you", JSONObject().put("x", it.first.toDouble()).put("z", it.second.toDouble())) }
        o.put("lost", s.lost)
        o.put("offFloor", s.offFloor)
        o.put("notOnStart", s.notOnStart)
        o.put("menu", s.menuOpen)
        o.put("coach", s.coach)
        o.put("language", s.language.tag)
        return o.toString()
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = DanceViewModel(DanceGraph.repository(context)) as T
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE
    }
}
