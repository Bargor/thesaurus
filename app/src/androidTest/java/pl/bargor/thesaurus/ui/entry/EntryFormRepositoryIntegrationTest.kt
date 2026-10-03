package pl.bargor.thesaurus.ui.entry

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pl.bargor.thesaurus.LocalNetworkPermissionRule
import pl.bargor.thesaurus.ThesaurusTheme
import pl.bargor.thesaurus.data.firebase.*
import pl.bargor.thesaurus.data.model.*

/** Real repository, authentication, cached listeners, ViewModel and the same add/edit form. */
@RunWith(AndroidJUnit4::class)
class EntryFormRepositoryIntegrationTest {
    @get:Rule val networkPermission = LocalNetworkPermissionRule()
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun cachedDropdownSelectionAndTypeOverrideQueueSignedEntryThenRestoreEditDraft() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val suffix = UUID.randomUUID().toString()
        val app = FirebaseApp.initializeApp(instrumentation.targetContext,
            FirebaseOptions.Builder().setApplicationId("1:1234567890:android:test")
                .setApiKey("fake-api-key").setProjectId("demo-thesaurus").build(), "entry-form-$suffix")
        val auth = FirebaseAuthFactory.create(app)
        FirebaseAuthFactory.connectToLocalEmulator(auth)
        val firestore = FirebaseFirestoreFactory.create(app, emulatorHost = "10.0.2.2")
        val store = ViewModelStore()
        try {
            withTimeout(90_000) {
                val email = "entry-form-$suffix@example.test"
                val uid = auth.createUserWithEmailAndPassword(email, "test-password-123").await().user!!.uid
                val repository = FirestoreRepositories(firestore)
                val home = (repository.createFirstHousehold(OnboardingIdentity(uid, email, "Ala"),
                    "Dom testu formularza") as FirstHouseholdResult.Created).householdId
                repository.observeCategories(home).first { it.state == SyncState.SYNCED && it.value.orEmpty().isNotEmpty() }
                val expense = Category("expense-$suffix", home, "Zakupy testowe", defaultEntryType = EntryType.EXPENSE,
                    color = "blue", authorId = uid, updatedById = uid)
                val income = Category("income-$suffix", home, "Wpływy testowe", defaultEntryType = EntryType.INCOME,
                    color = "green", authorId = uid, updatedById = uid)
                val salary = Subcategory("salary-$suffix", home, income.id, "Wypłata testowa", authorId = uid, updatedById = uid)
                repository.save(expense)
                repository.save(income)
                repository.save(salary)
                repository.saveCategoryOrder(CategoryOrder(home, uid, listOf(income.id, expense.id)))
                val today = LocalDate.of(2026, 9, 16)
                lateinit var vm: EntryFormViewModel
                instrumentation.runOnMainSync {
                    vm = EntryFormViewModel(repository, repository, SavedStateHandle())
                    store.put("add", vm)
                    vm.start(home, uid, today)
                }
                suspend fun state(predicate: (EntryFormUiState) -> Boolean) = withTimeout(15_000) { vm.state.first(predicate) }
                val initial = state { !it.isLoading && it.syncState == SyncState.SYNCED &&
                    it.categories.firstOrNull()?.category?.id == income.id &&
                    it.categories.first().subcategories.any { sub -> sub.id == salary.id } }
                assertEquals(listOf(income.id, expense.id), initial.categories.take(2).map { it.category.id })
                composeRule.setContent {
                    val current by vm.state.collectAsState()
                    ThesaurusTheme {
                        EntryFormScreen(current, vm::updateAmount, vm::updateTitle, vm::updateTags,
                            vm::updateDate, vm::updateType, vm::selectCategory, vm::selectSubcategory,
                            onSave = { vm.save(today) }, onBack = {})
                    }
                }
                firestore.disableNetwork().await()
                state { it.syncState == SyncState.OFFLINE }
                composeRule.onNodeWithTag("entry-category-picker").performScrollTo().performClick()
                composeRule.onNodeWithTag("entry-category-${expense.id}").assertIsDisplayed()
                composeRule.onNodeWithTag("entry-category-${income.id}").performClick()
                composeRule.onNodeWithTag("entry-category-menu").assertDoesNotExist()
                composeRule.onNodeWithTag("entry-subcategory-picker").performScrollTo().performClick()
                composeRule.onNodeWithTag("entry-subcategory-${salary.id}").performClick()
                composeRule.onNodeWithTag("entry-subcategory-menu").assertDoesNotExist()
                composeRule.onNodeWithTag("entry-type-expense").performScrollTo().performClick()
                composeRule.onNodeWithTag("entry-subcategory-picker").performScrollTo().performClick()
                composeRule.onNodeWithTag("entry-subcategory-${salary.id}").performScrollTo().assertIsSelected()
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                assertEquals(income.id, vm.state.value.categoryId)
                assertEquals(salary.id, vm.state.value.subcategoryId)
                composeRule.onNodeWithTag("entry-category-picker").performScrollTo().performClick()
                composeRule.onNodeWithTag("entry-category-${income.id}").assertIsSelected()
                // Selecting the current category must keep the manual direction and dependent selection.
                composeRule.onNodeWithTag("entry-category-${income.id}").performClick()
                assertEquals(EntryType.EXPENSE, vm.state.value.type)
                assertEquals(salary.id, vm.state.value.subcategoryId)
                composeRule.runOnIdle { vm.updateAmount("12,50"); vm.updateTitle("   "); vm.save(today) }
                state { it.saved && it.queuedOffline && !it.saving }
                val queued = withTimeout(15_000) {
                    repository.observeEntries(home).first { observation ->
                        observation.state == SyncState.PENDING && observation.value.orEmpty().any { it.categoryId == income.id }
                    }.value!!.single()
                }
                assertEquals(-1250L, queued.amountGrosze)
                assertEquals(income.id, queued.categoryId)
                assertEquals(salary.id, queued.subcategoryId)
                assertNull(queued.title)
                firestore.enableNetwork().await()
                state { it.saved && !it.queuedOffline && it.syncState == SyncState.SYNCED }

                // The edit route loads this exact signed entry, then retains a newer unsaved draft.
                val editHandle = SavedStateHandle()
                lateinit var edit: EntryFormViewModel
                instrumentation.runOnMainSync {
                    edit = EntryFormViewModel(repository, repository, editHandle)
                    store.put("edit", edit)
                    edit.start(home, uid, entryId = queued.id, today = today)
                }
                withTimeout(15_000) { edit.state.first { !it.isLoading && it.categoryId == income.id && it.subcategoryId == salary.id } }
                assertEquals("12,50", edit.state.value.amount)
                assertEquals(EntryType.EXPENSE, edit.state.value.type)
                instrumentation.runOnMainSync { edit.updateAmount("18"); edit.updateType(EntryType.INCOME) }
                lateinit var restored: EntryFormViewModel
                instrumentation.runOnMainSync {
                    val copiedHandle = SavedStateHandle(editHandle.keys().associateWith { editHandle.get<Any?>(it) })
                    restored = EntryFormViewModel(repository, repository, copiedHandle)
                    store.put("restored", restored)
                    restored.start(home, uid, entryId = queued.id, today = today)
                }
                withTimeout(15_000) { restored.state.first { !it.isLoading && it.categories.any { category -> category.category.id == income.id } } }
                assertEquals("18", restored.state.value.amount)
                assertEquals(EntryType.INCOME, restored.state.value.type)
                assertEquals(income.id, restored.state.value.categoryId)
                assertEquals(salary.id, restored.state.value.subcategoryId)
                instrumentation.runOnMainSync { restored.save(today) }
                val persisted = withTimeout(15_000) { repository.observeEntries(home).first { it.state == SyncState.SYNCED && it.value?.singleOrNull()?.amountGrosze == 1800L } }.value!!.single()
                assertEquals(income.id, persisted.categoryId)
                assertEquals(salary.id, persisted.subcategoryId)
                assertEquals(queued.id, repository.observeEntries(home).first { it.value?.singleOrNull()?.amountGrosze == 1800L }.value!!.single().id)
            }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            runCatching { withTimeout(10_000) { firestore.enableNetwork().await() } }
            runCatching { withTimeout(10_000) { firestore.terminate().await() } }
            // Named app/auth avoids touching developer accounts, households or cached entries.
        }
    }
}
