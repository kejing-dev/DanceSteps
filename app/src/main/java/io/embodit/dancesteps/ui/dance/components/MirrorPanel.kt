package io.embodit.dancesteps.ui.dance.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.CircularProgressDefaults
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentControlDefaults
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Text
import io.embodit.dancesteps.R
import io.embodit.dancesteps.domain.model.Pattern
import io.embodit.dancesteps.domain.model.Patterns
import io.embodit.dancesteps.domain.model.Role
import io.embodit.dancesteps.domain.model.Speed
import io.embodit.dancesteps.domain.model.SurfaceKind
import io.embodit.dancesteps.domain.model.Verdict
import io.embodit.dancesteps.domain.usecase.Side
import io.embodit.dancesteps.ui.dance.DanceEvent
import io.embodit.dancesteps.ui.dance.DanceUiState
import io.embodit.dancesteps.ui.dance.Phase
import io.embodit.dancesteps.ui.dance.debug.probe
import io.embodit.dancesteps.ui.dance.scene.DanceScene
import io.embodit.dancesteps.ui.dance.scene.PanelSpec
import io.embodit.dancesteps.ui.theme.DanceColors

/**
 * The mirror (design `panels.mirror`, 640 x 520 dp at scale 1): the only standing panel. Its content
 * is swapped by phase: starting, picker, placing, ready, dancing / count-in, paused, summary. The map
 * reads the scene's ten-hertz snapshot directly, so the rest of the panel does not recompose with it.
 */
@Composable
internal fun MirrorPanel(state: DanceUiState, filled: Boolean, scene: DanceScene, onEvent: (DanceEvent) -> Unit) {
    val (w, h) = PanelSpec.MIRROR_SIZE
    Glass(w.dp, h.dp) {
        if (!filled) return@Glass
        when (state.phase) {
            Phase.STARTING, Phase.BLOCKED -> Starting()
            Phase.PICKER -> Picker(state, onEvent)
            Phase.PLACING -> Placing(state, onEvent)
            Phase.READY -> Ready(state, scene, onEvent)
            Phase.COUNT_IN, Phase.DANCING -> Dancing(state, scene, onEvent)
            Phase.PAUSED -> Paused(state, scene, onEvent)
            Phase.SUMMARY -> Summary(state, scene, onEvent)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// shared bits
// ---------------------------------------------------------------------------------------------

@Composable
private fun patternName(p: Pattern): String =
    when (p.id) {
        Patterns.waltzBox.id -> rs(R.string.pattern_waltzBox)
        Patterns.rumbaBox.id -> rs(R.string.pattern_rumbaBox)
        Patterns.salsaBasic.id -> rs(R.string.pattern_salsaBasic)
        else -> rs(R.string.pattern_chachaBasic)
    }

@Composable
private fun speedName(s: Speed): String =
    when (s) {
        Speed.SLOW -> rs(R.string.speed_slow)
        Speed.MID -> rs(R.string.speed_mid)
        Speed.FULL -> rs(R.string.speed_full)
    }

@Composable
private fun roleName(r: Role): String = if (r == Role.LEADER) rs(R.string.role_leader) else rs(R.string.role_follower)

@Composable
private fun verdictName(v: Verdict): String =
    when (v) {
        Verdict.STEADY -> rs(R.string.verdict_steady)
        Verdict.OK -> rs(R.string.verdict_ok)
        Verdict.SLOWER -> rs(R.string.verdict_slower)
        Verdict.BIGGER -> rs(R.string.verdict_bigger)
    }

@Composable
private fun verdictColor(v: Verdict): Color =
    when (v) {
        Verdict.STEADY -> PicoTheme.colorScheme.passable
        Verdict.OK -> PicoTheme.colorScheme.alert
        Verdict.SLOWER -> PicoTheme.colorScheme.error
        Verdict.BIGGER -> PicoTheme.colorScheme.interaction
    }

/** The count numeral: S / Q of the rumba are words in the current language. */
@Composable
private fun countText(raw: String): String =
    when (raw) {
        "S" -> rs(R.string.count_slow)
        "Q" -> rs(R.string.count_quick)
        else -> raw
    }

@Composable
private fun RoleSegments(state: DanceUiState, onEvent: (DanceEvent) -> Unit, modifier: Modifier = Modifier) {
    Segments(
        items = listOf("role_leader" to rs(R.string.role_leader), "role_follower" to rs(R.string.role_follower)),
        selected = state.role.ordinal,
        onSelect = { onEvent(DanceEvent.RoleSelected(Role.entries[it])) },
        itemWidth = 104.dp,
        modifier = modifier,
    )
}

/** A SegmentControl whose items are [itemWidth] x 48; an item with a null text shows [leadingIcon]. */
@Composable
internal fun Segments(items: List<Pair<String, String?>>, selected: Int, onSelect: (Int) -> Unit, itemWidth: androidx.compose.ui.unit.Dp = 80.dp, leadingIcon: Int? = null, modifier: Modifier = Modifier) {
    SegmentControl(modifier = modifier.width(itemWidth * items.size).height(KeyHeight), backgroundColor = PicoTheme.colorScheme.fillTertiary) {
        items.forEachIndexed { i, (name, text) ->
            val onClick = { onSelect(i) }
            SegmentItem(
                selected = selected == i,
                onClick = onClick,
                modifier = Modifier.width(itemWidth).height(KeyHeight).probe(name, onClick),
                textStyle = PicoTheme.typography.labelLarge,
                colors =
                    SegmentControlDefaults.colors(
                        itemBackgroundColor = Color.Transparent,
                        itemContentColor = PicoTheme.colorScheme.labelPrimary,
                        selectedItemBackgroundColor = PicoTheme.colorScheme.fillSecondary,
                        selectedItemContentColor = PicoTheme.colorScheme.labelPrimary,
                    ),
                title = {
                    if (text == null && leadingIcon != null) {
                        Icon(painter = painterResource(leadingIcon), contentDescription = rs(R.string.lang_system), modifier = Modifier.size(20.dp), tint = PicoTheme.colorScheme.labelPrimary)
                    } else {
                        // design-style: inherited-content-color SegmentItem
                        Text(text = text ?: "", maxLines = 1, softWrap = false, overflow = TextOverflow.Visible)
                    }
                },
            )
        }
    }
}

/** The one big bottom button (design `spatialUiMap.playPause`: Button max, icon + word). */
@Composable
private fun MaxButton(name: String, text: String, iconRes: Int, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, primary: Boolean = true) {
    val container = if (primary) PicoTheme.colorScheme.interaction else PicoTheme.colorScheme.fillTertiary
    val content = if (!enabled) PicoTheme.colorScheme.labelQuaternary else if (primary) DanceColors.Ink else PicoTheme.colorScheme.labelPrimary
    Button(
        onClick = onClick,
        modifier = modifier.height(56.dp).probe(name, onClick),
        size = ButtonDefaults.Regular.copy(height = 56.dp, minWidth = 200.dp),
        shape = CircleShape,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
    ) {
        Icon(painter = painterResource(iconRes), contentDescription = null, modifier = Modifier.size(24.dp), tint = content)
        Spacer(modifier = Modifier.width(10.dp))
        Text(text = text, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis, style = PicoTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
    }
}

@Composable
private fun blinkPhase(): Float {
    val transition = rememberInfiniteTransition(label = "stance")
    val phase by transition.animateFloat(initialValue = 0f, targetValue = 1f, animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart), label = "blink")
    return phase
}

// ---------------------------------------------------------------------------------------------
// layouts
// ---------------------------------------------------------------------------------------------

@Composable
private fun ColumnScope.Starting() {
    TitleRow(title = rs(R.string.app_name))
    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), color = PicoTheme.colorScheme.labelPrimary, progressSize = CircularProgressDefaults.circleProgressSize(28.dp))
            Text(text = rs(R.string.starting), color = PicoTheme.colorScheme.labelSecondary, style = PicoTheme.typography.bodyLarge)
        }
    }
}

/** Picker: title + more, the role segment, four pattern rows (thumbnail, name, meter · steps, last result). */
@Composable
private fun ColumnScope.Picker(state: DanceUiState, onEvent: (DanceEvent) -> Unit) {
    TitleRow(title = rs(R.string.picker_title), trailing = { Key("more", R.drawable.ic_more, rs(R.string.action_more)) { onEvent(DanceEvent.MoreClicked) } })
    RoleSegments(state, onEvent)
    Spacer(modifier = Modifier.height(4.dp))
    for (p in Patterns.all) {
        val last = state.settings.lastOf(p.id)
        val onClick = { onEvent(DanceEvent.PatternClicked(p.id)) }
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().height(76.dp).probe("pattern_${p.id}", onClick),
            size = ButtonDefaults.Regular.copy(height = 76.dp, minWidth = 592.dp),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = PicoTheme.colorScheme.labelPrimary),
        ) {
            PatternThumb(p.forRole(state.role), modifier = Modifier.size(64.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val name = if (p.id == Patterns.chachaBasic.id) patternName(p) + "（" + rs(R.string.pattern_chacha_variant) + "）" else patternName(p)
                Text(text = name, color = PicoTheme.colorScheme.labelPrimary, style = PicoTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                val meta = rs(R.string.pattern_meta, p.meter, p.stepCount)
                val line = if (last != null) meta + " · " + rs(R.string.pattern_last, speedName(last.first), verdictName(last.second.verdict)) else meta
                Text(text = line, color = PicoTheme.colorScheme.labelSecondary, style = DanceType.figure, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(painter = painterResource(R.drawable.ic_chevron_right), contentDescription = null, modifier = Modifier.size(20.dp), tint = PicoTheme.colorScheme.labelSecondary)
        }
    }
}

@Composable
private fun sideName(side: Side): String =
    when (side) {
        Side.LEFT -> rs(R.string.side_left)
        Side.RIGHT -> rs(R.string.side_right)
        Side.FRONT -> rs(R.string.side_front)
        Side.BACK -> rs(R.string.side_back)
    }

@Composable
private fun awayName(side: Side): String =
    when (side) {
        Side.LEFT -> rs(R.string.side_right)
        Side.RIGHT -> rs(R.string.side_left)
        Side.FRONT -> rs(R.string.side_back)
        Side.BACK -> rs(R.string.side_front)
    }

@Composable
private fun whatName(kind: SurfaceKind): String =
    when (kind) {
        SurfaceKind.WALL -> rs(R.string.what_wall)
        SurfaceKind.DOOR -> rs(R.string.what_door)
        SurfaceKind.WINDOW -> rs(R.string.what_window)
        SurfaceKind.TABLE -> rs(R.string.what_table)
        SurfaceKind.CHAIR -> rs(R.string.what_sofa)
        SurfaceKind.BED -> rs(R.string.what_bed)
        SurfaceKind.CABINET -> rs(R.string.what_cabinet)
        else -> rs(R.string.what_thing)
    }

/** Placing: back + name, the instruction with a small picture, the cramped line, the stick hint, "place here". */
@Composable
private fun ColumnScope.Placing(state: DanceUiState, onEvent: (DanceEvent) -> Unit) {
    TitleRow(title = patternName(state.pattern), leading = { Key("back", R.drawable.ic_back, rs(R.string.action_back)) { onEvent(DanceEvent.BackClicked) } })
    Row(modifier = Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        PlacingPicture(modifier = Modifier.size(200.dp, 200.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = rs(R.string.placing_body), color = PicoTheme.colorScheme.labelPrimary, style = PicoTheme.typography.bodyLargeMultiline, maxLines = 4, overflow = TextOverflow.Ellipsis)
            val cramped = state.cramped
            val front = state.frontTooCloseCm
            if (front != null) {
                Text(text = rs(R.string.front_too_close, front), color = PicoTheme.colorScheme.alert, style = PicoTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            } else if (cramped != null) {
                Text(text = rs(R.string.cramped, whatName(cramped.what), sideName(cramped.side), awayName(cramped.side), cramped.moveCm), color = PicoTheme.colorScheme.alert, style = PicoTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painter = painterResource(R.drawable.ic_stick), contentDescription = null, modifier = Modifier.size(20.dp), tint = PicoTheme.colorScheme.labelSecondary)
                Text(text = rs(R.string.placing_stick), color = PicoTheme.colorScheme.labelSecondary, style = DanceType.figure, maxLines = 1)
            }
        }
    }
    MaxButton(
        name = "place_here",
        text = if (state.cramped != null && state.frontTooCloseCm == null) rs(R.string.place_anyway) else rs(R.string.place_here),
        iconRes = R.drawable.ic_check,
        onClick = { onEvent(DanceEvent.PlaceHereClicked) },
        modifier = Modifier.fillMaxWidth(),
        enabled = state.canPlace,
    )
}

/** The small picture: a person in the middle of a dashed square, facing up. */
@Composable
private fun PlacingPicture(modifier: Modifier) {
    val ink = PicoTheme.colorScheme.labelPrimary
    Box(
        modifier =
            modifier.drawBehind {
                val s = size.minDimension
                val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(s * 0.08f, s * 0.04f))
                drawRect(ink.copy(alpha = 0.6f), topLeft = androidx.compose.ui.geometry.Offset(s * 0.1f, s * 0.12f), size = androidx.compose.ui.geometry.Size(s * 0.8f, s * 0.8f), style = Stroke(width = 3f, pathEffect = dash))
                // the chevron at the front edge
                val p = androidx.compose.ui.graphics.Path()
                p.moveTo(s * 0.44f, s * 0.12f)
                p.lineTo(s * 0.5f, s * 0.05f)
                p.lineTo(s * 0.56f, s * 0.12f)
                drawPath(p, ink.copy(alpha = 0.6f), style = Stroke(width = 3f))
                // head and shoulders from above
                drawCircle(ink, radius = s * 0.07f, center = androidx.compose.ui.geometry.Offset(s * 0.5f, s * 0.52f))
                drawOval(ink.copy(alpha = 0.5f), topLeft = androidx.compose.ui.geometry.Offset(s * 0.34f, s * 0.47f), size = androidx.compose.ui.geometry.Size(s * 0.32f, s * 0.1f))
                // the two feet
                drawOval(DanceColors.FootLeft, topLeft = androidx.compose.ui.geometry.Offset(s * 0.40f, s * 0.60f), size = androidx.compose.ui.geometry.Size(s * 0.07f, s * 0.14f))
                drawOval(DanceColors.FootRight, topLeft = androidx.compose.ui.geometry.Offset(s * 0.53f, s * 0.60f), size = androidx.compose.ui.geometry.Size(s * 0.07f, s * 0.14f))
            }
    )
}

/** Ready: back · name · more; map left; role, speed + BPM, watch once right; start at the bottom. */
@Composable
private fun ColumnScope.Ready(state: DanceUiState, scene: DanceScene, onEvent: (DanceEvent) -> Unit) {
    TitleRow(
        title = patternName(state.pattern),
        leading = { Key("back", R.drawable.ic_back, rs(R.string.action_back)) { onEvent(DanceEvent.BackClicked) } },
        trailing = { Key("more", R.drawable.ic_more, rs(R.string.action_more)) { onEvent(DanceEvent.MoreClicked) } },
    )
    Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        MirrorMap(state.danced, scene.map.value, modifier = Modifier.fillMaxHeight().width(340.dp), stance = true, blinkPhase = blinkPhase())
        Column(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RoleSegments(state, onEvent)
            Segments(
                items = listOf("speed_slow" to rs(R.string.speed_slow), "speed_mid" to rs(R.string.speed_mid), "speed_full" to rs(R.string.speed_full)),
                selected = state.speed.ordinal,
                onSelect = { onEvent(DanceEvent.SpeedSelected(Speed.entries[it])) },
                itemWidth = 70.dp,
            )
            Text(text = rs(R.string.bpm, state.bpm) + " · " + state.pattern.meter, color = PicoTheme.colorScheme.labelSecondary, style = DanceType.figure, maxLines = 1)
            Spacer(modifier = Modifier.weight(1f))
            WordButton(
                name = "preview",
                text = if (state.preview) rs(R.string.stop) else rs(R.string.watch_once),
                onClick = { onEvent(DanceEvent.PreviewClicked) },
                iconRes = if (state.preview) R.drawable.ic_stop else R.drawable.ic_eye,
                minWidth = 160.dp,
            )
        }
    }
    MaxButton(name = "start", text = rs(R.string.start), iconRes = R.drawable.ic_play, onClick = { onEvent(DanceEvent.StartClicked) }, modifier = Modifier.fillMaxWidth(), enabled = !state.preview && !state.lost)
}

/** Dancing and count-in: name · role · meter · BPM; map left; numeral, round, beat dots right; pause + restart. */
@Composable
private fun ColumnScope.Dancing(state: DanceUiState, scene: DanceScene, onEvent: (DanceEvent) -> Unit) {
    val run = state.run
    val countingIn = state.phase == Phase.COUNT_IN
    val subtitle = if (countingIn) rs(R.string.get_ready) else roleName(state.role) + " · " + state.pattern.meter + " · " + rs(R.string.bpm, state.bpm)
    TitleRow(title = patternName(state.pattern) + "  ·  " + subtitle)
    Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        MirrorMap(state.danced, scene.map.value, modifier = Modifier.fillMaxHeight().width(340.dp))
        RunColumn(state, modifier = Modifier.weight(1f).fillMaxHeight())
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        MaxButton(name = "pause", text = rs(R.string.pause), iconRes = R.drawable.ic_pause, onClick = { onEvent(DanceEvent.PauseClicked) }, modifier = Modifier.weight(1f), primary = false)
        Key("restart", R.drawable.ic_restart, rs(R.string.restart)) { onEvent(DanceEvent.RestartClicked) }
    }
}

/** The numeral, "round n / 8" and the beat dots (design `panels.mirror.layouts.dancing`). */
@Composable
private fun RunColumn(state: DanceUiState, modifier: Modifier) {
    val run = state.run
    val countingIn = state.phase == Phase.COUNT_IN
    val p = state.pattern
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        val text = run?.countText?.let { countText(it) } ?: ""
        Text(
            text = text,
            color = PicoTheme.colorScheme.labelPrimary,
            style = PicoTheme.typography.displayLarge.copy(fontSize = 120.sp, lineHeight = 120.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum, lnum"),
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
            modifier = Modifier.height(130.dp).probe("count"),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (countingIn) rs(R.string.get_ready) else rs(R.string.loop_of, (run?.loop ?: 0) + 1, run?.loopsTotal ?: 8),
            color = PicoTheme.colorScheme.labelSecondary,
            style = DanceType.figure,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.height(16.dp))
        // beat dots: one per beat of the bar, the accent one bigger, the current one filled
        val beatInBar = if (run == null) -1 else if (countingIn) run.beatInLoop % p.beatsPerBar else run.beatInLoop % p.beatsPerBar
        val ink = PicoTheme.colorScheme.labelPrimary
        val dim = PicoTheme.colorScheme.labelSecondary
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            for (i in 0 until p.beatsPerBar) {
                val r = if (i == 0) 9.dp else 7.dp
                val on = i == beatInBar
                Box(modifier = Modifier.size(r * 2).drawBehind { if (on) drawCircle(ink) else drawCircle(dim, style = Stroke(width = 2f)) })
            }
        }
    }
}

/** Paused: like dancing, with back and more, and resume · restart · finish (after two loops). */
@Composable
private fun ColumnScope.Paused(state: DanceUiState, scene: DanceScene, onEvent: (DanceEvent) -> Unit) {
    TitleRow(
        title = patternName(state.pattern) + "  ·  " + roleName(state.role) + " · " + rs(R.string.bpm, state.bpm),
        leading = { Key("back", R.drawable.ic_back, rs(R.string.action_back)) { onEvent(DanceEvent.BackClicked) } },
        trailing = { Key("more", R.drawable.ic_more, rs(R.string.action_more)) { onEvent(DanceEvent.MoreClicked) } },
    )
    Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        MirrorMap(state.danced, scene.map.value, modifier = Modifier.fillMaxHeight().width(340.dp), stance = true, blinkPhase = blinkPhase())
        RunColumn(state, modifier = Modifier.weight(1f).fillMaxHeight())
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        MaxButton(name = "resume", text = rs(R.string.resume), iconRes = R.drawable.ic_play, onClick = { onEvent(DanceEvent.ResumeClicked) }, modifier = Modifier.weight(1f), enabled = !state.lost)
        Key("restart", R.drawable.ic_restart, rs(R.string.restart)) { onEvent(DanceEvent.RestartClicked) }
        if (state.canEndEarly) WordButton("end_early", rs(R.string.end_early), { onEvent(DanceEvent.EndEarlyClicked) }, minWidth = 150.dp, iconRes = R.drawable.ic_flag)
    }
}

/** Summary: route + whole trail left; verdict, deviation, speed · rounds right; again · faster · change step. */
@Composable
private fun ColumnScope.Summary(state: DanceUiState, scene: DanceScene, onEvent: (DanceEvent) -> Unit) {
    val r = state.summary
    TitleRow(
        title = patternName(state.pattern),
        leading = { Key("back", R.drawable.ic_back, rs(R.string.action_back)) { onEvent(DanceEvent.BackClicked) } },
        trailing = { Key("more", R.drawable.ic_more, rs(R.string.action_more)) { onEvent(DanceEvent.MoreClicked) } },
    )
    Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        MirrorMap(state.danced, scene.map.value, modifier = Modifier.fillMaxHeight().width(340.dp), showFeet = false)
        Column(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            if (r != null) {
                Text(text = verdictName(r.verdict), color = verdictColor(r.verdict), style = PicoTheme.typography.headlineLarge.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.probe("verdict"))
                Spacer(modifier = Modifier.height(12.dp))
                if (r.verdict != Verdict.BIGGER) {
                    Text(text = rs(R.string.deviation, r.devCm), color = PicoTheme.colorScheme.labelPrimary, style = PicoTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum, lnum"), maxLines = 1)
                    Spacer(modifier = Modifier.height(6.dp))
                }
                Text(text = rs(R.string.summary_meta, speedName(r.speed), r.loops), color = PicoTheme.colorScheme.labelSecondary, style = DanceType.figure, maxLines = 1)
            }
        }
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        MaxButton(name = "again", text = rs(R.string.again), iconRes = R.drawable.ic_play, onClick = { onEvent(DanceEvent.AgainClicked) }, modifier = Modifier.weight(1f))
        if (state.canGoFaster) WordButton("faster", rs(R.string.faster), { onEvent(DanceEvent.FasterClicked) }, minWidth = 120.dp, iconRes = R.drawable.ic_faster)
        WordButton("change_pattern", rs(R.string.change_pattern), { onEvent(DanceEvent.ChangePatternClicked) }, minWidth = 120.dp)
    }
}
