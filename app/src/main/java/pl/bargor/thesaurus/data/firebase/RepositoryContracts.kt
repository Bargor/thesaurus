package pl.bargor.thesaurus.data.firebase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import pl.bargor.thesaurus.data.model.Category
import pl.bargor.thesaurus.data.model.CategoryOrder
import pl.bargor.thesaurus.data.model.Household
import pl.bargor.thesaurus.data.model.Invitation
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.model.Member
import pl.bargor.thesaurus.data.model.Subcategory
import pl.bargor.thesaurus.data.model.SyncObservation
import pl.bargor.thesaurus.data.model.SyncState
import pl.bargor.thesaurus.data.model.User
import pl.bargor.thesaurus.data.model.orderedBy
import pl.bargor.thesaurus.data.observation.reduceSyncState

/** Firestore paths are intentionally centralized so security rules and data contracts stay aligned. */
object FirestorePaths {
    const val USERS = "users"
    const val HOUSEHOLDS = "households"
    const val MEMBERS = "members"
    const val ENTRIES = "entries"
    const val CATEGORIES = "categories"
    const val CATEGORY_ORDERS = "categoryOrders"
    const val SUBCATEGORIES = "subcategories"
    const val INVITATIONS = "invitations"
}

interface UserRepository {
    fun observeUser(userId: String): Flow<SyncObservation<User>>
    suspend fun save(user: User)
}

interface HouseholdRepository {
    fun observeHousehold(householdId: String): Flow<SyncObservation<Household>>
    fun observeMembers(householdId: String): Flow<SyncObservation<List<Member>>>
    suspend fun saveHousehold(household: Household)
    /** Owners may remove another member; Firestore Rules protect the owner document. */
    suspend fun removeMember(householdId: String, memberId: String)
}

interface LedgerRepository {
    fun observeEntries(
        householdId: String,
        includeDeleted: Boolean = false,
    ): Flow<SyncObservation<List<LedgerEntry>>>
    suspend fun save(entry: LedgerEntry)
    suspend fun tombstone(householdId: String, entryId: String, actorId: String)
}

interface OpeningBalanceRepository {
    /** Queued offline like other owner writes; listeners expose the pending value. */
    suspend fun saveOpeningBalance(householdId: String, amountGrosze: Long)
    /** Requires a complete server ledger and rejects any concurrent ledger/settings change. */
    suspend fun saveCurrentBalance(householdId: String, currentBalanceGrosze: Long)
}

/** Internal immutable server snapshot token; never persisted or exposed by the UI contract. */
internal data class PreparedCurrentBalance(
    val householdId: String,
    val expectedRevision: Long,
    val expectedOpeningBalanceGrosze: Long?,
    val derivedOpeningBalanceGrosze: Long,
)

interface TaxonomyRepository {
    fun observeCategories(householdId: String): Flow<SyncObservation<List<Category>>>
    fun observeSubcategories(
        householdId: String,
        categoryId: String,
    ): Flow<SyncObservation<List<Subcategory>>>
    suspend fun save(category: Category)
    suspend fun save(subcategory: Subcategory)

    /** Defaults keep lightweight test repositories source-compatible until ordering is exercised. */
    fun observeCategoryOrder(
        householdId: String,
        userId: String,
    ): Flow<SyncObservation<CategoryOrder>> = flowOf(SyncObservation(state = SyncState.SYNCED))

    suspend fun saveCategoryOrder(order: CategoryOrder) {
        error("Zapisywanie kolejności kategorii nie jest obsługiwane.")
    }
}

/** One ordering contract shared by management and entry forms. */
fun TaxonomyRepository.observeOrderedCategories(
    householdId: String,
    userId: String,
): Flow<SyncObservation<List<Category>>> = combine(
    observeCategories(householdId),
    observeCategoryOrder(householdId, userId),
) { categories, order ->
    SyncObservation(
        value = categories.value?.orderedBy(order.value),
        state = reduceSyncState(listOf(categories, order)),
        error = categories.error ?: order.error,
    )
}

interface InvitationRepository {
    /** Household-wide listing is owner-only under Firestore Rules. */
    fun observeInvitations(householdId: String): Flow<SyncObservation<List<Invitation>>>
    /** Invitees read a known random token directly; they never list addressed invitations. */
    fun observeInvitation(
        householdId: String,
        invitationId: String,
    ): Flow<SyncObservation<Invitation>>
    suspend fun create(invitation: Invitation)
    suspend fun revoke(householdId: String, invitationId: String)
    suspend fun accept(invitation: Invitation, member: Member)
}

/** A signed-in Firebase principal without coupling onboarding to the Firebase SDK. */
data class OnboardingIdentity(val uid: String, val email: String, val displayName: String?)

sealed interface FirstHouseholdResult {
    data class Created(val householdId: String) : FirstHouseholdResult
    data class Existing(val householdId: String) : FirstHouseholdResult
}

interface OnboardingRepository {
    /** Uses Firestore's normal server-then-cache policy so an existing household can open offline. */
    suspend fun householdIdFor(uid: String): String?
    /**
     * Creates profile, household, owner membership and starter taxonomy in one Firestore transaction.
     * A retry returns [FirstHouseholdResult.Existing], never a second household.
     */
    suspend fun createFirstHousehold(identity: OnboardingIdentity, householdName: String): FirstHouseholdResult
}
