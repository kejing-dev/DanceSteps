package io.embodit.dancesteps.ui.dance

/**
 * What the mirror's map draws, in pattern coordinates (floor metres, x right, z ahead, origin at the
 * middle of the start stance). Pushed by the scene ten times a second; never per frame.
 */
data class MapSnapshot(
    val you: Pair<Float, Float>? = null,
    val ghostL: Pair<Float, Float>? = null,
    val ghostR: Pair<Float, Float>? = null,
    val currentStep: Int? = null,
    val nextStep: Int? = null,
    /** Beat of the loop under way, or -1 outside a run. */
    val beatInLoop: Float = -1f,
    val trail: List<Pair<Float, Float>> = emptyList(),
)
