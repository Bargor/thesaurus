package pl.bargor.thesaurus.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import pl.bargor.thesaurus.R

@Composable
fun OpeningBalanceScreen(
    state: OpeningBalanceUiState,
    onAmountChange: (String) -> Unit,
    onModeChange: (OpeningBalanceMode) -> Unit,
    onSave: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val editable = state.isOwner && !state.saving && !state.saved && !state.loading
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_set_account_balance),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f).semantics { heading() })
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp).testTag("opening-balance-back")) {
                Text(stringResource(R.string.settings_back))
            }
            SettingsAction()
        }
        Text(stringResource(R.string.opening_balance_explanation))
        Text(stringResource(R.string.current_balance_explanation))
        if (state.loading) CircularProgressIndicator(Modifier.testTag("opening-balance-loading"))
        else if (!state.isOwner && state.error != OpeningBalanceError.LOAD) {
            Text(stringResource(R.string.opening_balance_read_only), Modifier.testTag("opening-balance-read-only"))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = state.mode == OpeningBalanceMode.OPENING, enabled = editable,
                onClick = { onModeChange(OpeningBalanceMode.OPENING) },
                label = { Text(stringResource(R.string.opening_balance_mode_opening)) },
                modifier = Modifier.testTag("opening-balance-mode-opening"))
            FilterChip(selected = state.mode == OpeningBalanceMode.CURRENT, enabled = editable,
                onClick = { onModeChange(OpeningBalanceMode.CURRENT) },
                label = { Text(stringResource(R.string.opening_balance_mode_current)) },
                modifier = Modifier.testTag("opening-balance-mode-current"))
        }
        val negative = state.amount.trimStart().startsWith('-')
        val signDescription = stringResource(if (negative) R.string.opening_balance_make_positive
            else R.string.opening_balance_make_negative)
        val signState = stringResource(if (negative) R.string.opening_balance_negative
            else R.string.opening_balance_positive)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = state.amount, onValueChange = onAmountChange,
            enabled = editable, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            label = { Text(stringResource(R.string.opening_balance_amount)) },
            supportingText = { Text(stringResource(R.string.opening_balance_amount_hint)) },
            isError = state.error == OpeningBalanceError.INVALID_AMOUNT,
            modifier = Modifier.weight(1f).testTag("opening-balance-amount"))
        TextButton(onClick = {
            val magnitude = state.amount.trimStart().removePrefix("-").removePrefix("+")
            onAmountChange(if (negative) magnitude else "-$magnitude")
        }, enabled = editable, contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(48.dp).testTag("opening-balance-sign")
            .semantics { contentDescription = signDescription; stateDescription = signState }) {
            Text("±")
        }
        }
        state.error?.let { error ->
            Text(stringResource(when (error) {
                OpeningBalanceError.LOAD -> R.string.opening_balance_load_error
                OpeningBalanceError.INVALID_AMOUNT -> R.string.opening_balance_amount_error
                OpeningBalanceError.SAVE -> R.string.opening_balance_save_error
                OpeningBalanceError.CURRENT_REQUIRES_SERVER -> R.string.current_balance_requires_server
            }), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("opening-balance-error"))
            if (error == OpeningBalanceError.LOAD) {
                TextButton(onClick = onRetry, modifier = Modifier.testTag("opening-balance-retry")) {
                    Text(stringResource(R.string.auth_retry))
                }
            }
        }
        if (state.queuedOffline) Text(stringResource(R.string.opening_balance_pending),
            Modifier.testTag("opening-balance-pending"))
        else if (state.saved) Text(stringResource(R.string.opening_balance_saved),
            Modifier.testTag("opening-balance-saved"))
        if (state.saving) CircularProgressIndicator(Modifier.testTag("opening-balance-saving"))
        Button(onClick = onSave, enabled = editable,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("opening-balance-save")) {
            Text(stringResource(R.string.opening_balance_save))
        }
    }
}
