package io.embodit.dancesteps.domain.usecase

import io.embodit.dancesteps.domain.model.DanceRules
import io.embodit.dancesteps.domain.model.Foot
import io.embodit.dancesteps.domain.model.Pattern
import kotlin.math.sqrt

/**
 * The expected centre of mass over one loop (design `patterns.centerRule`): 0.7 of the bearing foot +
 * 0.3 of the other; each step's shift starts on its beat and is complete half a beat later (the foot
 * lands first, the body follows), smoothstepped; nothing moves on a pause. Floor metres (x right, z ahead).
 */
class CenterPathUseCase(private val pattern: Pattern) {

    private class Key(val beat: Float, val x: Float, val z: Float)

    /** Centre after each step has been taken, in loop order. */
    private val keys: List<Key>

    init {
        var l = pattern.startL
        var r = pattern.startR
        // one warm-up loop so that the first centre is the steady-state one (the loop is closed)
        for (s in pattern.steps) if (s.foot == Foot.L) l = s.x to s.z else r = s.x to s.z
        val out = ArrayList<Key>()
        for (s in pattern.steps) {
            if (s.foot == Foot.L) l = s.x to s.z else r = s.x to s.z
            val bearing = if (s.foot == Foot.L) l else r
            val other = if (s.foot == Foot.L) r else l
            out += Key(s.beat, mix(bearing.first, other.first), mix(bearing.second, other.second))
        }
        keys = out
    }

    private fun mix(bearing: Float, other: Float): Float = DanceRules.BEARING_SHARE * bearing + (1f - DanceRules.BEARING_SHARE) * other

    /** Expected centre at [beatInLoop] (any value; wrapped into the loop). */
    fun at(beatInLoop: Float): Pair<Float, Float> {
        val n = pattern.loopBeats
        val b = ((beatInLoop % n) + n) % n
        var idx = -1
        for (i in keys.indices) if (keys[i].beat <= b + 1e-5f) idx = i
        val to = if (idx >= 0) keys[idx] else keys.last()
        val from = if (idx > 0) keys[idx - 1] else keys.last()
        val start = if (idx >= 0) to.beat else to.beat - n
        val t = ((b - start) / DanceRules.SHIFT_BEATS).coerceIn(0f, 1f)
        val s = t * t * (3f - 2f * t)
        return (from.x + (to.x - from.x) * s) to (from.z + (to.z - from.z) * s)
    }

    /** The whole loop sampled at [perBeat] points per beat (for drawing the dashed route). */
    fun sample(perBeat: Int = 16): List<Pair<Float, Float>> {
        val n = pattern.loopBeats * perBeat
        return List(n) { i -> at(i.toFloat() / perBeat) }
    }

    /** RMS distance of the expected centre from its own mean (the "how much one moves" yardstick). */
    fun rms(): Float {
        val pts = sample(8)
        val mx = pts.map { it.first }.average().toFloat()
        val mz = pts.map { it.second }.average().toFloat()
        var sum = 0.0
        for ((x, z) in pts) sum += ((x - mx) * (x - mx) + (z - mz) * (z - mz)).toDouble()
        return sqrt(sum / pts.size).toFloat()
    }
}
