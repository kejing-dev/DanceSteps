package io.embodit.dancesteps.domain.model

/**
 * The footprint seen from above (0.1.1, the headset run: "two ellipses" read as neither foot): a bare
 * sole with an inner arch and five toes, the big toe on the inside, so a left and a right foot are
 * mirror images and the toes say which way it points. Metres, x right, f ahead (toes), centred on the
 * foot; [FOOT_LENGTH] heel to big-toe tip. The floor meshes and the mirror's map both draw this.
 */
object FootShape {
    const val FOOT_LENGTH = 0.25f
    const val STROKE_M = 0.006f

    /** A toe: ellipse centre (x, f) and half-axes. */
    class Toe(val x: Float, val f: Float, val rx: Float, val rf: Float)

    /** Control points of the left sole without toes, counter-clockwise from the heel (x > 0 = inside). */
    private val leftSoleKeys =
        listOf(
            0.000f to -0.122f,
            0.025f to -0.112f,
            0.033f to -0.085f,
            0.027f to -0.048f,
            0.023f to -0.020f,
            0.032f to 0.014f,
            0.047f to 0.048f,
            0.046f to 0.074f,
            0.031f to 0.087f,
            0.004f to 0.086f,
            -0.024f to 0.078f,
            -0.042f to 0.062f,
            -0.047f to 0.035f,
            -0.043f to 0.000f,
            -0.038f to -0.040f,
            -0.034f to -0.080f,
            -0.025f to -0.110f,
        )

    private val leftToes =
        listOf(
            Toe(0.029f, 0.110f, 0.0150f, 0.0175f),
            Toe(0.004f, 0.106f, 0.0090f, 0.0110f),
            Toe(-0.014f, 0.100f, 0.0082f, 0.0100f),
            Toe(-0.029f, 0.091f, 0.0075f, 0.0090f),
            Toe(-0.042f, 0.078f, 0.0068f, 0.0080f),
        )

    /** The sole outline of the [left] or right foot, a closed polyline of [perKey] points per control point. */
    fun sole(left: Boolean, perKey: Int = 4): List<Pair<Float, Float>> {
        val keys = if (left) leftSoleKeys else leftSoleKeys.map { -it.first to it.second }.reversed()
        val n = keys.size
        val out = ArrayList<Pair<Float, Float>>(n * perKey)
        for (i in 0 until n) {
            val p0 = keys[(i - 1 + n) % n]
            val p1 = keys[i]
            val p2 = keys[(i + 1) % n]
            val p3 = keys[(i + 2) % n]
            for (k in 0 until perKey) {
                val t = k.toFloat() / perKey
                out += catmull(p0.first, p1.first, p2.first, p3.first, t) to catmull(p0.second, p1.second, p2.second, p3.second, t)
            }
        }
        return out
    }

    fun toes(left: Boolean): List<Toe> = if (left) leftToes else leftToes.map { Toe(-it.x, it.f, it.rx, it.rf) }

    /** A point inside the sole from which every outline point is visible (fan triangulation). */
    const val SOLE_CENTER_F = -0.010f

    private fun catmull(a: Float, b: Float, c: Float, d: Float, t: Float): Float {
        val t2 = t * t
        val t3 = t2 * t
        return 0.5f * (2f * b + (-a + c) * t + (2f * a - 5f * b + 4f * c - d) * t2 + (-a + 3f * b - 3f * c + d) * t3)
    }
}
