package pl.bargor.thesaurus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import pl.bargor.thesaurus.data.connectivity.NetworkMonitor
import pl.bargor.thesaurus.ui.OfflineStatusHost
import pl.bargor.thesaurus.ui.rememberNetworkOnline
import pl.bargor.thesaurus.ui.auth.AuthUiState
import pl.bargor.thesaurus.ui.auth.AuthViewModel

// Authentication and invitation dispatch share the app's single offline-status host.
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
