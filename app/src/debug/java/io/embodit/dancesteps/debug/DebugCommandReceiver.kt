package io.embodit.dancesteps.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.embodit.dancesteps.platform.DanceLog

/**
 * Debug builds only. Forwards adb broadcasts to the running Stage (action `io.embodit.dancesteps.DEBUG`);
 * see [DebugCommands] for the command list.
 *
 *   adb shell "am broadcast -a io.embodit.dancesteps.DEBUG -p io.embodit.dancesteps --es cmd 'state ready'"
 *
 * Extras: `cmd` = command name with its arguments after it, or `s` = the argument text on its own.
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: return
        val s = intent.getStringExtra("s")
        DanceLog.i("CMD recv '$cmd' s='$s'")
        DebugCommands.run(context.applicationContext, cmd, s)
    }
}
