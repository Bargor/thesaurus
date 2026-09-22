package pl.bargor.thesaurus.ui.family

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import pl.bargor.thesaurus.R
import pl.bargor.thesaurus.data.model.Invitation
import pl.bargor.thesaurus.data.model.InvitationStatus
import pl.bargor.thesaurus.data.model.Member
import pl.bargor.thesaurus.data.model.MemberRole
import pl.bargor.thesaurus.data.model.SyncState

private val InvitationDateFormatter = DateTimeFormatter.ofPattern("d MMMM uuuu, HH:mm", Locale.forLanguageTag("pl-PL"))

@Composable
fun FamilyScreen(
    state: FamilyUiState,
    onCreateInvitation: (String) -> Unit,
    onRevokeInvitation: (String) -> Unit,
    onRemoveMember: (String) -> Unit,
    onClearError: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var email by remember { mutableStateOf("") }
    var memberToRemove by remember { mutableStateOf<Member?>(null) }
    LaunchedEffect(state.shareUrl) {
        if (state.shareUrl != null) email = ""
    }
    Column(modifier = modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
            TextButton(onClick = onBack, modifier = Modifier.testTag("family-back")) {
                Text(stringResource(R.string.family_back))
            }
            Text(
                modifier = Modifier.weight(1f).padding(top = 12.dp).semantics { heading() },
                text = stringResource(R.string.family_title),
                style = MaterialTheme.typography.headlineSmall,
            )
        }
        if (state.loading) {
            CircularProgressIndicator(modifier = Modifier.padding(24.dp).testTag("family-loading"))
            return
        }
        FamilySyncNotice(state.syncState)
        state.error?.let {
            Text(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                text = stringResource(it.messageRes()),
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onClearError) { Text(stringResource(R.string.family_dismiss)) }
        }
        if (state.isOwner) {
            InvitationForm(
                email = email,
                saving = state.savingInvitation,
                onEmailChange = { email = it },
                onSubmit = { onCreateInvitation(email) },
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("family-list"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SectionTitle(R.string.family_members) }
            items(state.members, key = { it.uid }) { member ->
                MemberCard(
                    member = member,
                    canRemove = state.isOwner && member.role != MemberRole.OWNER,
                    removing = state.mutationInProgress == member.uid,
                    onRemove = { memberToRemove = member },
                )
            }
            if (state.isOwner) {
                item { SectionTitle(R.string.family_invitations) }
                if (state.invitations.isEmpty()) item { Text(stringResource(R.string.family_no_invitations), Modifier.padding(horizontal = 16.dp)) }
                items(state.invitations, key = { it.id }) { invitation ->
                    InvitationCard(
                        invitation = invitation,
                        revoking = state.mutationInProgress == invitation.id,
                        onRevoke = { onRevokeInvitation(invitation.id) },
                    )
                }
            }
        }
    }
    memberToRemove?.let { member ->
        AlertDialog(
            onDismissRequest = { memberToRemove = null },
            title = { Text(stringResource(R.string.family_remove_confirm_title)) },
            text = { Text(stringResource(R.string.family_remove_confirm_message, member.email)) },
            confirmButton = {
                Button(onClick = {
                    onRemoveMember(member.uid)
                    memberToRemove = null
                }) { Text(stringResource(R.string.family_remove_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { memberToRemove = null }) { Text(stringResource(R.string.taxonomy_cancel)) }
            },
        )
    }
}

@Composable
private fun InvitationForm(email: String, saving: Boolean, onEmailChange: (String) -> Unit, onSubmit: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("family-invite-form")) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.family_invite_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.family_invite_hint))
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth().testTag("family-invite-email"),
                value = email,
                onValueChange = onEmailChange,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                label = { Text(stringResource(R.string.family_invite_email)) },
            )
            Button(
                modifier = Modifier.testTag("family-create-invite"),
                onClick = onSubmit,
                enabled = !saving,
            ) { Text(stringResource(if (saving) R.string.family_creating else R.string.family_create_invite)) }
        }
    }
}

@Composable
private fun SectionTitle(titleRes: Int) = Text(
    modifier = Modifier.padding(start = 16.dp, top = 16.dp).semantics { heading() },
    text = stringResource(titleRes),
    style = MaterialTheme.typography.titleMedium,
)

@Composable
private fun MemberCard(member: Member, canRemove: Boolean, removing: Boolean, onRemove: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("family-member-${member.uid}")) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(member.displayName?.takeIf { it.isNotBlank() } ?: member.email)
            Text(member.email, style = MaterialTheme.typography.bodySmall)
            Text(stringResource(if (member.role == MemberRole.OWNER) R.string.family_owner else R.string.family_member))
            if (canRemove) {
                TextButton(
                    modifier = Modifier.testTag("family-remove-${member.uid}"),
                    enabled = !removing,
                    onClick = onRemove,
                ) { Text(stringResource(if (removing) R.string.family_removing else R.string.family_remove)) }
            }
        }
    }
}

@Composable
private fun InvitationCard(invitation: Invitation, revoking: Boolean, onRevoke: () -> Unit) {
    val status = when {
        invitation.status == InvitationStatus.PENDING && invitation.expiresAt <= java.time.Instant.now() -> R.string.family_invitation_expired
        else -> when (invitation.status) {
            InvitationStatus.PENDING -> R.string.family_invitation_pending
            InvitationStatus.ACCEPTED -> R.string.family_invitation_accepted
            InvitationStatus.REVOKED -> R.string.family_invitation_revoked
        }
    }
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("family-invitation-${invitation.id}")) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(invitation.email)
            Text(stringResource(status))
            Text(
                stringResource(
                    R.string.family_invitation_expires,
                    InvitationDateFormatter.format(invitation.expiresAt.atZone(ZoneId.systemDefault())),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            if (invitation.status == InvitationStatus.PENDING && invitation.expiresAt > java.time.Instant.now()) {
                TextButton(
                    modifier = Modifier.testTag("family-revoke-${invitation.id}"),
                    enabled = !revoking,
                    onClick = onRevoke,
                ) { Text(stringResource(if (revoking) R.string.family_revoking else R.string.family_revoke)) }
            }
        }
    }
}

@Composable
private fun FamilySyncNotice(syncState: SyncState) {
    val message = when (syncState) {
        SyncState.PENDING -> R.string.family_sync_pending
        SyncState.OFFLINE -> R.string.family_offline
        else -> null
    } ?: return
    Text(modifier = Modifier.padding(horizontal = 16.dp), text = stringResource(message))
}

private fun FamilyError.messageRes(): Int = when (this) {
    FamilyError.LOAD -> R.string.family_load_error
    FamilyError.INVALID_EMAIL -> R.string.family_invalid_email
    FamilyError.MUTATION -> R.string.family_mutation_error
}
