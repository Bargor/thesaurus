package pl.bargor.thesaurus.data.model

import java.time.Instant
import java.time.LocalDate
import java.util.Locale

/** Values accepted by Firestore are deliberately small and validation lives at this boundary. */
private fun String.requiredId(field: String): String = trim().also {
    require(it.isNotEmpty()) { "$field nie może być puste." }
    require(it.length <= 128) { "$field jest zbyt długie." }
}

fun normalizeTags(tags: Collection<String>): List<String> = tags
    .asSequence()
    .map(String::trim)
    .filter(String::isNotEmpty)
    .map { it.lowercase(Locale.ROOT) }
    .onEach { require(it.length <= 40) { "Tag jest zbyt długi." } }
    .distinct()
    .sorted()
    .take(10)
    .toList()

enum class MemberRole { OWNER, MEMBER }
enum class InvitationStatus { PENDING, ACCEPTED, REVOKED }
enum class SyncState { SYNCED, PENDING, OFFLINE, ERROR }
enum class EntryType { INCOME, EXPENSE }

data class SyncObservation<T>(
    val value: T? = null,
    val state: SyncState,
    val error: Throwable? = null,
)

data class User(
    val id: String,
    val email: String,
    /** Immutable household assignment. A user belongs to exactly one household. */
    val householdId: String,
    val displayName: String? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
) {
    init {
        id.requiredId("Id użytkownika")
        require(email.trim().contains('@'))
        householdId.requiredId("Id gospodarstwa")
        require(displayName?.trim()?.length ?: 0 <= 80)
    }
}

data class Household(
    val id: String,
    val name: String,
    val ownerId: String,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
) {
    init {
        id.requiredId("Id gospodarstwa")
        require(name.trim().isNotEmpty() && name.trim().length <= 80)
        ownerId.requiredId("Właściciel")
    }
}

data class Member(
    val uid: String,
    val email: String,
    val displayName: String?,
    val role: MemberRole,
    val invitationId: String? = null,
    val joinedAt: Instant? = null,
) {
    init {
        uid.requiredId("Id członka")
        require(email.trim().contains('@')) { "Adres e-mail jest nieprawidłowy." }
        require(displayName?.trim()?.length ?: 0 <= 80)
    }
}

data class Category(
    val id: String,
    val householdId: String,
    val name: String,
    val color: String? = null,
    val archived: Boolean = false,
    val defaultEntryType: EntryType = EntryType.EXPENSE,
    val authorId: String,
    val updatedById: String,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
) {
    init {
        id.requiredId("Id kategorii")
        householdId.requiredId("Id gospodarstwa")
        require(name.trim().isNotEmpty() && name.trim().length <= 60)
        require(color?.trim()?.length ?: 0 <= 16) { "Kolor jest zbyt długi." }
        authorId.requiredId("Autor")
        updatedById.requiredId("Aktualizujący")
    }
}

data class Subcategory(
    val id: String,
    val householdId: String,
    val categoryId: String,
    val name: String,
    val archived: Boolean = false,
    val authorId: String,
    val updatedById: String,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
) {
    init {
        id.requiredId("Id podkategorii")
        householdId.requiredId("Id gospodarstwa")
        categoryId.requiredId("Id kategorii")
        require(name.trim().isNotEmpty() && name.trim().length <= 60)
        authorId.requiredId("Autor")
        updatedById.requiredId("Aktualizujący")
    }
}

data class Invitation(
    val id: String,
    val householdId: String,
    val email: String,
    val invitedBy: String,
    val expiresAt: Instant,
    val status: InvitationStatus = InvitationStatus.PENDING,
    val acceptedById: String? = null,
    val createdAt: Instant? = null,
) {
    init {
        id.requiredId("Id zaproszenia")
        householdId.requiredId("Id gospodarstwa")
        require(email.trim().contains('@'))
        invitedBy.requiredId("Zapraszający")
    }
}

/** Signed amount: expense is negative, income is positive. Zero is never a ledger value. */
data class LedgerEntry(
    val id: String,
    val householdId: String,
    val amountGrosze: Long,
    val date: LocalDate,
    val title: String? = null,
    val categoryId: String,
    val subcategoryId: String? = null,
    val tags: List<String> = emptyList(),
    val authorId: String,
    val updatedById: String,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    val deleted: Boolean = false,
    val deletedAt: Instant? = null,
    val deletedById: String? = null,
) {
    val type: EntryType get() = if (amountGrosze > 0) EntryType.INCOME else EntryType.EXPENSE
    val normalizedTitle: String? = title?.trim()?.takeIf(String::isNotEmpty)
    val normalizedTags: List<String> = normalizeTags(tags)

    init {
        id.requiredId("Id wpisu")
        householdId.requiredId("Id gospodarstwa")
        require(amountGrosze != 0L) { "Kwota wpisu nie może wynosić zero." }
        require(title?.trim()?.length ?: 0 <= 160) { "Tytuł jest zbyt długi." }
        categoryId.requiredId("Id kategorii")
        subcategoryId?.requiredId("Id podkategorii")
        authorId.requiredId("Autor")
        updatedById.requiredId("Aktualizujący")
        if (deleted) {
            require(deletedById != null) { "Usunięty wpis musi wskazywać usuwającego." }
        } else {
            require(deletedAt == null && deletedById == null) {
                "Aktywny wpis nie może zawierać metadanych usunięcia."
            }
        }
    }
}

data class SummaryPeriod(val from: LocalDate, val to: LocalDate) {
    init { require(!to.isBefore(from)) }
}

data class ReportPeriod(
    val from: LocalDate,
    val to: LocalDate,
    val categoryIds: Set<String> = emptySet(),
) {
    init {
        require(!to.isBefore(from))
        categoryIds.forEach { it.requiredId("Id kategorii") }
    }
}
