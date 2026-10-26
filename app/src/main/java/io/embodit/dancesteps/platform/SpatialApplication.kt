package io.embodit.dancesteps.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import io.embodit.dancesteps.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DanceGraph.appContext = applicationContext
        launch(::mainApp)
    }
}
