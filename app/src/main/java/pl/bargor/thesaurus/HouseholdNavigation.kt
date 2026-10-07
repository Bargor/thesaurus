package pl.bargor.thesaurus

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarDefaults
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import pl.bargor.thesaurus.ui.LocalDismissOfflineTooltip
import pl.bargor.thesaurus.ui.balance.GlobalAccountBalanceBar
import pl.bargor.thesaurus.ui.balance.GlobalAccountBalanceUiState
import pl.bargor.thesaurus.ui.settings.LocalSettingsNavigation
import pl.bargor.thesaurus.ui.settings.SettingsNavigation
import pl.bargor.thesaurus.ui.settings.SettingsScreen

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

// Owns the household scaffold, tab stacks and transient settings/form destinations.
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
                // Fixture API retains its creation-only callback; edit completion stays on the form.
                if (entryFormContent != null) entryFormContent(entryId, {}) else {
                    EntryFormRoute(
                        householdId = householdId,
                        actorId = actorId,
                        entryId = entryId,
                        onBack = { navController.popBackStack() },
                        onCreated = null,
                    )
                }
            }
        }
        }
    }
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
