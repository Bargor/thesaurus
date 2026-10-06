package pl.bargor.thesaurus.ui.taxonomy

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import pl.bargor.thesaurus.R
import pl.bargor.thesaurus.data.model.CategoryPalette
import pl.bargor.thesaurus.data.model.EntryType
import pl.bargor.thesaurus.ui.asColor
import pl.bargor.thesaurus.ui.contrastingContent
import pl.bargor.thesaurus.ui.nameRes

@Composable
internal fun TaxonomyEditorDialog(
    title: String,
    initialName: String,
    initialType: EntryType,
    showDirection: Boolean,
    initialColor: String = CategoryPalette.defaultToken,
    onDismiss: () -> Unit,
    onConfirm: (String, EntryType, String) -> Unit,
) {
    var name by remember(title, initialName) { mutableStateOf(initialName) }
    var type by remember(title, initialType) { mutableStateOf(initialType) }
    var color by remember(title, initialColor) { mutableStateOf(initialColor) }
    val valid = TaxonomyValidation.nameOrNull(name) != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth().testTag("taxonomy-name"),
                    value = name,
                    onValueChange = { name = it },
                    isError = name.isNotBlank() && !valid,
                    label = { Text(stringResource(R.string.taxonomy_name)) },
                    supportingText = if (!valid) {
                        { Text(stringResource(R.string.taxonomy_name_error)) }
                    } else null,
                    singleLine = true,
                )
                if (showDirection) {
                    Text(stringResource(R.string.taxonomy_default_type), modifier = Modifier.padding(top = 12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = type == EntryType.EXPENSE,
                            onClick = { type = EntryType.EXPENSE },
                            label = { Text(stringResource(R.string.taxonomy_expense)) },
                        )
                        FilterChip(
                            selected = type == EntryType.INCOME,
                            onClick = { type = EntryType.INCOME },
                            label = { Text(stringResource(R.string.taxonomy_income)) },
                        )
                    }
                    Text(stringResource(R.string.taxonomy_color), modifier = Modifier.padding(top = 12.dp))
                    CategoryColorSelector(color, onSelected = { color = it })
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.taxonomy_cancel)) } },
        confirmButton = {
            Button(
                modifier = Modifier.testTag("taxonomy-save"),
                enabled = valid,
                onClick = { onConfirm(name, type, color) },
            ) { Text(stringResource(R.string.taxonomy_save)) }
        },
    )
}

@Composable
private fun CategoryColorSelector(selectedToken: String, onSelected: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CategoryPalette.swatches.chunked(5).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { swatch ->
                    val selected = selectedToken == swatch.token
                    val swatchColor = swatch.asColor()
                    val description = stringResource(
                        R.string.taxonomy_color_option,
                        stringResource(swatch.nameRes()),
                    )
                    val selection = stringResource(
                        if (selected) R.string.taxonomy_color_selected else R.string.taxonomy_color_unselected,
                    )
                    Surface(
                        onClick = { onSelected(swatch.token) },
                        modifier = Modifier.size(48.dp)
                            .testTag("taxonomy-color-${swatch.token}")
                            .semantics {
                                contentDescription = description
                                stateDescription = selection
                                this.selected = selected
                            },
                        shape = CircleShape,
                        color = swatchColor,
                        border = BorderStroke(if (selected) 3.dp else 1.dp, MaterialTheme.colorScheme.onSurface),
                    ) {
                        if (selected) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = swatchColor.contrastingContent(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
