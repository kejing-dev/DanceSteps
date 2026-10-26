package io.embodit.dancesteps.platform

import android.content.Context
import io.embodit.dancesteps.data.DanceRepository
import io.embodit.dancesteps.data.PrefsDanceRepository
import io.embodit.dancesteps.platform.audio.BeatEngine
import io.embodit.dancesteps.platform.input.ControllerInput
import io.embodit.dancesteps.platform.sense.PlaneTracker
import io.embodit.dancesteps.platform.tracking.HeadTracker

/**
 * Process-wide wiring of the platform services. The Stage starts and stops the sensing services and
 * the audio stream (every start has a stop: see `DanceScene.attach` / `detach`).
 */
object DanceGraph {
    val planes = PlaneTracker()
    val head = HeadTracker()
    val controller = ControllerInput()
    val beats = BeatEngine()

    @Volatile var appContext: Context? = null

    @Volatile private var repository: DanceRepository? = null

    fun repository(context: Context): DanceRepository =
        repository ?: synchronized(this) { repository ?: PrefsDanceRepository(context).also { repository = it } }
}
