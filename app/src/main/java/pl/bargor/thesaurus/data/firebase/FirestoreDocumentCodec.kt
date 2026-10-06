package pl.bargor.thesaurus.data.firebase

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import pl.bargor.thesaurus.data.model.Category
import pl.bargor.thesaurus.data.model.CategoryOrder
import pl.bargor.thesaurus.data.model.CategoryPalette
import pl.bargor.thesaurus.data.model.EntryType
import pl.bargor.thesaurus.data.model.Household
import pl.bargor.thesaurus.data.model.Invitation
import pl.bargor.thesaurus.data.model.InvitationStatus
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.model.Member
import pl.bargor.thesaurus.data.model.MemberRole
import pl.bargor.thesaurus.data.model.Subcategory
import pl.bargor.thesaurus.data.model.User
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

/** Shared document mapping retains the existing legacy coercions and default values. */
private fun DocumentSnapshot.instant(name: String): Instant? = getTimestamp(name)?.toDate()?.toInstant()
internal fun DocumentSnapshot.ledgerRevision(): Long {
    val value = get("ledgerRevision") ?: return 0L
    check(value is Long && value >= 0L) { "Nieprawidłowa wersja księgi." }
    return value
}
private fun Any?.string() = this as? String
private fun Any?.long() = this as? Long ?: (this as? Number)?.toLong()
private fun Any?.boolean() = this as? Boolean ?: false
private fun Any?.stringList() = (this as? List<*>)?.filterIsInstance<String>().orEmpty()
private fun Instant.toTimestamp() = Timestamp(epochSecond, nano)

internal fun User.toDocument() = buildMap<String, Any?> {
    put("email", email.trim().lowercase(Locale.ROOT))
    put("householdId", householdId)
    put("displayName", displayName?.trim()?.takeIf(String::isNotEmpty))
    put("updatedAt", FieldValue.serverTimestamp())
    if (createdAt == null) put("createdAt", FieldValue.serverTimestamp())
}

internal fun DocumentSnapshot.toUser(): User? = data?.let { fields ->
    User(
        id = id,
        email = fields["email"].string() ?: return null,
        householdId = fields["householdId"].string() ?: return null,
        displayName = fields["displayName"].string(),
        createdAt = instant("createdAt"),
        updatedAt = instant("updatedAt"),
    )
}

internal fun Household.toDocument() = buildMap<String, Any?> {
    put("name", name.trim())
    put("ownerId", ownerId)
    put("updatedAt", FieldValue.serverTimestamp())
    if (createdAt == null) put("createdAt", FieldValue.serverTimestamp())
}

internal fun DocumentSnapshot.toHousehold(): Household? = data?.let { fields ->
    Household(
        id = id,
        name = fields["name"].string() ?: return null,
        ownerId = fields["ownerId"].string() ?: return null,
        createdAt = instant("createdAt"),
        updatedAt = instant("updatedAt"),
        openingBalanceGrosze = fields["openingBalanceGrosze"] as? Long ?: 0L,
        ledgerRevision = fields["ledgerRevision"] as? Long ?: 0L,
    )
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

internal fun DocumentSnapshot.toLedgerEntry(): LedgerEntry? = data?.let { fields ->
    val date = fields["date"].string()
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: return null
    LedgerEntry(
        id = id,
        householdId = fields["householdId"].string() ?: return null,
        amountGrosze = fields["amountGrosze"].long() ?: return null,
        date = date,
        title = fields["title"].string(),
        categoryId = fields["categoryId"].string() ?: return null,
        subcategoryId = fields["subcategoryId"].string(),
        tags = fields["tags"].stringList(),
        authorId = fields["authorId"].string() ?: return null,
        updatedById = fields["updatedById"].string() ?: return null,
        createdAt = instant("createdAt"),
        updatedAt = instant("updatedAt"),
        deleted = fields["deleted"].boolean(),
        deletedAt = instant("deletedAt"),
        deletedById = fields["deletedById"].string(),
    )
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

internal fun DocumentSnapshot.toCategory(): Category? {
    val fields = data ?: return null
    val defaultEntryType = runCatching {
        EntryType.valueOf(fields["defaultEntryType"].string() ?: EntryType.EXPENSE.name)
    }.getOrDefault(EntryType.EXPENSE)
    return Category(
        id = id,
        householdId = fields["householdId"].string() ?: return null,
        name = fields["name"].string() ?: return null,
        color = CategoryPalette.normalizedToken(fields["color"].string()),
        archived = fields["archived"].boolean(),
        defaultEntryType = defaultEntryType,
        authorId = fields["authorId"].string() ?: return null,
        updatedById = fields["updatedById"].string() ?: return null,
        createdAt = instant("createdAt"),
        updatedAt = instant("updatedAt"),
    )
}

internal fun CategoryOrder.toDocument() = mapOf(
    "householdId" to householdId,
    "userId" to userId,
    "categoryIds" to categoryIds,
    "updatedAt" to FieldValue.serverTimestamp(),
)

internal fun DocumentSnapshot.toCategoryOrder(): CategoryOrder? {
    val fields = data ?: return null
    return CategoryOrder(
        householdId = fields["householdId"].string() ?: return null,
        userId = fields["userId"].string() ?: return null,
        categoryIds = fields["categoryIds"].stringList(),
        updatedAt = instant("updatedAt"),
    )
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

internal fun DocumentSnapshot.toSubcategory(): Subcategory? {
    val fields = data ?: return null
    return Subcategory(
        id = id,
        householdId = fields["householdId"].string() ?: return null,
        categoryId = fields["categoryId"].string() ?: return null,
        name = fields["name"].string() ?: return null,
        archived = fields["archived"].boolean(),
        authorId = fields["authorId"].string() ?: return null,
        updatedById = fields["updatedById"].string() ?: return null,
        createdAt = instant("createdAt"),
        updatedAt = instant("updatedAt"),
    )
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
internal fun DocumentSnapshot.toInvitation(): Invitation? {
    val fields = data ?: return null
    val expiry = getTimestamp("expiresAt")?.toDate()?.toInstant() ?: return null
    val status = runCatching {
        InvitationStatus.valueOf(fields["status"].string() ?: InvitationStatus.PENDING.name)
    }.getOrDefault(InvitationStatus.PENDING)
    return Invitation(
        id = id,
        householdId = fields["householdId"].string() ?: return null,
        email = fields["email"].string() ?: return null,
        invitedBy = fields["invitedBy"].string() ?: return null,
        expiresAt = expiry,
        status = status,
        acceptedById = fields["acceptedBy"].string(),
        createdAt = instant("createdAt"),
    )
}

internal fun Member.toDocument() = mapOf(
    "email" to email.trim().lowercase(Locale.ROOT),
    "displayName" to displayName?.trim()?.takeIf(String::isNotEmpty),
    "role" to role.name,
    "invitationId" to invitationId,
    "joinedAt" to (joinedAt?.toTimestamp() ?: FieldValue.serverTimestamp()),
)

internal fun DocumentSnapshot.toMember(): Member? {
    val fields = data ?: return null
    val role = runCatching {
        MemberRole.valueOf(fields["role"].string() ?: MemberRole.MEMBER.name)
    }.getOrDefault(MemberRole.MEMBER)
    val email = fields["email"].string() ?: return null
    return Member(
        uid = id,
        email = email,
        displayName = fields["displayName"].string(),
        role = role,
        invitationId = fields["invitationId"].string(),
        joinedAt = instant("joinedAt"),
    )
}
