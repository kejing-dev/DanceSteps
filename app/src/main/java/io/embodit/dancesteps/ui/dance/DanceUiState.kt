package io.embodit.dancesteps.ui.dance

import io.embodit.dancesteps.domain.model.BlockReason
import io.embodit.dancesteps.domain.model.DanceRules
import io.embodit.dancesteps.domain.model.LanguagePref
import io.embodit.dancesteps.domain.model.Pattern
import io.embodit.dancesteps.domain.model.Patterns
import io.embodit.dancesteps.domain.model.Role
import io.embodit.dancesteps.domain.model.Settings
import io.embodit.dancesteps.domain.model.Speed
import io.embodit.dancesteps.domain.model.Verdict
import io.embodit.dancesteps.domain.usecase.Cramped
import io.embodit.dancesteps.domain.usecase.FloorPose
import io.embodit.dancesteps.domain.usecase.PlaceCheck
import io.embodit.dancesteps.domain.usecase.TrailSample

/** The stages of the design package (`states[].kind == 阶段`). */
enum class Phase { STARTING, PICKER, PLACING, READY, COUNT_IN, DANCING, PAUSED, SUMMARY, BLOCKED }

/** Snackbar kinds (design `copy.snackbar`); all of them stay while their condition holds. */
enum class SnackKind { SCANNING, OFF_FLOOR, LOST, NOT_ON_START }

/** Synthetic dancers for QA (design `qaHooks`): replace the head's floor point while a run is on. */
enum class DebugWalk(val tag: String) {
    STAND_STILL("stand_still"),
    LATE("walk_late"),
    NOISY("walk_noisy"),
    OFFSET("walk_offset");

    companion object {
        fun fromTag(tag: String?): DebugWalk? = entries.firstOrNull { it.tag == tag }
    }
}

/** What the mirror's right column shows during the count-in, the dance and the pause. */
data class RunUi(
    /** First loop of this run (0 after start; the interrupted loop after a resume). */
    val startLoop: Int,
    /** Beats of the count-in bar before the music. */
    val countIn: Int,
    /** True while the count-in bar plays. */
    val countingIn: Boolean = true,
    /** 0-based loop under way. */
    val loop: Int = 0,
    /** Whole beat of the loop under way. */
    val beatInLoop: Int = 0,
    /** Step under way, or null on a pause beat. */
    val step: Int? = null,
    /** The big numeral: a count, a word, or a dot. */
    val countText: String = "",
    /** Grows with every start / resume, so that the scene re-arms the music even for identical settings. */
    val serial: Int = 0,
) {
    val loopsTotal: Int
        get() = DanceRules.LOOPS
}

data class SummaryUi(val verdict: Verdict, val devCm: Int, val speed: Speed, val loops: Int)

/** Where the run's samples stand (handed over by the scene when the run pauses or ends). */
data class RunSamples(val samples: List<TrailSample>, val posture: Pair<Float, Float>, val loopsDone: Int)

data class DanceUiState(
    val phase: Phase = Phase.STARTING,
    val pattern: Pattern = Patterns.waltzBox,
    val role: Role = Role.LEADER,
    val speed: Speed = Speed.SLOW,
    val settings: Settings = Settings(),
    /** The placed floor; null until "place here". */
    val floor: FloorPose? = null,
    /** Head height when the floor was placed (the mirror's height follows it). */
    val headYAtPlace: Float = 1.6f,
    val floorY: Float = 0f,
    val hasFloor: Boolean = false,
    val planeCount: Int = 0,
    /** Placing: the crowded side and the front wall check of the floor under the head. */
    val placeCheck: PlaceCheck = PlaceCheck.CLEAR,
    val run: RunUi? = null,
    val summary: SummaryUi? = null,
    val preview: Boolean = false,
    val lost: Boolean = false,
    val offFloor: Boolean = false,
    val notOnStart: Boolean = false,
    val menuOpen: Boolean = false,
    val coach: Boolean = false,
    val blocked: BlockReason? = null,
    val language: LanguagePref = LanguagePref.SYSTEM,
    val debugWalk: DebugWalk? = null,
    /** Debug: the you-dot walks +x 0.5 m (checks the map's orientation). */
    val debugWalkX: Boolean = false,
) {
    /** The pattern as it is danced (follower transform applied). */
    val danced: Pattern
        get() = pattern.forRole(role)

    val bpm: Int
        get() = DanceRules.tempoBpm(pattern, speed)

    val cramped: Cramped?
        get() = placeCheck.cramped

    val frontTooCloseCm: Int?
        get() = placeCheck.frontTooCloseCm

    val canPlace: Boolean
        get() = hasFloor && placeCheck.canPlace

    val scanning: Boolean
        get() = !hasFloor && (phase == Phase.PICKER || phase == Phase.PLACING)

    /** The one snackbar (design `informationArchitecture.densityCap`: at most one). */
    val snack: SnackKind?
        get() =
            when {
                lost -> SnackKind.LOST
                scanning -> SnackKind.SCANNING
                notOnStart && (phase == Phase.READY || phase == Phase.PAUSED) -> SnackKind.NOT_ON_START
                offFloor && phase == Phase.DANCING -> SnackKind.OFF_FLOOR
                else -> null
            }

    /** The run is being danced or counted in (the scene drives the footprints from the beat clock). */
    val running: Boolean
        get() = phase == Phase.COUNT_IN || phase == Phase.DANCING

    /** Pause: "finish and see the result" needs two complete loops. */
    val canEndEarly: Boolean
        get() = phase == Phase.PAUSED && (run?.loop ?: 0) >= DanceRules.END_EARLY_AFTER_LOOPS

    val canGoFaster: Boolean
        get() = speed.faster != null

    /** The start stance is shown and blinks (ready, paused). */
    val showStartStance: Boolean
        get() = phase == Phase.READY || phase == Phase.PAUSED

    /** QA name of this state: phase[+overlay...][:follower] (design `qaHooks`). */
    val qaName: String
        get() {
            val sb = StringBuilder(phase.name.lowercase().replace("_in", "In"))
            if (scanning) sb.append("+scanning")
            if (phase == Phase.PLACING && (placeCheck.cramped != null || placeCheck.frontTooCloseCm != null)) sb.append("+cramped")
            if (notOnStart && (phase == Phase.READY || phase == Phase.PAUSED)) sb.append("+notOnStart")
            if (preview) sb.append("+preview")
            if (offFloor && phase == Phase.DANCING) sb.append("+offFloor")
            if (lost) sb.append("+lost")
            if (menuOpen) sb.append("+menu")
            if (coach) sb.append("+coach")
            if (role == Role.FOLLOWER) sb.append(":follower")
            return sb.toString()
        }
}

/** Everything that can happen: panel controls, scene callbacks, lifecycle, debug hooks. */
sealed class DanceEvent {
    // scene
    data object SceneReady : DanceEvent()

    data class PlanesChanged(val count: Int, val hasFloor: Boolean, val floorY: Float) : DanceEvent()

    data class TrackingChanged(val lost: Boolean) : DanceEvent()

    data class Blocked(val reason: BlockReason?) : DanceEvent()

    /** Ten times a second while placing: the floor under the head and its check. */
    data class FloorFollow(val pose: FloorPose, val headY: Float, val check: PlaceCheck) : DanceEvent()

    /** Ten times a second: the head's floor point in floor coordinates (null before the floor is placed). */
    data class HeadSample(val local: Pair<Float, Float>?, val stageX: Float, val stageZ: Float, val headY: Float, val yaw: Float) : DanceEvent()

    /** The beat clock crossed into another beat (edge-triggered by the scene). */
    data class BeatChanged(val countingIn: Boolean, val loop: Int, val beatInLoop: Int, val step: Int?, val countText: String) : DanceEvent()

    data class RunFinished(val samples: RunSamples) : DanceEvent()

    /** Sent when a run pauses: the samples of the loops completed so far. */
    data class RunPaused(val samples: RunSamples) : DanceEvent()

    data object PreviewFinished : DanceEvent()

    // picker / placing
    data class PatternClicked(val id: String) : DanceEvent()

    data class RoleSelected(val role: Role) : DanceEvent()

    data class SpeedSelected(val speed: Speed) : DanceEvent()

    data object PlaceHereClicked : DanceEvent()

    data object BackClicked : DanceEvent()

    data object MoreClicked : DanceEvent()

    data object MenuDismissed : DanceEvent()

    data class LanguageSelected(val language: LanguagePref) : DanceEvent()

    data object ReplaceFloorClicked : DanceEvent()

    data object ExitClicked : DanceEvent()

    // ready / run
    data object StartClicked : DanceEvent()

    data object PreviewClicked : DanceEvent()

    data object PauseClicked : DanceEvent()

    data object ResumeClicked : DanceEvent()

    data object RestartClicked : DanceEvent()

    data object EndEarlyClicked : DanceEvent()

    data object AgainClicked : DanceEvent()

    data object FasterClicked : DanceEvent()

    data object ChangePatternClicked : DanceEvent()

    data object CoachDismissed : DanceEvent()

    data object RetryClicked : DanceEvent()

    // lifecycle
    data object WentBackground : DanceEvent()

    data object CameForeground : DanceEvent()

    // debug
    data class DebugState(val name: String) : DanceEvent()

    data object DebugPlace : DanceEvent()

    data class DebugWalkSet(val walk: DebugWalk?) : DanceEvent()

    data object DebugWalkX : DanceEvent()

    data class DebugTracking(val lost: Boolean?) : DanceEvent()

    data object DebugCoachReset : DanceEvent()

    data class DebugSummary(val verdict: Verdict, val devCm: Int) : DanceEvent()
}

sealed class DanceUiEffect {
    data object ExitApp : DanceUiEffect()

    /** Restart the trackers after the "unavailable" dialog's retry. */
    data object RetryTracking : DanceUiEffect()
}
