package pl.bargor.thesaurus.data.firebase

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.*
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import pl.bargor.thesaurus.data.model.*
import pl.bargor.thesaurus.data.transfer.*

/** Complete server exports and bounded, owner-only, additive backup transactions. */
@Singleton
class FirestoreJsonBackupBackend @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth,
) : JsonBackupBackend {
    private var lastUser: FirebaseUser? = null
    private var sessionKey = UUID.randomUUID().toString()
    private var categorySource: List<Category>? = null
    private var categoryIndex: Map<String, Category> = emptyMap()
    private var subcategorySource: List<Subcategory>? = null
    private var subcategoryIndex: Map<Pair<String, String>, Subcategory> = emptyMap()

    /** Revision-only snapshot copies retain the catalog list instances. Return the
     * selected immutable map while holding the lock so another plan cannot replace it. */
    @Synchronized private fun categories(snapshot: JsonBackupSnapshot): Map<String, Category> {
        if (categorySource !== snapshot.categories) {
            val indexed = snapshot.categories.associateBy { it.id }
            check(indexed.size == snapshot.categories.size) { "IMPORT_TAXONOMY_CHANGED" }
            categorySource = snapshot.categories
            categoryIndex = indexed
        }
        return categoryIndex
    }

    @Synchronized private fun subcategories(snapshot: JsonBackupSnapshot): Map<Pair<String, String>, Subcategory> {
        if (subcategorySource !== snapshot.subcategories) {
            val indexed = snapshot.subcategories.associateBy { it.categoryId to it.id }
            check(indexed.size == snapshot.subcategories.size) { "IMPORT_TAXONOMY_CHANGED" }
            subcategorySource = snapshot.subcategories
            subcategoryIndex = indexed
        }
        return subcategoryIndex
    }

    init {
        auth.addAuthStateListener {
            synchronized(this) {
                if (it.currentUser !== lastUser) {
                    lastUser = it.currentUser
                    sessionKey = UUID.randomUUID().toString()
                }
            }
        }
    }

    @Synchronized override fun currentIdentity(): CsvImportIdentity? {
        val user = auth.currentUser
        if (user !== lastUser) { lastUser = user; sessionKey = UUID.randomUUID().toString() }
        return user?.let { CsvImportIdentity(it.uid, sessionKey) }
    }

    private fun household(id: String) = firestore.collection(FirestorePaths.HOUSEHOLDS).document(id)
    private fun requireIdentity(identity: CsvImportIdentity) {
        check(currentIdentity() == identity) { "IMPORT_SESSION_CHANGED" }
    }
    private fun requireServer(document: DocumentSnapshot) {
        check(!document.metadata.isFromCache && !document.metadata.hasPendingWrites()) { "IMPORT_SYNC_REQUIRED" }
    }
    private fun requireServer(query: QuerySnapshot) {
        check(!query.metadata.isFromCache && !query.metadata.hasPendingWrites()) { "IMPORT_SYNC_REQUIRED" }
        query.documents.forEach(::requireServer)
    }

    /** The extra document proves overflow; a capped result is never treated as a
     * complete synchronized snapshot. Reject before allocating decoded model lists. */
    private suspend fun boundedCollection(reference: CollectionReference, maximum: Int): QuerySnapshot {
        val query = reference.limit(maximum.toLong() + 1L).get(Source.SERVER).await()
        requireServer(query)
        check(query.size() <= maximum) { "JSON_SNAPSHOT_LIMIT_EXCEEDED" }
        return query
    }

    private fun authorize(profile: DocumentSnapshot, home: DocumentSnapshot, membership: DocumentSnapshot,
        identity: CsvImportIdentity, id: String, requireOwner: Boolean): Household {
        requireIdentity(identity)
        requireServer(profile); requireServer(home); requireServer(membership)
        val user = checkNotNull(profile.toUser())
        val member = checkNotNull(membership.toMember())
        val value = checkNotNull(home.toHousehold())
        check(user.id == identity.uid && user.householdId == id && member.uid == identity.uid) { "IMPORT_MEMBER_REQUIRED" }
        if (requireOwner) check(value.ownerId == identity.uid && member.role == MemberRole.OWNER) { "IMPORT_OWNER_REQUIRED" }
        return value
    }

    override suspend fun loadSnapshot(householdId: String, expectedIdentity: CsvImportIdentity,
        requireOwner: Boolean): JsonBackupSnapshot {
        val result = withTimeoutOrNull(30_000) {
            requireIdentity(expectedIdentity)
            val reference = household(householdId)
            val profileRef = firestore.collection(FirestorePaths.USERS).document(expectedIdentity.uid)
            val memberRef = reference.collection(FirestorePaths.MEMBERS).document(expectedIdentity.uid)
            val before = authorize(profileRef.get(Source.SERVER).await(), reference.get(Source.SERVER).await(),
                memberRef.get(Source.SERVER).await(), expectedIdentity, householdId, requireOwner)
            val entries = boundedCollection(reference.collection(FirestorePaths.ENTRIES), BackupLimits.MAX_ENTRIES)
                .documents.map { checkNotNull(it.toLedgerEntry(expectedHouseholdId = householdId)) }
            val categories = boundedCollection(reference.collection(FirestorePaths.CATEGORIES), BackupLimits.MAX_CATEGORIES)
                .documents.map { checkNotNull(it.toCategory(expectedHouseholdId = householdId)) }
            val subcategories = mutableListOf<Subcategory>()
            categories.forEach { category ->
                val collection = reference.collection(FirestorePaths.CATEGORIES).document(category.id).collection(FirestorePaths.SUBCATEGORIES)
                val documents = boundedCollection(collection, BackupLimits.MAX_SUBCATEGORIES - subcategories.size)
                subcategories += documents.documents.map {
                        checkNotNull(it.toSubcategory(expectedHouseholdId = householdId, expectedCategoryId = category.id))
                }
            }
            // Memberships are authorization references, never export payloads. Bound
            // this independent catalog as well, including its overflow sentinel.
            val members = boundedCollection(reference.collection(FirestorePaths.MEMBERS), 10_000)
                .documents.map { checkNotNull(it.toMember()).uid }.toSet()
            val after = authorize(profileRef.get(Source.SERVER).await(), reference.get(Source.SERVER).await(),
                memberRef.get(Source.SERVER).await(), expectedIdentity, householdId, requireOwner)
            check(stableHousehold(before, after)) { "IMPORT_REVISION_CHANGED" }
            requireIdentity(expectedIdentity)
            JsonBackupSnapshot(expectedIdentity, after, entries, categories, subcategories.toList(), members)
        }
        currentCoroutineContext().ensureActive()
        return checkNotNull(result) { "IMPORT_SERVER_TIMEOUT" }
    }

    private fun stableHousehold(expected: Household, current: Household) =
        expected.id == current.id && expected.ownerId == current.ownerId &&
            expected.ledgerRevision == current.ledgerRevision && expected.openingBalanceGrosze == current.openingBalanceGrosze

    private fun authorize(transaction: Transaction, snapshot: JsonBackupSnapshot): Pair<DocumentReference, Household> {
        val reference = household(snapshot.household.id)
        val identity = snapshot.identity
        val latest = authorize(transaction.get(firestore.collection(FirestorePaths.USERS).document(identity.uid)),
            transaction.get(reference), transaction.get(reference.collection(FirestorePaths.MEMBERS).document(identity.uid)),
            identity, snapshot.household.id, true)
        check(stableHousehold(snapshot.household, latest)) { "IMPORT_REVISION_CHANGED" }
        return reference to latest
    }

    /** A dispatched Firebase Task cannot be cancelled. Observe its terminal result so a
     * cancelled UI operation cannot return while an unreported transaction is committing. */
    private suspend fun <T> terminalTransaction(snapshot: JsonBackupSnapshot, action: (Transaction) -> T): T {
        check(snapshot.synchronized) { "IMPORT_SYNC_REQUIRED" }
        requireIdentity(snapshot.identity)
        val available = withTimeoutOrNull(30_000) {
            household(snapshot.household.id).get(Source.SERVER).await().also(::requireServer)
        }
        currentCoroutineContext().ensureActive()
        val latest = checkNotNull(checkNotNull(available) { "IMPORT_SERVER_TIMEOUT" }.toHousehold())
        check(stableHousehold(snapshot.household, latest)) { "IMPORT_REVISION_CHANGED" }
        requireIdentity(snapshot.identity)
        val deadline = System.nanoTime() + 30_000_000_000L
        return withContext(NonCancellable) {
            firestore.runTransaction(TransactionOptions.Builder().setMaxAttempts(1).build()) { transaction ->
                check(System.nanoTime() < deadline) { "IMPORT_TRANSACTION_EXPIRED" }
                action(transaction).also { check(System.nanoTime() < deadline) { "IMPORT_TRANSACTION_EXPIRED" } }
            }.await()
        }
    }

    private fun requireAuthor(transaction: Transaction, reference: DocumentReference, snapshot: JsonBackupSnapshot, authorId: String) {
        check(authorId in snapshot.memberIds) { "IMPORT_AUTHOR_CHANGED" }
        val document = transaction.get(reference.collection(FirestorePaths.MEMBERS).document(authorId))
        requireServer(document)
        check(checkNotNull(document.toMember()).uid == authorId) { "IMPORT_AUTHOR_CHANGED" }
    }

    private fun category(transaction: Transaction, reference: DocumentReference, snapshot: JsonBackupSnapshot, id: String): Category {
        val document = transaction.get(reference.collection(FirestorePaths.CATEGORIES).document(id))
        requireServer(document)
        val value = checkNotNull(document.toCategory(expectedHouseholdId = reference.id))
        val expected = checkNotNull(categories(snapshot)[id]) { "IMPORT_TAXONOMY_CHANGED" }
        check(categoryUnchanged(expected, value)) { "IMPORT_TAXONOMY_CHANGED" }
        return value
    }

    // Newly created parents are added to the coordinator snapshot before their server
    // timestamps resolve. Existing parents retain their full preview audit fence.
    private fun categoryUnchanged(expected: Category, value: Category): Boolean =
        if (expected.createdAt == null && expected.updatedAt == null)
            expected.copy(createdAt = value.createdAt, updatedAt = value.updatedAt) == value
        else expected == value

    private fun subcategoryUnchanged(expected: Subcategory, value: Subcategory): Boolean =
        if (expected.createdAt == null && expected.updatedAt == null)
            expected.copy(createdAt = value.createdAt, updatedAt = value.updatedAt) == value
        else expected == value

    override suspend fun commit(snapshot: JsonBackupSnapshot, operation: JsonMergeOperation): JsonMergeWriteResult =
        terminalTransaction(snapshot) { transaction ->
            val (reference, latest) = authorize(transaction, snapshot)
            val uid = snapshot.identity.uid
            fun result(outcome: JsonMergeWriteOutcome) = JsonMergeWriteResult(latest.ledgerRevision, outcome)
            when (operation) {
                is JsonMergeOperation.OpeningBalance -> {
                    check(snapshot.entries.isEmpty() && latest.ledgerRevision == 0L) { "IMPORT_OPENING_BALANCE_CHANGED" }
                    if (latest.openingBalanceGrosze == operation.amountGrosze) result(JsonMergeWriteOutcome.SKIPPED)
                    else if (latest.openingBalanceGrosze != 0L) result(JsonMergeWriteOutcome.CONFLICT)
                    else {
                        requireIdentity(snapshot.identity)
                        transaction.update(reference, mapOf("openingBalanceGrosze" to operation.amountGrosze,
                            "updatedAt" to FieldValue.serverTimestamp()))
                        result(JsonMergeWriteOutcome.ADDED)
                    }
                }
                is JsonMergeOperation.NewCategory -> {
                    val incoming = operation.category
                    check(incoming.householdId == reference.id && incoming.id.matches(Regex("json_[a-f0-9]{64}")))
                    val target = reference.collection(FirestorePaths.CATEGORIES).document(incoming.id)
                    val existing = transaction.get(target).also(::requireServer)
                    requireAuthor(transaction, reference, snapshot, incoming.authorId)
                    requireIdentity(snapshot.identity)
                    if (existing.exists()) {
                        val value = checkNotNull(existing.toCategory(expectedHouseholdId = reference.id))
                        result(if (jsonCategoryContentKey(value) == jsonCategoryContentKey(incoming)) JsonMergeWriteOutcome.SKIPPED else JsonMergeWriteOutcome.CONFLICT)
                    } else {
                        transaction.set(target, incoming.copy(updatedById = uid, createdAt = null, updatedAt = null).toDocument())
                        result(JsonMergeWriteOutcome.ADDED)
                    }
                }
                is JsonMergeOperation.NewSubcategory -> {
                    val incoming = operation.subcategory
                    check(incoming.householdId == reference.id && incoming.id.matches(Regex("json_[a-f0-9]{64}")))
                    category(transaction, reference, snapshot, incoming.categoryId)
                    val target = reference.collection(FirestorePaths.CATEGORIES).document(incoming.categoryId)
                        .collection(FirestorePaths.SUBCATEGORIES).document(incoming.id)
                    val existing = transaction.get(target).also(::requireServer)
                    requireAuthor(transaction, reference, snapshot, incoming.authorId)
                    requireIdentity(snapshot.identity)
                    if (existing.exists()) {
                        val value = checkNotNull(existing.toSubcategory(expectedHouseholdId = reference.id, expectedCategoryId = incoming.categoryId))
                        result(if (jsonSubcategoryContentKey(value) == jsonSubcategoryContentKey(incoming)) JsonMergeWriteOutcome.SKIPPED else JsonMergeWriteOutcome.CONFLICT)
                    } else {
                        transaction.set(target, incoming.copy(updatedById = uid, createdAt = null, updatedAt = null).toDocument())
                        result(JsonMergeWriteOutcome.ADDED)
                    }
                }
                is JsonMergeOperation.NewEntry -> {
                    val incoming = operation.entry
                    check(incoming.householdId == reference.id && incoming.id.matches(Regex("json_[a-f0-9]{64}")))
                    val target = reference.collection(FirestorePaths.ENTRIES).document(incoming.id)
                    val existing = transaction.get(target).also(::requireServer)
                    category(transaction, reference, snapshot, incoming.categoryId)
                    incoming.subcategoryId?.let { id ->
                        val document = transaction.get(reference.collection(FirestorePaths.CATEGORIES).document(incoming.categoryId)
                            .collection(FirestorePaths.SUBCATEGORIES).document(id)).also(::requireServer)
                        val value = checkNotNull(document.toSubcategory(expectedHouseholdId = reference.id, expectedCategoryId = incoming.categoryId))
                        val expected = checkNotNull(subcategories(snapshot)[incoming.categoryId to id]) { "IMPORT_TAXONOMY_CHANGED" }
                        check(subcategoryUnchanged(expected, value)) { "IMPORT_TAXONOMY_CHANGED" }
                    }
                    requireAuthor(transaction, reference, snapshot, incoming.authorId)
                    requireIdentity(snapshot.identity)
                    if (existing.exists()) result(jsonEntryMergeOutcome(checkNotNull(existing.toLedgerEntry(expectedHouseholdId = reference.id)), incoming))
                    else {
                        check(latest.ledgerRevision < Long.MAX_VALUE) { "IMPORT_REVISION_OVERFLOW" }
                        transaction.set(target, incoming.copy(updatedById = uid, createdAt = null, updatedAt = null,
                            deletedAt = null, deletedById = if (incoming.deleted) uid else null).toDocument())
                        transaction.update(reference, "ledgerRevision", latest.ledgerRevision + 1)
                        JsonMergeWriteResult(latest.ledgerRevision + 1, JsonMergeWriteOutcome.ADDED)
                    }
                }
            }
        }
}
