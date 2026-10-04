package pl.bargor.thesaurus.ui.settings

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import pl.bargor.thesaurus.data.firebase.HouseholdRepository
import pl.bargor.thesaurus.data.firebase.OpeningBalanceRepository
import pl.bargor.thesaurus.data.model.Household
import pl.bargor.thesaurus.data.model.Member
import pl.bargor.thesaurus.data.model.SyncObservation
import pl.bargor.thesaurus.data.model.SyncState

@OptIn(ExperimentalCoroutinesApi::class)
class OpeningBalanceViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun before() = Dispatchers.setMain(dispatcher)
    @After fun after() = Dispatchers.resetMain()

    @Test fun bothModesAcceptSignedPolishAmountsAndRejectInvalidValuesWithoutWrites() = runTest {
        for (mode in OpeningBalanceMode.entries) {
            for ((input, expected) in listOf("123,45" to 12_345L, "-123,45" to -12_345L,
                "0" to 0L, "1 234 567 890,12" to 123_456_789_012L)) {
                val repo = FakeRepository()
                val vm = OpeningBalanceViewModel(repo, repo, SavedStateHandle())
                vm.start("home", "owner"); advanceUntilIdle()
                vm.changeMode(mode); vm.changeAmount(input); vm.save(); advanceUntilIdle()
                assertEquals(listOf(Write("home", mode, expected)), repo.writes)
                assertTrue(vm.state.value.saved); assertNull(vm.state.value.error)
            }
            for (input in listOf("", "abc", "1,234", "1 23,45", "92233720368547758,08")) {
                val repo = FakeRepository()
                val vm = OpeningBalanceViewModel(repo, repo, SavedStateHandle())
                vm.start("home", "owner"); advanceUntilIdle()
                vm.changeMode(mode); vm.changeAmount(input); vm.save(); advanceUntilIdle()
                assertTrue(repo.writes.isEmpty()); assertFalse(vm.state.value.saved)
                assertEquals(OpeningBalanceError.INVALID_AMOUNT, vm.state.value.error)
            }
        }
    }

    @Test fun cachedPositiveSettingIsEditableOfflineAndPendingWriteIsNotReportedAsServerSaved() = runTest {
        val repo = FakeRepository()
        repo.home.value = repo.home.value.copy(state = SyncState.OFFLINE)
        repo.gate = CompletableDeferred()
        val vm = OpeningBalanceViewModel(repo, repo, SavedStateHandle())
        vm.start("home", "owner"); advanceUntilIdle()
        assertEquals("250,00", vm.state.value.amount)
        assertEquals(25_000L, vm.state.value.openingBalanceGrosze)
        assertTrue(vm.state.value.isOwner)
        vm.changeAmount("300,00"); vm.save(); advanceUntilIdle()
        repo.home.value = SyncObservation(Household("home", "Dom", "owner", openingBalanceGrosze = 30_000), SyncState.PENDING)
        advanceUntilIdle()
        assertTrue(vm.state.value.saved); assertTrue(vm.state.value.queuedOffline); assertFalse(vm.state.value.saving)
        vm.save(); advanceUntilIdle(); assertEquals(1, repo.writes.size)
        repo.home.value = repo.home.value.copy(state = SyncState.SYNCED)
        repo.gate!!.complete(Unit); advanceUntilIdle()
        assertTrue(vm.state.value.saved); assertFalse(vm.state.value.queuedOffline)
    }

    @Test fun currentModeRequiresSyncedSettingsAndFailedSaveCanRetryWithoutChangingTheDraft() = runTest {
        val repo = FakeRepository()
        repo.home.value = repo.home.value.copy(state = SyncState.OFFLINE)
        val vm = OpeningBalanceViewModel(repo, repo, SavedStateHandle())
        vm.start("home", "owner"); advanceUntilIdle()
        vm.changeMode(OpeningBalanceMode.CURRENT); vm.changeAmount("-50,00"); vm.save(); advanceUntilIdle()
        assertTrue(repo.writes.isEmpty())
        assertEquals(OpeningBalanceError.CURRENT_REQUIRES_SERVER, vm.state.value.error)
        repo.home.value = repo.home.value.copy(state = SyncState.SYNCED)
        repo.failSave = true; advanceUntilIdle(); vm.save(); advanceUntilIdle()
        assertEquals(OpeningBalanceError.SAVE, vm.state.value.error); assertFalse(vm.state.value.saved)
        repo.failSave = false; vm.save(); advanceUntilIdle()
        assertEquals(listOf(Write("home", OpeningBalanceMode.CURRENT, -5_000), Write("home", OpeningBalanceMode.CURRENT, -5_000)), repo.writes)
        assertEquals("-50,00", vm.state.value.amount); assertTrue(vm.state.value.saved)
    }

    @Test fun restoredDraftSurvivesRecreationButCannotLeakToAnotherHouseholdOrActor() = runTest {
        val repo = FakeRepository()
        val handle = SavedStateHandle()
        val vm = OpeningBalanceViewModel(repo, repo, handle)
        vm.start("home", "owner"); advanceUntilIdle()
        vm.changeMode(OpeningBalanceMode.CURRENT); vm.changeAmount("-42,01")
        val recreated = OpeningBalanceViewModel(repo, repo, handle)
        recreated.start("home", "owner"); advanceUntilIdle()
        assertEquals("-42,01", recreated.state.value.amount)
        assertEquals(OpeningBalanceMode.CURRENT, recreated.state.value.mode)
        recreated.start("other", "owner"); advanceUntilIdle()
        assertEquals("0,50", recreated.state.value.amount)
        assertEquals(OpeningBalanceMode.OPENING, recreated.state.value.mode)
        repo.home.value = repo.home.value.copy(value = Household("home", "Dom", "owner", openingBalanceGrosze = 99_999))
        advanceUntilIdle(); assertEquals("0,50", recreated.state.value.amount)
        recreated.start("home", "member"); advanceUntilIdle()
        assertFalse(recreated.state.value.isOwner)
        recreated.changeAmount("12"); recreated.save(); advanceUntilIdle(); assertTrue(repo.writes.isEmpty())
    }

    @Test fun loadFailureDoesNotInventAnOpeningAmountAndRetryRecoversTheDraft() = runTest {
        val repo = FakeRepository()
        repo.home.value = SyncObservation(state = SyncState.ERROR, error = IllegalStateException("failure"))
        val vm = OpeningBalanceViewModel(repo, repo, SavedStateHandle())
        vm.start("home", "owner"); advanceUntilIdle()
        assertNull(vm.state.value.openingBalanceGrosze)
        assertFalse(vm.state.value.isOwner); assertEquals(OpeningBalanceError.LOAD, vm.state.value.error)
        vm.changeAmount("12,34")
        vm.retry(); repo.home.value = SyncObservation(Household("home", "Dom", "owner", openingBalanceGrosze = 25_000), SyncState.SYNCED)
        advanceUntilIdle()
        assertEquals("12,34", vm.state.value.amount); assertNull(vm.state.value.error)
    }

    @Test fun lateCompletionFromAnOldIdentityCannotMarkTheNewHouseholdSaved() = runTest {
        val repo = FakeRepository()
        repo.gate = CompletableDeferred()
        repo.ignoreCancellation = true
        val vm = OpeningBalanceViewModel(repo, repo, SavedStateHandle())
        vm.start("home", "owner"); advanceUntilIdle()
        vm.changeAmount("123,45"); vm.save(); advanceUntilIdle()
        assertTrue(vm.state.value.saving)
        vm.start("other", "owner"); advanceUntilIdle()
        assertEquals("other", vm.state.value.householdId)
        assertFalse(vm.state.value.saving); assertFalse(vm.state.value.saved)
        repo.gate!!.complete(Unit); advanceUntilIdle()
        assertEquals("other", vm.state.value.householdId)
        assertEquals("0,50", vm.state.value.amount)
        assertFalse(vm.state.value.saved); assertNull(vm.state.value.error)
    }

    @Test fun metadataOnlyCacheKeepsOwnerAndOpeningWithoutAcknowledgingAnUnmatchedWrite() = runTest {
        val repo = FakeRepository()
        repo.gate = CompletableDeferred()
        val vm = OpeningBalanceViewModel(repo, repo, SavedStateHandle())
        vm.start("home", "owner"); advanceUntilIdle()
        vm.changeAmount("300,00"); vm.save(); advanceUntilIdle()
        for (sync in listOf(SyncState.OFFLINE, SyncState.PENDING)) {
            repo.home.value = SyncObservation(state = sync); advanceUntilIdle()
            assertFalse(vm.state.value.loading); assertTrue(vm.state.value.isOwner)
            assertEquals(25_000L, vm.state.value.openingBalanceGrosze)
            assertTrue(vm.state.value.saving); assertFalse(vm.state.value.saved); assertFalse(vm.state.value.queuedOffline)
        }
        repo.home.value = SyncObservation(Household("home", "Dom", "owner", openingBalanceGrosze = 30_000), SyncState.PENDING)
        advanceUntilIdle(); assertTrue(vm.state.value.saved); assertTrue(vm.state.value.queuedOffline)
        repo.gate!!.complete(Unit); advanceUntilIdle()
    }

    @Test fun missingOrForeignSynchronizedHouseholdIsALoadErrorAndCannotGrantOwnerPermission() = runTest {
        for (household in listOf(null, Household("foreign", "Inny", "owner", openingBalanceGrosze = 999))) {
            val repo = FakeRepository()
            val vm = OpeningBalanceViewModel(repo, repo, SavedStateHandle())
            vm.start("home", "owner"); advanceUntilIdle()
            repo.home.value = SyncObservation(household, SyncState.SYNCED); advanceUntilIdle()
            assertFalse(vm.state.value.loading); assertFalse(vm.state.value.isOwner)
            assertNull(vm.state.value.openingBalanceGrosze); assertEquals(OpeningBalanceError.LOAD, vm.state.value.error)
            vm.save(); advanceUntilIdle(); assertTrue(repo.writes.isEmpty())
            repo.home.value = SyncObservation(state = SyncState.OFFLINE); advanceUntilIdle()
            assertFalse(vm.state.value.isOwner); assertNull(vm.state.value.openingBalanceGrosze)
        }
    }

    private data class Write(val household: String, val mode: OpeningBalanceMode, val amount: Long)
    private class FakeRepository : HouseholdRepository, OpeningBalanceRepository {
        val home = MutableStateFlow(SyncObservation(Household("home", "Dom", "owner", openingBalanceGrosze = 25_000), SyncState.SYNCED))
        val other = MutableStateFlow(SyncObservation(Household("other", "Inny", "owner", openingBalanceGrosze = 50), SyncState.SYNCED))
        val writes = mutableListOf<Write>()
        var gate: CompletableDeferred<Unit>? = null
        var failSave = false
        var ignoreCancellation = false
        override fun observeHousehold(householdId: String): Flow<SyncObservation<Household>> = if (householdId == "home") home else other
        override fun observeMembers(householdId: String): Flow<SyncObservation<List<Member>>> = error("Unused")
        override suspend fun saveHousehold(household: Household) = Unit
        override suspend fun removeMember(householdId: String, memberId: String) = Unit
        override suspend fun saveOpeningBalance(householdId: String, amountGrosze: Long) = write(householdId, OpeningBalanceMode.OPENING, amountGrosze)
        override suspend fun saveCurrentBalance(householdId: String, currentBalanceGrosze: Long) = write(householdId, OpeningBalanceMode.CURRENT, currentBalanceGrosze)
        private suspend fun write(household: String, mode: OpeningBalanceMode, amount: Long) {
            writes += Write(household, mode, amount)
            if (failSave) error("concurrent change")
            if (ignoreCancellation) withContext(NonCancellable) { gate?.await() } else gate?.await()
        }
    }
}
