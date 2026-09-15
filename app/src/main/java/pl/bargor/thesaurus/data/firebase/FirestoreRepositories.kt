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
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import pl.bargor.thesaurus.data.model.Category
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
}

interface LedgerRepository {
    fun observeEntries(
        householdId: String,
        includeDeleted: Boolean = false,
    ): Flow<SyncObservation<List<LedgerEntry>>>
    suspend fun save(entry: LedgerEntry)
    suspend fun tombstone(householdId: String, entryId: String, actorId: String)
}

interface TaxonomyRepository {
    fun observeCategories(householdId: String): Flow<SyncObservation<List<Category>>>
    fun observeSubcategories(
        householdId: String,
        categoryId: String,
    ): Flow<SyncObservation<List<Subcategory>>>
    suspend fun save(category: Category)
    suspend fun save(subcategory: Subcategory)
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
    put("color", color?.trim())
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
        color = fields["color"].string(),
        archived = fields["archived"].boolean(),
        defaultEntryType = defaultEntryType,
        authorId = fields["authorId"].string() ?: return null,
        updatedById = fields["updatedById"].string() ?: return null,
        createdAt = instant("createdAt"),
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
        household(entry.householdId)
            .collection(FirestorePaths.ENTRIES)
            .document(entry.id)
            .set(entry.toDocument(), SetOptions.merge())
            .await()
    }

    override suspend fun tombstone(householdId: String, entryId: String, actorId: String) {
        household(householdId)
            .collection(FirestorePaths.ENTRIES)
            .document(entryId)
            .update(
                mapOf(
                    "deleted" to true,
                    "deletedById" to actorId,
                    "deletedAt" to FieldValue.serverTimestamp(),
                    "updatedById" to actorId,
                    "updatedAt" to FieldValue.serverTimestamp(),
                ),
            )
            .await()
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
        firestore.runBatch { batch ->
            batch.set(
                firestore.collection(FirestorePaths.USERS).document(member.uid),
                mapOf(
                    "email" to member.email.trim().lowercase(Locale.ROOT),
                    "displayName" to member.displayName?.trim()?.takeIf(String::isNotEmpty),
                    "householdId" to invitation.householdId,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                ),
            )
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
                            "color" to null,
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
