package pl.bargor.thesaurus

import androidx.annotation.StringRes
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.runtime.Composable
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import pl.bargor.thesaurus.ui.auth.AuthUiState
import pl.bargor.thesaurus.ui.auth.AuthViewModel
import pl.bargor.thesaurus.ui.entry.EntryFormScreen
import pl.bargor.thesaurus.ui.entry.EntryFormViewModel
import pl.bargor.thesaurus.ui.taxonomy.TaxonomyScreen
import pl.bargor.thesaurus.ui.taxonomy.TaxonomyViewModel

enum class Destination(
    @param:StringRes val labelRes: Int,
    @param:StringRes val emptyMessageRes: Int,
    val navigationTestTag: String,
    val route: String,
) {
    Entries(R.string.navigation_entries, R.string.empty_entries, "navigation-entries", "entries"),
    Summary(R.string.navigation_summary, R.string.empty_summary, "navigation-summary", "summary"),
    Reports(R.string.navigation_reports, R.string.empty_reports, "navigation-reports", "reports"),
}

@Composable
fun ThesaurusApp(authViewModel: AuthViewModel = viewModel()) {
    val authState by authViewModel.state.collectAsState()
    when (val state = authState) {
        is AuthUiState.Ready -> HouseholdApp(state.identity.uid, state.householdId)
        else -> AuthenticationContent(
            state = state,
            onSignIn = authViewModel::signIn,
            onCreateHousehold = authViewModel::createHousehold,
            onRetry = authViewModel::retry,
        )
    }
}

@Composable
internal fun HouseholdApp(
    actorId: String = "preview-user",
    householdId: String = "preview-household",
) {
    val navController = rememberNavController()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { destination ->
                    val label = stringResource(destination.labelRes)
                    NavigationBarItem(
                        modifier = Modifier.testTag(destination.navigationTestTag),
                        selected = currentRoute == destination.route,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = when (destination) {
                                    Destination.Entries -> Icons.Default.Description
                                    Destination.Summary -> Icons.Default.Summarize
                                    Destination.Reports -> Icons.Default.Assessment
                                },
                                contentDescription = null,
                            )
                        },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Entries.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Destination.entries.forEach { destination ->
                composable(destination.route) {
                    DestinationContent(
                        destination = destination,
                        onOpenSettings = { navController.navigate("settings") },
                        onAddEntry = { navController.navigate("add-entry") },
                    )
                }
            }
            composable("settings") {
                TaxonomyRoute(
                    householdId = householdId,
                    actorId = actorId,
                    onBack = { navController.popBackStack() },
                )
            }
            composable("add-entry") {
                EntryFormRoute(
                    householdId = householdId,
                    actorId = actorId,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

@Composable
private fun AuthenticationContent(
    state: AuthUiState,
    onSignIn: (Activity) -> Unit,
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
                Text(stringResource(R.string.auth_signed_out_description))
                Spacer(Modifier.height(12.dp))
                Button(
                    modifier = Modifier.testTag("google-sign-in"),
                    onClick = { context.findActivity()?.let(onSignIn) },
                ) { Text(stringResource(R.string.auth_google_sign_in)) }
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
    onBack: () -> Unit,
    entryFormViewModel: EntryFormViewModel = viewModel(),
) {
    LaunchedEffect(householdId, actorId) { entryFormViewModel.start(householdId, actorId) }
    val state by entryFormViewModel.state.collectAsState()
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
private fun TaxonomyRoute(
    householdId: String,
    actorId: String,
    onBack: () -> Unit,
    taxonomyViewModel: TaxonomyViewModel = viewModel(),
) {
    LaunchedEffect(householdId, actorId) { taxonomyViewModel.start(householdId, actorId) }
    val state by taxonomyViewModel.state.collectAsState()
    TaxonomyScreen(state = state, onMutation = taxonomyViewModel::mutate, onBack = onBack)
}
