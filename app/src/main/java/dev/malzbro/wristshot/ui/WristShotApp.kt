package dev.malzbro.wristshot.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import dev.malzbro.wristshot.recording.SessionRepository

private object Routes {
    const val HOME = "home"
    const val LABEL = "label"
    const val DEBUG = "debug"
}

/** UI layer root: home, labeling and detector debug screens; swipe right to go back. */
@Composable
fun WristShotApp(onStartSession: () -> Unit, onStopSession: () -> Unit) {
    val state by SessionRepository.state.collectAsStateWithLifecycle()
    val navController = rememberSwipeDismissableNavController()
    val view = LocalView.current

    MaterialTheme {
        SwipeDismissableNavHost(navController = navController, startDestination = Routes.HOME) {
            composable(Routes.HOME) {
                HomeScreen(
                    state = state,
                    onStart = {
                        onStartSession()
                        navController.navigate(Routes.LABEL)
                    },
                    onStop = onStopSession,
                    onOpenLabeling = { navController.navigate(Routes.LABEL) },
                    onOpenDebug = { navController.navigate(Routes.DEBUG) },
                    onBackKeyMarksShotChange = SessionRepository::setBackKeyMarksShot,
                )
            }
            composable(Routes.LABEL) {
                LabelScreen(
                    state = state,
                    onDone = { navController.popBackStack() },
                    onLabel = { label, source -> view.recordLabelWithHaptic(label, source) },
                )
            }
            composable(Routes.DEBUG) {
                DebugScreen(state = state)
            }
        }
    }
}
