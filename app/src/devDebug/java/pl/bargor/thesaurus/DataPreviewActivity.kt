package pl.bargor.thesaurus

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import pl.bargor.thesaurus.ui.data.*

/** Standalone DEV rendering fixture; it never instantiates a Firebase or transfer backend. */
class DataPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra("mode") ?: "owner"
        setContent {
            var warning by rememberSaveable { mutableStateOf<String?>(null) }
            ThesaurusTheme {
                Surface {
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        Text(stringResource(R.string.dev_data_banner), Modifier.padding(12.dp))
                        Box(Modifier.weight(1f)) { HouseholdApp(
                            entriesContent = { settings, _, _, _ ->
                                androidx.compose.material3.TextButton(onClick = settings) { Text(stringResource(R.string.settings_title)) }
                            },
                            browseContent = {}, summaryContent = {}, reportsContent = {},
                            dataContent = { back ->
                                DataScreen(
                                    DataUiState(canExport = true, importEnabled = mode == "owner",
                                        importBlockCode = when (mode) {
                                            "member" -> "DATA_OWNER_REQUIRED"
                                            "offline" -> "DATA_OFFLINE"
                                            "pending" -> "DATA_PENDING"
                                            else -> null
                                        }, warning = warning?.let(DataFormat::valueOf)),
                                    onExport = { warning = it.name }, onImport = {},
                                    onConfirmExport = { warning = null }, onDismissWarning = { warning = null }, onBack = back,
                                )
                            },
                        ) }
                    }
                }
            }
        }
    }
}
