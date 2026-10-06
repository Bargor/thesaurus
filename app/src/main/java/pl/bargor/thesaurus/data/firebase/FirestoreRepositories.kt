package pl.bargor.thesaurus.data.firebase

import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SnapshotMetadata
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await
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
import pl.bargor.thesaurus.data.model.SyncObservation
import pl.bargor.thesaurus.data.model.SyncState
import pl.bargor.thesaurus.data.model.User
import pl.bargor.thesaurus.data.model.orderedBy
import pl.bargor.thesaurus.data.model.deriveOpeningBalance
import pl.bargor.thesaurus.data.observation.reduceSyncState
import pl.bargor.thesaurus.data.onboarding.StarterTaxonomy
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import javax.inject.Inject

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

/** Persistent local cache is enabled explicitly. Emulator routing is opt-in at creation time. */
object FirebaseFirestoreFactory {
    fun create(
        app: FirebaseApp = FirebaseApp.getInstance(),
        emulatorHost: String? = null,
        emulatorPort: Int = 8080,
    ): FirebaseFirestore =
        FirebaseFirestore.getInstance(app).also { firestore ->
            emulatorHost?.let { firestore.useEmulator(it, emulatorPort) }
            firestore.firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(PersistentCacheSettings.newBuilder().build())
                .build()
            firestore.persistentCacheIndexManager?.enableIndexAutoCreation()
        }
}

object FirebaseAuthFactory {
    fun create(app: FirebaseApp = FirebaseApp.getInstance()): FirebaseAuth =
        FirebaseAuth.getInstance(app)

    fun connectToLocalEmulator(
        auth: FirebaseAuth,
        host: String = "10.0.2.2",
        port: Int = 9099,
    ) {
        auth.useEmulator(host, port)
    }
}

fun syncState(hasPendingWrites: Boolean, fromCache: Boolean): SyncState = when {
    hasPendingWrites -> SyncState.PENDING
    fromCache -> SyncState.OFFLINE
    else -> SyncState.SYNCED
}

fun syncState(metadata: SnapshotMetadata): SyncState = syncState(metadata.hasPendingWrites(), metadata.isFromCache)

private fun <T> Query.observations(
    mapper: (DocumentSnapshot) -> T?,
): Flow<SyncObservation<List<T>>> = callbackFlow {
    val registration = addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
        when {
            error != null -> trySend(SyncObservation(state = SyncState.ERROR, error = error))
            snapshot != null -> trySend(
                SyncObservation(
                    value = snapshot.documents.mapNotNull(mapper),
                    state = syncState(snapshot.metadata),
                ),
            )
        }
    }
    awaitClose { registration.remove() }
}

private fun <T> DocumentReference.observations(
    mapper: (DocumentSnapshot) -> T?,
): Flow<SyncObservation<T>> = callbackFlow {
    val registration = addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
        when {
            error != null -> trySend(SyncObservation(state = SyncState.ERROR, error = error))
            snapshot != null -> trySend(
                SyncObservation(
                    value = mapper(snapshot),
                    state = syncState(snapshot.metadata),
                ),
            )
        }
    }
    awaitClose { registration.remove() }
}

private fun DocumentSnapshot.instant(name: String): Instant? = getTimestamp(name)?.toDate()?.toInstant()
private fun DocumentSnapshot.ledgerRevision(): Long {
    val value = get("ledgerRevision") ?: return 0L
    check(value is Long && value >= 0L) { "Nieprawidłowa wersja księgi." }
    return value
}
private fun Any?.string() = this as? String
private fun Any?.long() = this as? Long ?: (this as? Number)?.toLong()
private fun Any?.boolean() = this as? Boolean ?: false
private fun Any?.stringList() = (this as? List<*>)?.filterIsInstance<String>().orEmpty()
private fun Instant.toTimestamp() = Timestamp(epochSecond, nano)

private fun User.toDocument() = buildMap<String, Any?> {
    put("email", email.trim().lowercase(Locale.ROOT))
    put("householdId", householdId)
    put("displayName", displayName?.trim()?.takeIf(String::isNotEmpty))
    put("updatedAt", FieldValue.serverTimestamp())
    if (createdAt == null) put("createdAt", FieldValue.serverTimestamp())
}

private fun DocumentSnapshot.toUser(): User? = data?.let { fields ->
    User(
        id = id,
        email = fields["email"].string() ?: return null,
        householdId = fields["householdId"].string() ?: return null,
        displayName = fields["displayName"].string(),
        createdAt = instant("createdAt"),
        updatedAt = instant("updatedAt"),
    )
}

private fun Household.toDocument() = buildMap<String, Any?> {
    put("name", name.trim())
    put("ownerId", ownerId)
    put("updatedAt", FieldValue.serverTimestamp())
    if (createdAt == null) put("createdAt", FieldValue.serverTimestamp())
}

private fun DocumentSnapshot.toHousehold(): Household? = data?.let { fields ->
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

private fun LedgerEntry.toDocument() = buildMap<String, Any?> {
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

private fun DocumentSnapshot.toLedgerEntry(): LedgerEntry? = data?.let { fields ->
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

private fun Category.toDocument() = buildMap<String, Any?> {
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

private fun DocumentSnapshot.toCategory(): Category? {
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

private fun CategoryOrder.toDocument() = mapOf(
    "householdId" to householdId,
    "userId" to userId,
    "categoryIds" to categoryIds,
    "updatedAt" to FieldValue.serverTimestamp(),
)

private fun DocumentSnapshot.toCategoryOrder(): CategoryOrder? {
    val fields = data ?: return null
    return CategoryOrder(
        householdId = fields["householdId"].string() ?: return null,
        userId = fields["userId"].string() ?: return null,
        categoryIds = fields["categoryIds"].stringList(),
        updatedAt = instant("updatedAt"),
    )
}

private fun Subcategory.toDocument() = buildMap<String, Any?> {
    put("householdId", householdId)
    put("categoryId", categoryId)
    put("name", name.trim())
    put("archived", archived)
    put("authorId", authorId)
    put("updatedById", updatedById)
    put("updatedAt", FieldValue.serverTimestamp())
    if (createdAt == null) put("createdAt", FieldValue.serverTimestamp())
}

private fun DocumentSnapshot.toSubcategory(): Subcategory? {
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

private fun Invitation.toDocument() = mapOf(
    "householdId" to householdId,
    "email" to email.trim().lowercase(Locale.ROOT),
    "invitedBy" to invitedBy,
    "expiresAt" to expiresAt.toTimestamp(),
    "status" to status.name,
    "acceptedBy" to acceptedById,
    "createdAt" to (createdAt?.toTimestamp() ?: FieldValue.serverTimestamp()),
)
private fun DocumentSnapshot.toInvitation(): Invitation? {
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

private fun Member.toDocument() = mapOf(
    "email" to email.trim().lowercase(Locale.ROOT),
    "displayName" to displayName?.trim()?.takeIf(String::isNotEmpty),
    "role" to role.name,
    "invitationId" to invitationId,
    "joinedAt" to (joinedAt?.toTimestamp() ?: FieldValue.serverTimestamp()),
)

class FirestoreRepositories @Inject constructor(private val firestore: FirebaseFirestore) :
    UserRepository,
    HouseholdRepository,
    OpeningBalanceRepository,
    LedgerRepository,
    TaxonomyRepository,
    InvitationRepository,
    OnboardingRepository {
    private fun household(id: String) = firestore.collection(FirestorePaths.HOUSEHOLDS).document(id)

    override fun observeUser(userId: String): Flow<SyncObservation<User>> =
        firestore.collection(FirestorePaths.USERS).document(userId).observations(DocumentSnapshot::toUser)

    override suspend fun save(user: User) {
        firestore.collection(FirestorePaths.USERS).document(user.id)
            .update(
                mapOf(
                    "displayName" to user.displayName?.trim()?.takeIf(String::isNotEmpty),
                    "updatedAt" to FieldValue.serverTimestamp(),
                ),
            ).await()
    }

    override fun observeHousehold(
        householdId: String,
    ): Flow<SyncObservation<Household>> = callbackFlow {
        val registration = household(householdId)
            .addSnapshotListener(MetadataChanges.INCLUDE) { value, error ->
                when {
                    error != null -> trySend(
                        SyncObservation(state = SyncState.ERROR, error = error),
                    )
                    value != null -> trySend(
                        SyncObservation(
                            value = value.toHousehold(),
                            state = syncState(value.metadata),
                        ),
                    )
                }
            }
        awaitClose {
            registration.remove()
        }
    }

    override fun observeMembers(householdId: String): Flow<SyncObservation<List<Member>>> =
        household(householdId).collection(FirestorePaths.MEMBERS).observations { doc ->
            val data = doc.data ?: return@observations null
            val role = runCatching {
                MemberRole.valueOf(data["role"].string() ?: MemberRole.MEMBER.name)
            }.getOrDefault(MemberRole.MEMBER)
            val email = data["email"].string() ?: return@observations null
            Member(
                uid = doc.id,
                email = email,
                displayName = data["displayName"].string(),
                role = role,
                invitationId = data["invitationId"].string(),
                joinedAt = doc.instant("joinedAt"),
            )
        }

    override suspend fun saveHousehold(household: Household) {
        household(household.id).set(household.toDocument(), SetOptions.merge()).await()
    }

    override suspend fun saveOpeningBalance(householdId: String, amountGrosze: Long) {
        household(householdId).update(
            mapOf("openingBalanceGrosze" to amountGrosze, "updatedAt" to FieldValue.serverTimestamp()),
        ).await()
    }

    override suspend fun saveCurrentBalance(householdId: String, currentBalanceGrosze: Long) {
        commitCurrentBalance(prepareCurrentBalance(householdId, currentBalanceGrosze))
    }

    internal suspend fun prepareCurrentBalance(
        householdId: String,
        currentBalanceGrosze: Long,
    ): PreparedCurrentBalance {
        val reference = household(householdId)
        val before = reference.get(Source.SERVER).await()
        check(before.exists() && syncState(before.metadata) == SyncState.SYNCED) {
            "Bieżące saldo wymaga zsynchronizowanego gospodarstwa."
        }
        val snapshot = reference.collection(FirestorePaths.ENTRIES).get(Source.SERVER).await()
        check(syncState(snapshot.metadata) == SyncState.SYNCED &&
            snapshot.documents.none { it.metadata.hasPendingWrites() }) {
            "Bieżące saldo wymaga pełnej synchronizacji wpisów."
        }
        val entries = snapshot.documents.map { document ->
            check(document.get("amountGrosze") is Long && document.get("deleted") is Boolean &&
                document.getString("householdId") == householdId) { "Nie można odczytać wszystkich wpisów." }
            checkNotNull(document.toLedgerEntry()) { "Nie można odczytać wszystkich wpisów." }
        }
        val after = reference.get(Source.SERVER).await()
        check(after.exists() && syncState(after.metadata) == SyncState.SYNCED &&
            before.ledgerRevision() == after.ledgerRevision()) {
            "Wpisy zmieniły się podczas obliczania salda. Spróbuj ponownie."
        }
        val rawOpening = after.get("openingBalanceGrosze")
        check(!after.contains("openingBalanceGrosze") || rawOpening is Long) { "Nieprawidłowe saldo początkowe." }
        return PreparedCurrentBalance(
            householdId = householdId,
            expectedRevision = after.ledgerRevision(),
            expectedOpeningBalanceGrosze = rawOpening as Long?,
            derivedOpeningBalanceGrosze = deriveOpeningBalance(currentBalanceGrosze, entries),
        )
    }

    internal suspend fun commitCurrentBalance(prepared: PreparedCurrentBalance) {
        val reference = household(prepared.householdId)
        firestore.runTransaction { transaction ->
            val latest = transaction.get(reference)
            check(latest.exists() && !latest.metadata.hasPendingWrites() &&
                latest.ledgerRevision() == prepared.expectedRevision &&
                latest.get("openingBalanceGrosze") == prepared.expectedOpeningBalanceGrosze) {
                "Saldo lub wpisy zmieniły się podczas zapisu. Spróbuj ponownie."
            }
            transaction.update(reference, mapOf(
                "openingBalanceGrosze" to prepared.derivedOpeningBalanceGrosze,
                "updatedAt" to FieldValue.serverTimestamp(),
            ))
        }.await()
    }

    override suspend fun removeMember(householdId: String, memberId: String) {
        household(householdId).collection(FirestorePaths.MEMBERS).document(memberId).delete().await()
    }

    override fun observeEntries(
        householdId: String,
        includeDeleted: Boolean,
    ): Flow<SyncObservation<List<LedgerEntry>>> {
        var query: Query = household(householdId)
            .collection(FirestorePaths.ENTRIES)
            .orderBy("date", Query.Direction.DESCENDING)
        if (!includeDeleted) query = query.whereEqualTo("deleted", false)
        return query.observations(DocumentSnapshot::toLedgerEntry)
    }

    override suspend fun save(entry: LedgerEntry) {
        val reference = household(entry.householdId)
        val entryReference = reference
            .collection(FirestorePaths.ENTRIES)
            .document(entry.id)
        firestore.runBatch { batch ->
            batch.set(entryReference, entry.toDocument(), SetOptions.merge())
            batch.update(reference, "ledgerRevision", FieldValue.increment(1L))
        }.await()
    }

    override suspend fun tombstone(householdId: String, entryId: String, actorId: String) {
        val reference = household(householdId)
        val entryReference = reference
            .collection(FirestorePaths.ENTRIES)
            .document(entryId)
        firestore.runBatch { batch ->
            batch.update(
                entryReference,
                mapOf(
                    "deleted" to true,
                    "deletedById" to actorId,
                    "deletedAt" to FieldValue.serverTimestamp(),
                    "updatedById" to actorId,
                    "updatedAt" to FieldValue.serverTimestamp(),
                ),
            )
            batch.update(reference, "ledgerRevision", FieldValue.increment(1L))
        }.await()
    }

    override fun observeCategories(householdId: String): Flow<SyncObservation<List<Category>>> =
        household(householdId)
            .collection(FirestorePaths.CATEGORIES)
            .orderBy("name")
            .observations(DocumentSnapshot::toCategory)

    override fun observeSubcategories(
        householdId: String,
        categoryId: String,
    ): Flow<SyncObservation<List<Subcategory>>> = household(householdId)
        .collection(FirestorePaths.CATEGORIES)
        .document(categoryId)
        .collection(FirestorePaths.SUBCATEGORIES)
        .orderBy("name")
        .observations(DocumentSnapshot::toSubcategory)

    override suspend fun save(category: Category) {
        household(category.householdId)
            .collection(FirestorePaths.CATEGORIES)
            .document(category.id)
            .set(category.toDocument(), SetOptions.merge())
            .await()
    }

    override suspend fun save(subcategory: Subcategory) {
        household(subcategory.householdId)
            .collection(FirestorePaths.CATEGORIES)
            .document(subcategory.categoryId)
            .collection(FirestorePaths.SUBCATEGORIES)
            .document(subcategory.id)
            .set(subcategory.toDocument(), SetOptions.merge())
            .await()
    }

    override fun observeCategoryOrder(
        householdId: String,
        userId: String,
    ): Flow<SyncObservation<CategoryOrder>> = firestore
        .collection(FirestorePaths.USERS)
        .document(userId)
        .collection(FirestorePaths.CATEGORY_ORDERS)
        .document(householdId)
        .observations(DocumentSnapshot::toCategoryOrder)

    override suspend fun saveCategoryOrder(order: CategoryOrder) {
        firestore.collection(FirestorePaths.USERS)
            .document(order.userId)
            .collection(FirestorePaths.CATEGORY_ORDERS)
            .document(order.householdId)
            .set(order.toDocument())
            .await()
    }

    override fun observeInvitations(householdId: String): Flow<SyncObservation<List<Invitation>>> =
        household(householdId)
            .collection(FirestorePaths.INVITATIONS)
            .observations(DocumentSnapshot::toInvitation)

    override fun observeInvitation(
        householdId: String,
        invitationId: String,
    ): Flow<SyncObservation<Invitation>> =
        household(householdId)
            .collection(FirestorePaths.INVITATIONS)
            .document(invitationId)
            .observations(DocumentSnapshot::toInvitation)

    override suspend fun create(invitation: Invitation) {
        household(invitation.householdId).collection(FirestorePaths.INVITATIONS)
            .document(invitation.id).set(invitation.toDocument()).await()
    }

    override suspend fun revoke(householdId: String, invitationId: String) {
        household(householdId)
            .collection(FirestorePaths.INVITATIONS)
            .document(invitationId)
            .update("status", InvitationStatus.REVOKED.name).await()
    }

    override suspend fun accept(invitation: Invitation, member: Member) {
        require(invitation.status == InvitationStatus.PENDING)
        require(member.invitationId == invitation.id)
        require(member.role == MemberRole.MEMBER)
        val household = household(invitation.householdId)
        val profile = firestore.collection(FirestorePaths.USERS).document(member.uid)
        val existingProfile = profile.get().await()
        firestore.runBatch { batch ->
            if (existingProfile.exists()) {
                // A removed member keeps the immutable household profile. Re-inviting them to the
                // same household must not try to rewrite createdAt or householdId.
                batch.update(
                    profile,
                    mapOf(
                        "displayName" to member.displayName?.trim()?.takeIf(String::isNotEmpty),
                        "updatedAt" to FieldValue.serverTimestamp(),
                    ),
                )
            } else {
                batch.set(
                    profile,
                    mapOf(
                        "email" to member.email.trim().lowercase(Locale.ROOT),
                        "displayName" to member.displayName?.trim()?.takeIf(String::isNotEmpty),
                        "householdId" to invitation.householdId,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp(),
                    ),
                )
            }
            batch.update(
                household.collection(FirestorePaths.INVITATIONS).document(invitation.id),
                mapOf("status" to InvitationStatus.ACCEPTED.name, "acceptedBy" to member.uid),
            )
            batch.set(household.collection(FirestorePaths.MEMBERS).document(member.uid), member.toDocument())
        }.await()
    }

    override suspend fun householdIdFor(uid: String): String? =
        firestore.collection(FirestorePaths.USERS).document(uid).get().await().toUser()?.householdId

    override suspend fun createFirstHousehold(
        identity: OnboardingIdentity,
        householdName: String,
    ): FirstHouseholdResult {
        val normalizedName = householdName.trim()
        require(normalizedName.isNotEmpty() && normalizedName.length <= 80) {
            "Nazwa gospodarstwa musi mieć od 1 do 80 znaków."
        }
        val users = firestore.collection(FirestorePaths.USERS)
        val user = users.document(identity.uid)
        // Generate once outside the transaction: retries always target the same candidate household.
        val household = firestore.collection(FirestorePaths.HOUSEHOLDS).document()
        val member = household.collection(FirestorePaths.MEMBERS).document(identity.uid)
        return firestore.runTransaction { transaction ->
            val existing = transaction.get(user).toUser()
            if (existing != null) {
                FirstHouseholdResult.Existing(existing.householdId)
            } else {
                val normalizedEmail = identity.email.trim().lowercase(Locale.ROOT)
                transaction.set(
                    user,
                    mapOf(
                        "email" to normalizedEmail,
                        "displayName" to identity.displayName?.trim()?.takeIf(String::isNotEmpty),
                        "householdId" to household.id,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp(),
                    ),
                )
                transaction.set(
                    household,
                    mapOf(
                        "name" to normalizedName,
                        "ownerId" to identity.uid,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp(),
                    ),
                )
                transaction.set(
                    member,
                    mapOf(
                        "email" to normalizedEmail,
                        "displayName" to identity.displayName?.trim()?.takeIf(String::isNotEmpty),
                        "role" to MemberRole.OWNER.name,
                        "invitationId" to null,
                        "joinedAt" to FieldValue.serverTimestamp(),
                    ),
                )
                StarterTaxonomy.categories.forEach { category ->
                    val categoryReference = household.collection(FirestorePaths.CATEGORIES).document(category.id)
                    transaction.set(
                        categoryReference,
                        mapOf(
                            "householdId" to household.id,
                            "name" to category.name,
                            "color" to category.color,
                            "archived" to false,
                            "defaultEntryType" to category.defaultEntryType.name,
                            "authorId" to identity.uid,
                            "updatedById" to identity.uid,
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp(),
                        ),
                    )
                    category.subcategories.forEach { subcategory ->
                        transaction.set(
                            categoryReference.collection(FirestorePaths.SUBCATEGORIES).document(subcategory.id),
                            mapOf(
                                "householdId" to household.id,
                                "categoryId" to category.id,
                                "name" to subcategory.name,
                                "archived" to false,
                                "authorId" to identity.uid,
                                "updatedById" to identity.uid,
                                "createdAt" to FieldValue.serverTimestamp(),
                                "updatedAt" to FieldValue.serverTimestamp(),
                            ),
                        )
                    }
                }
                FirstHouseholdResult.Created(household.id)
            }
        }.await()
    }
}
