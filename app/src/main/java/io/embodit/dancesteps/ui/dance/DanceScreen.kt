package io.embodit.dancesteps.ui.dance

import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.dancesteps.platform.DanceLog
import io.embodit.dancesteps.ui.dance.components.DanceStage
import io.embodit.dancesteps.ui.dance.components.LocalDanceResources
import io.embodit.dancesteps.ui.dance.components.localizedResources
import io.embodit.dancesteps.ui.dance.debug.DanceProbe
import io.embodit.dancesteps.ui.dance.scene.DanceFrameSystem
import io.embodit.dancesteps.ui.dance.scene.DanceScene

/**
 * The one screen of the app, living in a Mixed Stage. It wires the three parts together: the
 * ViewModel holds the state, the [DanceScene] turns state into entities and reports the head, planes
 * and beats, and [DanceStage] declares the panels.
 */
@Composable
fun DanceScreen() {
    val context = LocalContext.current
    val viewModel: DanceViewModel = viewModel(factory = DanceViewModel.Factory(context))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current

    remember(context) {
        DanceProbe.enabled = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        true
    }
    val scene = remember(viewModel) { DanceScene(viewModel::onEvent) }

    DisposableEffect(scene) {
        registerSystem<DanceFrameSystem>()
        DanceProbe.host =
            object : DanceProbe.Host {
                override fun dispatch(event: DanceEvent) = viewModel.onEvent(event)

                override fun uiState(): DanceUiState = viewModel.state.value

                override fun dumpJson(): String = viewModel.dumpJson()

                override fun scene(): DanceProbe.SceneProbe = scene

                override fun pressControl(name: String): Boolean = DanceProbe.click(name)
            }
        DanceLog.i("STAGE enter")
        onDispose {
            DanceProbe.host = null
            unregisterSystem<DanceFrameSystem>()
            scene.detach()
            DanceLog.i("STAGE disposed")
        }
    }

    // background / foreground: a run pauses by itself, the audio stream is flushed (design `rules.background`)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel, scene) {
        val observer =
            object : LifecycleEventObserver {
                override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                    when (event) {
                        Lifecycle.Event.ON_STOP -> {
                            viewModel.onEvent(DanceEvent.WentBackground)
                            scene.onBackground()
                        }
                        Lifecycle.Event.ON_START -> {
                            scene.onForeground()
                            viewModel.onEvent(DanceEvent.CameForeground)
                        }
                        else -> Unit
                    }
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(scene, state) { scene.render(state) }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect: DanceUiEffect ->
            when (effect) {
                DanceUiEffect.ExitApp -> {
                    DanceLog.i("STAGE close requested")
                    runCatching { navigator.closeStage() }.onFailure { DanceLog.e("STAGE close failed: $it", it) }
                }
                DanceUiEffect.RetryTracking -> scene.restartTracking()
            }
        }
    }

    val resources = remember(state.language, context) { localizedResources(context, state.language) }
    CompositionLocalProvider(LocalDanceResources provides resources) {
        DanceStage(state = state, scene = scene, onEvent = viewModel::onEvent)
    }
}
