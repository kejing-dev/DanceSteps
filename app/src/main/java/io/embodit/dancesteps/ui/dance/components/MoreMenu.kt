package io.embodit.dancesteps.ui.dance.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.platform.Material
import io.embodit.dancesteps.R
import io.embodit.dancesteps.domain.model.LanguagePref
import io.embodit.dancesteps.ui.dance.DanceEvent
import io.embodit.dancesteps.ui.dance.DanceUiState
import io.embodit.dancesteps.ui.dance.Phase
import io.embodit.dancesteps.ui.dance.scene.PanelSpec

/**
 * The More menu (design `panels.moreMenu`, a pre-built attachment panel below the More key): language
 * segment (acts in place, the menu stays), "place the floor again", exit. No count voice row: no licensed
 * voice samples ship with v1, so the switch does not appear (design `audio.voice`).
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
        val canReplace = state.phase == Phase.PICKER || state.phase == Phase.READY || state.phase == Phase.PAUSED || state.phase == Phase.SUMMARY
        WordButton("menu_replace", rs(R.string.menu_replace), { onEvent(DanceEvent.ReplaceFloorClicked) }, modifier = Modifier.fillMaxWidth(), enabled = canReplace, iconRes = R.drawable.ic_floor)
        WordButton("menu_exit", rs(R.string.menu_exit), { onEvent(DanceEvent.ExitClicked) }, modifier = Modifier.fillMaxWidth(), iconRes = R.drawable.ic_exit)
    }
}
