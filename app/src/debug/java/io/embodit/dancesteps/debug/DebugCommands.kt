package io.embodit.dancesteps.debug

import android.content.Context
import io.embodit.dancesteps.domain.model.LanguagePref
import io.embodit.dancesteps.domain.model.Role
import io.embodit.dancesteps.domain.model.Speed
import io.embodit.dancesteps.domain.model.Verdict
import io.embodit.dancesteps.platform.DanceLog
import io.embodit.dancesteps.ui.dance.DanceEvent
import io.embodit.dancesteps.ui.dance.DebugWalk
import io.embodit.dancesteps.ui.dance.debug.DanceProbe
import java.io.File
import java.util.Locale

/**
 * QA hooks of the debug build (design `qaHooks`). One log line (tag `DanceSteps`) answers every command.
 *
 *   adb shell "am broadcast -a io.embodit.dancesteps.DEBUG -p io.embodit.dancesteps --es cmd 'state dancing+offFloor:follower'"
 *
 * - `state <name>`              straight to a designed state: starting, picker, placing, ready, countIn, dancing, paused,
 *                               summary, blocked; overlays after `+`: scanning, cramped, front, notOnStart, preview, offFloor,
 *                               lost, menu, coach; `:follower` selects the follower steps (e.g. `state ready+coach`)
 * - `place`                     puts the dance floor under the head now (as "place here" would)
 * - `pattern <id>`              waltzBox | rumbaBox | salsaBasic | chachaBasic (as tapping its row)
 * - `role leader|follower`, `speed slow|mid|full`
 * - `start`, `pause`, `resume`, `restart`, `end`, `preview`, `again`, `faster`
 * - `walk_late|walk_noisy|walk_offset|stand_still`   a synthetic dancer runs all 8 loops (checks the verdict); `walk off` stops
 * - `walk_x`                    the you-dot walks +x 0.5 m over 2 s (the map's dot must move right)
 * - `lost` / `found`            tracking lost forced / released
 * - `coach_reset`               the first-run coachmark shows again
 * - `summary steady|ok|slower|bigger [cm]`   the summary with that verdict
 * - `lang system|zh|en`, `menu`, `replace`, `back`, `exit`
 * - `ui <control>`              presses a panel control by name (DanceProbe names: pattern_<id>, role_leader, role_follower,
 *                               speed_slow, speed_mid, speed_full, place_here, back, more, start, preview, pause, resume,
 *                               restart, end_early, again, faster, change_pattern, menu_replace, menu_exit, lang_*,
 *                               coach_got_it, dialog_exit, dialog_retry)
 * - `dump`                      the model as one JSON line (`DUMP {...}`), also written to files/dump.json
 * - `pose`, `bounds`, `planes`, `park`, `fps on|off`
 */
internal object DebugCommands {

    fun run(context: Context, raw: String, s: String?) {
        val tokens = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return
        val name = tokens.first().lowercase(Locale.US)
        val rest = tokens.drop(1)
        val text = (s ?: rest.joinToString(" ")).trim()
        val host = DanceProbe.host
        if (host == null) {
            DanceLog.i("CMD $name ignored: the Stage is not running")
            return
        }
        try {
            dispatch(context, host, name, text)
        } catch (t: Throwable) {
            DanceLog.e("CMD $name failed: $t", t)
        }
    }

    private fun dispatch(context: Context, host: DanceProbe.Host, name: String, text: String) {
        val scene = host.scene()
        val word = text.lowercase(Locale.US)
        when (name) {
            "state" -> host.dispatch(DanceEvent.DebugState(text))
            "place" -> host.dispatch(DanceEvent.DebugPlace)
            "pattern" -> host.dispatch(DanceEvent.PatternClicked(text))
            "role" -> host.dispatch(DanceEvent.RoleSelected(Role.fromTag(word)))
            "speed" -> host.dispatch(DanceEvent.SpeedSelected(Speed.fromTag(word)))
            "start" -> host.dispatch(DanceEvent.StartClicked)
            "pause" -> host.dispatch(DanceEvent.PauseClicked)
            "resume" -> host.dispatch(DanceEvent.ResumeClicked)
            "restart" -> host.dispatch(DanceEvent.RestartClicked)
            "end" -> host.dispatch(DanceEvent.EndEarlyClicked)
            "preview" -> host.dispatch(DanceEvent.PreviewClicked)
            "again" -> host.dispatch(DanceEvent.AgainClicked)
            "faster" -> host.dispatch(DanceEvent.FasterClicked)
            "walk_late", "walk_noisy", "walk_offset", "stand_still" -> host.dispatch(DanceEvent.DebugWalkSet(DebugWalk.fromTag(name)))
            "walk" -> host.dispatch(DanceEvent.DebugWalkSet(DebugWalk.fromTag(word)))
            "walk_x" -> host.dispatch(DanceEvent.DebugWalkX)
            "lost" -> host.dispatch(DanceEvent.DebugTracking(true))
            "found" -> host.dispatch(DanceEvent.DebugTracking(false))
            "tracking" -> host.dispatch(DanceEvent.DebugTracking(if (word == "lost") true else if (word == "ok") false else null))
            "coach_reset", "coach" -> host.dispatch(DanceEvent.DebugCoachReset)
            "summary" -> {
                val parts = word.split(Regex("\\s+"))
                val verdict = Verdict.fromTag(parts.firstOrNull()) ?: Verdict.STEADY
                host.dispatch(DanceEvent.DebugSummary(verdict, parts.getOrNull(1)?.toIntOrNull() ?: 5))
            }
            "lang" -> host.dispatch(DanceEvent.LanguageSelected(LanguagePref.fromTag(word)))
            "menu" -> host.dispatch(DanceEvent.MoreClicked)
            "replace" -> host.dispatch(DanceEvent.ReplaceFloorClicked)
            "back" -> host.dispatch(DanceEvent.BackClicked)
            "exit" -> host.dispatch(DanceEvent.ExitClicked)
            "ui" -> DanceLog.i("UI $text pressed=${host.pressControl(text)}")
            "dump" -> {
                val json = host.dumpJson()
                DanceLog.i("DUMP $json")
                runCatching { File(context.filesDir, "dump.json").writeText(json) }
            }
            "pose" -> DanceLog.i(scene?.poseLine() ?: NO_SCENE)
            "bounds" -> {
                scene?.panelLines()?.forEach { DanceLog.i(it) } ?: DanceLog.i(NO_SCENE)
                for (panel in listOf("mirror", "moreMenu", "snackbar", "blockedDialog", "coach")) {
                    for ((ctrl, b) in DanceProbe.controlsOf(panel)) {
                        DanceLog.i(String.format(Locale.US, "CTRL panel=%s name=%s cx=%.1f cy=%.1f w=%.1f h=%.1f", panel, ctrl, b.cx, b.cy, b.w, b.h))
                    }
                }
            }
            "planes" -> scene?.planesSummary()?.forEach { DanceLog.i(it) } ?: DanceLog.i(NO_SCENE)
            "park" -> DanceLog.i(scene?.parkPanel() ?: NO_SCENE)
            "fps" -> DanceLog.i(scene?.setFpsLine(word == "on") ?: NO_SCENE)
            else -> DanceLog.i("CMD unknown '$name'")
        }
    }

    private const val NO_SCENE = "CMD ignored: no scene"
}
