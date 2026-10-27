package io.embodit.dancesteps.domain.model

import kotlin.math.abs

/** Why spatial sensing cannot be used (design state `blocked`). */
enum class BlockReason { NOT_IN_FULL_SPACE, WITHOUT_PERMISSION, DEVICE_NOT_SUPPORTED }

enum class Foot { L, R }

/** Leader steps or their follower transform (design `patterns.follower`). */
enum class Role(val tag: String) {
    LEADER("leader"),
    FOLLOWER("follower");

    companion object {
        fun fromTag(tag: String?): Role = entries.firstOrNull { it.tag == tag } ?: LEADER
    }
}

/** Tempo as a share of the pattern's full BPM (design `audio.tempo`). */
enum class Speed(val tag: String, val factor: Float) {
    SLOW("slow", 0.6f),
    MID("mid", 0.8f),
    FULL("full", 1.0f);

    val faster: Speed?
        get() = entries.getOrNull(ordinal + 1)

    companion object {
        fun fromTag(tag: String?): Speed = entries.firstOrNull { it.tag == tag } ?: SLOW
    }
}

enum class LanguagePref(val tag: String) {
    SYSTEM("system"),
    ZH("zh"),
    EN("en");

    companion object {
        fun fromTag(tag: String?): LanguagePref = entries.firstOrNull { it.tag == tag } ?: SYSTEM
    }
}

/** The one conclusion of a run (design `rules.score`). */
enum class Verdict(val tag: String) {
    STEADY("steady"),
    OK("ok"),
    SLOWER("slower"),
    BIGGER("bigger");

    companion object {
        fun fromTag(tag: String?): Verdict? = entries.firstOrNull { it.tag == tag }
    }
}

/** The music the synthesiser plays for a pattern (design `patterns[].music`). */
enum class MusicStyle { WALTZ, RUMBA, SALSA, CHACHA }

/** One step of a pattern: where [foot] lands (floor metres, x right, z ahead) on [beat] of the loop. */
data class Step(val beat: Float, val count: String, val foot: Foot, val x: Float, val z: Float, val yaw: Float = 0f)

/** A dance pattern (design `patterns.list`), leader version. */
data class Pattern(
    val id: String,
    val meter: String,
    val bpm: Int,
    val beatsPerBar: Int,
    val loopBeats: Int,
    val counts: String,
    val startL: Pair<Float, Float>,
    val startR: Pair<Float, Float>,
    val steps: List<Step>,
    /** Beats of the loop with no step (the count shows a dot). */
    val pauses: List<Int>,
    val music: MusicStyle,
) {
    val stepCount: Int
        get() = steps.size

    /** The pattern as the follower dances it: (x, z) to (-x, -z), feet swapped, heading kept. */
    fun forRole(role: Role): Pattern =
        if (role == Role.LEADER) this
        else
            copy(
                startL = -startR.first to -startR.second,
                startR = -startL.first to -startL.second,
                steps = steps.map { s -> s.copy(foot = if (s.foot == Foot.L) Foot.R else Foot.L, x = -s.x, z = -s.z) },
            )

    /** Distinct landing spots, each with the foot that lands there (design `entities3d.footprint`). */
    val spots: List<Spot> by lazy {
        val out = ArrayList<Spot>()
        for ((i, s) in steps.withIndex()) {
            val existing = out.firstOrNull { it.foot == s.foot && abs(it.x - s.x) < 1e-4f && abs(it.z - s.z) < 1e-4f }
            if (existing != null) existing.stepIndices += i else out += Spot(out.size, s.foot, s.x, s.z, s.yaw, arrayListOf(i))
        }
        out
    }

    /** Index of the spot of step [i]. */
    fun spotOf(i: Int): Int = spots.indexOfFirst { i in it.stepIndices }

    /** Where [foot] stands right before step [i] of the loop (the loop is closed, so the previous loop's end counts). */
    fun footBefore(i: Int): Pair<Float, Float> {
        val foot = steps[i].foot
        for (k in i - 1 downTo 0) if (steps[k].foot == foot) return steps[k].x to steps[k].z
        for (k in steps.indices.reversed()) if (steps[k].foot == foot) return steps[k].x to steps[k].z
        return if (foot == Foot.L) startL else startR
    }

    /** True when step [i] lands where that foot already stands (an in-place tap: ring, no arrow). */
    fun inPlace(i: Int): Boolean {
        val (x, z) = footBefore(i)
        return abs(x - steps[i].x) < 1e-4f && abs(z - steps[i].z) < 1e-4f
    }

    /** Bounding box centre of the start stance and every landing spot (the pattern is centred on the floor). */
    val boundsCenter: Pair<Float, Float>
        get() {
            val xs = steps.map { it.x } + startL.first + startR.first
            val zs = steps.map { it.z } + startL.second + startR.second
            return ((xs.min() + xs.max()) / 2f) to ((zs.min() + zs.max()) / 2f)
        }

    /** The step under way at [beatInLoop] (its beat <= position < the next step or pause), or null on a pause. */
    fun stepAt(beatInLoop: Float): Int? {
        var current = -1
        for ((i, s) in steps.withIndex()) if (s.beat <= beatInLoop + 1e-4f) current = i
        if (current < 0) return null
        val next = steps.getOrNull(current + 1)?.beat ?: loopBeats.toFloat()
        val pause = pauses.map { it.toFloat() }.filter { it > steps[current].beat }.minOrNull() ?: loopBeats.toFloat()
        return if (beatInLoop < minOf(next, pause) - 1e-4f) current else null
    }

    /** The step that comes after step [i] (wrapping into the next loop). */
    fun nextStep(i: Int): Int = (i + 1) % steps.size

    /** The first step whose beat is > [beatInLoop] (wrapping), i.e. the one the dancer is waiting for. */
    fun upcomingStep(beatInLoop: Float): Int {
        for ((i, s) in steps.withIndex()) if (s.beat > beatInLoop + 1e-4f) return i
        return 0
    }
}

/** A landing spot on the floor: one footprint entity. */
class Spot(val index: Int, val foot: Foot, val x: Float, val z: Float, val yaw: Float, val stepIndices: ArrayList<Int>)

/** Stored result of a pattern at a speed (picker row "last: slow · steady"). */
data class LastResult(val verdict: Verdict, val devCm: Int, val loops: Int)

data class Settings(
    val lastPatternId: String? = null,
    val role: Role = Role.LEADER,
    /** Per pattern id; a pattern never opened before starts at SLOW (design `patterns.defaultSpeed`). */
    val speeds: Map<String, Speed> = emptyMap(),
    val language: LanguagePref = LanguagePref.SYSTEM,
    val coachSeen: Boolean = false,
    /** The soft step sound on every step of the pattern (0.1.1; the More menu turns it off). */
    val stepSound: Boolean = true,
    /** Key: pattern id, a colon, the speed tag. */
    val results: Map<String, LastResult> = emptyMap(),
) {
    fun speedOf(patternId: String): Speed = speeds[patternId] ?: Speed.SLOW

    fun resultOf(patternId: String, speed: Speed): LastResult? = results[patternId + ":" + speed.tag]

    /** The result shown on a picker row: the one at the speed the pattern would open with. */
    fun lastOf(patternId: String): Pair<Speed, LastResult>? {
        val speed = speedOf(patternId)
        return resultOf(patternId, speed)?.let { speed to it }
    }
}

/** Every number of the design that is not a pattern (design `rules`, `entities3d`, `panels`). */
object DanceRules {
    const val FLOOR_SIZE_M = 1.5f
    const val FLOOR_HALF_M = FLOOR_SIZE_M / 2f
    const val TAPE_WIDTH_M = 0.03f
    const val TAPE_THICK_M = 0.002f
    const val TAPE_DASH_M = 0.12f
    const val TAPE_GAP_M = 0.06f
    const val FRONT_ARROW_M = 0.20f

    /** Mirror panel centre: 1.25 m ahead of the floor centre, 0.10 m below the head at placing time, leaning back 5 degrees. */
    const val MIRROR_AHEAD_M = 1.25f
    const val MIRROR_BELOW_HEAD_M = 0.10f
    const val MIRROR_LEAN_DEG = 5f

    /** A front wall nearer than this to the floor centre leaves no room for the mirror. */
    const val FRONT_MIN_M = 1.35f

    /** Taste D7: no floor plane this long after the start -> an estimated floor at head height minus this. */
    const val VIRTUAL_FLOOR_AFTER_MS = 5000L
    const val EYE_HEIGHT_M = 1.55f
    const val FLOOR_NUDGE_M = 0.02f
    const val FLOOR_NUDGE_MAX_M = 0.40f

    const val LOOPS = 8
    const val END_EARLY_AFTER_LOOPS = 2
    const val START_RADIUS_M = 0.25f
    const val OFF_FLOOR_MARGIN_M = 0.2f
    const val OFF_FLOOR_AFTER_MS = 1000L
    const val POSTURE_CAP_M = 0.12f

    /** Score thresholds (placeholders until a headset run, design `rules.score` part 3). */
    const val MOVE_RATIO_MIN = 0.5f
    const val STEADY_CM = 6f
    const val OK_CM = 12f
    const val SCORE_WINDOW_BEATS = 0.25f

    /** Footprint geometry, metres: the outline is [FootShape]; 2 mm above the floor. */
    const val FOOT_LIFT = 0.002f
    const val FOOT_LENGTH = FootShape.FOOT_LENGTH

    const val YOU_DOT_R = 0.06f
    const val TRAIL_WIDTH_M = 0.012f
    const val TRAIL_SAMPLE_MS = 100L
    const val TRAIL_LIVE_SEGMENTS = 160
    const val TRAIL_POOL = 400
    const val PATH_WIDTH_M = 0.015f
    const val PATH_DASH_M = 0.05f
    const val PATH_GAP_M = 0.04f
    const val ARROW_WIDTH_M = 0.02f
    const val ARROW_HEAD_M = 0.06f
    const val WEIGHT_DOT_R = 0.03f

    /** Expected centre of mass: 0.7 of the bearing foot, 0.3 of the other; the shift takes half a beat. */
    const val BEARING_SHARE = 0.7f
    const val SHIFT_BEATS = 0.5f

    fun tempoBpm(pattern: Pattern, speed: Speed): Int = Math.round(pattern.bpm * speed.factor)
}
