package pl.bargor.thesaurus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import pl.bargor.thesaurus.ui.balance.GlobalAccountBalanceBar
import pl.bargor.thesaurus.ui.balance.GlobalAccountBalanceUiState
import pl.bargor.thesaurus.ui.balance.GlobalAccountBalanceViewModel
import pl.bargor.thesaurus.ui.browse.BrowseScreen
import pl.bargor.thesaurus.ui.browse.BrowseViewModel
import pl.bargor.thesaurus.ui.entries.EntryListScreen
import pl.bargor.thesaurus.ui.entries.EntryListViewModel
import pl.bargor.thesaurus.ui.entry.EntryFormScreen
import pl.bargor.thesaurus.ui.entry.EntryFormUiState
import pl.bargor.thesaurus.ui.entry.EntryFormViewModel
import pl.bargor.thesaurus.ui.family.FamilyScreen
import pl.bargor.thesaurus.ui.family.FamilyViewModel
import pl.bargor.thesaurus.ui.reports.ReportsScreen
import pl.bargor.thesaurus.ui.reports.ReportsCalendarEffect
import pl.bargor.thesaurus.ui.reports.ReportsViewModel
import pl.bargor.thesaurus.ui.settings.OpeningBalanceScreen
import pl.bargor.thesaurus.ui.settings.OpeningBalanceUiState
import pl.bargor.thesaurus.ui.settings.OpeningBalanceViewModel
import pl.bargor.thesaurus.ui.summary.SummaryPeriodMode
import pl.bargor.thesaurus.ui.summary.SummaryScreen
import pl.bargor.thesaurus.ui.summary.SummaryViewModel
import pl.bargor.thesaurus.ui.taxonomy.TaxonomyScreen
import pl.bargor.thesaurus.ui.taxonomy.TaxonomyViewModel

// Route bindings run at their existing NavHost entries, preserving Hilt owners and draft state.
@Composable
internal fun OpeningBalanceRoute(
    householdId: String,
    actorId: String,
    onBack: () -> Unit,
    viewModel: OpeningBalanceViewModel = hiltViewModel(),
) {
    LaunchedEffect(householdId, actorId) { viewModel.start(householdId, actorId) }
    val observed by viewModel.state.collectAsState()
    val visible = if (observed.householdId == householdId && observed.actorId == actorId) observed
        else OpeningBalanceUiState(householdId = householdId, actorId = actorId)
    OpeningBalanceScreen(visible, viewModel::changeAmount, viewModel::changeMode,
        viewModel::save, viewModel::retry, onBack)
}

@Composable
internal fun GlobalAccountBalanceRoute(
    householdId: String,
    actorId: String,
    // Called above NavHost: one app-scoped listener survives destination navigation.
    viewModel: GlobalAccountBalanceViewModel = hiltViewModel(key = "global-account-balance"),
) {
    val observed by viewModel.state.collectAsState()
    LaunchedEffect(viewModel, householdId, actorId) { viewModel.start(householdId, actorId) }
    DisposableEffect(viewModel) { onDispose { viewModel.stop() } }
    // Effects run after composition; gate the first frame of an account change too.
    val visible = if (observed.householdId == householdId && observed.actorId == actorId) observed
        else GlobalAccountBalanceUiState(householdId = householdId, actorId = actorId)
    GlobalAccountBalanceBar(visible, onRetry = viewModel::retry)
}

@Composable
internal fun BrowseRoute(
    householdId: String,
    actorId: String,
    onOpenEntry: (String) -> Unit,
    browseViewModel: BrowseViewModel = hiltViewModel(),
) {
    LaunchedEffect(householdId, actorId) { browseViewModel.start(householdId, actorId) }
    val state by browseViewModel.state.collectAsState()
    BrowseScreen(
        state = state,
        onSelectPeriodMode = browseViewModel::selectPeriodMode,
        onPreviousPeriod = {
            if (state.mode == SummaryPeriodMode.MONTH) browseViewModel.previousMonth()
            else browseViewModel.previousYear()
        },
        onNextPeriod = {
            if (state.mode == SummaryPeriodMode.MONTH) browseViewModel.nextMonth()
            else browseViewModel.nextYear()
        },
        onToggleCategory = browseViewModel::toggleCategory,
        onToggleSubcategory = browseViewModel::toggleSubcategory,
        onRetry = browseViewModel::retry,
        onOpenEntry = onOpenEntry,
    )
}

@Composable
internal fun SummaryRoute(
    householdId: String,
    actorId: String,
    onOpenEntry: (String) -> Unit,
    summaryViewModel: SummaryViewModel = hiltViewModel(),
) {
    LaunchedEffect(householdId, actorId) { summaryViewModel.start(householdId, actorId) }
    val state by summaryViewModel.state.collectAsState()
    SummaryScreen(
        state = state,
        onSelectPeriodMode = summaryViewModel::selectPeriodMode,
        onPreviousPeriod = summaryViewModel::previousYear,
        onNextPeriod = summaryViewModel::nextYear,
        onOpenPeriod = summaryViewModel::openPeriod,
        onClosePeriod = summaryViewModel::closePeriod,
        onOpenEntry = onOpenEntry,
        onRetry = summaryViewModel::retry,
    )
}

@Composable
internal fun ReportsRoute(
    householdId: String,
    onOpenEntry: (String) -> Unit,
    reportsViewModel: ReportsViewModel = hiltViewModel(),
) {
    LaunchedEffect(householdId) { reportsViewModel.start(householdId) }
    ReportsCalendarEffect(householdId, reportsViewModel)
    val state by reportsViewModel.state.collectAsState()
    ReportsScreen(
        state = state,
        onSelectPeriodMode = reportsViewModel::selectPeriodMode,
        onPreviousPeriod = reportsViewModel::previousPeriod,
        onNextPeriod = reportsViewModel::nextPeriod,
        onSelectType = reportsViewModel::selectType,
        onCustomFromChange = reportsViewModel::updateCustomFrom,
        onCustomToChange = reportsViewModel::updateCustomTo,
        onApplyCustomPeriod = reportsViewModel::applyCustomPeriod,
        onOpenEntry = onOpenEntry,
        onRetry = reportsViewModel::retry,
        onSelectCategory = reportsViewModel::selectCategory,
        onSelectSubcategory = reportsViewModel::selectSubcategory,
        onSelectSort = reportsViewModel::selectSort,
        onToggleSortDirection = reportsViewModel::toggleSortDirection,
        onOpenFilters = reportsViewModel::openFilters,
        onDismissFilters = reportsViewModel::dismissFilters,
        onApplyFilters = reportsViewModel::applyFilters,
        onResetFilters = reportsViewModel::resetFilters,
        onSelectMembers = reportsViewModel::selectMembers,
    )
}

@Composable
internal fun EntryListRoute(
    householdId: String,
    actorId: String,
    onOpenSettings: () -> Unit,
    onAddEntry: () -> Unit,
    onOpenFamily: () -> Unit,
    onEditEntry: (String) -> Unit,
    entryListViewModel: EntryListViewModel = hiltViewModel(),
) {
    LaunchedEffect(householdId, actorId) { entryListViewModel.start(householdId, actorId) }
    val state by entryListViewModel.state.collectAsState()
    EntryListScreen(
        state = state,
        onChangeSort = entryListViewModel::changeSort,
        onRevealMore = entryListViewModel::revealMoreEntries,
        onRetry = entryListViewModel::retry,
        onOpenSettings = onOpenSettings,
        onAddEntry = onAddEntry,
        onOpenFamily = onOpenFamily,
        onEditEntry = onEditEntry,
        onConfirmDelete = entryListViewModel::confirmDelete,
        onUndoDelete = entryListViewModel::undoDelete,
    )
}

@Composable
internal fun FamilyRoute(
    householdId: String,
    actorId: String,
    onBack: () -> Unit,
    familyViewModel: FamilyViewModel = hiltViewModel(),
) {
    LaunchedEffect(householdId, actorId) { familyViewModel.start(householdId, actorId) }
    val state by familyViewModel.state.collectAsState()
    FamilyShareEffect(state.shareUrl, familyViewModel::shareHandled)
    FamilyScreen(
        state = state,
        onCreateInvitation = familyViewModel::createInvitation,
        onRevokeInvitation = familyViewModel::revokeInvitation,
        onRemoveMember = familyViewModel::removeMember,
        onClearError = familyViewModel::clearError,
        onBack = onBack,
    )
}

@Composable
internal fun EntryFormRoute(
    householdId: String,
    actorId: String,
    entryId: String? = null,
    onBack: () -> Unit,
    onCreated: (() -> Unit)?,
    entryFormViewModel: EntryFormViewModel = hiltViewModel(),
) {
    require(entryId != null || onCreated != null) { "New entries require a creation navigation callback." }
    LaunchedEffect(householdId, actorId, entryId) { entryFormViewModel.start(householdId, actorId, entryId) }
    val state by entryFormViewModel.state.collectAsState()
    if (onCreated != null) EntryFormCompletion(state, onCreated)
    EntryFormScreen(
        state = state,
        onAmountChange = entryFormViewModel::updateAmount,
        onTitleChange = entryFormViewModel::updateTitle,
        onTagsChange = entryFormViewModel::updateTags,
        onDateChange = entryFormViewModel::updateDate,
        onTypeChange = entryFormViewModel::updateType,
        onCategorySelected = entryFormViewModel::selectCategory,
        onSubcategorySelected = entryFormViewModel::selectSubcategory,
        onSave = entryFormViewModel::save,
        onBack = onBack,
    )
}

@Composable
internal fun EntryFormCompletion(state: EntryFormUiState, onCreated: () -> Unit) {
    LaunchedEffect(state.saved, state.editingEntryId) {
        if (state.saved && state.editingEntryId == null) onCreated()
    }
}

@Composable
internal fun TaxonomyRoute(
    householdId: String,
    actorId: String,
    onBack: () -> Unit,
    taxonomyViewModel: TaxonomyViewModel = hiltViewModel(),
) {
    LaunchedEffect(householdId, actorId) { taxonomyViewModel.start(householdId, actorId) }
    val state by taxonomyViewModel.state.collectAsState()
    TaxonomyScreen(
        state = state,
        onMutation = taxonomyViewModel::mutate,
        onMoveCategory = taxonomyViewModel::moveCategory,
        onReorderCategories = taxonomyViewModel::reorderCategories,
        onBack = onBack,
    )
}
