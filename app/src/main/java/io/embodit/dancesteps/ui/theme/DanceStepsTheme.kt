package io.embodit.dancesteps.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.systemColorScheme

/**
 * The app theme: the design's colour scheme and the Noto Sans type family. Five roles carry exact
 * design values; the adaptive roles are forwarded from the system (Vibrant) scheme on purpose.
 * Text on glass uses `labelPrimary`; on solid faces `labelPrimaryLight`.
 */
@Composable
fun DanceStepsTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val system = systemColorScheme(context)
    val colors =
        remember(system) {
            system.copy(
                labelPrimaryLight = DanceColors.LabelPrimaryLight,
                error = DanceColors.Error,
                alert = DanceColors.Alert,
                passable = DanceColors.Passable,
                interaction = DanceColors.Interaction,
            )
        }
    val family = rememberDanceFontFamily()
    val base = PicoTheme.typography
    val typography = remember(base, family) { base.withFamily(family) }
    PicoTheme(colorScheme = colors, typography = typography, content = content)
}
