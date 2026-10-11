package pl.bargor.thesaurus.ui.data

import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class DataDocumentContractTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun exportsUseCreateDocumentOpenableWithExactMimeAndTypeDateTitle() {
        for ((type, mime, extension) in listOf(Triple("wpisy", "text/csv", "csv"), Triple("kopia", "application/json", "json"))) {
            val title = "thesaurus-$type-2026-10-11.$extension"
            val format = if (extension == "csv") DataFormat.CSV else DataFormat.JSON
            assertEquals(mime, DataDocumentContracts.exportMimeType(format))
            val intent = DataDocumentContracts.createDocument(format).createIntent(context, title)
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
            assertEquals(mime, intent.type)
            assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
            assertEquals(title, intent.getStringExtra(Intent.EXTRA_TITLE))
        }
    }

    @Test fun importsUseOpenDocumentWithExplicitSupportedMimeTypesAndCancelReturnsNoUri() {
        for ((format, types) in listOf(DataFormat.CSV to arrayOf("text/csv", "text/comma-separated-values", "application/csv", "text/plain"),
            DataFormat.JSON to arrayOf("application/json", "text/json"))) {
            assertArrayEquals(types, DataDocumentContracts.importMimeTypes(format))
            val contract = DataDocumentContracts.openDocument()
            val intent = contract.createIntent(context, types)
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
            assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
            assertArrayEquals(types, intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
            assertNull(contract.parseResult(android.app.Activity.RESULT_CANCELED, Intent()))
        }
    }

    @Test fun applicationRequestsNoBroadStorageOrMediaPermissions() {
        @Suppress("DEPRECATION")
        val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertTrue(permissions.none { it in setOf("android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.MANAGE_EXTERNAL_STORAGE", "android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_MEDIA_AUDIO") })
    }
}
