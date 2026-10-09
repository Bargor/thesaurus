package pl.bargor.thesaurus.data.firebase

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import kotlinx.coroutines.CancellationException
import pl.bargor.thesaurus.data.model.Category
import pl.bargor.thesaurus.data.model.CategoryOrder
import pl.bargor.thesaurus.data.model.CategoryPalette
import pl.bargor.thesaurus.data.model.Household
import pl.bargor.thesaurus.data.model.Invitation
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.model.Member
import pl.bargor.thesaurus.data.model.Subcategory
import pl.bargor.thesaurus.data.model.User
import java.time.Instant
import java.util.Locale

/** Missing documents are valid null results; existing documents must decode in full. */
private fun <T> DocumentSnapshot.decodeDocument(type: FirestoreDocumentType,
    decoder: (String, Map<String, Any?>, Boolean) -> T): T? {
    try {
        if (!exists()) return null
        val fields = data ?: throw FirestoreDecodeException(type, "_document", FirestoreDecodeReason.MISSING_FIELD)
        return decoder(id, fields, metadata.hasPendingWrites())
    } catch (error: FirestoreDecodeException) {
        throw error.atDocumentPath(reference.path)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        throw FirestoreDecodeException(type, "_document", FirestoreDecodeReason.INVALID_VALUE)
            .atDocumentPath(reference.path)
    }
}

internal fun DocumentSnapshot.ledgerRevision(): Long = try {
    FirestoreDocumentDecoder.ledgerRevision(data ?: throw FirestoreDecodeException(
        FirestoreDocumentType.HOUSEHOLD, "_document", FirestoreDecodeReason.MISSING_FIELD))
} catch (error: FirestoreDecodeException) {
    throw error.atDocumentPath(reference.path)
} catch (error: CancellationException) {
    throw error
} catch (_: Exception) {
    throw FirestoreDecodeException(FirestoreDocumentType.HOUSEHOLD, "_document", FirestoreDecodeReason.INVALID_VALUE)
        .atDocumentPath(reference.path)
}
private fun Instant.toTimestamp() = Timestamp(epochSecond, nano)

internal fun User.toDocument() = buildMap<String, Any?> {
    put("email", email.trim().lowercase(Locale.ROOT))
    put("householdId", householdId)
    put("displayName", displayName?.trim()?.takeIf(String::isNotEmpty))
    put("updatedAt", FieldValue.serverTimestamp())
    if (createdAt == null) put("createdAt", FieldValue.serverTimestamp())
}

internal fun DocumentSnapshot.toUser(): User? =
    decodeDocument(FirestoreDocumentType.USER) { id, fields, pending ->
        FirestoreDocumentDecoder.user(id, fields, pending)
    }

internal fun Household.toDocument() = buildMap<String, Any?> {
    put("name", name.trim())
    put("ownerId", ownerId)
    put("updatedAt", FieldValue.serverTimestamp())
    if (createdAt == null) put("createdAt", FieldValue.serverTimestamp())
}

internal fun DocumentSnapshot.toHousehold(): Household? =
    decodeDocument(FirestoreDocumentType.HOUSEHOLD) { id, fields, pending ->
        FirestoreDocumentDecoder.household(id, fields, pending)
    }

internal fun LedgerEntry.toDocument() = buildMap<String, Any?> {
    put("householdId", householdId)
    put("amountGrosze", amountGrosze)
    put("date", date.toString())
    put("title", normalizedTitle)
    put("categoryId", categoryId)
    put("subcategoryId", subcategoryId)
    put("tags", normalizedTags)
    put("authorId", authorId)
    put("updatedById", updatedById)
    if (createdAt == null) put("createdAt", FieldValue.serverTimestamp())
    put("updatedAt", FieldValue.serverTimestamp())
    put("deleted", deleted)
    put("deletedAt", if (deleted) FieldValue.serverTimestamp() else null)
    put("deletedById", if (deleted) deletedById else null)
}

internal fun DocumentSnapshot.toLedgerEntry(expectedHouseholdId: String? = null): LedgerEntry? =
    decodeDocument(FirestoreDocumentType.LEDGER_ENTRY) { id, fields, pending ->
        FirestoreDocumentDecoder.ledgerEntry(id, fields, pending, expectedHouseholdId)
    }

internal fun Category.toDocument() = buildMap<String, Any?> {
    put("householdId", householdId)
    put("name", name.trim())
    put("color", CategoryPalette.forCategory(this@toDocument).token)
    put("archived", archived)
    put("defaultEntryType", defaultEntryType.name)
    put("authorId", authorId)
    put("updatedById", updatedById)
    put("updatedAt", FieldValue.serverTimestamp())
    if (createdAt == null) put("createdAt", FieldValue.serverTimestamp())
}

internal fun DocumentSnapshot.toCategory(expectedHouseholdId: String? = null): Category? =
    decodeDocument(FirestoreDocumentType.CATEGORY) { id, fields, pending ->
        FirestoreDocumentDecoder.category(id, fields, pending, expectedHouseholdId)
    }

internal fun CategoryOrder.toDocument() = mapOf(
    "householdId" to householdId,
    "userId" to userId,
    "categoryIds" to categoryIds,
    "updatedAt" to FieldValue.serverTimestamp(),
)

internal fun DocumentSnapshot.toCategoryOrder(expectedHouseholdId: String? = null, expectedUserId: String? = null): CategoryOrder? =
    decodeDocument(FirestoreDocumentType.CATEGORY_ORDER) { id, fields, pending ->
        FirestoreDocumentDecoder.categoryOrder(id, fields, pending, expectedHouseholdId, expectedUserId)
    }

internal fun Subcategory.toDocument() = buildMap<String, Any?> {
    put("householdId", householdId)
    put("categoryId", categoryId)
    put("name", name.trim())
    put("archived", archived)
    put("authorId", authorId)
    put("updatedById", updatedById)
    put("updatedAt", FieldValue.serverTimestamp())
    if (createdAt == null) put("createdAt", FieldValue.serverTimestamp())
}

internal fun DocumentSnapshot.toSubcategory(expectedHouseholdId: String? = null, expectedCategoryId: String? = null): Subcategory? =
    decodeDocument(FirestoreDocumentType.SUBCATEGORY) { id, fields, pending ->
        FirestoreDocumentDecoder.subcategory(id, fields, pending, expectedHouseholdId, expectedCategoryId)
    }

internal fun Invitation.toDocument() = mapOf(
    "householdId" to householdId,
    "email" to email.trim().lowercase(Locale.ROOT),
    "invitedBy" to invitedBy,
    "expiresAt" to expiresAt.toTimestamp(),
    "status" to status.name,
    "acceptedBy" to acceptedById,
    "createdAt" to (createdAt?.toTimestamp() ?: FieldValue.serverTimestamp()),
)
internal fun DocumentSnapshot.toInvitation(expectedHouseholdId: String? = null): Invitation? =
    decodeDocument(FirestoreDocumentType.INVITATION) { id, fields, pending ->
        FirestoreDocumentDecoder.invitation(id, fields, pending, expectedHouseholdId)
    }

internal fun Member.toDocument() = mapOf(
    "email" to email.trim().lowercase(Locale.ROOT),
    "displayName" to displayName?.trim()?.takeIf(String::isNotEmpty),
    "role" to role.name,
    "invitationId" to invitationId,
    "joinedAt" to (joinedAt?.toTimestamp() ?: FieldValue.serverTimestamp()),
)

internal fun DocumentSnapshot.toMember(): Member? =
    decodeDocument(FirestoreDocumentType.MEMBER) { id, fields, pending ->
        FirestoreDocumentDecoder.member(id, fields, pending)
    }
