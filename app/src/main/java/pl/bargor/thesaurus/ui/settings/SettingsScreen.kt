package pl.bargor.thesaurus.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import pl.bargor.thesaurus.R

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenCategories: () -> Unit,
    onOpenFamily: () -> Unit,
    onSetAccountBalance: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f).semantics { heading() })
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp).testTag("settings-back")) {
                Text(stringResource(R.string.settings_back))
            }
            SettingsAction()
        }
        OutlinedButton(onClick = onOpenCategories,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("settings-categories")) {
            Text(stringResource(R.string.settings_categories))
        }
        OutlinedButton(onClick = onOpenFamily,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("settings-family")) {
            Text(stringResource(R.string.settings_family))
        }
        OutlinedButton(onClick = onSetAccountBalance,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("settings-opening-balance")) {
            Text(stringResource(R.string.settings_set_account_balance))
        }
    }
}
