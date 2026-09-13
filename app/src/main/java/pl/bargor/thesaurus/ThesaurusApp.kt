package pl.bargor.thesaurus

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

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
fun ThesaurusApp() {
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
                    DestinationContent(destination)
                }
            }
        }
    }
}

@Composable
private fun DestinationContent(destination: Destination) {
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
    }
}
