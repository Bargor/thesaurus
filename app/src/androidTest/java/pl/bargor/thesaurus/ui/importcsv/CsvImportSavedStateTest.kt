package pl.bargor.thesaurus.ui.importcsv

import android.content.ContextWrapper
import android.os.Bundle
import android.os.Parcel
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import pl.bargor.thesaurus.data.model.*
import pl.bargor.thesaurus.data.transfer.*

class CsvImportSavedStateTest {
    @Test fun registryParcelContainsOnlySmallTokenAndFreshViewModelRestoresPrivateDraft() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val directory = File(target.cacheDir, "issue112-saved-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(target) { override fun getNoBackupFilesDir() = directory }
        val backend = Backend()
        val coordinator = CsvImportCoordinator(backend) { LocalDate.of(2026, 10, 10) }
        val store = PrivateCsvImportCheckpointStore(context)
        lateinit var owner: Owner
        lateinit var vm: CsvImportViewModel
        instrumentation.runOnMainSync {
            owner = Owner(null, coordinator, store)
            vm = owner.model()
            vm.bind("house", "owner")
            vm.load("date;amount;category;title\nbad;-1;Food;SECRET-LARGE-SOURCE-${"x".repeat(6000)}".toByteArray())
        }
        try {
            await { vm.state.value.phase == CsvImportPhase.READY && !vm.state.value.busy }
            instrumentation.runOnMainSync { vm.start() }
            await { vm.state.value.phase == CsvImportPhase.ATTENTION && !vm.state.value.busy }
            val draft = listOf("2026-10-09", "-1", "Food", "PRIVATE-DRAFT")
            instrumentation.runOnMainSync { vm.editRow(2, draft) }
            await { vm.state.value.drafts[2] == draft && !vm.state.value.busy }
            lateinit var restoredBundle: Bundle
            instrumentation.runOnMainSync {
                val saved = Bundle().also(owner.controller::performSave)
                val parcel = Parcel.obtain()
                try {
                    parcel.writeBundle(saved)
                    assertTrue("Only opaque token and scope belong in Bundle", parcel.dataSize() < 4096)
                    val serialized = parcel.marshall()
                    assertFalse(String(serialized, Charsets.ISO_8859_1).contains("PRIVATE-DRAFT"))
                    assertFalse(String(serialized, Charsets.UTF_16LE).contains("SECRET-LARGE-SOURCE"))
                    parcel.setDataPosition(0)
                    restoredBundle = requireNotNull(parcel.readBundle(CsvImportViewModel::class.java.classLoader))
                } finally { parcel.recycle() }
                owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
                val rotation = Owner(saved, coordinator, store, owner.viewModelStore)
                assertSame(vm, rotation.model())
                assertEquals(draft, rotation.model().state.value.drafts[2])
                rotation.viewModelStore.clear()
                rotation.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
                owner = Owner(restoredBundle, coordinator, PrivateCsvImportCheckpointStore(context))
                val old = vm
                vm = owner.model()
                assertNotSame(old, vm)
                vm.bind("house", "owner")
            }
            await { vm.state.value.phase == CsvImportPhase.RESTORED && !vm.state.value.busy }
            assertEquals(draft, vm.state.value.drafts[2])
            assertTrue(backend.writes.isEmpty())
        } finally {
            instrumentation.runOnMainSync { owner.viewModelStore.clear(); owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY) }
            directory.deleteRecursively()
        }
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(10000) { while (!condition()) delay(10) }
    private class Owner(saved: Bundle?, val coordinator: CsvImportCoordinator, val store: PrivateCsvImportCheckpointStore,
        override val viewModelStore: ViewModelStore = ViewModelStore()) : SavedStateRegistryOwner, ViewModelStoreOwner {
        val registry = LifecycleRegistry(this)
        val controller = SavedStateRegistryController.create(this)
        override val lifecycle get() = registry
        override val savedStateRegistry get() = controller.savedStateRegistry
        init { controller.performAttach(); controller.performRestore(saved); enableSavedStateHandles(); registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE) }
        fun model(): CsvImportViewModel {
            val factory = viewModelFactory { initializer { CsvImportViewModel(coordinator, store, createSavedStateHandle()) } }
            val extras = MutableCreationExtras().apply {
                this[SAVED_STATE_REGISTRY_OWNER_KEY] = this@Owner
                this[VIEW_MODEL_STORE_OWNER_KEY] = this@Owner
            }
            return ViewModelProvider(viewModelStore, factory, extras)[CsvImportViewModel::class.java]
        }
    }
    private class Backend : CsvImportBackend {
        val writes = mutableListOf<LedgerEntry>()
        override fun currentIdentity() = CsvImportIdentity("owner", "session")
        override suspend fun loadSnapshot(householdId: String, expectedIdentity: CsvImportIdentity) =
            CsvImportSnapshot(expectedIdentity, Household("house", "Synthetic", "owner"), writes.toList(),
                listOf(Category("food", "house", "Food", authorId = "owner", updatedById = "owner")), emptyList())
        override suspend fun commitEntry(snapshot: CsvImportSnapshot, entry: LedgerEntry): CsvImportCommitResult {
            writes += entry; return CsvImportCommitResult(writes.size.toLong(), true)
        }
    }
}
