package pl.bargor.thesaurus.ui.family

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import pl.bargor.thesaurus.R
import pl.bargor.thesaurus.data.model.SyncState

@Composable
fun InvitationAcceptScreen(
    state: InvitationAcceptUiState,
    onAccept: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp).semantics { heading() },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.invitation_accept_title), style = MaterialTheme.typography.headlineMedium)
        when (state) {
            InvitationAcceptUiState.Loading -> CircularProgressIndicator(modifier = Modifier.padding(24.dp).testTag("invitation-loading"))
            InvitationAcceptUiState.Invalid -> InvitationMessage(R.string.invitation_invalid, onSignOut)
            InvitationAcceptUiState.WrongEmail -> InvitationMessage(R.string.invitation_wrong_email, onSignOut)
            InvitationAcceptUiState.Expired -> InvitationMessage(R.string.invitation_expired, onSignOut)
            InvitationAcceptUiState.Revoked -> InvitationMessage(R.string.invitation_revoked, onSignOut)
            InvitationAcceptUiState.Used -> InvitationMessage(R.string.invitation_used, onSignOut)
            InvitationAcceptUiState.Error -> InvitationMessage(R.string.invitation_error, onSignOut)
            InvitationAcceptUiState.Accepted -> InvitationMessage(R.string.invitation_accepted, onSignOut)
            is InvitationAcceptUiState.Accepting -> CircularProgressIndicator(modifier = Modifier.padding(24.dp).testTag("invitation-accepting"))
            is InvitationAcceptUiState.Available -> {
                Text(stringResource(R.string.invitation_for_email, state.invitation.email), modifier = Modifier.padding(top = 16.dp))
                if (state.syncState != SyncState.SYNCED) Text(stringResource(R.string.invitation_sync_required))
                Button(
                    modifier = Modifier.padding(top = 16.dp).testTag("accept-invitation"),
                    onClick = onAccept,
                    enabled = state.syncState == SyncState.SYNCED,
                ) {
                    Text(stringResource(R.string.invitation_accept))
                }
                SignOutButton(onSignOut)
            }
        }
    }
}

@Composable
private fun InvitationMessage(message: Int, onSignOut: () -> Unit) {
    Text(stringResource(message), modifier = Modifier.padding(top = 16.dp))
    SignOutButton(onSignOut)
}

@Composable
private fun SignOutButton(onSignOut: () -> Unit) {
    Button(modifier = Modifier.padding(top = 16.dp).testTag("invitation-sign-out"), onClick = onSignOut) {
        Text(stringResource(R.string.invitation_sign_out))
    }
}
