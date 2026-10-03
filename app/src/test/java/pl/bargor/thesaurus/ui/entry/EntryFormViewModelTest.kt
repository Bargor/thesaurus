package pl.bargor.thesaurus.ui.entry

import androidx.lifecycle.SavedStateHandle
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pl.bargor.thesaurus.data.firebase.LedgerRepository
import pl.bargor.thesaurus.data.firebase.TaxonomyRepository
import pl.bargor.thesaurus.data.model.Category
import pl.bargor.thesaurus.data.model.CategoryOrder
import pl.bargor.thesaurus.data.model.EntryType
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.model.Subcategory
import pl.bargor.thesaurus.data.model.SyncObservation
import pl.bargor.thesaurus.data.model.SyncState

@OptIn(ExperimentalCoroutinesApi::class)
class EntryFormViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `null category and subcategory snapshots preserve restored IDs until real snapshots arrive`() = runTest {
        val today = LocalDate.of(2026, 9, 16)
        val handle = SavedStateHandle(mapOf(
            "entry.household" to "home", "entry.actor" to "actor", "entry.hasDraft" to true,
            "entry.categoryId" to "food", "entry.subcategoryId" to "groceries", "entry.amount" to "4",
        ))
        val taxonomy = ControlledTaxonomyRepository()
        val vm = EntryFormViewModel(FakeLedgerRepository(), taxonomy, handle)
        vm.start("home", "actor", today)
        advanceUntilIdle()
        assertTrue(vm.state.value.isLoading)
        assertEquals("food", vm.state.value.categoryId)
        assertEquals("groceries", vm.state.value.subcategoryId)
        val food = Category("food", "home", "Jedzenie", authorId = "actor", updatedById = "actor")
        taxonomy.categoriesFor("home").value = SyncObservation(listOf(food), SyncState.OFFLINE)
        advanceUntilIdle()
        assertFalse(vm.state.value.isLoading)
        assertEquals("food", vm.state.value.categoryId)
        assertEquals("groceries", vm.state.value.subcategoryId)
        taxonomy.subcategoriesFor("home", "food").value = SyncObservation(listOf(
            Subcategory("groceries", "home", "food", "Zakupy", authorId = "actor", updatedById = "actor"),
        ), SyncState.OFFLINE)
        advanceUntilIdle()
        assertEquals("groceries", vm.state.value.subcategoryId)
        assertEquals("groceries", vm.state.value.categories.single().subcategories.single().id)
        taxonomy.subcategoriesFor("home", "food").value = SyncObservation(emptyList(), SyncState.SYNCED)
        advanceUntilIdle()
        assertEquals("food", vm.state.value.categoryId)
        assertNull(vm.state.value.subcategoryId)
        taxonomy.categoriesFor("home").value = SyncObservation(emptyList(), SyncState.SYNCED)
        advanceUntilIdle()
        assertNull(vm.state.value.categoryId)
        assertNull(vm.state.value.subcategoryId)
    }

    @Test
    fun `actor and household rebind reset drafts and cancel previous taxonomy listeners`() = runTest {
        val today = LocalDate.of(2026, 9, 16)
        val taxonomy = ControlledTaxonomyRepository()
        val food = Category("food", "home", "Jedzenie", authorId = "actor", updatedById = "actor")
        val income = Category("income", "home", "Wpływy", authorId = "actor", updatedById = "actor")
        val other = Category("other", "other-home", "Inne", authorId = "actor", updatedById = "actor")
        taxonomy.categoriesFor("home").value = SyncObservation(listOf(food, income), SyncState.SYNCED)
        taxonomy.categoriesFor("other-home").value = SyncObservation(listOf(other), SyncState.SYNCED)
        taxonomy.subcategoriesFor("home", "food").value = SyncObservation(emptyList(), SyncState.SYNCED)
        taxonomy.subcategoriesFor("home", "income").value = SyncObservation(emptyList(), SyncState.SYNCED)
        taxonomy.subcategoriesFor("other-home", "other").value = SyncObservation(emptyList(), SyncState.SYNCED)
        taxonomy.orderFor("home", "actor").value = SyncObservation(CategoryOrder("home", "actor", listOf("food", "income")), SyncState.SYNCED)
        taxonomy.orderFor("home", "other-actor").value = SyncObservation(CategoryOrder("home", "other-actor", listOf("income", "food")), SyncState.SYNCED)
        val vm = EntryFormViewModel(FakeLedgerRepository(), taxonomy, SavedStateHandle())
        vm.start("home", "actor", today)
        advanceUntilIdle()
        vm.selectCategory("food")
        vm.updateAmount("7")
        vm.updateTitle("Draft pierwszego użytkownika")
        vm.start("home", "other-actor", today)
        advanceUntilIdle()
        assertEquals("", vm.state.value.amount)
        assertEquals("", vm.state.value.title)
        assertNull(vm.state.value.categoryId)
        assertEquals(listOf("income", "food"), vm.state.value.categories.map { it.category.id })
        taxonomy.orderFor("home", "actor").value = SyncObservation(CategoryOrder("home", "actor", listOf("income")), SyncState.PENDING)
        advanceUntilIdle()
        assertEquals(SyncState.SYNCED, vm.state.value.syncState)
        assertEquals(listOf("income", "food"), vm.state.value.categories.map { it.category.id })
        vm.selectCategory("income")
        vm.updateAmount("8")
        vm.start("other-home", "other-actor", today)
        advanceUntilIdle()
        assertEquals("", vm.state.value.amount)
        assertNull(vm.state.value.categoryId)
        assertEquals(listOf("other"), vm.state.value.categories.map { it.category.id })
        taxonomy.categoriesFor("home").value = SyncObservation(emptyList(), SyncState.ERROR, IllegalStateException("old listener"))
        advanceUntilIdle()
        assertEquals(listOf("other"), vm.state.value.categories.map { it.category.id })
        assertNull(vm.state.value.error)
    }

    @Test
    fun `unchanged owner edit is acknowledged only when the requested updater is observed`() = runTest {
        val today = LocalDate.of(2026, 9, 16)
        val createdAt = Instant.parse("2026-09-14T08:00:00Z")
        val original = LedgerEntry("existing", "home", -500, today, title = "Zakupy",
            categoryId = "food", tags = listOf("dom"), authorId = "author", updatedById = "author",
            createdAt = createdAt)
        val ledger = FakeLedgerRepository(holdSaveTask = true, initialEntries = listOf(original), emitSnapshotOnSave = false)
        val taxonomy = FakeTaxonomyRepository(listOf(Category("food", "home", "Jedzenie", authorId = "author", updatedById = "author")))
        val handle = SavedStateHandle()
        val vm = EntryFormViewModel(ledger, taxonomy, handle)
        vm.start("home", "owner", entryId = original.id, today = today)
        advanceUntilIdle()
        // Save the unchanged form: the write still requests the current owner as updater.
        vm.save(today)
        advanceUntilIdle()
        assertTrue(vm.state.value.saving)
        assertEquals("owner", ledger.saved.single().updatedById)
        val restored = EntryFormViewModel(ledger, taxonomy, SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }))
        restored.start("home", "owner", entryId = original.id, today = today)
        advanceUntilIdle()
        assertFalse("Matching user fields with the old updater cannot acknowledge the owner's write", restored.state.value.saved)
        assertFalse(restored.state.value.queuedOffline)
        restored.save(today)
        advanceUntilIdle()
        assertEquals(listOf(original.id, original.id), ledger.saved.map { it.id })
        val retried = ledger.saved.last()
        assertEquals(original.amountGrosze, retried.amountGrosze)
        assertEquals(original.date, retried.date)
        assertEquals(original.categoryId, retried.categoryId)
        assertEquals(original.subcategoryId, retried.subcategoryId)
        assertEquals(original.title, retried.title)
        assertEquals(original.tags, retried.tags)
        assertEquals("author", retried.authorId)
        assertEquals(createdAt, retried.createdAt)
        assertEquals("owner", retried.updatedById)
        // Even another pending snapshot with matching fields but the old updater is insufficient.
        ledger.publishSnapshot(listOf(original), SyncState.PENDING)
        advanceUntilIdle()
        assertFalse(restored.state.value.saved)
        ledger.publishSnapshot(listOf(retried), SyncState.PENDING)
        advanceUntilIdle()
        assertTrue(restored.state.value.saved)
        assertTrue(restored.state.value.queuedOffline)
        assertFalse(restored.state.value.saving)
        restored.save(today)
        advanceUntilIdle()
        assertEquals(2, ledger.saved.size)
    }

    @Test
    fun `old same ID edit snapshot cannot acknowledge a restored newer pending payload`() = runTest {
        val today = LocalDate.of(2026, 9, 16)
        val original = LedgerEntry("existing", "home", -500, today.minusDays(1), title = "Dawny tytuł",
            categoryId = "food", authorId = "author", updatedById = "author")
        val ledger = FakeLedgerRepository(holdSaveTask = true, initialEntries = listOf(original), emitSnapshotOnSave = false)
        val taxonomy = FakeTaxonomyRepository(listOf(
            Category("food", "home", "Jedzenie", authorId = "author", updatedById = "author"),
            Category("income", "home", "Wpływy", defaultEntryType = EntryType.INCOME, authorId = "author", updatedById = "author"),
        ), mapOf("income" to listOf(Subcategory("salary", "home", "income", "Wypłata", authorId = "author", updatedById = "author"))))
        val handle = SavedStateHandle()
        val vm = EntryFormViewModel(ledger, taxonomy, handle)
        vm.start("home", "owner", entryId = original.id, today = today)
        advanceUntilIdle()
        vm.selectCategory("income")
        vm.selectSubcategory("salary")
        vm.updateAmount("9")
        vm.updateTitle("Nowy tytuł")
        vm.updateTags("dom")
        vm.updateDate(today)
        vm.save(today)
        advanceUntilIdle()
        assertTrue(vm.state.value.saving)
        val restored = EntryFormViewModel(ledger, taxonomy, SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }))
        restored.start("home", "owner", entryId = original.id, today = today)
        advanceUntilIdle()
        assertFalse("The old cached document is not an acknowledgement of the new edit", restored.state.value.saved)
        assertFalse(restored.state.value.queuedOffline)
        assertEquals("9", restored.state.value.amount)
        assertEquals("income", restored.state.value.categoryId)
        assertEquals("salary", restored.state.value.subcategoryId)
        assertEquals("Nowy tytuł", restored.state.value.title)
        restored.save(today)
        advanceUntilIdle()
        assertEquals(listOf(original.id, original.id), ledger.saved.map { it.id })
        val retried = ledger.saved.last()
        assertEquals(900L, retried.amountGrosze)
        assertEquals(today, retried.date)
        assertEquals("income", retried.categoryId)
        assertEquals("salary", retried.subcategoryId)
        assertEquals("Nowy tytuł", retried.title)
        assertEquals(listOf("dom"), retried.tags)
        assertEquals("author", retried.authorId)
        assertEquals("owner", retried.updatedById)
        ledger.publishSnapshot(listOf(retried), SyncState.PENDING)
        advanceUntilIdle()
        assertTrue(restored.state.value.saved)
        assertTrue(restored.state.value.queuedOffline)
        assertFalse(restored.state.value.saving)
        restored.save(today)
        advanceUntilIdle()
        assertEquals("Retry must not create another document or another write after acknowledgement", 2, ledger.saved.size)
    }

    @Test
    fun `restoring before local acknowledgement retries the same pending document ID`() = runTest {
        val today = LocalDate.of(2026, 9, 16)
        val ledger = FakeLedgerRepository(holdSaveTask = true, emitSnapshotOnSave = false)
        val taxonomy = FakeTaxonomyRepository(listOf(Category("food", "home", "Jedzenie", authorId = "actor", updatedById = "actor")))
        val handle = SavedStateHandle()
        val vm = EntryFormViewModel(ledger, taxonomy, handle)
        vm.start("home", "actor", today)
        advanceUntilIdle()
        vm.selectCategory("food")
        vm.updateAmount("4")
        vm.save(today)
        advanceUntilIdle()
        assertTrue(vm.state.value.saving)
        val pendingId = ledger.saved.single().id
        val restored = EntryFormViewModel(ledger, taxonomy, SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }))
        restored.start("home", "actor", today)
        advanceUntilIdle()
        assertFalse(restored.state.value.saved)
        restored.save(today)
        advanceUntilIdle()
        assertEquals(listOf(pendingId, pendingId), ledger.saved.map { it.id })
    }

    @Test
    fun `restoring acknowledged offline completion does not unlock duplicate creation`() = runTest {
        val today = LocalDate.of(2026, 9, 16)
        val ledger = FakeLedgerRepository(holdSaveTask = true)
        val taxonomy = FakeTaxonomyRepository(listOf(Category("food", "home", "Jedzenie", authorId = "actor", updatedById = "actor")))
        val handle = SavedStateHandle()
        val vm = EntryFormViewModel(ledger, taxonomy, handle)
        vm.start("home", "actor", today)
        advanceUntilIdle()
        vm.selectCategory("food")
        vm.updateAmount("4")
        vm.save(today)
        advanceUntilIdle()
        assertTrue(vm.state.value.saved)
        val restored = EntryFormViewModel(ledger, taxonomy, SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }))
        restored.start("home", "actor", today)
        advanceUntilIdle()
        assertTrue(restored.state.value.saved)
        assertTrue(restored.state.value.queuedOffline)
        assertFalse(restored.state.value.saving)
        restored.save(today)
        advanceUntilIdle()
        assertEquals(1, ledger.saved.size)
    }

    @Test
    fun `offline ordered categories of both defaults remain selectable after manual type override`() = runTest {
        val taxonomy = FakeTaxonomyRepository(
            categories = listOf(
                Category("food", "home", "Jedzenie", authorId = "actor", updatedById = "actor"),
                Category("income", "home", "Wpływy", defaultEntryType = EntryType.INCOME, authorId = "actor", updatedById = "actor"),
            ),
            subcategories = mapOf("income" to listOf(Subcategory("salary", "home", "income", "Wypłata", authorId = "actor", updatedById = "actor"))),
            orderIds = listOf("income", "food"), syncState = SyncState.OFFLINE,
        )
        val vm = EntryFormViewModel(FakeLedgerRepository(), taxonomy, SavedStateHandle())
        vm.start("home", "actor", LocalDate.of(2026, 9, 16))
        advanceUntilIdle()
        vm.selectCategory("income")
        vm.selectSubcategory("salary")
        vm.updateType(EntryType.EXPENSE)
        assertEquals("income", vm.state.value.categoryId)
        assertEquals("salary", vm.state.value.subcategoryId)
        assertEquals(listOf("income", "food"), vm.state.value.categories.map { it.category.id })
        assertEquals(SyncState.OFFLINE, vm.state.value.syncState)
        vm.selectCategory("food")
        assertEquals("food", vm.state.value.categoryId)
        assertNull(vm.state.value.subcategoryId)
        assertEquals(EntryType.EXPENSE, vm.state.value.type)
    }

    @Test
    fun `archived and missing categories cannot replace a valid selection`() = runTest {
        val vm = EntryFormViewModel(FakeLedgerRepository(), FakeTaxonomyRepository(listOf(
            Category("food", "home", "Jedzenie", authorId = "actor", updatedById = "actor"),
            Category("old", "home", "Dawna", archived = true, authorId = "actor", updatedById = "actor"),
        )), SavedStateHandle())
        vm.start("home", "actor", LocalDate.of(2026, 9, 16))
        advanceUntilIdle()
        vm.selectCategory("food")
        vm.updateType(EntryType.INCOME)
        vm.selectCategory("old")
        vm.selectCategory("missing")
        assertEquals("food", vm.state.value.categoryId)
        assertEquals(EntryType.INCOME, vm.state.value.type)
    }

    @Test
    fun `saved draft restores all input including type override and dependent selection`() = runTest {
        val today = LocalDate.of(2026, 9, 16)
        val handle = SavedStateHandle()
        val taxonomy = FakeTaxonomyRepository(
            listOf(Category("income", "home", "Wpływy", defaultEntryType = EntryType.INCOME, authorId = "actor", updatedById = "actor")),
            mapOf("income" to listOf(Subcategory("salary", "home", "income", "Wypłata", authorId = "actor", updatedById = "actor"))),
        )
        val ledger = FakeLedgerRepository()
        val vm = EntryFormViewModel(ledger, taxonomy, handle)
        vm.start("home", "actor", today)
        advanceUntilIdle()
        vm.selectCategory("income")
        vm.selectSubcategory("salary")
        vm.updateType(EntryType.EXPENSE)
        vm.updateAmount("17,25")
        vm.updateTitle("Zmieniony tytuł")
        vm.updateTags("dom, praca")
        vm.updateDate(today.minusDays(2))
        val restoredHandle = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
        val restored = EntryFormViewModel(ledger, taxonomy, restoredHandle)
        restored.start("home", "actor", today.plusDays(1))
        advanceUntilIdle()
        with(restored.state.value) {
            assertEquals("income", categoryId)
            assertEquals("salary", subcategoryId)
            assertEquals(EntryType.EXPENSE, type)
            assertEquals("17,25", amount)
            assertEquals("Zmieniony tytuł", title)
            assertEquals("dom, praca", tags)
            assertEquals(today.minusDays(2), date)
            assertFalse(saved)
            assertFalse(saving)
        }
        restored.save(today)
        advanceUntilIdle()
        assertEquals(-1725L, ledger.saved.single().amountGrosze)
        assertEquals("salary", ledger.saved.single().subcategoryId)
    }

    @Test
    fun `edit draft restore does not overwrite unsaved input from persisted entry`() = runTest {
        val today = LocalDate.of(2026, 9, 16)
        val original = LedgerEntry("existing", "home", -500, today, categoryId = "food", authorId = "actor", updatedById = "actor")
        val ledger = FakeLedgerRepository(initialEntries = listOf(original))
        val taxonomy = FakeTaxonomyRepository(listOf(Category("food", "home", "Jedzenie", authorId = "actor", updatedById = "actor")))
        val handle = SavedStateHandle()
        val vm = EntryFormViewModel(ledger, taxonomy, handle)
        vm.start("home", "actor", entryId = "existing", today = today)
        advanceUntilIdle()
        vm.updateAmount("9,50")
        vm.updateType(EntryType.INCOME)
        val restored = EntryFormViewModel(ledger, taxonomy, SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }))
        restored.start("home", "actor", entryId = "existing", today = today)
        advanceUntilIdle()
        assertEquals("9,50", restored.state.value.amount)
        assertEquals(EntryType.INCOME, restored.state.value.type)
        restored.save(today)
        advanceUntilIdle()
        assertEquals("existing", ledger.saved.single().id)
        assertEquals(950L, ledger.saved.single().amountGrosze)
    }

    @Test
    fun `parser accepts Polish comma and keyboard dot only when exactly representable in grosze`() {
        assertEquals(1_250L, EntryFormValidation.parseMagnitudeGrosze("12,50"))
        assertEquals(1_250L, EntryFormValidation.parseMagnitudeGrosze("12.50"))
        assertEquals(1_200L, EntryFormValidation.parseMagnitudeGrosze("12,0"))
        assertNull(EntryFormValidation.parseMagnitudeGrosze("12,345"))
        assertNull(EntryFormValidation.parseMagnitudeGrosze("0"))
        assertNull(EntryFormValidation.parseMagnitudeGrosze("-12"))
        assertNull(EntryFormValidation.parseMagnitudeGrosze("9,99 zł"))
        assertNull(EntryFormValidation.normalizedTags((1..11).joinToString(",") { "tag$it" }))
        assertEquals(listOf("dom"), EntryFormValidation.normalizedTags("Dom, DOM"))
    }

    @Test
    fun `category sets its default but user can override and saved entry has a signed amount`() = runTest {
        val ledger = FakeLedgerRepository()
        val categories = listOf(
            Category("food", "home", "Jedzenie", defaultEntryType = EntryType.EXPENSE, authorId = "actor", updatedById = "actor"),
            Category("income", "home", "Wpływy", defaultEntryType = EntryType.INCOME, authorId = "actor", updatedById = "actor"),
        )
        val viewModel = EntryFormViewModel(ledger, FakeTaxonomyRepository(categories), SavedStateHandle())
        val today = LocalDate.of(2026, 9, 16)
        viewModel.start("home", "actor", today)
        advanceUntilIdle()

        viewModel.selectCategory("income")
        assertEquals(EntryType.INCOME, viewModel.state.value.type)
        viewModel.updateType(EntryType.EXPENSE)
        viewModel.updateAmount("12,50")
        viewModel.updateTitle("   ")
        viewModel.updateTags(" Dom, zakupy,DOM, ")
        viewModel.save(today)
        advanceUntilIdle()

        val saved = ledger.saved.single()
        assertEquals(-1_250L, saved.amountGrosze)
        assertNull(saved.title)
        assertEquals(listOf("dom", "zakupy"), saved.tags)
        assertEquals("income", saved.categoryId)
        assertTrue(viewModel.state.value.saved)
        assertNull(viewModel.state.value.editingEntryId)
    }

    @Test
    fun `entry form uses the current users persisted category order`() = runTest {
        val categories = listOf(
            Category("food", "home", "Jedzenie", authorId = "actor", updatedById = "actor"),
            Category("home", "home", "Dom", authorId = "actor", updatedById = "actor"),
            Category("other", "home", "Inne", authorId = "actor", updatedById = "actor"),
        )
        val viewModel = EntryFormViewModel(
            FakeLedgerRepository(),
            FakeTaxonomyRepository(categories, orderIds = listOf("other", "food", "home")),
            SavedStateHandle(),
        )

        viewModel.start("home", "actor", LocalDate.of(2026, 9, 16))
        advanceUntilIdle()

        assertEquals(listOf("other", "food", "home"), viewModel.state.value.categories.map { it.category.id })
    }

    @Test
    fun `category change clears subcategory and applies default while same category preserves override`() = runTest {
        val ledger = FakeLedgerRepository()
        val taxonomy = FakeTaxonomyRepository(
            categories = listOf(
                Category("food", "home", "Jedzenie", authorId = "actor", updatedById = "actor"),
                Category("income", "home", "Wpływy", defaultEntryType = EntryType.INCOME, authorId = "actor", updatedById = "actor"),
                Category("other", "home", "Inne", authorId = "actor", updatedById = "actor"),
            ),
            subcategories = mapOf(
                "food" to listOf(Subcategory("groceries", "home", "food", "Zakupy", authorId = "actor", updatedById = "actor")),
                "income" to listOf(Subcategory("salary", "home", "income", "Wypłata", authorId = "actor", updatedById = "actor")),
            ),
        )
        val viewModel = EntryFormViewModel(ledger, taxonomy, SavedStateHandle())
        val today = LocalDate.of(2026, 9, 16)
        viewModel.start("home", "actor", today)
        advanceUntilIdle()

        viewModel.selectCategory("food")
        viewModel.selectSubcategory("groceries")
        viewModel.updateType(EntryType.INCOME)
        viewModel.selectCategory("food")
        assertEquals("groceries", viewModel.state.value.subcategoryId)
        assertEquals(EntryType.INCOME, viewModel.state.value.type)

        viewModel.selectCategory("income")
        assertNull(viewModel.state.value.subcategoryId)
        assertEquals(EntryType.INCOME, viewModel.state.value.type)
        viewModel.selectSubcategory("groceries")
        assertNull(viewModel.state.value.subcategoryId)
        viewModel.selectSubcategory("salary")
        viewModel.selectSubcategory(null)
        assertNull(viewModel.state.value.subcategoryId)

        viewModel.updateType(EntryType.EXPENSE)
        viewModel.selectCategory("other")
        assertEquals(EntryType.EXPENSE, viewModel.state.value.type)
        viewModel.updateAmount("4")
        viewModel.save(today)
        advanceUntilIdle()
        assertEquals("other", ledger.saved.single().categoryId)
        assertNull(ledger.saved.single().subcategoryId)
    }

    @Test
    fun `cleared historical subcategory stays optional after edit draft restoration and saves null`() = runTest {
        val today = LocalDate.of(2026, 9, 16)
        val original = LedgerEntry("existing", "home", -500, today, categoryId = "food", subcategoryId = "old",
            authorId = "actor", updatedById = "actor")
        val ledger = FakeLedgerRepository(initialEntries = listOf(original))
        val taxonomy = FakeTaxonomyRepository(
            listOf(Category("food", "home", "Jedzenie", authorId = "actor", updatedById = "actor")),
            mapOf("food" to listOf(
                Subcategory("old", "home", "food", "Dawna", archived = true, authorId = "actor", updatedById = "actor"),
                Subcategory("active", "home", "food", "Aktywna", authorId = "actor", updatedById = "actor"),
            )),
        )
        val handle = SavedStateHandle()
        val vm = EntryFormViewModel(ledger, taxonomy, handle)
        vm.start("home", "actor", entryId = original.id, today = today)
        advanceUntilIdle()
        assertEquals("old", vm.state.value.subcategoryId)
        vm.selectSubcategory("active")
        vm.selectSubcategory("old")
        assertEquals("Archived subcategories cannot be reassigned", "active", vm.state.value.subcategoryId)
        vm.selectSubcategory(null)
        vm.updateType(EntryType.INCOME)
        val restored = EntryFormViewModel(ledger, taxonomy, SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }))
        restored.start("home", "actor", entryId = original.id, today = today)
        advanceUntilIdle()
        assertEquals("food", restored.state.value.categoryId)
        assertNull(restored.state.value.subcategoryId)
        assertEquals(EntryType.INCOME, restored.state.value.type)
        assertEquals(listOf("old", "active"), restored.state.value.categories.single().subcategories.map { it.id })
        restored.save(today)
        advanceUntilIdle()
        assertEquals(original.id, ledger.saved.single().id)
        assertEquals("food", ledger.saved.single().categoryId)
        assertNull(ledger.saved.single().subcategoryId)
        assertEquals(500L, ledger.saved.single().amountGrosze)
    }

    @Test
    fun `editing restores category and optional subcategory selection`() = runTest {
        val entry = LedgerEntry(
            id = "existing", householdId = "home", amountGrosze = 500,
            date = LocalDate.of(2026, 9, 15), categoryId = "income", subcategoryId = "salary",
            authorId = "actor", updatedById = "actor",
        )
        val viewModel = EntryFormViewModel(
            FakeLedgerRepository(initialEntries = listOf(entry)),
            FakeTaxonomyRepository(
                categories = listOf(Category("income", "home", "Wpływy", defaultEntryType = EntryType.INCOME, authorId = "actor", updatedById = "actor")),
                subcategories = mapOf("income" to listOf(Subcategory("salary", "home", "income", "Wypłata", authorId = "actor", updatedById = "actor"))),
            ),
            SavedStateHandle(),
        )
        viewModel.start("home", "actor", entryId = "existing", today = LocalDate.of(2026, 9, 16))
        advanceUntilIdle()
        assertEquals("income", viewModel.state.value.categoryId)
        assertEquals("salary", viewModel.state.value.subcategoryId)
        assertEquals(EntryType.INCOME, viewModel.state.value.type)
        viewModel.start("home", "actor", entryId = "existing", today = LocalDate.of(2026, 9, 16))
        assertEquals("salary", viewModel.state.value.subcategoryId)
    }

    @Test
    fun `future date invalid amount and inactive category do not queue writes`() = runTest {
        val ledger = FakeLedgerRepository()
        val viewModel = EntryFormViewModel(
            ledger,
            FakeTaxonomyRepository(listOf(Category("food", "home", "Jedzenie", authorId = "actor", updatedById = "actor"))),
            SavedStateHandle(),
        )
        val today = LocalDate.of(2026, 9, 16)
        viewModel.start("home", "actor", today)
        advanceUntilIdle()
        viewModel.selectCategory("food")
        viewModel.updateAmount("0")
        viewModel.save(today)
        assertEquals(EntryFormError.InvalidAmount, viewModel.state.value.error)
        viewModel.updateAmount("1")
        viewModel.updateDate(today.plusDays(1))
        viewModel.save(today)
        assertEquals(EntryFormError.FutureDate, viewModel.state.value.error)
        assertTrue(ledger.saved.isEmpty())
    }

    @Test
    fun `repository failure is retained and saving is cleared`() = runTest {
        val ledger = FakeLedgerRepository(fail = true)
        val viewModel = EntryFormViewModel(
            ledger,
            FakeTaxonomyRepository(listOf(Category("food", "home", "Jedzenie", authorId = "actor", updatedById = "actor"))),
            SavedStateHandle(),
        )
        val today = LocalDate.of(2026, 9, 16)
        viewModel.start("home", "actor", today)
        advanceUntilIdle()
        viewModel.selectCategory("food")
        viewModel.updateAmount("2")
        viewModel.save(today)
        advanceUntilIdle()
        assertEquals(EntryFormError.SaveFailed, viewModel.state.value.error)
        assertFalse(viewModel.state.value.saving)
        assertFalse(viewModel.state.value.saved)
    }

    @Test
    fun `pending local snapshot completes the form while offline save task is still pending`() = runTest {
        val ledger = FakeLedgerRepository(holdSaveTask = true)
        val viewModel = EntryFormViewModel(
            ledger,
            FakeTaxonomyRepository(listOf(Category("food", "home", "Jedzenie", authorId = "actor", updatedById = "actor"))),
            SavedStateHandle(),
        )
        val today = LocalDate.of(2026, 9, 16)
        viewModel.start("home", "actor", today)
        advanceUntilIdle()
        viewModel.selectCategory("food")
        viewModel.updateAmount("4")
        viewModel.save(today)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.saved)
        assertTrue(viewModel.state.value.queuedOffline)
        assertFalse(viewModel.state.value.saving)
        assertNull(viewModel.state.value.editingEntryId)
        viewModel.save(today)
        advanceUntilIdle()
        assertEquals(1, ledger.saved.size)
    }

    @Test
    fun `editing a signed entry exposes magnitude and preserves immutable author metadata`() = runTest {
        val createdAt = Instant.parse("2026-09-14T08:00:00Z")
        val original = LedgerEntry(
            id = "existing", householdId = "home", amountGrosze = -1_250,
            date = LocalDate.of(2026, 9, 14), title = "Zakupy", categoryId = "food",
            tags = listOf("dom"), authorId = "author", updatedById = "author",
            createdAt = createdAt,
        )
        val ledger = FakeLedgerRepository(initialEntries = listOf(original))
        val viewModel = EntryFormViewModel(
            ledger,
            FakeTaxonomyRepository(listOf(
                Category(
                    "food", "home", "Jedzenie", archived = true,
                    authorId = "author", updatedById = "author",
                ),
            )),
            SavedStateHandle(),
        )
        val today = LocalDate.of(2026, 9, 16)
        viewModel.start("home", "owner", entryId = "existing", today = today)
        advanceUntilIdle()

        assertEquals("12,50", viewModel.state.value.amount)
        assertEquals(EntryType.EXPENSE, viewModel.state.value.type)
        assertEquals("Zakupy", viewModel.state.value.title)
        assertTrue(viewModel.state.value.categories.single().category.archived)
        viewModel.updateType(EntryType.INCOME)
        viewModel.updateAmount("15")
        viewModel.save(today)
        advanceUntilIdle()

        val saved = ledger.saved.single()
        assertEquals("existing", saved.id)
        assertEquals(1_500L, saved.amountGrosze)
        assertEquals("author", saved.authorId)
        assertEquals("owner", saved.updatedById)
        assertEquals(createdAt, saved.createdAt)
        assertEquals("existing", viewModel.state.value.editingEntryId)
    }
}

private class FakeLedgerRepository(
    private val fail: Boolean = false,
    private val holdSaveTask: Boolean = false,
    initialEntries: List<LedgerEntry> = emptyList(),
    private val emitSnapshotOnSave: Boolean = true,
) : LedgerRepository {
    val saved = mutableListOf<LedgerEntry>()
    private val entries = MutableStateFlow(SyncObservation(initialEntries, SyncState.SYNCED))
    fun publishSnapshot(value: List<LedgerEntry>, state: SyncState) {
        entries.value = SyncObservation(value, state)
    }
    override fun observeEntries(householdId: String, includeDeleted: Boolean): Flow<SyncObservation<List<LedgerEntry>>> =
        entries
    override suspend fun save(entry: LedgerEntry) {
        if (fail) error("offline")
        saved += entry
        if (emitSnapshotOnSave) entries.value = SyncObservation(listOf(entry), SyncState.PENDING)
        if (holdSaveTask) kotlinx.coroutines.awaitCancellation()
    }
    override suspend fun tombstone(householdId: String, entryId: String, actorId: String) = Unit
}

private class ControlledTaxonomyRepository : TaxonomyRepository {
    private val categoryFlows = mutableMapOf<String, MutableStateFlow<SyncObservation<List<Category>>>>()
    private val subcategoryFlows = mutableMapOf<Pair<String, String>, MutableStateFlow<SyncObservation<List<Subcategory>>>>()
    private val orderFlows = mutableMapOf<Pair<String, String>, MutableStateFlow<SyncObservation<CategoryOrder>>>()
    fun categoriesFor(home: String) = categoryFlows.getOrPut(home) { MutableStateFlow(SyncObservation(state = SyncState.OFFLINE)) }
    fun subcategoriesFor(home: String, categoryId: String) = subcategoryFlows.getOrPut(home to categoryId) { MutableStateFlow(SyncObservation(state = SyncState.OFFLINE)) }
    fun orderFor(home: String, actor: String) = orderFlows.getOrPut(home to actor) { MutableStateFlow(SyncObservation(state = SyncState.SYNCED)) }
    override fun observeCategories(householdId: String): Flow<SyncObservation<List<Category>>> = categoriesFor(householdId)
    override fun observeSubcategories(householdId: String, categoryId: String): Flow<SyncObservation<List<Subcategory>>> = subcategoriesFor(householdId, categoryId)
    override fun observeCategoryOrder(householdId: String, userId: String): Flow<SyncObservation<CategoryOrder>> = orderFor(householdId, userId)
    override suspend fun save(category: Category) = Unit
    override suspend fun save(subcategory: Subcategory) = Unit
}

private class FakeTaxonomyRepository(
    categories: List<Category>,
    private val subcategories: Map<String, List<Subcategory>> = emptyMap(),
    private val orderIds: List<String>? = null,
    private val syncState: SyncState = SyncState.SYNCED,
) : TaxonomyRepository {
    private val state = MutableStateFlow(SyncObservation(categories, syncState))
    override fun observeCategories(householdId: String): Flow<SyncObservation<List<Category>>> = state
    override fun observeSubcategories(householdId: String, categoryId: String): Flow<SyncObservation<List<Subcategory>>> =
        flowOf(SyncObservation(subcategories[categoryId].orEmpty(), syncState))
    override suspend fun save(category: Category) = Unit
    override suspend fun save(subcategory: Subcategory) = Unit
    override fun observeCategoryOrder(
        householdId: String,
        userId: String,
    ): Flow<SyncObservation<CategoryOrder>> = flowOf(
        SyncObservation(orderIds?.let { CategoryOrder(householdId, userId, it) }, syncState),
    )
}
