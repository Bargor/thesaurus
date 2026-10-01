package pl.bargor.thesaurus.ui.summary

import android.os.Bundle
import android.os.Parcel
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Clock
import java.time.Instant
import java.time.Year
import java.time.YearMonth
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import pl.bargor.thesaurus.data.firebase.*
import pl.bargor.thesaurus.data.model.*

class SummarySavedStateTest {
    @Test fun registryRestoresOpenZeroMonthDetailForSameHousehold() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val original = Owner(null)
            val vm = original.summary()
            vm.start("home", "actor")
            vm.previousYear()
            vm.openPeriod(SummaryPeriodKey(SummaryPeriodMode.MONTH, 2025, 2))
            assertEquals(2, vm.state.value.detailCard!!.key.month)
            val saved = Bundle().also(original.controller::performSave)
            val parcel = Parcel.obtain()
            val restoredBundle = try {
                parcel.writeBundle(saved); parcel.setDataPosition(0)
                requireNotNull(parcel.readBundle(SummaryViewModel::class.java.classLoader))
            } finally { parcel.recycle() }
            original.viewModelStore.clear()
            original.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            val restored = Owner(restoredBundle)
            val restoredVm = restored.summary()
            restoredVm.start("home", "actor")
            assertEquals(SummaryPeriodKey(SummaryPeriodMode.MONTH, 2025, 2), restoredVm.state.value.detailCard!!.key)
            assertEquals(Year.of(2025), restoredVm.state.value.year)
            restoredVm.closePeriod()
            assertEquals(Year.of(2025), restoredVm.state.value.year)
            restored.viewModelStore.clear()
            restored.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
    }
    @Test fun configurationRecreationRetainsViewModelAndSelectedPeriod() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val original = Owner(null)
            val vm = original.summary()
            vm.selectPeriodMode(SummaryPeriodMode.YEAR)
            vm.previousYear()
            val saved = Bundle().also(original.controller::performSave)
            // Configuration recreation retains the store while replacing the lifecycle owner.
            original.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            val recreated = Owner(saved, original.viewModelStore)
            val retainedVm = recreated.summary()
            assertSame(vm, retainedVm)
            assertEquals(SummaryPeriodMode.YEAR, retainedVm.state.value.mode)
            assertEquals(Year.of(2025), retainedVm.state.value.year)
            retainedVm.selectPeriodMode(SummaryPeriodMode.MONTH)
            assertEquals(YearMonth.of(2025, 9), retainedVm.state.value.month)
            recreated.viewModelStore.clear()
            recreated.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
    }

    @Test fun registryBundleRoundTripRestoresModeYearAndRememberedMonth() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val original = Owner(null)
            val vm = original.summary()
            vm.selectPeriodMode(SummaryPeriodMode.YEAR)
            vm.previousYear()
            val saved = Bundle().also(original.controller::performSave)
            // Parcel the registry output, as Android does across process recreation.
            val parcel = Parcel.obtain()
            val restoredBundle = try {
                parcel.writeBundle(saved)
                parcel.setDataPosition(0)
                requireNotNull(parcel.readBundle(SummaryViewModel::class.java.classLoader))
            } finally { parcel.recycle() }
            original.viewModelStore.clear()
            original.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            val restored = Owner(restoredBundle)
            val restoredVm = restored.summary()
            assertEquals(SummaryPeriodMode.YEAR, restoredVm.state.value.mode)
            assertEquals(Year.of(2025), restoredVm.state.value.year)
            assertEquals(YearMonth.of(2025, 9), restoredVm.state.value.month)
            restoredVm.selectPeriodMode(SummaryPeriodMode.MONTH)
            assertEquals(YearMonth.of(2025, 9), restoredVm.state.value.month)
            restored.viewModelStore.clear()
            restored.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
    }

    private class Owner(saved: Bundle?, override val viewModelStore: ViewModelStore = ViewModelStore()) : SavedStateRegistryOwner, ViewModelStoreOwner {
        val registry = LifecycleRegistry(this)
        val controller = SavedStateRegistryController.create(this)
        override val lifecycle get() = registry
        override val savedStateRegistry get() = controller.savedStateRegistry

        init {
            controller.performAttach()
            controller.performRestore(saved)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        }

        @Suppress("DEPRECATION", "UNCHECKED_CAST")
        fun summary(): SummaryViewModel {
            val factory = object : AbstractSavedStateViewModelFactory(this, null) {
                override fun <T : ViewModel> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T =
                    SummaryViewModel(
                        EmptyRepositories, EmptyRepositories, EmptyRepositories,
                        Clock.fixed(Instant.parse("2026-09-15T12:00:00Z"), ZoneOffset.UTC), handle,
                    ) as T
            }
            return ViewModelProvider(this, factory)[SummaryViewModel::class.java]
        }
    }

    private object EmptyRepositories : LedgerRepository, TaxonomyRepository, HouseholdRepository {
        override fun observeEntries(householdId: String, includeDeleted: Boolean): Flow<SyncObservation<List<LedgerEntry>>> = flowOf(SyncObservation(emptyList(), SyncState.SYNCED))
        override fun observeCategories(householdId: String): Flow<SyncObservation<List<Category>>> = flowOf(SyncObservation(emptyList(), SyncState.SYNCED))
        override fun observeSubcategories(householdId: String, categoryId: String): Flow<SyncObservation<List<Subcategory>>> = flowOf(SyncObservation(emptyList(), SyncState.SYNCED))
        override fun observeHousehold(householdId: String): Flow<SyncObservation<Household>> = flowOf(SyncObservation(state = SyncState.SYNCED))
        override fun observeMembers(householdId: String): Flow<SyncObservation<List<Member>>> = flowOf(SyncObservation(emptyList(), SyncState.SYNCED))
        override suspend fun save(entry: LedgerEntry) = Unit
        override suspend fun tombstone(householdId: String, entryId: String, actorId: String) = Unit
        override suspend fun save(category: Category) = Unit
        override suspend fun save(subcategory: Subcategory) = Unit
        override suspend fun saveHousehold(household: Household) = Unit
        override suspend fun removeMember(householdId: String, memberId: String) = Unit
    }

}
