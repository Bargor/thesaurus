package pl.bargor.thesaurus.ui.balance

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.math.BigInteger
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.ceil
import pl.bargor.thesaurus.R
import pl.bargor.thesaurus.data.model.SyncState

internal fun formatGlobalAccountBalance(amount: BigInteger): String =
    (if (amount.signum() > 0) "+" else "") + NumberFormat
        .getCurrencyInstance(Locale.forLanguageTag("pl-PL")).format(BigDecimal(amount, 2))

/** A compact shared footer; measurement keeps full amounts at the user's text size. */
@Composable
internal fun GlobalAccountBalanceBar(
    state: GlobalAccountBalanceUiState,
    horizontalInsets: WindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
    onRetry: () -> Unit = {},
) {
    val label = stringResource(R.string.global_account_balance_label)
    val amount = state.amountGrosze
    val value = remember(amount) { amount?.let(::formatGlobalAccountBalance) }
    val status = when {
        state.hasError -> stringResource(R.string.global_account_balance_error)
        state.isLoading || value == null -> stringResource(R.string.global_account_balance_loading)
        (amount?.signum() ?: 0) > 0 -> stringResource(R.string.global_account_balance_positive)
        (amount?.signum() ?: 0) < 0 -> stringResource(R.string.global_account_balance_negative)
        else -> stringResource(R.string.global_account_balance_zero)
    }
    val syncDescription = when (state.syncState) {
        SyncState.PENDING -> stringResource(R.string.global_account_balance_pending)
        SyncState.OFFLINE -> stringResource(R.string.global_account_balance_cached)
        else -> ""
    }
    val description = listOfNotNull(label, value, status, syncDescription.takeIf(String::isNotEmpty))
        .joinToString(". ")
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val amountColor = when (amount?.signum()) {
        1 -> if (dark) Color(0xFF8FDBA1) else Color(0xFF146C2E)
        -1 -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    val labelStyle = MaterialTheme.typography.labelSmall
    val amountStyle = MaterialTheme.typography.bodyMedium
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val widths = remember(label, value, labelStyle, amountStyle, measurer, density) {
        fun width(text: String, style: androidx.compose.ui.text.TextStyle): Int {
            val measured = measurer.measure(text, style, softWrap = false, maxLines = 1)
            return ceil(maxOf(measured.size.width.toFloat(), measured.multiParagraph.maxIntrinsicWidth))
                .toLong().coerceAtMost(Int.MAX_VALUE.toLong() - 2).toInt() + 2
        }
        width(label, labelStyle) to width(value ?: "", amountStyle)
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().testTag("global-account-balance")
            .semantics(mergeDescendants = true) {
                contentDescription = description
                stateDescription = status
            }) {
        BoxWithConstraints(Modifier.fillMaxWidth()
            .windowInsetsPadding(horizontalInsets)
            .padding(horizontal = 16.dp, vertical = 4.dp)) {
            val availableWidth = constraints.maxWidth
            when {
                state.hasError -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(label, style = labelStyle)
                        Text(status, Modifier.testTag("global-account-balance-error"), style = amountStyle)
                    }
                    TextButton(onClick = onRetry, modifier = Modifier.testTag("global-account-balance-retry")) {
                        Text(stringResource(R.string.global_account_balance_retry))
                    }
                }
                state.isLoading || value == null -> Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = labelStyle)
                    Text(status, Modifier.testTag("global-account-balance-loading"), style = amountStyle)
                }
                widths.first.toLong() + widths.second + with(density) { 12.dp.roundToPx() } <= availableWidth ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(label, style = labelStyle)
                        Text(value, Modifier.width(with(density) { widths.second.toDp() })
                            .testTag("global-account-balance-amount"),
                            style = amountStyle, color = amountColor, softWrap = false, maxLines = 1)
                    }
                else -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(label, style = labelStyle)
                    if (widths.second > availableWidth) {
                        // Only extreme amounts wider than the whole footer use scrolling.
                        // The full exact value also remains in the accessibility description.
                        Box(Modifier.fillMaxWidth().testTag("global-account-balance-amount-scroll")
                            .horizontalScroll(rememberScrollState())) {
                            Text(value, Modifier.width(with(density) { widths.second.toDp() })
                                .testTag("global-account-balance-amount"), style = amountStyle,
                                color = amountColor, softWrap = false, maxLines = 1)
                        }
                        Text(stringResource(R.string.global_account_balance_scroll_hint), style = labelStyle)
                    } else {
                        Text(value, Modifier.width(with(density) { widths.second.toDp() })
                            .testTag("global-account-balance-amount"),
                            style = amountStyle, color = amountColor, softWrap = false, maxLines = 1)
                    }
                }
            }
        }
    }
}
