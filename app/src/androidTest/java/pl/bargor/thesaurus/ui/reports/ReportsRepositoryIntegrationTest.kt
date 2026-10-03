package pl.bargor.thesaurus.ui.reports

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.GoogleAuthProvider
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pl.bargor.thesaurus.LocalNetworkPermissionRule
import pl.bargor.thesaurus.data.firebase.*
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.model.Invitation
import pl.bargor.thesaurus.data.model.Member
import pl.bargor.thesaurus.data.model.MemberRole
import pl.bargor.thesaurus.data.model.SyncState

@RunWith(AndroidJUnit4::class)
class ReportsRepositoryIntegrationTest {
    @get:Rule val localNetworkPermissionRule = LocalNetworkPermissionRule()
    @Test fun scopedCachedReportsUpdateOfflineTaxonomyAndPendingLedgerThenRecoverAndRemoveTombstones() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val suffix = UUID.randomUUID().toString()
        val app = FirebaseApp.initializeApp(instrumentation.targetContext,
            FirebaseOptions.Builder().setApplicationId("1:1234567890:android:test").setApiKey("fake-api-key")
                .setProjectId("demo-thesaurus").build(), "reports-$suffix")
        val auth = FirebaseAuthFactory.create(app)
        FirebaseAuthFactory.connectToLocalEmulator(auth)
        val firestore = FirebaseFirestoreFactory.create(app, emulatorHost = "10.0.2.2")
        val store = ViewModelStore()
        try {
            withTimeout(90_000) {
                val email = "reports-$suffix@example.test"
                val uid = auth.createUserWithEmailAndPassword(email, "test-password-123").await().user!!.uid
                val repository = FirestoreRepositories(firestore)
                val home = (repository.createFirstHousehold(OnboardingIdentity(uid, email, "Ala"), "Dom raportów") as FirstHouseholdResult.Created).householdId
                val categories = repository.observeCategories(home).first { it.state == SyncState.SYNCED && it.value.orEmpty().isNotEmpty() }.value!!
                val food = categories.single { it.id == "jedzenie" }
                val income = categories.single { it.id == "wplywy" }
                val subs = repository.observeSubcategories(home, food.id).first { it.state == SyncState.SYNCED && it.value.orEmpty().isNotEmpty() }.value!!
                val shop = subs.single { it.id == "supermarket" }
                val restaurant = subs.single { it.id == "restauracja" }
                fun entry(id: String, amount: Long, category: String = food.id, sub: String? = shop.id, date: String = "2026-09-12") = LedgerEntry(
                    "$id-$suffix", home, amount, LocalDate.parse(date), categoryId = category, subcategoryId = sub, authorId = uid, updatedById = uid)
                val expense = entry("expense", -1000)
                val refund = entry("refund", 100)
                val excluded = entry("restaurant", -400, sub = restaurant.id)
                val paycheck = entry("income", 10_000, income.id, null)
                repository.save(expense); repository.save(refund); repository.save(excluded); repository.save(paycheck)
                repository.save(entry("august", -900, date = "2026-08-31"))
                lateinit var vm: ReportsViewModel
                val saved = SavedStateHandle()
                instrumentation.runOnMainSync {
                    vm = ReportsViewModel(repository, repository, Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC), saved, repository)
                    store.put("reports", vm); vm.start(home)
                }
                suspend fun state(stage: String, predicate: (ReportsUiState) -> Boolean): ReportsUiState {
                    try {
                        return withTimeout(15_000) { vm.state.first(predicate) }
                    } catch (timeout: TimeoutCancellationException) {
                        val last = vm.state.value
                        throw AssertionError("Timed out at $stage: category=${last.selectedCategoryId}, " +
                            "subcategory=${last.selectedSubcategoryId}, " +
                            "subcategories=${last.allSubcategories.map { it.categoryId + "/" + it.id }}, " +
                            "entries=${last.entries.map { it.entry.id }}, " +
                            "sync=${last.syncState}, error=${last.hasError}", timeout)
                    }
                }
                state("initial entries and scoped taxonomy") {
                    !it.isLoading && it.syncState == SyncState.SYNCED && it.entries.size == 4 &&
                        it.categories.isNotEmpty() && it.allSubcategories.any { sub -> sub.categoryId == food.id && sub.id == shop.id }
                }
                instrumentation.runOnMainSync { vm.openFilters(); vm.selectCategory(food.id); vm.selectSubcategory(shop.id); vm.applyFilters() }
                val scoped = state("apply food/shop") { it.selectedSubcategoryId == shop.id && it.entries.size == 2 }
                assertEquals((-900).toBigInteger(), scoped.aggregation.totals.netGrosze)
                assertBalanceMatches(scoped)
                assertEquals(1100.toBigInteger(), scoped.aggregation.categories.single().amountGrosze)
                assertEquals(setOf(expense.id, refund.id), scoped.entries.map { it.entry.id }.toSet())
                firestore.disableNetwork().await()
                val cached = state("disable network") { it.syncState == SyncState.OFFLINE }
                instrumentation.runOnMainSync { vm.openFilters(); vm.selectSort(ReportEntrySort.AMOUNT); vm.toggleSortDirection() }
                assertEquals(cached.entries, vm.state.value.entries)
                assertEquals(cached.aggregation, vm.state.value.aggregation)
                assertEquals(cached.balanceTrend, vm.state.value.balanceTrend)
                instrumentation.runOnMainSync { vm.dismissFilters(); vm.openFilters() }
                assertEquals(ReportEntrySort.DATE, vm.state.value.filterDraft!!.sort)
                instrumentation.runOnMainSync { vm.selectSort(ReportEntrySort.AMOUNT); vm.toggleSortDirection(); vm.applyFilters() }
                val sortedCached = state("commit offline cached sorting") {
                    it.syncState == SyncState.OFFLINE && it.sort == ReportEntrySort.AMOUNT &&
                        it.direction == ReportSortDirection.ASCENDING && it.filterDraft == null
                }
                assertEquals(listOf(expense.id, refund.id), sortedCached.entries.map { it.entry.id })
                assertEquals(cached.aggregation, sortedCached.aggregation)
                assertEquals(cached.balanceTrend, sortedCached.balanceTrend)
                instrumentation.runOnMainSync {
                    vm.openFilters(); vm.resetFilters()
                    val restoredState = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
                    store.clear()
                    vm = ReportsViewModel(repository, repository,
                        Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC), restoredState, repository)
                    store.put("reports", vm); vm.start(home)
                }
                val restoredCached = state("restore applied sorting and scope from offline cache") {
                    !it.isLoading && it.syncState == SyncState.OFFLINE && it.entries.size == 2 &&
                        it.selectedSubcategoryId == shop.id && it.filterDraft == null
                }
                assertEquals(ReportEntrySort.AMOUNT, restoredCached.sort)
                assertEquals(ReportSortDirection.ASCENDING, restoredCached.direction)
                assertEquals(sortedCached.entries.map { it.entry.id }, restoredCached.entries.map { it.entry.id })
                assertEquals(sortedCached.aggregation, restoredCached.aggregation)
                assertEquals(sortedCached.balanceTrend, restoredCached.balanceTrend)
                assertBalanceMatches(restoredCached)
                instrumentation.runOnMainSync { vm.openFilters() }
                assertEquals(ReportEntrySort.AMOUNT, vm.state.value.filterDraft!!.sort)
                instrumentation.runOnMainSync { vm.dismissFilters() }
                val local = entry("local", -500)
                val writes = listOf(async { repository.save(local) }, async { repository.save(food.copy(name = "Jedzenie lokalne", updatedById = uid)) }, async { repository.save(shop.copy(name = "Sklep lokalny", updatedById = uid)) })
                try {
                    val pending = state("pending local entry and renamed taxonomy") { it.syncState == SyncState.PENDING && it.entries.size == 3 && it.entries.all { item -> item.categoryName == "Jedzenie lokalne" && item.subcategoryName == "Sklep lokalny" } }
                    assertEquals((-1400).toBigInteger(), pending.aggregation.totals.netGrosze)
                    assertBalanceMatches(pending)
                    assertEquals(1600.toBigInteger(), pending.aggregation.categories.single().amountGrosze)
                    assertEquals(shop.id, pending.selectedSubcategoryId)
                    instrumentation.runOnMainSync { vm.selectPeriodMode(ReportPeriodMode.YEAR) }
                    val annual = state("annual offline scope") { it.mode == ReportPeriodMode.YEAR && it.entries.size == 4 }
                    assertEquals((-2300).toBigInteger(), annual.aggregation.totals.netGrosze)
                    assertEquals(2, annual.aggregation.trend.size)
                    assertBalanceMatches(annual)
                    assertEquals(pl.bargor.thesaurus.data.model.ReportBalanceGranularity.MONTHLY, annual.balanceTrend!!.granularity)
                    instrumentation.runOnMainSync { vm.selectPeriodMode(ReportPeriodMode.MONTH) }
                    state("return to monthly scope") { it.mode == ReportPeriodMode.MONTH && it.entries.size == 3 }
                    firestore.enableNetwork().await()
                    withTimeout(15_000) { writes.forEach { it.await() } }
                    state("network recovery") { it.syncState == SyncState.SYNCED && it.entries.size == 3 }
                } finally { writes.filter { it.isActive }.forEach { it.cancel() } }
                repository.tombstone(home, local.id, uid)
                val afterDelete = state("tombstone local entry") { it.syncState == SyncState.SYNCED && it.entries.size == 2 && it.entries.none { item -> item.entry.id == local.id } }
                assertEquals((-900).toBigInteger(), afterDelete.aggregation.totals.netGrosze)
                assertBalanceMatches(afterDelete)
                assertEquals(1100.toBigInteger(), afterDelete.aggregation.categories.single().amountGrosze)
                instrumentation.runOnMainSync { vm.openFilters(); vm.resetFilters(); vm.applyFilters(); vm.clearControls() }
                val all = state("reset scope") { !it.hasActiveFilters && it.entries.size == 4 }
                assertEquals(8700.toBigInteger(), all.aggregation.totals.netGrosze)
                assertBalanceMatches(all)
            }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            runCatching { withTimeout(10_000) { firestore.enableNetwork().await() } }
            runCatching { withTimeout(10_000) { firestore.terminate().await() } }
            // Keep the named fake app registered until process exit to avoid Auth worker races.
        }
    }
    @Test fun realMembersAndHistoricalAuthorsFilterByUidAndRemainScopedWhileOffline() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val suffix = UUID.randomUUID().toString()
        val app = FirebaseApp.initializeApp(instrumentation.targetContext,
            FirebaseOptions.Builder().setApplicationId("1:1234567890:android:members")
                .setApiKey("fake-api-key").setProjectId("demo-thesaurus").build(), "report-members-$suffix")
        val auth = FirebaseAuthFactory.create(app)
        FirebaseAuthFactory.connectToLocalEmulator(auth)
        val firestore = FirebaseFirestoreFactory.create(app, emulatorHost = "10.0.2.2")
        val store = ViewModelStore()
        suspend fun signIn(subject: String, email: String) = auth.signInWithCredential(
            GoogleAuthProvider.getCredential("""{"sub":"$subject","email":"$email","email_verified":true}""", null)
        ).await().user!!.uid
        try {
            withTimeout(90_000) {
                val ownerEmail = "report-owner-$suffix@example.test"
                val guestEmail = "report-guest-$suffix@example.test"
                val owner = signIn("owner-$suffix", ownerEmail)
                val repository = FirestoreRepositories(firestore)
                val home = (repository.createFirstHousehold(OnboardingIdentity(owner, ownerEmail, "Ala"), "Members") as FirstHouseholdResult.Created).householdId
                val invite = Invitation("invite-$suffix", home, guestEmail, owner, expiresAt = Instant.now().plusSeconds(86_400))
                repository.create(invite)
                val ownerEntry = LedgerEntry("owner-$suffix", home, -100, LocalDate.of(2026, 9, 12),
                    categoryId = "jedzenie", authorId = owner, updatedById = owner)
                repository.save(ownerEntry)
                auth.signOut()
                val guest = signIn("guest-$suffix", guestEmail)
                repository.accept(invite, Member(guest, guestEmail, "Ala", MemberRole.MEMBER, invitationId = invite.id))
                val guestEntry = ownerEntry.copy(id = "guest-$suffix", amountGrosze = -200, authorId = guest, updatedById = guest)
                repository.save(guestEntry)
                auth.signOut()
                assertEquals(owner, signIn("owner-$suffix", ownerEmail))
                lateinit var vm: ReportsViewModel
                instrumentation.runOnMainSync {
                    vm = ReportsViewModel(repository, repository,
                        Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC), SavedStateHandle(), repository)
                    store.put("reports", vm); vm.start(home)
                }
                suspend fun state(predicate: (ReportsUiState) -> Boolean) =
                    withTimeout(15_000) { vm.state.first(predicate) }
                val all = state { !it.isLoading && it.syncState == SyncState.SYNCED && it.entries.size == 2 && it.members.size == 2 }
                assertEquals(setOf(owner, guest), all.members.map { it.id }.toSet())
                assertEquals(2, all.members.map { it.name }.distinct().size)
                instrumentation.runOnMainSync { vm.openFilters(); vm.selectMembers(setOf(guest)); vm.applyFilters() }
                val guestOnly = state { it.entries.size == 1 && it.entries.single().entry.id == guestEntry.id }
                assertEquals(200.toBigInteger(), guestOnly.aggregation.totals.expenseGrosze)
                assertBalanceMatches(guestOnly)
                assertEquals(200.toBigInteger(), guestOnly.aggregation.categories.single().amountGrosze)
                instrumentation.runOnMainSync { vm.openFilters(); vm.selectMembers(setOf(owner, guest)); vm.applyFilters() }
                state { it.entries.size == 2 }
                repository.removeMember(home, guest)
                state { it.members.any { option -> option.id == guest && option.former } }
                instrumentation.runOnMainSync { vm.openFilters(); vm.selectMembers(setOf(guest)); vm.applyFilters() }
                state { it.entries.size == 1 && it.selectedMemberIds == setOf(guest) }
                firestore.disableNetwork().await()
                val offline = state { it.syncState == SyncState.OFFLINE }
                assertEquals(listOf(guestEntry.id), offline.entries.map { it.entry.id })
                assertBalanceMatches(offline)
                instrumentation.runOnMainSync { vm.openFilters(); vm.selectMembers(emptySet()); vm.applyFilters() }
                state { it.entries.isEmpty() && it.aggregation.totals.isEmpty && it.aggregation.categories.isEmpty() && it.aggregation.trend.isEmpty() }
                instrumentation.runOnMainSync { vm.openFilters(); vm.selectMembers(null); vm.applyFilters() }
                state { it.entries.size == 2 }
                firestore.enableNetwork().await()
                state { it.syncState == SyncState.SYNCED }
            }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            runCatching { withTimeout(10_000) { firestore.enableNetwork().await() } }
            runCatching { withTimeout(10_000) { firestore.terminate().await() } }
        }
    }

    private fun assertBalanceMatches(state: ReportsUiState) {
        val trend = requireNotNull(state.balanceTrend)
        assertEquals(state.aggregation.totals.entryCount, trend.entryCount)
        assertEquals(state.aggregation.totals.netGrosze, trend.endBalanceGrosze)
        assertEquals(state.entries.fold(java.math.BigInteger.ZERO) { sum, item -> sum + java.math.BigInteger.valueOf(item.entry.amountGrosze) }, trend.endBalanceGrosze)
        assertTrue(trend.buckets.zipWithNext().all { (a, b) -> a.to < b.from })
    }
}
