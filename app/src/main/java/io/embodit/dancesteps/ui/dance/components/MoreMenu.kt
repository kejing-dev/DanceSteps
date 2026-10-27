package io.embodit.dancesteps.ui.dance.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.text.style.TextOverflow
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.platform.Material
import io.embodit.dancesteps.R
import io.embodit.dancesteps.domain.model.LanguagePref
import io.embodit.dancesteps.ui.dance.DanceEvent
import io.embodit.dancesteps.ui.dance.DanceUiState
import io.embodit.dancesteps.ui.dance.Phase
import io.embodit.dancesteps.ui.dance.scene.PanelSpec

/**
 * The More menu (design `panels.moreMenu`, a pre-built attachment panel below the More key): language
 * segment (acts in place, the menu stays), the step sound on / off and the floor height keys (0.1.1; both act in
 * place), "place the floor again", exit. No count voice row: no licensed voice samples ship with v1, so the
 * switch does not appear (design `audio.voice`).
 */
@Composable
internal fun MoreMenu(state: DanceUiState, filled: Boolean, onEvent: (DanceEvent) -> Unit) {
    val (w, h) = PanelSpec.MENU_SIZE
    Glass(w.dp, h.dp, corner = 24.dp, material = Material.Thick, padding = 12.dp) {
        if (!filled) return@Glass
        Row(modifier = Modifier.fillMaxWidth().height(KeyHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Segments(
                items = listOf("lang_system" to null, "lang_zh" to rs(R.string.lang_zh), "lang_en" to rs(R.string.lang_en)),
                selected = state.language.ordinal,
                onSelect = { onEvent(DanceEvent.LanguageSelected(LanguagePref.entries[it])) },
                itemWidth = 76.dp,
                leadingIcon = R.drawable.ic_globe,
            )
        }
        WordButton(
            "menu_step_sound",
            if (state.settings.stepSound) rs(R.string.menu_step_sound_on) else rs(R.string.menu_step_sound_off),
            { onEvent(DanceEvent.StepSoundToggled) },
            modifier = Modifier.fillMaxWidth(),
            iconRes = R.drawable.ic_feet,
        )
        Row(modifier = Modifier.fillMaxWidth().height(KeyHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = rs(R.string.menu_floor) + figureCm(state.floorNudge),
                color = PicoTheme.colorScheme.labelPrimary,
                style = DanceType.figure,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 12.dp),
            )
            Key("floor_down", R.drawable.ic_down, rs(R.string.floor_down)) { onEvent(DanceEvent.FloorNudged(-1)) }
            Key("floor_up", R.drawable.ic_up, rs(R.string.floor_up)) { onEvent(DanceEvent.FloorNudged(1)) }
        }
        val canReplace = state.phase == Phase.PICKER || state.phase == Phase.READY || state.phase == Phase.PAUSED || state.phase == Phase.SUMMARY
        WordButton("menu_replace", rs(R.string.menu_replace), { onEvent(DanceEvent.ReplaceFloorClicked) }, modifier = Modifier.fillMaxWidth(), enabled = canReplace, iconRes = R.drawable.ic_floor)
        WordButton("menu_exit", rs(R.string.menu_exit), { onEvent(DanceEvent.ExitClicked) }, modifier = Modifier.fillMaxWidth(), iconRes = R.drawable.ic_exit)
    }
}

/** " +4 cm" / " -2 cm" after the floor-height label; nothing at zero. */
private fun figureCm(m: Float): String {
    val cm = Math.round(m * 100f)
    return if (cm == 0) "" else if (cm > 0) "  +$cm cm" else "  $cm cm"
}
