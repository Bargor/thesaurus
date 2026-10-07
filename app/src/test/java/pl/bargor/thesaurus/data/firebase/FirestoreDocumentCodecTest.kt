package pl.bargor.thesaurus.data.firebase

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import pl.bargor.thesaurus.data.model.Category
import pl.bargor.thesaurus.data.model.CategoryOrder
import pl.bargor.thesaurus.data.model.EntryType
import pl.bargor.thesaurus.data.model.Invitation
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.model.Member
import pl.bargor.thesaurus.data.model.MemberRole
import pl.bargor.thesaurus.data.model.User

class FirestoreDocumentCodecTest {
    private val created = Instant.parse("2026-10-06T12:00:00.123456789Z")

    @Test fun ledgerSerializationPreservesSignedAmountNormalizesTextAndClearsDeletionMetadata() {
        val entry = LedgerEntry("entry", "home", -12345, LocalDate.of(2026, 10, 6),
            title = "  Zakupy  ", categoryId = "food", subcategoryId = "shop",
            tags = listOf(" Food ", "FOOD", "a"), authorId = "author", updatedById = "editor")
        val document = entry.toDocument()
        assertEquals(-12345L, document["amountGrosze"])
        assertEquals("2026-10-06", document["date"])
        assertEquals("Zakupy", document["title"])
        assertEquals(listOf("a", "food"), document["tags"])
        assertEquals("author", document["authorId"])
        assertEquals("editor", document["updatedById"])
        assertEquals(false, document["deleted"])
        assertNull(document["deletedAt"])
        assertNull(document["deletedById"])
        assertEquals(FieldValue.serverTimestamp(), document["createdAt"])
        assertEquals(FieldValue.serverTimestamp(), document["updatedAt"])

        // A merge must preserve the immutable server creation timestamp on updates.
        assertFalse(entry.copy(createdAt = created).toDocument().containsKey("createdAt"))
        val tombstone = entry.copy(deleted = true, deletedById = "editor", deletedAt = created).toDocument()
        assertEquals(true, tombstone["deleted"])
        assertEquals("editor", tombstone["deletedById"])
        assertEquals(FieldValue.serverTimestamp(), tombstone["deletedAt"])
    }

    @Test fun profileAndMembershipSerializationPreserveEmailNormalizationAndTimestampPolicies() {
        val profile = User("author", " ALICE@EXAMPLE.TEST ", "home", "  Alice  ", createdAt = created).toDocument()
        assertEquals("alice@example.test", profile["email"])
        assertEquals("Alice", profile["displayName"])
        assertEquals("home", profile["householdId"])
        assertFalse(profile.containsKey("createdAt"))
        assertEquals(FieldValue.serverTimestamp(), profile["updatedAt"])

        val member = Member("author", " ALICE@EXAMPLE.TEST ", "  ", MemberRole.MEMBER,
            invitationId = "invite", joinedAt = created).toDocument()
        assertEquals("alice@example.test", member["email"])
        assertNull(member["displayName"])
        assertEquals("MEMBER", member["role"])
        assertEquals("invite", member["invitationId"])
        assertEquals(Timestamp(created.epochSecond, created.nano), member["joinedAt"])
    }

    @Test fun taxonomySerializationRetainsColorDirectionAndUserScopedOrder() {
        val category = Category("food", "home", "  Food  ", color = "mint", archived = true,
            defaultEntryType = EntryType.INCOME, authorId = "author", updatedById = "editor", createdAt = created)
            .toDocument()
        assertEquals("Food", category["name"])
        assertEquals("mint", category["color"])
        assertEquals("INCOME", category["defaultEntryType"])
        assertEquals(true, category["archived"])
        assertFalse(category.containsKey("createdAt"))
        val order = CategoryOrder("home", "author", listOf("food", "other")).toDocument()
        assertEquals("home", order["householdId"])
        assertEquals("author", order["userId"])
        assertEquals(listOf("food", "other"), order["categoryIds"])
        assertEquals(FieldValue.serverTimestamp(), order["updatedAt"])
    }

    @Test fun invitationSerializationPreservesNanosecondTimestampsAndPendingStatus() {
        val invitation = Invitation("invite", "home", " ALICE@EXAMPLE.TEST ", "owner", created.plusSeconds(60),
            createdAt = created).toDocument()
        assertEquals("alice@example.test", invitation["email"])
        assertEquals("PENDING", invitation["status"])
        assertNull(invitation["acceptedBy"])
        assertEquals(Timestamp(created.epochSecond, created.nano), invitation["createdAt"])
        assertEquals(Timestamp(created.epochSecond + 60, created.nano), invitation["expiresAt"])
    }
}
