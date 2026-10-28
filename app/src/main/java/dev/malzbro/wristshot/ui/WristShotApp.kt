package dev.malzbro.wristshot.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import dev.malzbro.wristshot.recording.SessionRepository

private object Routes {
    const val HOME = "home"
    const val LABEL = "label"
}

/** UI layer root: two screens, swipe right to go back. */
@Composable
fun WristShotApp(onStartSession: () -> Unit, onStopSession: () -> Unit) {
    val state by SessionRepository.state.collectAsStateWithLifecycle()
    val navController = rememberSwipeDismissableNavController()

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
                    onBackKeyMarksShotChange = SessionRepository::setBackKeyMarksShot,
                )
            }
            composable(Routes.LABEL) {
                LabelScreen(state = state, onDone = { navController.popBackStack() })
            }
        }
    }
}
