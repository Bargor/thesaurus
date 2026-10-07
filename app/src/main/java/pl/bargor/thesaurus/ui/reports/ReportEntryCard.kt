package pl.bargor.thesaurus.ui.reports

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import pl.bargor.thesaurus.data.model.PlnMoney
import pl.bargor.thesaurus.data.model.PlnSign
import java.time.format.DateTimeFormatter
import java.util.Locale
import pl.bargor.thesaurus.R
import pl.bargor.thesaurus.ui.categoryAccentColor
import pl.bargor.thesaurus.ui.categoryContainer

private val reportsLocale = Locale.forLanguageTag("pl-PL")
private val reportsDateFormatter = DateTimeFormatter.ofPattern("d MMM", reportsLocale)
@Composable
internal fun ReportEntryCard(item: ReportEntryItem, onOpen: (String) -> Unit) {
    val entry = item.entry
    val label = listOfNotNull(item.categoryName, item.subcategoryName, entry.normalizedTitle, entry.amountGrosze.signedCurrency()).joinToString(", ")
    val openDescription = stringResource(R.string.reports_open_entry, label)
    val accent = categoryAccentColor(entry.categoryId, item.categoryColor)
    val surface = MaterialTheme.colorScheme.surface
    val dark = surface.luminance() < 0.5f
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onOpen(entry.id) }
            .testTag("report-entry-${entry.id}").semantics { contentDescription = openDescription },
        colors = CardDefaults.cardColors(
            containerColor = accent.categoryContainer(surface, selected = false, dark = dark),
        ),
        border = BorderStroke(1.dp, accent),
    ) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            androidx.compose.foundation.layout.Box(
                Modifier.width(6.dp).fillMaxHeight().background(accent)
                    .testTag("report-entry-category-color-${entry.id}"),
            )
            Column(Modifier.padding(12.dp).weight(1f)) {
                Text(item.categoryName, style = MaterialTheme.typography.labelLarge)
                item.subcategoryName?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                entry.normalizedTitle?.let { Text(it) }
                Text("${entry.date.format(reportsDateFormatter)} · ${entry.amountGrosze.signedCurrency()}")
            }
        }
    }
}


private fun Long.signedCurrency(): String = PlnMoney.currency(this, PlnSign.EXPLICIT_POSITIVE)
