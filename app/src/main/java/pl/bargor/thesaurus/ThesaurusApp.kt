package pl.bargor.thesaurus

import androidx.annotation.StringRes
import android.app.Activity
import android.content.Intent
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.ShortNavigationBarDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import pl.bargor.thesaurus.data.connectivity.AndroidNetworkMonitor
import pl.bargor.thesaurus.data.connectivity.NetworkMonitor
import pl.bargor.thesaurus.ui.OfflineStatusHost
import pl.bargor.thesaurus.ui.LocalDismissOfflineTooltip
import pl.bargor.thesaurus.ui.rememberNetworkOnline
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import pl.bargor.thesaurus.ui.auth.AuthUiState
import pl.bargor.thesaurus.ui.auth.AuthViewModel
import pl.bargor.thesaurus.ui.entry.EntryFormScreen
import pl.bargor.thesaurus.ui.entry.EntryFormUiState
import pl.bargor.thesaurus.ui.entry.EntryFormViewModel
import pl.bargor.thesaurus.ui.entries.EntryListScreen
import pl.bargor.thesaurus.ui.entries.EntryListViewModel
import pl.bargor.thesaurus.ui.family.FamilyScreen
import pl.bargor.thesaurus.ui.family.FamilyViewModel
import pl.bargor.thesaurus.ui.family.InvitationAcceptScreen
import pl.bargor.thesaurus.ui.family.InvitationAcceptViewModel
import pl.bargor.thesaurus.ui.taxonomy.TaxonomyScreen
import pl.bargor.thesaurus.ui.taxonomy.TaxonomyViewModel
import pl.bargor.thesaurus.ui.summary.SummaryScreen
import pl.bargor.thesaurus.ui.summary.SummaryViewModel
import pl.bargor.thesaurus.ui.summary.SummaryPeriodMode
import pl.bargor.thesaurus.ui.reports.ReportsScreen
import pl.bargor.thesaurus.ui.reports.ReportsViewModel
import pl.bargor.thesaurus.ui.browse.BrowseScreen
import pl.bargor.thesaurus.ui.browse.BrowseViewModel
import pl.bargor.thesaurus.ui.balance.GlobalAccountBalanceBar
import pl.bargor.thesaurus.ui.balance.GlobalAccountBalanceUiState
import pl.bargor.thesaurus.ui.balance.GlobalAccountBalanceViewModel
import pl.bargor.thesaurus.ui.settings.LocalSettingsNavigation
import pl.bargor.thesaurus.ui.settings.SettingsNavigation
import pl.bargor.thesaurus.ui.settings.SettingsScreen
import pl.bargor.thesaurus.ui.settings.OpeningBalanceScreen
import pl.bargor.thesaurus.ui.settings.OpeningBalanceViewModel
import pl.bargor.thesaurus.ui.settings.OpeningBalanceUiState

enum class Destination(
    @param:StringRes val labelRes: Int,
    @param:StringRes val emptyMessageRes: Int,
    val navigationTestTag: String,
    val route: String,
) {
    Entries(R.string.navigation_entries, R.string.empty_entries, "navigation-entries", "entries"),
    Browse(R.string.navigation_browse, R.string.empty_summary, "navigation-browse", "browse"),
    Summary(R.string.navigation_summary, R.string.empty_summary, "navigation-summary", "summary"),
    Reports(R.string.navigation_reports, R.string.empty_reports, "navigation-reports", "reports"),
}

@Composable
fun ThesaurusApp(
    authViewModel: AuthViewModel = hiltViewModel(),
    invitationLink: FamilyInvitationLink? = null,
    networkMonitor: NetworkMonitor = rememberNetworkMonitor(),
) {
    val isOnline by rememberNetworkOnline(networkMonitor)
    val authState by authViewModel.state.collectAsState()
    var pendingInvitation by remember { mutableStateOf(invitationLink) }
    LaunchedEffect(invitationLink) { pendingInvitation = invitationLink }
    OfflineStatusHost(isOnline = isOnline, screenKey = Pair(authState::class, pendingInvitation)) {
        when (val state = authState) {
            is AuthUiState.Ready -> if (pendingInvitation == null) {
                HouseholdApp(state.identity.uid, state.householdId, onSignOut = authViewModel::signOut,
                    balanceState = null)
            } else if (pendingInvitation?.householdId == state.householdId) {
                InvitationAcceptRoute(
                    link = pendingInvitation!!,
                    identity = state.identity,
                    onSignOut = authViewModel::signOut,
                    onAccepted = { identity ->
                        pendingInvitation = null
                        authViewModel.membershipAccepted(identity)
                    },
                )
            } else {
                ExistingHouseholdInvitationContent { pendingInvitation = null }
            }
            is AuthUiState.NeedsHousehold -> pendingInvitation?.let { link ->
                InvitationAcceptRoute(
                    link,
                    state.identity,
                    authViewModel::signOut,
                    onAccepted = { identity ->
                        pendingInvitation = null
                        authViewModel.membershipAccepted(identity)
                    },
                )
            } ?: AuthenticationContent(
                state = state,
                onSignIn = authViewModel::signIn,
                onEmailSignIn = authViewModel::signInWithEmail,
                onCreateHousehold = authViewModel::createHousehold,
                onRetry = authViewModel::retry,
            )
            else -> AuthenticationContent(
                state = state,
                onSignIn = authViewModel::signIn,
                onEmailSignIn = authViewModel::signInWithEmail,
                onCreateHousehold = authViewModel::createHousehold,
                onRetry = authViewModel::retry,
            )
        }
    }
}

@Composable
private fun rememberNetworkMonitor(): NetworkMonitor {
    val context = LocalContext.current
    return remember(context) { AndroidNetworkMonitor(context) }
}

@Composable
internal fun HouseholdApp(
    actorId: String = "preview-user",
    householdId: String = "preview-household",
    entriesContent: @Composable (
        onOpenSettings: () -> Unit,
        onAddEntry: () -> Unit,
        onOpenFamily: () -> Unit,
        onEditEntry: (String) -> Unit,
    ) -> Unit = { onOpenSettings, onAddEntry, onOpenFamily, onEditEntry ->
        EntryListRoute(
            householdId = householdId,
            actorId = actorId,
            onOpenSettings = onOpenSettings,
            onAddEntry = onAddEntry,
            onOpenFamily = onOpenFamily,
            onEditEntry = onEditEntry,
        )
    },
    summaryContent: @Composable (onOpenEntry: (String) -> Unit) -> Unit = { onOpenEntry ->
        SummaryRoute(householdId, actorId, onOpenEntry)
    },
    reportsContent: @Composable (onOpenEntry: (String) -> Unit) -> Unit = { onOpenEntry ->
        ReportsRoute(householdId, onOpenEntry)
    },
    entryFormContent: (@Composable (entryId: String?, onCreated: () -> Unit) -> Unit)? = null,
    taxonomyContent: (@Composable (onBack: () -> Unit) -> Unit)? = null,
    navController: NavHostController = rememberNavController(),
    onSignOut: () -> Unit = {},
    browseContent: @Composable (onOpenEntry: (String) -> Unit) -> Unit = { onOpenEntry ->
        BrowseRoute(householdId, actorId, onOpenEntry)
    },
    // Preview/navigation fixtures stay independent of Hilt; production requests the live source explicitly.
    balanceState: GlobalAccountBalanceUiState? = GlobalAccountBalanceUiState(),
    familyContent: (@Composable (onBack: () -> Unit) -> Unit)? = null,
    openingBalanceContent: (@Composable (onBack: () -> Unit) -> Unit)? = null,
) {
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route
    val dismissOfflineTooltip = LocalDismissOfflineTooltip.current
    LaunchedEffect(currentBackStackEntry) { dismissOfflineTooltip() }
    val settingsRoutes = setOf("settings", "taxonomy", "family", "opening-balance")
    val openSettings = {
        if (currentRoute != "settings") navController.navigate("settings") { launchSingleTop = true }
        Unit
    }

    Scaffold(
        // Move the shared footer and scrollable screen together above the keyboard.
        // Insets are consumed here, so navigation does not add its bottom inset twice.
        modifier = Modifier.imePadding(),
        bottomBar = {
            Column {
                DeveloperToolsContent(onSignOut)
                if (balanceState != null) GlobalAccountBalanceBar(balanceState)
                else GlobalAccountBalanceRoute(householdId, actorId)
                HouseholdNavigationBar(currentRoute = currentRoute, onNavigate = { destination ->
                    // Temporary screens belong to their source tab and are never saved as tabs.
                    val mainRoutes = Destination.entries.map { it.route }.toSet()
                    while (navController.currentDestination?.route !in mainRoutes) {
                        if (!navController.popBackStack()) break
                    }
                    navController.navigate(destination.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                })
            }
        },
    ) { padding ->
        CompositionLocalProvider(LocalSettingsNavigation provides SettingsNavigation(
            selected = currentRoute in settingsRoutes, open = openSettings,
        )) {
        NavHost(
            navController = navController,
            startDestination = Destination.Entries.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            composable(Destination.Entries.route) {
                entriesContent(
                    openSettings,
                    { navController.navigate("add-entry") },
                    { navController.navigate("family") },
                    { entryId -> navController.navigate("edit-entry/$entryId") },
                )
            }
            composable(Destination.Summary.route) { summaryContent { entryId -> navController.navigate("edit-entry/$entryId") } }
            composable(Destination.Browse.route) { browseContent { entryId -> navController.navigate("edit-entry/$entryId") } }
            composable(Destination.Reports.route) { reportsContent { entryId -> navController.navigate("edit-entry/$entryId") } }
            composable("settings") {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenCategories = { navController.navigate("taxonomy") {
                        launchSingleTop = true
                    } },
                    onOpenFamily = { navController.navigate("family") {
                        launchSingleTop = true
                    } },
                    onSetAccountBalance = { navController.navigate("opening-balance") {
                        launchSingleTop = true
                    } },
                )
            }
            composable("taxonomy") {
                val onBack = { navController.popBackStack(); Unit }
                if (taxonomyContent != null) taxonomyContent(onBack) else TaxonomyRoute(
                    householdId = householdId,
                    actorId = actorId,
                    onBack = onBack,
                )
            }
            composable("opening-balance") {
                val onBack = { navController.popBackStack(); Unit }
                if (openingBalanceContent != null) openingBalanceContent(onBack)
                else OpeningBalanceRoute(householdId, actorId, onBack)
            }
            composable("family") {
                val onBack = { navController.popBackStack(); Unit }
                if (familyContent != null) familyContent(onBack) else FamilyRoute(
                    householdId = householdId,
                    actorId = actorId,
                    onBack = onBack,
                )
            }
            composable("add-entry") {
                val onCreated = {
                    navController.navigate(Destination.Entries.route) {
                        popUpTo(Destination.Entries.route)
                        launchSingleTop = true
                    }
                    Unit
                }
                if (entryFormContent != null) entryFormContent(null, onCreated) else {
                    EntryFormRoute(
                        householdId = householdId,
                        actorId = actorId,
                        onBack = { navController.popBackStack() },
                        onCreated = onCreated,
                    )
                }
            }
            composable("edit-entry/{entryId}") { backStackEntry ->
                val entryId = backStackEntry.arguments?.getString("entryId")
                if (entryFormContent != null) entryFormContent(entryId, {}) else {
                    EntryFormRoute(
                        householdId = householdId,
                        actorId = actorId,
                        entryId = entryId,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun OpeningBalanceRoute(
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
private fun GlobalAccountBalanceRoute(
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
internal fun HouseholdNavigationBar(
    currentRoute: String?,
    onNavigate: (Destination) -> Unit,
    windowInsets: WindowInsets = ShortNavigationBarDefaults.windowInsets,
) {
    // The short bar measures its content, so large labels can grow beyond 64 dp.
    ShortNavigationBar(
        modifier = Modifier.testTag("bottom-navigation"),
        windowInsets = windowInsets,
    ) {
        Destination.entries.forEach { destination ->
            ShortNavigationBarItem(
                modifier = Modifier.testTag(destination.navigationTestTag),
                selected = currentRoute == destination.route,
                onClick = { onNavigate(destination) },
                icon = {
                    Icon(
                        modifier = Modifier.testTag("${destination.navigationTestTag}-icon"),
                        imageVector = when (destination) {
                            Destination.Entries -> Icons.Default.Description
                            Destination.Summary -> Icons.Default.Summarize
                            Destination.Browse -> Icons.Default.AccountTree
                            Destination.Reports -> Icons.Default.Assessment
                        },
                        contentDescription = null,
                    )
                },
                label = {
                    Text(
                        stringResource(destination.labelRes),
                        modifier = Modifier.testTag("${destination.navigationTestTag}-label"),
                    )
                },
            )
        }
    }
}

@Composable
private fun BrowseRoute(
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
private fun SummaryRoute(
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
private fun ReportsRoute(
    householdId: String,
    onOpenEntry: (String) -> Unit,
    reportsViewModel: ReportsViewModel = hiltViewModel(),
) {
    LaunchedEffect(householdId) { reportsViewModel.start(householdId) }
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
private fun EntryListRoute(
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
        onLoadNextPage = entryListViewModel::loadNextPage,
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
private fun FamilyRoute(
    householdId: String,
    actorId: String,
    onBack: () -> Unit,
    familyViewModel: FamilyViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val shareChooserTitle = stringResource(R.string.family_share_chooser)
    LaunchedEffect(householdId, actorId) { familyViewModel.start(householdId, actorId) }
    val state by familyViewModel.state.collectAsState()
    LaunchedEffect(state.shareUrl) {
        state.shareUrl?.let { url ->
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url),
                    shareChooserTitle,
                ),
            )
            familyViewModel.shareHandled()
        }
    }
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
private fun InvitationAcceptRoute(
    link: FamilyInvitationLink,
    identity: pl.bargor.thesaurus.data.firebase.OnboardingIdentity,
    onSignOut: () -> Unit,
    onAccepted: (pl.bargor.thesaurus.data.firebase.OnboardingIdentity) -> Unit,
    invitationAcceptViewModel: InvitationAcceptViewModel = hiltViewModel(),
) {
    LaunchedEffect(link, identity) { invitationAcceptViewModel.start(link, identity) }
    val state by invitationAcceptViewModel.state.collectAsState()
    LaunchedEffect(state) {
        if (state is pl.bargor.thesaurus.ui.family.InvitationAcceptUiState.Accepted) onAccepted(identity)
    }
    InvitationAcceptScreen(state = state, onAccept = invitationAcceptViewModel::accept, onSignOut = onSignOut)
}

@Composable
private fun ExistingHouseholdInvitationContent(onContinue: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.invitation_existing_household), style = MaterialTheme.typography.bodyLarge)
        Button(modifier = Modifier.padding(top = 16.dp), onClick = onContinue) {
            Text(stringResource(R.string.invitation_continue))
        }
    }
}

@Composable
private fun AuthenticationContent(
    state: AuthUiState,
    onSignIn: (Activity) -> Unit,
    onEmailSignIn: (String, String) -> Unit,
    onCreateHousehold: (String) -> Unit,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    val authScreenDescription = stringResource(R.string.auth_screen_description)
    var householdName by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .semantics { contentDescription = authScreenDescription },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            modifier = Modifier.semantics { heading() },
            text = stringResource(R.string.auth_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(16.dp))
        when (state) {
            AuthUiState.CheckingSession,
            AuthUiState.SigningIn,
            is AuthUiState.CreatingHousehold -> {
                CircularProgressIndicator(modifier = Modifier.testTag("auth-progress"))
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.auth_please_wait))
            }

            AuthUiState.SignedOut -> {
                SignInContent(context, onSignIn, onEmailSignIn)
            }

            is AuthUiState.NeedsHousehold -> {
                Text(stringResource(R.string.onboarding_welcome, state.identity.displayName ?: state.identity.email))
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    modifier = Modifier.testTag("household-name"),
                    value = householdName,
                    onValueChange = { householdName = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.onboarding_household_name)) },
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    modifier = Modifier.testTag("create-household"),
                    onClick = { onCreateHousehold(householdName) },
                ) { Text(stringResource(R.string.onboarding_create_household)) }
            }

            AuthUiState.GoogleConfigurationRequired -> {
                Text(stringResource(R.string.auth_google_configuration_required))
                Spacer(Modifier.height(12.dp))
                Button(onClick = onRetry) { Text(stringResource(R.string.auth_back)) }
            }

            is AuthUiState.Error -> {
                Text(
                    stringResource(
                        if (state.duringOnboarding) R.string.onboarding_error
                        else R.string.auth_connection_error,
                    ),
                )
                Spacer(Modifier.height(12.dp))
                Button(modifier = Modifier.testTag("auth-retry"), onClick = onRetry) {
                    Text(stringResource(R.string.auth_retry))
                }
            }

            is AuthUiState.Ready -> Unit
        }
    }
}

@Composable
internal fun GoogleSignInContent(context: Context, onSignIn: (Activity) -> Unit) {
    Text(stringResource(R.string.auth_signed_out_description))
    Spacer(Modifier.height(12.dp))
    Button(
        modifier = Modifier.testTag("google-sign-in"),
        onClick = { context.findActivity()?.let(onSignIn) },
    ) { Text(stringResource(R.string.auth_google_sign_in)) }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun DestinationContent(
    destination: Destination,
    onOpenSettings: () -> Unit,
    onAddEntry: () -> Unit,
) {
    val label = stringResource(destination.labelRes)
    val emptyMessage = stringResource(destination.emptyMessageRes)
    val screenDescription = stringResource(R.string.screen_description, label)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .semantics { contentDescription = screenDescription },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            modifier = Modifier.semantics { heading() },
            text = label,
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            modifier = Modifier.padding(top = 12.dp),
            text = emptyMessage,
            style = MaterialTheme.typography.bodyLarge,
        )
        if (destination == Destination.Entries) {
            Button(
                modifier = Modifier.padding(top = 24.dp).testTag("add-entry"),
                onClick = onAddEntry,
            ) { Text(stringResource(R.string.entry_add)) }
            Button(
                modifier = Modifier.padding(top = 12.dp).testTag("open-taxonomy-settings"),
                onClick = onOpenSettings,
            ) { Text(stringResource(R.string.open_taxonomy_settings)) }
        }
    }
}

@Composable
private fun EntryFormRoute(
    householdId: String,
    actorId: String,
    entryId: String? = null,
    onBack: () -> Unit,
    onCreated: () -> Unit = {},
    entryFormViewModel: EntryFormViewModel = hiltViewModel(),
) {
    LaunchedEffect(householdId, actorId, entryId) { entryFormViewModel.start(householdId, actorId, entryId) }
    val state by entryFormViewModel.state.collectAsState()
    EntryFormCompletion(state, onCreated)
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
private fun TaxonomyRoute(
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
