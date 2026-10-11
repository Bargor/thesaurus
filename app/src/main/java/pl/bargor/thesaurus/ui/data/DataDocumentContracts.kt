package pl.bargor.thesaurus.ui.data

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts

/** SAF contracts restricted to files that can be opened as a byte stream. */
object DataDocumentContracts {
    fun exportMimeType(format: DataFormat): String = when (format) {
        DataFormat.CSV -> "text/csv"
        DataFormat.JSON -> "application/json"
    }

    fun importMimeTypes(format: DataFormat): Array<String> = when (format) {
        DataFormat.CSV -> arrayOf("text/csv", "text/comma-separated-values", "application/csv", "text/plain")
        DataFormat.JSON -> arrayOf("application/json", "text/json")
    }

    fun createDocument(format: DataFormat): ActivityResultContracts.CreateDocument =
        object : ActivityResultContracts.CreateDocument(exportMimeType(format)) {
            override fun createIntent(context: Context, input: String): Intent =
                super.createIntent(context, input).addCategory(Intent.CATEGORY_OPENABLE)
        }

    fun openDocument(): ActivityResultContracts.OpenDocument =
        object : ActivityResultContracts.OpenDocument() {
            override fun createIntent(context: Context, input: Array<String>): Intent =
                super.createIntent(context, input).addCategory(Intent.CATEGORY_OPENABLE)
        }
}
