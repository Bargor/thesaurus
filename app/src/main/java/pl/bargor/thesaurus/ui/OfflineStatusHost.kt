package pl.bargor.thesaurus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import pl.bargor.thesaurus.R

internal val LocalDismissOfflineTooltip = staticCompositionLocalOf<() -> Unit> { {} }
internal const val OFFLINE_TOOLTIP_DURATION_MS = 5_000L

/** A stable header slot avoids covering screen controls and never moves content on connection changes. */
@Composable
internal fun OfflineStatusHost(
    isOnline: Boolean,
    screenKey: Any?,
    content: @Composable () -> Unit,
) {
    var visible by remember(isOnline, screenKey) { mutableStateOf(false) }
    var tooltipGeneration by remember(isOnline, screenKey) { mutableIntStateOf(0) }
    var iconBounds by remember { mutableStateOf(Rect.Zero) }
    var hostBounds by remember { mutableStateOf(Rect.Zero) }
    LaunchedEffect(visible, tooltipGeneration) {
        if (visible) {
            delay(OFFLINE_TOOLTIP_DURATION_MS)
            visible = false
        }
    }
    Box(
        Modifier.fillMaxSize().testTag("offline-status-host")
            .onGloballyPositioned { hostBounds = it.boundsInRoot() }
            .pointerInput(isOnline, screenKey) {
                // Observe without consuming: the same tap still reaches its intended control.
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (visible && event.changes.any {
                            it.changedToDownIgnoreConsumed() &&
                                !iconBounds.contains(it.position + hostBounds.topLeft)
                        }) visible = false
                    }
                }
            },
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Box(Modifier.fillMaxWidth().height(48.dp).testTag("connection-header")) {
                if (!isOnline) IconButton(
                    onClick = {
                        visible = !visible
                        if (visible) tooltipGeneration++
                    },
                    modifier = Modifier.align(Alignment.TopEnd).padding(end = 4.dp).size(48.dp)
                        .testTag("offline-indicator")
                        .onGloballyPositioned { iconBounds = it.boundsInRoot() },
                ) {
                    Icon(Icons.Default.PowerOff, stringResource(R.string.offline_indicator_description))
                }
            }
            Box(Modifier.weight(1f)) {
                CompositionLocalProvider(LocalDismissOfflineTooltip provides { visible = false }) { content() }
            }
        }
        if (visible && !isOnline) {
            // Material Surface installs a pointer handler even when non-clickable. Drawing this
            // bubble directly lets hit testing include the destination underneath its bounds.
            Box(
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding()
                    .padding(top = 52.dp, start = 16.dp, end = 8.dp)
                    .shadow(4.dp, MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.inverseSurface, MaterialTheme.shapes.medium)
                    .testTag("offline-tooltip").semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Text(
                    stringResource(R.string.offline_tooltip), Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                )
            }
        }
    }
}
