package pl.bargor.thesaurus.data.firebase

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.*
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import pl.bargor.thesaurus.data.model.*
import pl.bargor.thesaurus.data.transfer.*

/** Import uses server reads and transactions exclusively, so it cannot queue offline writes. */
@Singleton
class FirestoreCsvImportBackend @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth,
) : CsvImportBackend {
    private var lastUser: FirebaseUser? = null
    private var sessionKey = UUID.randomUUID().toString()
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
    private fun requireIdentity(identity: CsvImportIdentity) { check(currentIdentity() == identity) { "IMPORT_SESSION_CHANGED" } }
    private fun requireServer(snapshot: DocumentSnapshot) {
        check(!snapshot.metadata.isFromCache && !snapshot.metadata.hasPendingWrites()) { "IMPORT_SYNC_REQUIRED" }
    }
    private fun requireServer(snapshot: QuerySnapshot) {
        check(!snapshot.metadata.isFromCache && !snapshot.metadata.hasPendingWrites()) { "IMPORT_SYNC_REQUIRED" }
        snapshot.documents.forEach(::requireServer)
    }
    private fun authorize(profile: DocumentSnapshot, home: DocumentSnapshot, member: DocumentSnapshot, identity: CsvImportIdentity, id: String): Household {
        requireIdentity(identity)
        requireServer(profile); requireServer(home); requireServer(member)
        val user = checkNotNull(profile.toUser())
        val household = checkNotNull(home.toHousehold())
        val membership = checkNotNull(member.toMember())
        check(user.id == identity.uid && user.householdId == id && household.ownerId == identity.uid &&
            membership.uid == identity.uid && membership.role == MemberRole.OWNER) { "IMPORT_OWNER_REQUIRED" }
        return household
    }
    override suspend fun loadSnapshot(householdId: String, expectedIdentity: CsvImportIdentity): CsvImportSnapshot {
        val result = withTimeoutOrNull(30_000) {
        requireIdentity(expectedIdentity)
        val reference = household(householdId)
        val profile = firestore.collection(FirestorePaths.USERS).document(expectedIdentity.uid).get(Source.SERVER).await()
        val member = reference.collection(FirestorePaths.MEMBERS).document(expectedIdentity.uid).get(Source.SERVER).await()
        val before = reference.get(Source.SERVER).await()
        authorize(profile, before, member, expectedIdentity, householdId)
        val ledger = reference.collection(FirestorePaths.ENTRIES).get(Source.SERVER).await().also(::requireServer)
        val taxonomy = reference.collection(FirestorePaths.CATEGORIES).get(Source.SERVER).await().also(::requireServer)
        val categories = taxonomy.documents.map { checkNotNull(it.toCategory(expectedHouseholdId = householdId)) }
        val subcategories = categories.flatMap { category ->
            reference.collection(FirestorePaths.CATEGORIES).document(category.id).collection(FirestorePaths.SUBCATEGORIES)
                .get(Source.SERVER).await().also(::requireServer).documents.map {
                    checkNotNull(it.toSubcategory(expectedHouseholdId = householdId, expectedCategoryId = category.id))
                }
        }
        val after = reference.get(Source.SERVER).await().also(::requireServer)
        val home = checkNotNull(after.toHousehold())
        check(before.ledgerRevision() == after.ledgerRevision() && home.ownerId == expectedIdentity.uid) { "IMPORT_REVISION_CHANGED" }
        requireIdentity(expectedIdentity)
        CsvImportSnapshot(expectedIdentity, home, ledger.documents.map { checkNotNull(it.toLedgerEntry(expectedHouseholdId = householdId)) }, categories, subcategories)
        }
        currentCoroutineContext().ensureActive()
        return checkNotNull(result) { "IMPORT_SERVER_TIMEOUT" }
    }

    private fun authorize(transaction: Transaction, snapshot: CsvImportSnapshot): Pair<DocumentReference, Household> {
        val reference = household(snapshot.household.id)
        val identity = snapshot.identity
        val profile = transaction.get(firestore.collection(FirestorePaths.USERS).document(identity.uid))
        val home = transaction.get(reference)
        val member = transaction.get(reference.collection(FirestorePaths.MEMBERS).document(identity.uid))
        val latest = authorize(profile, home, member, identity, snapshot.household.id)
        check(latest.ledgerRevision == snapshot.household.ledgerRevision) { "IMPORT_REVISION_CHANGED" }
        return reference to latest
    }

    /** Firestore Tasks cannot cancel an already dispatched commit. Await its terminal result even
     * when the caller cancels; returning from a timeout would allow a late, unreported write. */
    private suspend fun <T> terminalTransaction(snapshot: CsvImportSnapshot, action: (Transaction) -> T): T {
        requireIdentity(snapshot.identity)
        // Transactions have a separate RPC path. A server-only read also respects the SDK's
        // disableNetwork state; never dispatch a new import transaction from offline cache.
        val available = withTimeoutOrNull(30_000) {
            household(snapshot.household.id).get(Source.SERVER).await().also(::requireServer)
        }
        currentCoroutineContext().ensureActive()
        val home = checkNotNull(checkNotNull(available) { "IMPORT_SERVER_TIMEOUT" }.toHousehold())
        check(home.ownerId == snapshot.identity.uid && home.ledgerRevision == snapshot.household.ledgerRevision) {
            "IMPORT_REVISION_CHANGED"
        }
        requireIdentity(snapshot.identity)
        val deadline = System.nanoTime() + 30_000_000_000L
        return withContext(NonCancellable) {
            firestore.runTransaction(TransactionOptions.Builder().setMaxAttempts(1).build()) { transaction ->
                check(System.nanoTime() < deadline) { "IMPORT_TRANSACTION_EXPIRED" }
                action(transaction).also { check(System.nanoTime() < deadline) { "IMPORT_TRANSACTION_EXPIRED" } }
            }.await()
        }
    }

    override suspend fun commitEntry(snapshot: CsvImportSnapshot, entry: LedgerEntry): CsvImportCommitResult {
        requireIdentity(snapshot.identity)
        check(entry.householdId == snapshot.household.id && entry.authorId == snapshot.identity.uid &&
            entry.updatedById == snapshot.identity.uid && !entry.deleted && entry.id == "csv_${csvImportFingerprint(entry)}")
        return terminalTransaction(snapshot) { transaction ->
            val (reference, latest) = authorize(transaction, snapshot)
            val target = reference.collection(FirestorePaths.ENTRIES).document(entry.id)
            val existing = transaction.get(target)
            val category = transaction.get(reference.collection(FirestorePaths.CATEGORIES).document(entry.categoryId))
            val subcategory = entry.subcategoryId?.let {
                transaction.get(reference.collection(FirestorePaths.CATEGORIES).document(entry.categoryId).collection(FirestorePaths.SUBCATEGORIES).document(it))
            }
            val categoryValue = checkNotNull(category.toCategory(expectedHouseholdId = entry.householdId))
            check(!categoryValue.archived && categoryValue == snapshot.category(entry.categoryId)) { "IMPORT_TAXONOMY_CHANGED" }
            if (subcategory != null) {
                val value = checkNotNull(subcategory.toSubcategory(expectedHouseholdId = entry.householdId, expectedCategoryId = entry.categoryId))
                check(!value.archived && value == snapshot.subcategory(entry.categoryId, checkNotNull(entry.subcategoryId))) { "IMPORT_TAXONOMY_CHANGED" }
            }
            requireIdentity(snapshot.identity)
            if (existing.exists()) {
                val value = checkNotNull(existing.toLedgerEntry(expectedHouseholdId = entry.householdId))
                check(csvImportFingerprint(value) == csvImportFingerprint(entry)) { "IMPORT_ID_COLLISION" }
                CsvImportCommitResult(latest.ledgerRevision, false)
            } else {
                check(latest.ledgerRevision < Long.MAX_VALUE) { "IMPORT_REVISION_OVERFLOW" }
                transaction.set(target, entry.toDocument())
                transaction.update(reference, "ledgerRevision", latest.ledgerRevision + 1)
                CsvImportCommitResult(latest.ledgerRevision + 1, true)
            }
        }
    }
    override suspend fun createCategory(snapshot: CsvImportSnapshot, category: Category) {
        check(category.householdId == snapshot.household.id && category.authorId == snapshot.identity.uid && category.updatedById == snapshot.identity.uid && !category.archived)
        terminalTransaction(snapshot) { transaction ->
            val (reference, _) = authorize(transaction, snapshot)
            val target = reference.collection(FirestorePaths.CATEGORIES).document(category.id)
            check(!transaction.get(target).exists()) { "IMPORT_CATEGORY_EXISTS" }
            requireIdentity(snapshot.identity)
            transaction.set(target, category.toDocument())
        }
    }
    override suspend fun createSubcategory(snapshot: CsvImportSnapshot, subcategory: Subcategory) {
        check(subcategory.householdId == snapshot.household.id && subcategory.authorId == snapshot.identity.uid && subcategory.updatedById == snapshot.identity.uid && !subcategory.archived)
        terminalTransaction(snapshot) { transaction ->
            val (reference, _) = authorize(transaction, snapshot)
            val parent = reference.collection(FirestorePaths.CATEGORIES).document(subcategory.categoryId)
            check(!checkNotNull(transaction.get(parent).toCategory(expectedHouseholdId = subcategory.householdId)).archived)
            val target = parent.collection(FirestorePaths.SUBCATEGORIES).document(subcategory.id)
            check(!transaction.get(target).exists()) { "IMPORT_SUBCATEGORY_EXISTS" }
            requireIdentity(snapshot.identity)
            transaction.set(target, subcategory.toDocument())
        }
    }
}
