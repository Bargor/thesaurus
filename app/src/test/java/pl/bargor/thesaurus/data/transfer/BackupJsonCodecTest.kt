package pl.bargor.thesaurus.data.transfer

import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class BackupJsonCodecTest {
    private val instant = Instant.parse("2026-10-10T10:11:12.123456789Z")
    private val category = BackupCategory("food", "Żywność", "author", "editor", "mint", true,
        TransferEntryType.EXPENSE, instant, instant)
    private val subcategory = BackupSubcategory("shop", "food", "Łódź", "author", "editor", true, instant, instant)
    private val entry = BackupEntry("entry", -12345, LocalDate.of(2024, 2, 29), "food", "author", "editor",
        title = "Zażółć gęślą jaźń", subcategoryId = "shop", tags = listOf("a", "żółć"),
        createdAt = instant, updatedAt = instant, deleted = true, deletedAt = instant, deletedById = "deleter")
    private fun fixture() = BackupDocument(ExportMetadata(instant, "household", "0.1.0"), -98765,
        listOf(category), listOf(subcategory), listOf(entry))
    private fun json(document: BackupDocument = fixture()) = BackupJsonCodec.encode(document).toString(Charsets.UTF_8)
    private fun decode(value: String) = BackupJsonCodec.decode(value.toByteArray(Charsets.UTF_8))
    private fun rejected(code: TransferErrorCode? = null, operation: () -> Unit) {
        val error = assertThrows(BackupContractException::class.java, operation)
        if (code != null) assertEquals(code, error.code)
        assertNull(error.cause)
        assertEquals("Backup contract error: ${error.code.name}", error.message)
    }

    @Test fun roundTripPreservesEveryFileValueIncludingAuditsArchiveAndTombstone() {
        assertEquals(fixture(), BackupJsonCodec.decode(BackupJsonCodec.encode(fixture())))
        assertTrue(json().contains("Zażółć gęślą jaźń"))
        assertFalse(json().contains("email"))
        assertFalse(json().contains("members"))
        assertFalse(json().contains("invitations"))
    }

    @Test fun signedLongExtremesAndZeroOpeningBalanceAreExact() {
        for (amount in listOf(Long.MIN_VALUE, Long.MAX_VALUE, -1L, 1L)) {
            for (balance in listOf(Long.MIN_VALUE, Long.MAX_VALUE, 0L)) {
                val document = fixture().copy(openingBalanceGrosze = balance, entries = listOf(entry.copy(amountGrosze = amount)))
                assertEquals(document, BackupJsonCodec.decode(BackupJsonCodec.encode(document)))
            }
        }
    }

    @Test fun optionalNullsAndPendingTombstoneTimestampSurvive() {
        val document = fixture().copy(metadata = ExportMetadata(instant, "household"),
            categories = listOf(category.copy(color = null, createdAt = null, updatedAt = null)),
            subcategories = listOf(subcategory.copy(createdAt = null, updatedAt = null)),
            entries = listOf(entry.copy(title = null, tags = emptyList(), subcategoryId = null,
                createdAt = null, updatedAt = null, deletedAt = null)))
        assertEquals(document, BackupJsonCodec.decode(BackupJsonCodec.encode(document)))
        assertTrue(json(document).contains("\"title\":null"))
    }

    @Test fun outputAndIsoDatesAreIndependentOfDefaultLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            val bytes = BackupJsonCodec.encode(fixture())
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertArrayEquals(bytes, BackupJsonCodec.encode(fixture()))
            assertEquals(fixture(), BackupJsonCodec.decode(bytes))
            assertTrue(bytes.toString(Charsets.UTF_8).contains("2024-02-29"))
        } finally { Locale.setDefault(previous) }
    }

    @Test fun outputIsCanonicalForCollectionOrderWithoutDroppingOrNormalizingRawTags() {
        val original = fixture().copy(categories = listOf(category, category.copy(id = "a")),
            subcategories = listOf(subcategory, subcategory.copy(id = "a")),
            entries = listOf(entry, entry.copy(id = "a", title = "", tags = listOf(" Food ", "FOOD", "a"))))
        val shuffled = original.copy(categories = original.categories.reversed(), subcategories = original.subcategories.reversed(),
            entries = original.entries.reversed().map { it.copy(tags = it.tags.reversed()) })
        assertArrayEquals(BackupJsonCodec.encode(original), BackupJsonCodec.encode(shuffled))
        val decoded = BackupJsonCodec.decode(BackupJsonCodec.encode(original))
        assertEquals(listOf("a", "food"), decoded.categories.map { it.id })
        assertEquals(listOf(" Food ", "FOOD", "a"), decoded.entries.first().tags)
        assertEquals("", decoded.entries.first().title)
    }

    @Test fun unknownFieldsAndMissingRequiredFieldsAreRejected() {
        rejected { decode(json().replace("\"formatVersion\":1", "\"formatVersion\":1,\"accounts\":[]")) }
        rejected { decode(json().replace("\"authorId\":\"author\"", "\"authorId\":\"author\",\"email\":\"private@example.test\"")) }
        rejected { decode(json().replace("\"formatVersion\":1,", "")) }
        rejected { decode(json().replace("\"authorId\":\"author\",", "")) }
    }

    @Test fun invalidTaxonomyMetadataTagsAndUnicodeAreRejected() {
        val bad = listOf(fixture().copy(metadata = fixture().metadata.copy(sourceHouseholdId = " ")),
            fixture().copy(categories = listOf(category.copy(color = "#ffffff"))),
            fixture().copy(categories = listOf(category.copy(name = "x".repeat(61)))),
            fixture().copy(entries = listOf(entry.copy(tags = listOf("a", "a")))),
            fixture().copy(entries = listOf(entry.copy(tags = listOf(" ")))),
            fixture().copy(entries = listOf(entry.copy(tags = listOf("x".repeat(41))))),
            fixture().copy(entries = listOf(entry.copy(title = "\uD800"))))
        bad.forEach { document -> rejected(TransferErrorCode.INVALID_VALUE) { BackupJsonCodec.encode(document) } }
        rejected { decode(json().replace("Zażółć gęślą jaźń", "\\uD800")) }
    }

    @Test fun zeroAmountsAndInvalidOptionalAndAuditValuesAreRejectedOnBothBoundaries() {
        val invalid = listOf(entry.copy(amountGrosze = 0), entry.copy(title = "x".repeat(161)),
            entry.copy(authorId = ""), entry.copy(updatedById = " "), entry.copy(deletedById = null),
            entry.copy(deleted = false), entry.copy(subcategoryId = ""))
        invalid.forEach { item -> rejected(TransferErrorCode.INVALID_VALUE) { BackupJsonCodec.encode(fixture().copy(entries = listOf(item))) } }
        rejected(TransferErrorCode.INVALID_VALUE) { decode(json().replace("\"amountGrosze\":-12345", "\"amountGrosze\":0")) }
    }

    @Test fun unsupportedVersionsAreRejectedAndNeverInterpretedAsCurrent() {
        for (version in listOf("0", "2", "-1", "2147483647")) {
            rejected(TransferErrorCode.UNSUPPORTED_VERSION) { decode(json().replace("\"formatVersion\":1", "\"formatVersion\":$version")) }
        }
        rejected(TransferErrorCode.UNSUPPORTED_VERSION) { BackupJsonCodec.encode(fixture().copy(formatVersion = 2)) }
    }

    @Test fun integerFieldsRejectCoercionFractionExponentOverflowAndNull() {
        for (value in listOf("\"-12345\"", "-12345.0", "-1e3", "9223372036854775808", "-9223372036854775809", "null", "true")) {
            rejected { decode(json().replace("\"amountGrosze\":-12345", "\"amountGrosze\":$value")) }
        }
        for (value in listOf("\"1\"", "1.0", "1e0", "null", "true")) {
            rejected { decode(json().replace("\"formatVersion\":1", "\"formatVersion\":$value")) }
        }
    }

    @Test fun invalidDateEnumBooleanAndContainerTypesAreRejected() {
        listOf(json().replace("2024-02-29", "2023-02-29"),
            json().replace("2024-02-29", "29/02/2024"),
            json().replace("EXPENSE", "UNKNOWN"),
            json().replace("\"archived\":true", "\"archived\":\"true\""),
            json().replace("\"tags\":[\"a\",\"żółć\"]", "\"tags\":null"),
            json().replace("\"categories\":[", "\"categories\":{")).forEach { bad -> rejected { decode(bad) } }
    }

    @Test fun duplicateKeysAtRootAndNestedLevelsAreRejectedIncludingEscapedNames() {
        listOf(json().replace("\"formatVersion\":1", "\"formatVersion\":1,\"formatVersion\":1"),
            json().replace("\"authorId\":\"author\"", "\"authorId\":\"author\",\"authorId\":\"author\""),
            json().replace("\"formatVersion\":1", "\"formatVersion\":1,\"\\u0066ormatVersion\":1")).forEach { bad ->
                rejected(TransferErrorCode.MALFORMED_JSON) { decode(bad) }
            }
    }

    @Test fun malformedUtf8AndNonJsonSyntaxAreRejectedWithoutLeakingInput() {
        rejected(TransferErrorCode.MALFORMED_UTF8) { BackupJsonCodec.decode(byteArrayOf(0xc3.toByte(), 0x28)) }
        for (bad in listOf("", "secret raw content", json() + "{}", json().replace("\"formatVersion\":1", "\"formatVersion\":01"),
            json().replace("\"formatVersion\":1", "\"formatVersion\":+1"), "/* secret */" + json(), json().dropLast(1) + ",}")) {
            rejected(TransferErrorCode.MALFORMED_JSON) { decode(bad) }
        }
    }

    @Test fun brokenRelationsAndDuplicateIdentifiersAreRejected() {
        val invalid = listOf(fixture().copy(categories = listOf(category, category)),
            fixture().copy(entries = listOf(entry, entry)), fixture().copy(subcategories = listOf(subcategory, subcategory)),
            fixture().copy(entries = listOf(entry.copy(categoryId = "missing"))),
            fixture().copy(entries = listOf(entry.copy(subcategoryId = "missing"))),
            fixture().copy(subcategories = listOf(subcategory.copy(categoryId = "missing"))),
            fixture().copy(categories = listOf(category, category.copy(id = "other")),
                entries = listOf(entry.copy(categoryId = "other"))))
        invalid.forEach { bad -> rejected(TransferErrorCode.INVALID_VALUE) { BackupJsonCodec.encode(bad) } }
    }

    @Test fun subcategoryIdentifiersAreScopedToTheirCategory() {
        val document = fixture().copy(categories = listOf(category, category.copy(id = "other")),
            subcategories = listOf(subcategory, subcategory.copy(categoryId = "other")))
        assertEquals(document, BackupJsonCodec.decode(BackupJsonCodec.encode(document)))
    }

    @Test fun oversizedBytesAndCollectionsAreRejectedRatherThanTruncated() {
        rejected(TransferErrorCode.LIMIT_EXCEEDED) { BackupJsonCodec.decode(ByteArray(BackupLimits.MAX_BYTES + 1)) }
        rejected(TransferErrorCode.LIMIT_EXCEEDED) { BackupJsonCodec.encode(fixture().copy(entries = listOf(entry.copy(tags = List(11) { "tag$it" })))) }
        for (bad in listOf(fixture().copy(categories = List(BackupLimits.MAX_CATEGORIES + 1) { category.copy(id = "c$it") }),
            fixture().copy(subcategories = List(BackupLimits.MAX_SUBCATEGORIES + 1) { subcategory.copy(id = "s$it") }),
            fixture().copy(entries = List(BackupLimits.MAX_ENTRIES + 1) { entry.copy(id = "e$it") }))) {
            rejected(TransferErrorCode.LIMIT_EXCEEDED) { BackupJsonCodec.encode(bad) }
        }
    }

    @Test fun excessiveJsonNestingHasControlledError() {
        val nested = "[".repeat(BackupLimits.MAX_JSON_DEPTH + 1) + "0" + "]".repeat(BackupLimits.MAX_JSON_DEPTH + 1)
        rejected { decode(nested) }
    }
}
