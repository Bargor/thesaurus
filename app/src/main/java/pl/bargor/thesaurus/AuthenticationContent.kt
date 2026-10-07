package pl.bargor.thesaurus

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import pl.bargor.thesaurus.ui.auth.AuthUiState
import pl.bargor.thesaurus.ui.family.InvitationAcceptScreen
import pl.bargor.thesaurus.ui.family.InvitationAcceptViewModel

@Composable
internal fun InvitationAcceptRoute(
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
internal fun ExistingHouseholdInvitationContent(onContinue: () -> Unit) {
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
internal fun AuthenticationContent(
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
