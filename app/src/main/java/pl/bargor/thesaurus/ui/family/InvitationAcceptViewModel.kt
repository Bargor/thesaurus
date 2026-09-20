package pl.bargor.thesaurus.ui.family

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.bargor.thesaurus.FamilyInvitationLink
import pl.bargor.thesaurus.data.firebase.InvitationRepository
import pl.bargor.thesaurus.data.firebase.OnboardingIdentity
import pl.bargor.thesaurus.data.model.Invitation
import pl.bargor.thesaurus.data.model.InvitationStatus
import pl.bargor.thesaurus.data.model.Member
import pl.bargor.thesaurus.data.model.MemberRole
import pl.bargor.thesaurus.data.model.SyncState

sealed interface InvitationAcceptUiState {
    data object Loading : InvitationAcceptUiState
    data object Invalid : InvitationAcceptUiState
    data object WrongEmail : InvitationAcceptUiState
    data object Expired : InvitationAcceptUiState
    data object Revoked : InvitationAcceptUiState
    data object Used : InvitationAcceptUiState
    data class Available(val invitation: Invitation, val syncState: SyncState) : InvitationAcceptUiState
    data class Accepting(val invitation: Invitation) : InvitationAcceptUiState
    data object Accepted : InvitationAcceptUiState
    data object Error : InvitationAcceptUiState
}

@HiltViewModel
class InvitationAcceptViewModel @Inject constructor(
    private val invitationRepository: InvitationRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow<InvitationAcceptUiState>(InvitationAcceptUiState.Loading)
    val state: StateFlow<InvitationAcceptUiState> = mutableState.asStateFlow()
    private var observeJob: Job? = null
    private var identity: OnboardingIdentity? = null
    private var link: FamilyInvitationLink? = null

    fun start(link: FamilyInvitationLink, signedInIdentity: OnboardingIdentity) {
        if (this.link == link && identity == signedInIdentity && observeJob?.isActive == true) return
        observeJob?.cancel()
        identity = signedInIdentity
        this.link = link
        mutableState.value = InvitationAcceptUiState.Loading
        observeJob = viewModelScope.launch {
            invitationRepository.observeInvitation(link.householdId, link.invitationId).collect { observation ->
                mutableState.value = when {
                    observation.error != null -> InvitationAcceptUiState.Error
                    observation.value == null -> InvitationAcceptUiState.Invalid
                    else -> observation.value.toAcceptState(signedInIdentity, observation.state)
                }
            }
        }
    }

    fun accept() {
        val available = state.value as? InvitationAcceptUiState.Available ?: return
        if (available.syncState != SyncState.SYNCED || available.invitation.expiresAt <= Instant.now()) return
        val signedInIdentity = identity ?: return
        val invitation = available.invitation
        mutableState.value = InvitationAcceptUiState.Accepting(invitation)
        viewModelScope.launch {
            val member = Member(
                uid = signedInIdentity.uid,
                email = signedInIdentity.email,
                displayName = signedInIdentity.displayName,
                role = MemberRole.MEMBER,
                invitationId = invitation.id,
            )
            runCatching { invitationRepository.accept(invitation, member) }
                .onSuccess { mutableState.value = InvitationAcceptUiState.Accepted }
                .onFailure { mutableState.value = InvitationAcceptUiState.Error }
        }
    }
}

private fun Invitation.toAcceptState(identity: OnboardingIdentity, syncState: SyncState): InvitationAcceptUiState = when {
    !email.equals(identity.email.trim(), ignoreCase = true) -> InvitationAcceptUiState.WrongEmail
    status == InvitationStatus.REVOKED -> InvitationAcceptUiState.Revoked
    status == InvitationStatus.ACCEPTED -> InvitationAcceptUiState.Used
    expiresAt <= Instant.now() -> InvitationAcceptUiState.Expired
    else -> InvitationAcceptUiState.Available(this, syncState)
}
