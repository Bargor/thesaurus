package pl.bargor.thesaurus

import android.content.Intent
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

@Composable
internal fun DeveloperToolsContent(onSignOut: () -> Unit) {
    val context = LocalContext.current
    TextButton(modifier = Modifier.testTag("dev-json-backup-preview"), onClick = {
        context.startActivity(Intent(context, JsonBackupPreviewActivity::class.java))
    }) {
        Text(stringResource(R.string.dev_json_backup_preview))
    }
    TextButton(modifier = Modifier.testTag("dev-csv-import-preview"), onClick = {
        context.startActivity(Intent(context, CsvImportPreviewActivity::class.java))
    }) {
        Text(stringResource(R.string.dev_csv_import_preview))
    }
    TextButton(modifier = Modifier.testTag("dev-sign-out"), onClick = onSignOut) {
        Text(stringResource(R.string.dev_sign_out))
    }
}
