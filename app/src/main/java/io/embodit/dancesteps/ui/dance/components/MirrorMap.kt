package io.embodit.dancesteps.ui.dance.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import io.embodit.dancesteps.R
import io.embodit.dancesteps.domain.model.DanceRules
import io.embodit.dancesteps.domain.model.Foot
import io.embodit.dancesteps.domain.model.FootShape
import io.embodit.dancesteps.domain.model.Pattern
import io.embodit.dancesteps.domain.usecase.CenterPathUseCase
import io.embodit.dancesteps.ui.dance.MapSnapshot
import io.embodit.dancesteps.ui.theme.DanceColors
import kotlin.math.sin

/**
 * The map on the mirror (design `panels.mirror.mapOrientation`): the dance floor from above, up = the
 * floor's front (towards this panel), right = your right hand; never mirrored. Opaque mapInk ground
 * (the tertiary fill is translucent and cannot serve as a ground). Draws the dashed floor edge, every
 * footprint (dim; current full, next half under a white edge with the arrow from where that foot stands),
 * the gold expected-centre route with the weight dot, the two ghost feet, your dot and your trail.
 * [snapshot] arrives ten times a second from the scene.
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
            if (spot.index == nextSpot) drawFoot(at(spot.x, spot.z), s, spot.foot == Foot.L, Color.White, fill = false, ghost = true)
        }
        // the arrow: from where the next step's foot stands to where it lands (none for an in-place step)
        val next = snapshot.nextStep
        if (next != null && next in pattern.steps.indices && !pattern.inPlace(next)) {
            val (fx, fz) = pattern.footBefore(next)
            val to = pattern.steps[next]
            drawArrow(at(fx, fz), at(to.x, to.z), if (to.foot == Foot.L) DanceColors.FootLeft else DanceColors.FootRight, s)
        }
    }
    snapshot.weight?.let { (x, z) -> drawCircle(DanceColors.PathGold, radius = DanceRules.WEIGHT_DOT_R * s, center = at(x, z)) }
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

/** A footprint from above ([FootShape]: sole and five toes, big toe inside), toes up. */
internal fun DrawScope.drawFoot(center: Offset, s: Float, left: Boolean, colour: Color, fill: Boolean, ghost: Boolean = false) {
    val style = if (fill) androidx.compose.ui.graphics.drawscope.Fill else Stroke(width = if (ghost) 2.5f else 1.8f)
    val sole = Path()
    FootShape.sole(left).forEachIndexed { i, (x, f) -> if (i == 0) sole.moveTo(center.x + x * s, center.y - f * s) else sole.lineTo(center.x + x * s, center.y - f * s) }
    sole.close()
    drawPath(sole, colour, style = style)
    for (t in FootShape.toes(left)) {
        drawOval(colour, topLeft = Offset(center.x + (t.x - t.rx) * s, center.y - (t.f + t.rf) * s), size = Size(2f * t.rx * s, 2f * t.rf * s), style = style)
    }
}

/** A shaft and a two-legged head from [from] to [to] (screen), stopping short of both footprint centres. */
private fun DrawScope.drawArrow(from: Offset, to: Offset, colour: Color, s: Float) {
    val d = to - from
    val len = d.getDistance()
    if (len < 1f) return
    val dir = d / len
    val a = from + dir * (0.05f * s)
    val b = to - dir * (0.06f * s)
    if ((b - a).getDistance() < 0.06f * s) return
    val w = 4f
    drawLine(colour, a, b, strokeWidth = w)
    val side = Offset(-dir.y, dir.x)
    val head = DanceRules.ARROW_HEAD_M * s
    drawLine(colour, b, b - dir * head + side * head, strokeWidth = w)
    drawLine(colour, b, b - dir * head - side * head, strokeWidth = w)
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

/**
 * One footprint as a glyph (legend, coachmark): [left] in its foot colour, toes up, filling the height.
 * [edge] draws the next-step look instead (half fill under a white edge).
 */
@Composable
internal fun FootGlyph(left: Boolean, modifier: Modifier, edge: Boolean = false) {
    Box(
        modifier =
            modifier.drawBehind {
                val s = size.height / (FootShape.FOOT_LENGTH + 0.02f)
                val c = Offset(size.width / 2f, size.height / 2f + 0.005f * s)
                val colour = if (left) DanceColors.FootLeft else DanceColors.FootRight
                drawFoot(c, s, left, if (edge) colour.copy(alpha = 0.55f) else colour, fill = true)
                if (edge) drawFoot(c, s, left, Color.White, fill = false, ghost = true)
            }
    )
}

/**
 * The legend under the map (0.1.1, always on while the footprints are): left foot, right foot, the next
 * step's white edge, the gold weight dot.
 */
@Composable
internal fun FootLegend(modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val label = PicoTheme.colorScheme.labelSecondary
        val style = PicoTheme.typography.labelMedium
        FootGlyph(left = true, modifier = Modifier.size(10.dp, 22.dp))
        Text(text = rs(R.string.legend_left), color = label, style = style, maxLines = 1, softWrap = false)
        FootGlyph(left = false, modifier = Modifier.size(10.dp, 22.dp))
        Text(text = rs(R.string.legend_right), color = label, style = style, maxLines = 1, softWrap = false)
        FootGlyph(left = true, edge = true, modifier = Modifier.size(10.dp, 22.dp))
        Text(text = rs(R.string.legend_next), color = label, style = style, maxLines = 1, softWrap = false)
        Box(modifier = Modifier.size(10.dp).drawBehind { drawCircle(DanceColors.PathGold) })
        Text(text = rs(R.string.legend_weight), color = label, style = style, maxLines = 1, softWrap = false)
    }
}
