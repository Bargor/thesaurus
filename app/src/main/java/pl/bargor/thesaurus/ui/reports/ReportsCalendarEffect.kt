package pl.bargor.thesaurus.ui.reports

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** A reports destination owns its calendar timer only while it is resumed. */
@Composable
internal fun ReportsCalendarEffect(householdId: String, viewModel: ReportsViewModel) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, householdId, viewModel) {
        val observer = LifecycleEventObserver { _, _ ->
            viewModel.setForeground(householdId, lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
        lifecycle.addObserver(observer)
        // Registration may precede start(); the ViewModel remembers the household intent.
        viewModel.setForeground(householdId, lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose {
            lifecycle.removeObserver(observer)
            viewModel.setForeground(householdId, false)
        }
    }
}
