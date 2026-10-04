package pl.bargor.thesaurus.ui.settings

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import pl.bargor.thesaurus.R

internal data class SettingsNavigation(val selected: Boolean, val open: () -> Unit)

internal val LocalSettingsNavigation = staticCompositionLocalOf<SettingsNavigation?> { null }

/** Shares the same action in each screen's existing header without adding a second toolbar. */
@Composable
fun SettingsAction(onOpenSettings: (() -> Unit)? = null) {
    val navigation = LocalSettingsNavigation.current
    val open = onOpenSettings ?: navigation?.open ?: return
    val isSelected = navigation?.selected == true
    val description = stringResource(if (isSelected) R.string.settings_selected else R.string.settings_unselected)
    IconButton(
        onClick = open,
        modifier = Modifier.size(48.dp).testTag("global-open-settings").semantics {
            selected = isSelected
            stateDescription = description
        },
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                else androidx.compose.ui.graphics.Color.Transparent,
            contentColor = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings_open_description))
    }
}
