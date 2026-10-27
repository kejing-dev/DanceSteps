package io.embodit.dancesteps.ui.dance.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.CircularProgressDefaults
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.dancesteps.R
import io.embodit.dancesteps.domain.model.BlockReason
import io.embodit.dancesteps.ui.dance.DanceEvent
import io.embodit.dancesteps.ui.dance.SnackKind
import io.embodit.dancesteps.ui.dance.debug.probe
import io.embodit.dancesteps.ui.dance.scene.PanelSpec
import kotlin.math.abs

private const val FADE_IN_MS = 150

/**
 * "Spatial features unavailable" (design `panels.blockedDialog`): a self-built attachment panel
 * (480 x 280 dp at 1.125, 0.9 m ahead) with icon, title, one sentence, exit and retry. In a Stage the
 * SpatialUI `AlertDialog` composes nothing (Spatial Ruler finding).
 */
@Composable
internal fun BlockedDialog(reason: BlockReason?, onEvent: (DanceEvent) -> Unit) {
    val (w, h) = PanelSpec.DIALOG_SIZE
    Box(modifier = Modifier.size(w.dp, h.dp), contentAlignment = Alignment.Center) {
        if (reason == null) return@Box
        val fade = remember(reason) { Animatable(0f) }
        LaunchedEffect(reason) { fade.animateTo(1f, tween(FADE_IN_MS)) }
        val ink = PicoTheme.colorScheme.labelPrimary
        Column(
            modifier =
                Modifier
                    .width(400.dp)
                    .heightIn(max = h.dp)
                    .graphicsLayer { alpha = fade.value }
                    .clip(RoundedCornerShape(32.dp))
                    .backgroundMaterial(enable = true, style = Material.Thick)
                    .padding(32.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(painter = painterResource(R.drawable.ic_unavailable), contentDescription = null, modifier = Modifier.size(32.dp), tint = PicoTheme.colorScheme.error)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = rs(R.string.blocked_title), color = ink, style = PicoTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(text = rs(R.string.blocked_body), color = PicoTheme.colorScheme.labelSecondary, style = PicoTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            Row(modifier = Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                WordButton("dialog_exit", rs(R.string.action_exit), { onEvent(DanceEvent.ExitClicked) }, minWidth = 120.dp, iconRes = R.drawable.ic_exit)
                WordButton("dialog_retry", rs(R.string.action_retry), { onEvent(DanceEvent.RetryClicked) }, primary = true, minWidth = 120.dp, iconRes = R.drawable.ic_retry)
            }
        }
    }
}

@Composable
private fun snackText(kind: SnackKind): String =
    when (kind) {
        SnackKind.SCANNING -> rs(R.string.snack_scanning)
        SnackKind.VIRTUAL_FLOOR -> rs(R.string.snack_virtual_floor)
        SnackKind.OFF_FLOOR -> rs(R.string.snack_off_floor)
        SnackKind.LOST -> rs(R.string.snack_lost)
        SnackKind.NOT_ON_START -> rs(R.string.snack_not_on_start)
    }

/**
 * The snackbar (design `panels.snackbar`): an attachment panel 0.04 m above the mirror's top edge, at
 * most 520 dp wide; icon or spinner + one line. The capsule takes the width of its message inside the
 * fixed-size panel ([applied]: the scene squeezes the panel to the capsule's width and the capsule is
 * drawn stretched back).
 */
@Composable
internal fun SnackPanel(snack: SnackKind?, applied: Float = 1f, onWidth: (Float) -> Unit = {}) {
    val (w, h) = PanelSpec.SNACK_SIZE
    Box(modifier = Modifier.size(w.dp, h.dp), contentAlignment = Alignment.Center) {
        if (snack == null) return@Box
        val density = LocalDensity.current
        val panelPx = with(density) { w.dp.toPx() }
        val ink = PicoTheme.colorScheme.labelPrimary
        val fade = remember(snack) { Animatable(0f) }
        LaunchedEffect(snack) { fade.animateTo(1f, tween(FADE_IN_MS)) }
        Row(
            modifier =
                Modifier
                    .widthIn(min = 240.dp, max = w.dp)
                    .defaultMinSize(minHeight = 56.dp)
                    .onSizeChanged { onWidth(it.width / density.density) }
                    .graphicsLayer {
                        val own = size.width / panelPx
                        scaleX = 1f / applied
                        alpha = if (abs(applied - own) <= own * 0.12f) fade.value else 0f
                    }
                    .clip(RoundedCornerShape(28.dp))
                    .backgroundMaterial(enable = true, style = Material.Thick)
                    .probe("snackbar")
                    .padding(start = 16.dp, top = 8.dp, end = 20.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (snack == SnackKind.SCANNING) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = ink, progressSize = CircularProgressDefaults.circleProgressSize(20.dp))
            } else {
                Icon(
                    painter =
                        painterResource(
                            when (snack) {
                                SnackKind.LOST -> R.drawable.ic_unavailable
                                SnackKind.OFF_FLOOR, SnackKind.VIRTUAL_FLOOR -> R.drawable.ic_floor
                                SnackKind.NOT_ON_START -> R.drawable.ic_feet
                                else -> R.drawable.ic_check
                            }
                        ),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = ink,
                )
            }
            Text(
                text = snackText(snack),
                color = ink,
                style = PicoTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * First-run coachmark (design `panels.coach`; 0.1.1: 520 x 136 dp) beside the mirror: the footprint legend.
 * A left and a right footprint as on the floor, "these are your left / right feet, step on the lit one",
 * "just watch this mirror", got it.
 */
@Composable
internal fun CoachPanel(shown: Boolean, onEvent: (DanceEvent) -> Unit) {
    val (w, h) = PanelSpec.COACH_SIZE
    Box(modifier = Modifier.size(w.dp, h.dp), contentAlignment = Alignment.Center) {
        if (!shown) return@Box
        val fade = remember { Animatable(0f) }
        LaunchedEffect(Unit) { fade.animateTo(1f, tween(FADE_IN_MS)) }
        Row(
            modifier =
                Modifier
                    .size(w.dp, h.dp)
                    .graphicsLayer { alpha = fade.value }
                    .clip(RoundedCornerShape(24.dp))
                    .backgroundMaterial(enable = true, style = Material.Thick)
                    .probe("coach")
                    .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FootGlyph(left = true, modifier = Modifier.size(32.dp, 72.dp))
                FootGlyph(left = false, modifier = Modifier.size(32.dp, 72.dp))
            }
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = rs(R.string.coach_legend),
                    color = PicoTheme.colorScheme.labelPrimary,
                    style = PicoTheme.typography.bodyLargeMultiline.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = rs(R.string.coach_mirror),
                    color = PicoTheme.colorScheme.labelSecondary,
                    style = PicoTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            WordButton("coach_got_it", rs(R.string.action_got_it), { onEvent(DanceEvent.CoachDismissed) }, primary = true, minWidth = 88.dp)
        }
    }
}
