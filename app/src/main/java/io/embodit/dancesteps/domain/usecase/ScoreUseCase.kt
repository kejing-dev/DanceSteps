package io.embodit.dancesteps.domain.usecase

import io.embodit.dancesteps.domain.model.DanceRules
import io.embodit.dancesteps.domain.model.Pattern
import io.embodit.dancesteps.domain.model.Verdict
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One recorded sample of the dancer: head floor point in floor metres at a beat position (loop-relative beats). */
data class TrailSample(val beat: Float, val x: Float, val z: Float)

data class Score(val verdict: Verdict, val devCm: Int, val moveRatio: Float, val samples: Int)

/**
 * The one verdict of a run (design `rules.score`):
 * 1. moved or not: RMS of the dancer about their mean over the RMS of the expected centre; below 0.5 = "bigger steps";
 * 2. per-sample error = distance to the nearest expected centre within a quarter beat; mean <= 6 cm steady, <= 12 ok, else slower.
 * Samples of a loop that a pause interrupted are not passed in. [postureOffset] is subtracted first.
 */
class ScoreUseCase {

    fun score(pattern: Pattern, samples: List<TrailSample>, postureOffset: Pair<Float, Float> = 0f to 0f): Score? {
        if (samples.size < MIN_SAMPLES) return null
        val path = CenterPathUseCase(pattern)
        val pts = samples.map { TrailSample(it.beat, it.x - postureOffset.first, it.z - postureOffset.second) }
        val mx = pts.map { it.x }.average().toFloat()
        val mz = pts.map { it.z }.average().toFloat()
        var sum = 0.0
        for (p in pts) sum += ((p.x - mx) * (p.x - mx) + (p.z - mz) * (p.z - mz)).toDouble()
        val youRms = sqrt(sum / pts.size).toFloat()
        val expectedRms = path.rms().coerceAtLeast(0.01f)
        val ratio = youRms / expectedRms
        var err = 0.0
        for (p in pts) {
            var best = Float.MAX_VALUE
            var w = -DanceRules.SCORE_WINDOW_BEATS
            while (w <= DanceRules.SCORE_WINDOW_BEATS + 1e-4f) {
                val (ex, ez) = path.at(p.beat + w)
                val d = hypot(p.x - ex, p.z - ez)
                if (d < best) best = d
                w += WINDOW_STEP
            }
            err += best
        }
        val meanCm = (err / pts.size * 100.0).toFloat()
        val verdict =
            when {
                ratio < DanceRules.MOVE_RATIO_MIN -> Verdict.BIGGER
                meanCm <= DanceRules.STEADY_CM -> Verdict.STEADY
                meanCm <= DanceRules.OK_CM -> Verdict.OK
                else -> Verdict.SLOWER
            }
        return Score(verdict, meanCm.roundToInt(), ratio, pts.size)
    }

    private companion object {
        const val MIN_SAMPLES = 8
        const val WINDOW_STEP = 0.05f
    }
}
