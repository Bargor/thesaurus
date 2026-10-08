package pl.bargor.thesaurus.data.firebase

import com.google.firebase.Timestamp
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test
import pl.bargor.thesaurus.data.model.*

class FirestoreDocumentDecoderTest {
    private val stamp = Timestamp(1_600_000_000, 123_456_789)
    private val audit = mapOf("createdAt" to stamp, "updatedAt" to stamp)
    private fun entry(): Map<String, Any?> = audit + mapOf(
        "householdId" to "home", "amountGrosze" to -1250L, "date" to "2026-10-07",
        "categoryId" to "food", "authorId" to "actor", "updatedById" to "actor",
        "deleted" to false, "deletedAt" to null, "deletedById" to null, "tags" to emptyList<String>(),
    )
    private fun category(): Map<String, Any?> = audit + mapOf(
        "householdId" to "home", "name" to "Food", "color" to "amber", "archived" to false,
        "defaultEntryType" to "EXPENSE", "authorId" to "actor", "updatedById" to "actor",
    )

    @Test fun exactSignedLongAndCalendarDatesArePreservedWithoutNumericCoercion() {
        for (amount in listOf(Long.MIN_VALUE, Long.MAX_VALUE, -1L, 1L)) {
            assertEquals(amount, FirestoreDocumentDecoder.ledgerEntry("entry", entry() + ("amountGrosze" to amount)).amountGrosze)
        }
        assertEquals(LocalDate.of(2099, 1, 1), FirestoreDocumentDecoder.ledgerEntry("entry", entry() + ("date" to "2099-01-01")).date)
        for (value in listOf(0L, 12.5, 12.0, 12, "1250", null)) {
            val error = rejected { FirestoreDocumentDecoder.ledgerEntry("entry", entry() + ("amountGrosze" to value)) }
            assertEquals(FirestoreDocumentType.LEDGER_ENTRY, error.documentType)
            assertEquals("amountGrosze", error.field)
        }
        for (value in listOf("2026-02-29", "2026-04-31", "2026-13-01", "2026-1-01", "not-a-date", 123L, null)) {
            assertEquals("date", rejected { FirestoreDocumentDecoder.ledgerEntry("entry", entry() + ("date" to value)) }.field)
        }
    }

    @Test fun requiredFieldsAndIdsFailInsteadOfDroppingOrRepairingRecords() {
        for (field in listOf("householdId", "amountGrosze", "date", "categoryId", "authorId", "updatedById", "deleted", "createdAt", "updatedAt")) {
            val error = rejected { FirestoreDocumentDecoder.ledgerEntry("entry", entry() - field) }
            assertEquals(field, error.field)
            assertEquals(FirestoreDecodeReason.MISSING_FIELD, error.reason)
        }
        for (field in listOf("householdId", "categoryId", "authorId", "updatedById")) {
            for (value in listOf("", "   ", "x".repeat(129), "a/b", 123L)) {
                rejected { FirestoreDocumentDecoder.ledgerEntry("entry", entry() + (field to value)) }
            }
        }
        rejected { FirestoreDocumentDecoder.ledgerEntry(" ", entry()) }
        assertEquals(FirestoreDecodeReason.CONTEXT_MISMATCH, rejected {
            FirestoreDocumentDecoder.ledgerEntry("entry", entry(), expectedHouseholdId = "other")
        }.reason)
        // Additional fields can be introduced by future clients without coercing known fields.
        assertEquals(-1250L, FirestoreDocumentDecoder.ledgerEntry("entry", entry() + ("futureField" to 42L)).amountGrosze)
    }

    @Test fun nullableOptionsAndLegacyTagsDoNotPermitWrongTypesOrOversizedMixedLists() {
        val optional = FirestoreDocumentDecoder.ledgerEntry("entry", entry() - "tags")
        assertNull(optional.title)
        assertNull(optional.subcategoryId)
        assertTrue(optional.tags.isEmpty())
        for (change in listOf("title" to 12L, "title" to "x".repeat(161),
            "subcategoryId" to false, "subcategoryId" to " ", "tags" to null,
            "tags" to "food", "tags" to listOf("food", 1L), "tags" to listOf(""),
            "tags" to listOf("x".repeat(41)),
            "tags" to (1..11).map { "tag$it" })) {
            rejected { FirestoreDocumentDecoder.ledgerEntry("entry", entry() + change) }
        }
        assertEquals(listOf("a", "food"), FirestoreDocumentDecoder.ledgerEntry("entry",
            entry() + ("tags" to listOf("Food", "a", "food"))).normalizedTags)
        assertNull(FirestoreDocumentDecoder.ledgerEntry("entry", entry() + ("title" to "   ")).title)
        assertEquals("Shopping", FirestoreDocumentDecoder.ledgerEntry("entry", entry() + ("title" to " Shopping ")).title)
    }

    @Test fun timestampsRetainNanosecondsAndOnlyPendingExplicitNullIsAllowed() {
        val decoded = FirestoreDocumentDecoder.ledgerEntry("entry", entry())
        assertEquals(Instant.ofEpochSecond(stamp.seconds, stamp.nanoseconds.toLong()), decoded.createdAt)
        for (field in listOf("createdAt", "updatedAt")) {
            val pending = entry() + (field to null)
            assertNull(if (field == "createdAt") FirestoreDocumentDecoder.ledgerEntry("entry", pending, true).createdAt
                else FirestoreDocumentDecoder.ledgerEntry("entry", pending, true).updatedAt)
            rejected { FirestoreDocumentDecoder.ledgerEntry("entry", pending, false) }
            rejected { FirestoreDocumentDecoder.ledgerEntry("entry", entry() - field, true) }
            rejected { FirestoreDocumentDecoder.ledgerEntry("entry", entry() + (field to "timestamp"), true) }
        }
    }

    @Test fun deletionMetadataIsConsistentAndMalformedTombstonesAreNotHiddenByFiltering() {
        val tombstone = entry() + mapOf("deleted" to true, "deletedById" to "actor", "deletedAt" to stamp)
        assertTrue(FirestoreDocumentDecoder.ledgerEntry("entry", tombstone).deleted)
        assertNull(FirestoreDocumentDecoder.ledgerEntry("entry", tombstone + ("deletedAt" to null), true).deletedAt)
        for (fields in listOf(tombstone - "deletedAt", tombstone + ("deletedAt" to null),
            tombstone + ("deletedById" to null), tombstone + ("deletedById" to " "),
            entry() + ("deletedAt" to stamp), entry() + ("deletedById" to "actor"), entry() + ("deleted" to "false"))) {
            rejected { FirestoreDocumentDecoder.ledgerEntry("entry", fields) }
        }
        val observation = decodeCollectionObservation(listOf(entry(), tombstone - "date"), SyncState.SYNCED,
            decoder = { FirestoreDocumentDecoder.ledgerEntry("entry", it) },
            transform = { visibleLedgerEntries(it, includeDeleted = false) })
        assertEquals(SyncState.ERROR, observation.state)
        assertNull(observation.value)
    }

    @Test fun pendingWritesNeverPermitWrongTimestampTypesOrActiveDeletionMetadata() {
        for (field in listOf("createdAt", "updatedAt")) {
            for (value in listOf(123L, "timestamp", true)) {
                assertEquals(field, rejected {
                    FirestoreDocumentDecoder.ledgerEntry("entry", entry() + (field to value), true)
                }.field)
            }
        }
        for (change in listOf("deletedAt" to stamp, "deletedAt" to 123L, "deletedById" to "actor")) {
            rejected { FirestoreDocumentDecoder.ledgerEntry("entry", entry() + change, true) }
        }
        val active = FirestoreDocumentDecoder.ledgerEntry("entry", entry() - "deletedAt" - "deletedById", true)
        assertFalse(active.deleted)
        assertNull(active.deletedAt)
        assertNull(active.deletedById)
    }

    @Test fun decodedLedgerPreservesDescendingDateAndDocumentIdOrder() {
        val older = FirestoreDocumentDecoder.ledgerEntry("z", entry() + ("date" to "2026-10-06"))
        val first = FirestoreDocumentDecoder.ledgerEntry("a", entry())
        val last = FirestoreDocumentDecoder.ledgerEntry("b", entry())
        val deleted = FirestoreDocumentDecoder.ledgerEntry("c", entry() + mapOf(
            "deleted" to true, "deletedById" to "actor", "deletedAt" to stamp))
        assertEquals(listOf("b", "a", "z"), visibleLedgerEntries(listOf(older, first, deleted, last), false).map { it.id })
        assertEquals(listOf("c", "b", "a", "z"), visibleLedgerEntries(listOf(older, first, deleted, last), true).map { it.id })
    }

    @Test fun householdOnlyDefaultsAbsentBalancesAndRevisions() {
        val fields = audit + mapOf("name" to "Home", "ownerId" to "owner")
        val legacy = FirestoreDocumentDecoder.household("home", fields)
        assertEquals(0L, legacy.openingBalanceGrosze)
        assertEquals(0L, legacy.ledgerRevision)
        for (field in listOf("openingBalanceGrosze", "ledgerRevision")) {
            for (value in listOf(null, "0", 0, 0.0)) {
                rejected { FirestoreDocumentDecoder.household("home", fields + (field to value)) }
            }
        }
        rejected { FirestoreDocumentDecoder.household("home", fields + ("ledgerRevision" to -1L)) }
        assertEquals(Long.MIN_VALUE, FirestoreDocumentDecoder.household("home",
            fields + ("openingBalanceGrosze" to Long.MIN_VALUE)).openingBalanceGrosze)
        for (field in listOf("name", "ownerId", "createdAt", "updatedAt")) {
            rejected { FirestoreDocumentDecoder.household("home", fields - field) }
        }
    }

    @Test fun analogousModelsUseStrictEnumsBooleansOptionsAndScopedIds() {
        val legacyCategory = FirestoreDocumentDecoder.category("food", category() - "color" - "defaultEntryType")
        assertNull(legacyCategory.color)
        assertEquals(EntryType.EXPENSE, legacyCategory.defaultEntryType)
        for (change in listOf("defaultEntryType" to "UNKNOWN", "defaultEntryType" to 0L, "defaultEntryType" to null,
            "archived" to "false", "archived" to null, "color" to "unknown", "color" to 1L)) {
            rejected { FirestoreDocumentDecoder.category("food", category() + change) }
        }
        rejected { FirestoreDocumentDecoder.category("food", category(), expectedHouseholdId = "other") }
        val subcategory = category() - "color" - "defaultEntryType" + ("categoryId" to "food")
        assertEquals("food", FirestoreDocumentDecoder.subcategory("shop", subcategory, expectedCategoryId = "food").categoryId)
        rejected { FirestoreDocumentDecoder.subcategory("shop", subcategory, expectedCategoryId = "other") }
        val order = mapOf("householdId" to "home", "userId" to "actor", "categoryIds" to listOf("food"), "updatedAt" to stamp)
        assertEquals(listOf("food"), FirestoreDocumentDecoder.categoryOrder("home", order).categoryIds)
        rejected { FirestoreDocumentDecoder.categoryOrder("other", order) }
        rejected { FirestoreDocumentDecoder.categoryOrder("home", order, expectedUserId = "other") }
        for (value in listOf(null, "food", listOf("food", 1L), listOf("food", "food"))) {
            rejected { FirestoreDocumentDecoder.categoryOrder("home", order + ("categoryIds" to value)) }
        }
        val profile = audit + mapOf("email" to "owner@example.test", "householdId" to "home", "displayName" to null)
        assertEquals("home", FirestoreDocumentDecoder.user("owner", profile).householdId)
        assertNull(FirestoreDocumentDecoder.user("owner", profile + ("displayName" to "  ")).displayName)
        assertEquals("Owner", FirestoreDocumentDecoder.user("owner", profile + ("displayName" to " Owner ")).displayName)
        rejected { FirestoreDocumentDecoder.user("owner", profile + ("displayName" to 123L)) }
        rejected { FirestoreDocumentDecoder.user("owner", profile + ("email" to "invalid")) }
        val member = mapOf("email" to "owner@example.test", "displayName" to null, "role" to "OWNER", "joinedAt" to stamp)
        assertEquals(MemberRole.OWNER, FirestoreDocumentDecoder.member("owner", member).role)
        rejected { FirestoreDocumentDecoder.member("owner", member + ("role" to "UNKNOWN")) }
        rejected { FirestoreDocumentDecoder.member("owner", member - "joinedAt") }
        val invitation = mapOf("householdId" to "home", "email" to "owner@example.test", "invitedBy" to "owner",
            "status" to "PENDING", "expiresAt" to stamp, "createdAt" to stamp, "acceptedBy" to null)
        assertEquals(InvitationStatus.PENDING, FirestoreDocumentDecoder.invitation("invite", invitation).status)
        rejected { FirestoreDocumentDecoder.invitation("invite", invitation + ("status" to "UNKNOWN")) }
        rejected { FirestoreDocumentDecoder.invitation("invite", invitation + ("expiresAt" to null), true) }
        rejected { FirestoreDocumentDecoder.invitation("invite", invitation + ("acceptedBy" to 1L)) }
        for (status in listOf("PENDING", "REVOKED")) {
            rejected { FirestoreDocumentDecoder.invitation("invite", invitation + mapOf("status" to status, "acceptedBy" to "actor")) }
        }
        for (fields in listOf(invitation + ("status" to "ACCEPTED"), invitation + ("status" to "ACCEPTED") - "acceptedBy")) {
            assertEquals("acceptedBy", rejected { FirestoreDocumentDecoder.invitation("invite", fields) }.field)
        }
        assertEquals("actor", FirestoreDocumentDecoder.invitation("invite",
            invitation + mapOf("status" to "ACCEPTED", "acceptedBy" to "actor")).acceptedById)
        rejected { FirestoreDocumentDecoder.invitation("invite", invitation, expectedHouseholdId = "other") }
    }

    @Test fun diagnosticFingerprintsAreStableScopedAndNeverRetainRawPayloadOrCause() {
        val path = "households/private-home/entries/private-entry"
        val fingerprint = fingerprintFirestoreDocumentPath(path)
        assertEquals(64, fingerprint.length)
        assertEquals(fingerprint, fingerprintFirestoreDocumentPath(path))
        assertNotEquals(fingerprint, fingerprintFirestoreDocumentPath("households/other/entries/private-entry"))
        val error = rejected { FirestoreDocumentDecoder.ledgerEntry("entry", entry() + ("amountGrosze" to "private-money")) }
            .atDocumentPath(path)
        assertEquals(fingerprint, error.documentFingerprint)
        assertNull(error.cause)
        val diagnostic = error.toString() + error.stackTraceToString()
        for (sensitive in listOf("private-home", "private-entry", "private-money", path)) assertFalse(diagnostic.contains(sensitive))
        assertTrue(diagnostic.contains("amountGrosze"))
    }

    private fun rejected(block: () -> Any?): FirestoreDecodeException =
        assertThrows(FirestoreDecodeException::class.java) { block() }
}
