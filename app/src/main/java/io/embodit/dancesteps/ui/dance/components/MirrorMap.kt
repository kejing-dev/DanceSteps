package io.embodit.dancesteps.ui.dance.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import io.embodit.dancesteps.domain.model.DanceRules
import io.embodit.dancesteps.domain.model.Foot
import io.embodit.dancesteps.domain.model.Pattern
import io.embodit.dancesteps.domain.usecase.CenterPathUseCase
import io.embodit.dancesteps.ui.dance.MapSnapshot
import io.embodit.dancesteps.ui.theme.DanceColors
import kotlin.math.sin

/**
 * The map on the mirror (design `panels.mirror.mapOrientation`): the dance floor from above, up = the
 * floor's front (towards this panel), right = your right hand; never mirrored. Opaque mapInk ground
 * (the tertiary fill is translucent and cannot serve as a ground). Draws the dashed floor edge, every
 * footprint (dim; current full, next half), the gold expected-centre route, the two ghost feet, your
 * dot and your trail. [snapshot] arrives ten times a second from the scene.
 *
 * @param showFeet false in the summary (route and trail only)
 * @param stance the blinking start stance (ready / paused)
 * @param blinkPhase 0..1 for the stance blink
 */
@Composable
internal fun MirrorMap(
    pattern: Pattern,
    snapshot: MapSnapshot,
    modifier: Modifier,
    showFeet: Boolean = true,
    stance: Boolean = false,
    blinkPhase: Float = 0f,
    showRoute: Boolean = true,
) {
    Box(
        modifier =
            modifier.clip(RoundedCornerShape(16.dp)).drawBehind {
                drawRect(DanceColors.MapInk)
                drawMap(pattern, snapshot, showFeet, stance, blinkPhase, showRoute)
            }
    )
}

private fun DrawScope.drawMap(pattern: Pattern, snapshot: MapSnapshot, showFeet: Boolean, stance: Boolean, blinkPhase: Float, showRoute: Boolean) {
    // 1.5 m floor plus a little air fills the square; pattern coordinates are centred on the floor
    val extent = DanceRules.FLOOR_SIZE_M + 0.2f
    val s = minOf(size.width, size.height) / extent
    val cx = size.width / 2f
    val cy = size.height / 2f
    val (ox, oz) = pattern.boundsCenter
    fun at(x: Float, z: Float) = Offset(cx + (x - ox) * s, cy - (z - oz) * s)

    // floor edge: dashed tape
    val h = DanceRules.FLOOR_HALF_M
    val tape = Color.White.copy(alpha = 0.45f)
    val dash = PathEffect.dashPathEffect(floatArrayOf(DanceRules.TAPE_DASH_M * s, DanceRules.TAPE_GAP_M * s))
    val tl = Offset(cx - h * s, cy - h * s)
    drawRect(tape, topLeft = tl, size = Size(2 * h * s, 2 * h * s), style = Stroke(width = 3f, pathEffect = dash))
    // the "front" chevron at the top edge
    val chevron = Path()
    chevron.moveTo(cx - 0.1f * s, cy - h * s)
    chevron.lineTo(cx, cy - (h + 0.08f) * s)
    chevron.lineTo(cx + 0.1f * s, cy - h * s)
    drawPath(chevron, tape, style = Stroke(width = 3f))

    // the route
    if (showRoute) {
        val pts = CenterPathUseCase(pattern).sample(12)
        val route = Path()
        pts.forEachIndexed { i, (x, z) -> val p = at(x, z); if (i == 0) route.moveTo(p.x, p.y) else route.lineTo(p.x, p.y) }
        route.close()
        drawPath(route, DanceColors.PathGold, style = Stroke(width = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(DanceRules.PATH_DASH_M * s, DanceRules.PATH_GAP_M * s))))
    }

    // footprints
    if (showFeet) {
        val currentSpot = snapshot.currentStep?.let { pattern.spotOf(it) }
        val nextSpot = snapshot.nextStep?.let { pattern.spotOf(it) }
        for (spot in pattern.spots) {
            val colour = if (spot.foot == Foot.L) DanceColors.FootLeft else DanceColors.FootRight
            val alpha =
                when {
                    spot.index == currentSpot -> 1f
                    spot.index == nextSpot -> 0.55f
                    else -> 0.22f
                }
            drawFoot(at(spot.x, spot.z), s, spot.foot == Foot.L, colour.copy(alpha = alpha), fill = spot.index == currentSpot || spot.index == nextSpot)
        }
    }
    if (stance) {
        val a = 0.4f + 0.6f * (0.5f + 0.5f * sin(blinkPhase * 2f * Math.PI.toFloat()))
        drawFoot(at(pattern.startL.first, pattern.startL.second), s, true, DanceColors.FootLeft.copy(alpha = a), fill = false)
        drawFoot(at(pattern.startR.first, pattern.startR.second), s, false, DanceColors.FootRight.copy(alpha = a), fill = false)
    }

    // the ghost feet: solid, a shade lighter, where the dancer's feet should be now
    if (snapshot.beatInLoop >= 0f) {
        snapshot.ghostL?.let { drawFoot(at(it.first, it.second), s, true, DanceColors.FootLeft.copy(alpha = 0.9f), fill = true, ghost = true) }
        snapshot.ghostR?.let { drawFoot(at(it.first, it.second), s, false, DanceColors.FootRight.copy(alpha = 0.9f), fill = true, ghost = true) }
    }

    // the trail and you
    if (snapshot.trail.size > 1) {
        val path = Path()
        snapshot.trail.forEachIndexed { i, (x, z) -> val p = at(x, z); if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
        drawPath(path, DanceColors.You.copy(alpha = 0.8f), style = Stroke(width = 2.5f))
    }
    snapshot.you?.let { (x, z) ->
        val p = at(x, z)
        drawCircle(DanceColors.You, radius = DanceRules.YOU_DOT_R * s * 0.8f, center = p)
        drawCircle(DanceColors.Interaction, radius = DanceRules.YOU_DOT_R * s * 0.8f + 3f, center = p, style = Stroke(width = 2f))
    }
}

/** A footprint from above: forefoot and heel ellipses, toes up. */
private fun DrawScope.drawFoot(center: Offset, s: Float, left: Boolean, colour: Color, fill: Boolean, ghost: Boolean = false) {
    val padW = DanceRules.PAD_W * s
    val padL = DanceRules.PAD_L * s
    val heelW = DanceRules.HEEL_W * s
    val heelL = DanceRules.HEEL_L * s
    val len = DanceRules.FOOT_LENGTH * s
    val toe = (if (left) DanceRules.BIG_TOE_OFFSET else -DanceRules.BIG_TOE_OFFSET) * s
    val padCy = -(len / 2f - padL / 2f)
    val heelCy = len / 2f - heelL / 2f
    val style = if (fill) androidx.compose.ui.graphics.drawscope.Fill else Stroke(width = if (ghost) 2.5f else 1.8f)
    translate(center.x, center.y) {
        rotate(0f, Offset.Zero) {
            drawOval(colour, topLeft = Offset(toe - padW / 2f, padCy - padL / 2f), size = Size(padW, padL), style = style)
            drawOval(colour, topLeft = Offset(-heelW / 2f, heelCy - heelL / 2f), size = Size(heelW, heelL), style = style)
        }
    }
}

/** The 64 x 64 thumbnail of a pattern on its picker row: the footprints from above. */
@Composable
internal fun PatternThumb(pattern: Pattern, modifier: Modifier) {
    Box(
        modifier =
            modifier.clip(RoundedCornerShape(12.dp)).drawBehind {
                drawRect(DanceColors.MapInk)
                val s = minOf(size.width, size.height) / 1.1f
                val (ox, oz) = pattern.boundsCenter
                fun at(x: Float, z: Float) = Offset(size.width / 2f + (x - ox) * s, size.height / 2f - (z - oz) * s)
                for (spot in pattern.spots) {
                    val colour = if (spot.foot == Foot.L) DanceColors.FootLeft else DanceColors.FootRight
                    drawFoot(at(spot.x, spot.z), s, spot.foot == Foot.L, colour.copy(alpha = 0.9f), fill = true)
                }
            }
    )
}
