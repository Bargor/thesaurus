package pl.bargor.thesaurus.data.firebase

import com.google.firebase.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import pl.bargor.thesaurus.data.model.*

enum class FirestoreDocumentType { USER, HOUSEHOLD, MEMBER, LEDGER_ENTRY, CATEGORY, SUBCATEGORY, CATEGORY_ORDER, INVITATION, UNKNOWN }
enum class FirestoreDecodeReason { MISSING_FIELD, WRONG_TYPE, INVALID_VALUE, CONTEXT_MISMATCH }

/** Diagnostics contain schema labels only, never payload values, document paths or original causes. */
class FirestoreDecodeException internal constructor(
    val documentType: FirestoreDocumentType,
    val field: String,
    val reason: FirestoreDecodeReason,
    val documentFingerprint: String? = null,
) : IllegalArgumentException("Nieprawidłowe dane Firestore: ${documentType.name}, $field, ${reason.name}" +
    (documentFingerprint?.let { ", $it" } ?: "") + ".")

/** Hash only a known scoped document path to correlate diagnostics without exposing private IDs. */
internal fun fingerprintFirestoreDocumentPath(path: String): String = MessageDigest.getInstance("SHA-256")
    .digest(path.toByteArray(Charsets.UTF_8)).joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

internal fun FirestoreDecodeException.atDocumentPath(path: String): FirestoreDecodeException =
    FirestoreDecodeException(documentType, field, reason, fingerprintFirestoreDocumentPath(path))

/**
 * Map decoding is shared by listeners and one-shot reads. Audit timestamps must be present;
 * null audit timestamps require a pending server-timestamp write. Missing settled timestamps fail.
 * Nullable optional text/color/active deletion metadata allow absent/null, never a wrong type.
 * Legacy omissions: household balance/revision default to zero, category color to null,
 * category direction to expense, entry tags to empty. Present wrong types/unknown values never default.
 */
internal object FirestoreDocumentDecoder {
    private fun <T> decode(type: FirestoreDocumentType, id: String, fields: Map<String, Any?>,
        pending: Boolean, body: Fields.() -> T): T {
        val reader = Fields(type, fields, pending)
        return try {
            reader.validId("_id", id)
            reader.body()
        } catch (error: FirestoreDecodeException) {
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            throw FirestoreDecodeException(type, "_document", FirestoreDecodeReason.INVALID_VALUE)
        }
    }

    fun user(id: String, fields: Map<String, Any?>, hasPendingWrites: Boolean = false): User =
        decode(FirestoreDocumentType.USER, id, fields, hasPendingWrites) {
            User(id, email(), id("householdId"), optionalText("displayName", 80, blankAsNull = true), timestamp("createdAt"), timestamp("updatedAt"))
        }

    fun household(id: String, fields: Map<String, Any?>, hasPendingWrites: Boolean = false): Household =
        decode(FirestoreDocumentType.HOUSEHOLD, id, fields, hasPendingWrites) {
            Household(id, text("name", 80), id("ownerId"), timestamp("createdAt"), timestamp("updatedAt"),
                long("openingBalanceGrosze", absentDefault = 0), long("ledgerRevision", absentDefault = 0).also {
                    valid("ledgerRevision", it >= 0)
                })
        }

    fun member(id: String, fields: Map<String, Any?>, hasPendingWrites: Boolean = false): Member =
        decode(FirestoreDocumentType.MEMBER, id, fields, hasPendingWrites) {
            Member(id, email(), optionalText("displayName", 80, blankAsNull = true), enumValue<MemberRole>("role"),
                optionalId("invitationId"), timestamp("joinedAt"))
        }

    fun ledgerEntry(id: String, fields: Map<String, Any?>, hasPendingWrites: Boolean = false,
        expectedHouseholdId: String? = null): LedgerEntry =
        decode(FirestoreDocumentType.LEDGER_ENTRY, id, fields, hasPendingWrites) {
            val household = contextId("householdId", expectedHouseholdId)
            val amount = long("amountGrosze").also { valid("amountGrosze", it != 0L) }
            val date = date()
            val deleted = boolean("deleted")
            val deletedBy = optionalId("deletedById")
            val deletedAt = timestamp("deletedAt", nullable = !deleted, required = deleted)
            valid("deletedById", if (deleted) deletedBy != null else deletedBy == null)
            valid("deletedAt", deleted || deletedAt == null)
            LedgerEntry(id, household, amount, date, optionalText("title", 160, blankAsNull = true), id("categoryId"),
                optionalId("subcategoryId"), normalizeTags(strings("tags", 10, 40, absentEmpty = true, allowDuplicates = true)), id("authorId"), id("updatedById"),
                timestamp("createdAt"), timestamp("updatedAt"), deleted, deletedAt, deletedBy)
        }

    fun category(id: String, fields: Map<String, Any?>, hasPendingWrites: Boolean = false,
        expectedHouseholdId: String? = null): Category =
        decode(FirestoreDocumentType.CATEGORY, id, fields, hasPendingWrites) {
            val color = optionalText("color", 40)?.also { valid("color", CategoryPalette.isToken(it)) }
            Category(id, contextId("householdId", expectedHouseholdId), text("name", 60), color,
                boolean("archived"), enumValue("defaultEntryType", EntryType.EXPENSE), id("authorId"), id("updatedById"),
                timestamp("createdAt"), timestamp("updatedAt"))
        }

    fun subcategory(id: String, fields: Map<String, Any?>, hasPendingWrites: Boolean = false,
        expectedHouseholdId: String? = null, expectedCategoryId: String? = null): Subcategory =
        decode(FirestoreDocumentType.SUBCATEGORY, id, fields, hasPendingWrites) {
            Subcategory(id, contextId("householdId", expectedHouseholdId), contextId("categoryId", expectedCategoryId),
                text("name", 60), boolean("archived"), id("authorId"), id("updatedById"),
                timestamp("createdAt"), timestamp("updatedAt"))
        }

    fun categoryOrder(id: String, fields: Map<String, Any?>, hasPendingWrites: Boolean = false,
        expectedHouseholdId: String? = null, expectedUserId: String? = null): CategoryOrder =
        decode(FirestoreDocumentType.CATEGORY_ORDER, id, fields, hasPendingWrites) {
            val household = contextId("householdId", expectedHouseholdId)
            valid("householdId", household == id, FirestoreDecodeReason.CONTEXT_MISMATCH)
            CategoryOrder(household, contextId("userId", expectedUserId),
                strings("categoryIds", CategoryOrder.MAX_CATEGORIES, 128).onEach { validId("categoryIds", it) }, timestamp("updatedAt"))
        }

    fun invitation(id: String, fields: Map<String, Any?>, hasPendingWrites: Boolean = false,
        expectedHouseholdId: String? = null): Invitation =
        decode(FirestoreDocumentType.INVITATION, id, fields, hasPendingWrites) {
            val status = enumValue<InvitationStatus>("status")
            val acceptedBy = optionalId("acceptedBy")
            valid("acceptedBy", if (status == InvitationStatus.ACCEPTED) acceptedBy != null else acceptedBy == null)
            Invitation(id, contextId("householdId", expectedHouseholdId), email(), id("invitedBy"),
                requireNotNull(timestamp("expiresAt", required = true, pendingNull = false)),
                status, acceptedBy, timestamp("createdAt"))
        }

    fun ledgerRevision(fields: Map<String, Any?>): Long = Fields(FirestoreDocumentType.HOUSEHOLD, fields, false)
        .run { long("ledgerRevision", absentDefault = 0).also { valid("ledgerRevision", it >= 0) } }

    private class Fields(val type: FirestoreDocumentType, val fields: Map<String, Any?>, val pending: Boolean) {
        fun fail(field: String, reason: FirestoreDecodeReason): Nothing = throw FirestoreDecodeException(type, field, reason)
        fun valid(field: String, condition: Boolean, reason: FirestoreDecodeReason = FirestoreDecodeReason.INVALID_VALUE) {
            if (!condition) fail(field, reason)
        }
        private fun required(field: String): Any {
            if (!fields.containsKey(field)) fail(field, FirestoreDecodeReason.MISSING_FIELD)
            return fields[field] ?: fail(field, FirestoreDecodeReason.WRONG_TYPE)
        }
        fun text(field: String, maximum: Int): String {
            val value = required(field) as? String ?: fail(field, FirestoreDecodeReason.WRONG_TYPE)
            valid(field, value.isNotBlank() && value.length <= maximum)
            return value
        }
        fun optionalText(field: String, maximum: Int, blankAsNull: Boolean = false): String? = fields[field]?.let {
            val value = it as? String ?: fail(field, FirestoreDecodeReason.WRONG_TYPE)
            valid(field, value.length <= maximum && (blankAsNull || value.isNotBlank()))
            if (blankAsNull) value.trim().takeIf { trimmed -> trimmed.isNotEmpty() } else value
        }
        fun validId(field: String, value: String): String {
            valid(field, value.isNotBlank() && value.length <= 128 && '/' !in value)
            return value
        }
        fun id(field: String): String = validId(field, text(field, 128))
        fun optionalId(field: String): String? = optionalText(field, 128)?.let { validId(field, it) }
        fun contextId(field: String, expected: String?): String = id(field).also {
            valid(field, expected == null || it == expected, FirestoreDecodeReason.CONTEXT_MISMATCH)
        }
        fun email(): String = text("email", 254).also {
            valid("email", EMAIL.matches(it))
        }
        fun long(field: String, absentDefault: Long? = null): Long {
            if (!fields.containsKey(field) && absentDefault != null) return absentDefault
            return required(field) as? Long ?: fail(field, FirestoreDecodeReason.WRONG_TYPE)
        }
        fun boolean(field: String): Boolean = required(field) as? Boolean ?: fail(field, FirestoreDecodeReason.WRONG_TYPE)
        inline fun <reified T : Enum<T>> enumValue(field: String, absentDefault: T? = null): T {
            if (!fields.containsKey(field) && absentDefault != null) return absentDefault
            val value = text(field, 40)
            return enumValues<T>().firstOrNull { it.name == value } ?: fail(field, FirestoreDecodeReason.INVALID_VALUE)
        }
        fun strings(field: String, maximum: Int, itemMaximum: Int, absentEmpty: Boolean = false, allowDuplicates: Boolean = false): List<String> {
            if (!fields.containsKey(field) && absentEmpty) return emptyList()
            val list = required(field) as? List<*> ?: fail(field, FirestoreDecodeReason.WRONG_TYPE)
            valid(field, list.size <= maximum)
            val strings = list.map {
                val value = it as? String ?: fail(field, FirestoreDecodeReason.WRONG_TYPE)
                valid(field, value.isNotBlank() && value.length <= itemMaximum)
                value
            }
            valid(field, allowDuplicates || strings.distinct().size == strings.size)
            return strings
        }
        fun date(): LocalDate {
            val value = text("date", 10)
            valid("date", DATE.matches(value))
            return try { LocalDate.parse(value) } catch (_: DateTimeParseException) { fail("date", FirestoreDecodeReason.INVALID_VALUE) }
        }
        fun timestamp(field: String, nullable: Boolean = false, required: Boolean = true, pendingNull: Boolean = true): Instant? {
            if (!fields.containsKey(field)) {
                if (required) fail(field, FirestoreDecodeReason.MISSING_FIELD)
                return null
            }
            val value = fields[field] ?: return if (nullable || (pending && pendingNull)) null
                else fail(field, FirestoreDecodeReason.WRONG_TYPE)
            val timestamp = value as? Timestamp ?: fail(field, FirestoreDecodeReason.WRONG_TYPE)
            return Instant.ofEpochSecond(timestamp.seconds, timestamp.nanoseconds.toLong())
        }
    }
    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    private val DATE = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
}
