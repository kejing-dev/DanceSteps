package io.embodit.dancesteps.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The design's exact colours (design package `palette`). This is the only file with colour literals.
 * Four `ColorScheme` roles are fixed here and injected into `PicoTheme` by [DanceStepsTheme]; the
 * other roles inherit SpatialUI Vibrant. The brand tokens are used by the 3D materials and the
 * mirror map canvas, where adaptive colours must not land.
 */
object DanceColors {
    // ---- ColorScheme roles with a fixed design value
    val LabelPrimaryLight = Color(0xFFFFFFFF) // design-style: fixed-figma-color labelPrimaryLight
    val Interaction = Color(0xFFFFC94D) // design-style: fixed-figma-color interaction: your ring, main buttons, selection
    val Passable = Color(0xFF5FD39A) // design-style: fixed-figma-color passable: "steady"
    val Alert = Color(0xFFFF9F43) // design-style: fixed-figma-color alert: cramped edge, "not bad"
    val Error = Color(0xFFFF6B6B) // design-style: fixed-figma-color error: "try slower", unavailable

    // ---- brand tokens (design `palette.brandTokens`)
    val FootLeft = Color(0xFFFF7A59) // design-style: fixed-figma-color footLeft: coral
    val FootRight = Color(0xFF2EC4D6) // design-style: fixed-figma-color footRight: cyan
    val PathGold = Color(0xFFFFC94D) // design-style: fixed-figma-color pathGold: the expected centre route
    val Tape = Color(0xFFFFFFFF) // design-style: fixed-figma-color floorTape: white, 60 % placing / 35 % placed
    val You = Color(0xFFFFFFFF) // design-style: fixed-figma-color you: your dot and trail
    val MapInk = Color(0xFF16181D) // design-style: fixed-figma-color mapInk: opaque map ground on the mirror
    val Ink = Color(0xFF15181D) // design-style: fixed-figma-color ink: text on the interaction fill
}
