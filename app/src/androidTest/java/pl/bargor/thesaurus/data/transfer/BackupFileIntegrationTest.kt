package pl.bargor.thesaurus.data.transfer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Only instrumentation cache files are touched; no household/account/backend is required. */
@RunWith(AndroidJUnit4::class)
class BackupFileIntegrationTest {
    @Test fun utf8BackupRoundTripsThroughAndroidFileStreams() {
        val instant = Instant.parse("2026-10-10T13:14:15.123456789Z")
        val document = BackupDocument(ExportMetadata(instant, "file-contract-household"), Long.MIN_VALUE,
            categories = listOf(BackupCategory("food", "Żywność", "author", "editor", color = "mint", archived = true)),
            subcategories = listOf(BackupSubcategory("shop", "food", "Łódź", "author", "editor", archived = true)),
            entries = listOf(BackupEntry("entry", Long.MAX_VALUE, LocalDate.of(2024, 2, 29), "food", "author", "editor",
                title = "Zażółć gęślą jaźń", subcategoryId = "shop", tags = listOf("a", "żółć"),
                createdAt = instant, updatedAt = instant, deleted = true, deletedAt = instant, deletedById = "deleter")))
        withTemporaryFile { file ->
            val bytes = BackupJsonCodec.encode(document)
            file.outputStream().use { it.write(bytes) }
            val actual = file.inputStream().use { it.readBytes() }
            assertArrayEquals(bytes, actual)
            assertEquals(document, BackupJsonCodec.decode(actual))
            assertTrue(actual.toString(Charsets.UTF_8).contains("Zażółć gęślą jaźń"))
        }
    }

    @Test fun corruptedFileReturnsSanitizedContractFailure() {
        withTemporaryFile { file ->
            file.outputStream().use { it.write(byteArrayOf(0xc3.toByte(), 0x28)) }
            val error = assertThrows(BackupContractException::class.java) {
                BackupJsonCodec.decode(file.inputStream().use { it.readBytes() })
            }
            assertEquals(TransferErrorCode.MALFORMED_UTF8, error.code)
            assertEquals("Backup contract error: MALFORMED_UTF8", error.message)
            assertNull(error.cause)
        }
    }

    private fun withTemporaryFile(action: (File) -> Unit) {
        val cache = InstrumentationRegistry.getInstrumentation().context.cacheDir
        val file = File.createTempFile("issue108-backup-contract-", ".json", cache)
        try { action(file) } finally { check(file.delete()) }
    }
}
