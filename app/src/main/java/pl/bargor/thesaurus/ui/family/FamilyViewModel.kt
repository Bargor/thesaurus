package pl.bargor.thesaurus.ui.family

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.bargor.thesaurus.FamilyInvitationLink
import pl.bargor.thesaurus.data.firebase.HouseholdRepository
import pl.bargor.thesaurus.data.firebase.InvitationRepository
import pl.bargor.thesaurus.data.model.Invitation
import pl.bargor.thesaurus.data.model.InvitationStatus
import pl.bargor.thesaurus.data.model.Member
import pl.bargor.thesaurus.data.model.MemberRole
import pl.bargor.thesaurus.data.model.SyncObservation
import pl.bargor.thesaurus.data.model.SyncState

data class FamilyUiState(
    val loading: Boolean = true,
    val members: List<Member> = emptyList(),
    val invitations: List<Invitation> = emptyList(),
    val isOwner: Boolean = false,
    val syncState: SyncState = SyncState.SYNCED,
    val error: FamilyError? = null,
    val savingInvitation: Boolean = false,
    val mutationInProgress: String? = null,
    val shareUrl: String? = null,
)

enum class FamilyError { LOAD, INVALID_EMAIL, MUTATION }

/** Owner-only mutations stay in the repository; this class only coordinates Polish UI state. */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class FamilyViewModel @Inject constructor(
    private val householdRepository: HouseholdRepository,
    private val invitationRepository: InvitationRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(FamilyUiState())
    val state: StateFlow<FamilyUiState> = mutableState.asStateFlow()
    private var observeJob: Job? = null
    private var context: Pair<String, String>? = null

    fun start(householdId: String, actorId: String) {
        if (context == householdId to actorId && observeJob?.isActive == true) return
        observeJob?.cancel()
        context = householdId to actorId
        mutableState.value = FamilyUiState()
        observeJob = viewModelScope.launch {
            householdRepository.observeMembers(householdId)
                .flatMapLatest { members ->
                    val isOwner = members.value.orEmpty().any { it.uid == actorId && it.role == MemberRole.OWNER }
                    val invitations = if (isOwner) invitationRepository.observeInvitations(householdId)
                    else flowOf(SyncObservation(value = emptyList(), state = SyncState.SYNCED))
                    invitations.combine(flowOf(members)) { invite, member -> member to invite }
                }
                .collect { (memberObservation, invitationObservation) ->
                    val members = memberObservation.value.orEmpty()
                    val invitations = invitationObservation.value.orEmpty()
                    mutableState.update { old ->
                        old.copy(
                            loading = false,
                            members = members.sortedWith(compareBy<Member> { it.role != MemberRole.OWNER }.thenBy { it.email }),
                            invitations = invitations.sortedByDescending { it.createdAt },
                            isOwner = members.any { it.uid == actorId && it.role == MemberRole.OWNER },
                            syncState = relevantSyncState(memberObservation.state, invitationObservation.state),
                            error = (memberObservation.error ?: invitationObservation.error)?.let { FamilyError.LOAD },
                        )
                    }
                }
        }
    }

    fun createInvitation(email: String) {
        val (householdId, actorId) = context ?: return
        val normalized = email.trim().lowercase(Locale.ROOT)
        if (!normalized.isPlausibleEmail()) {
            mutableState.update { it.copy(error = FamilyError.INVALID_EMAIL) }
            return
        }
        if (!state.value.isOwner) return
        viewModelScope.launch {
            mutableState.update { it.copy(savingInvitation = true, error = null) }
            val invitation = Invitation(
                id = UUID.randomUUID().toString(),
                householdId = householdId,
                email = normalized,
                invitedBy = actorId,
                expiresAt = Instant.now().plus(7, ChronoUnit.DAYS),
            )
            runCatching { invitationRepository.create(invitation) }
                .onSuccess {
                    mutableState.update {
                        it.copy(savingInvitation = false, shareUrl = FamilyInvitationLink(householdId, invitation.id).url())
                    }
                }
                .onFailure { mutableState.update { it.copy(savingInvitation = false, error = FamilyError.MUTATION) } }
        }
    }

    fun revokeInvitation(invitationId: String) = mutate(invitationId) { householdId ->
        invitationRepository.revoke(householdId, invitationId)
    }

    fun removeMember(memberId: String) = mutate(memberId) { householdId ->
        householdRepository.removeMember(householdId, memberId)
    }

    fun shareHandled() = mutableState.update { it.copy(shareUrl = null) }

    fun clearError() = mutableState.update { it.copy(error = null) }

    private fun mutate(id: String, operation: suspend (String) -> Unit) {
        val (householdId) = context ?: return
        if (!state.value.isOwner) return
        viewModelScope.launch {
            mutableState.update { it.copy(mutationInProgress = id, error = null) }
            runCatching { operation(householdId) }
                .onSuccess { mutableState.update { it.copy(mutationInProgress = null) } }
                .onFailure { mutableState.update { it.copy(mutationInProgress = null, error = FamilyError.MUTATION) } }
        }
    }
}

private fun String.isPlausibleEmail(): Boolean = length <= 254 &&
    matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))

private fun relevantSyncState(first: SyncState, second: SyncState): SyncState = when {
    first == SyncState.ERROR || second == SyncState.ERROR -> SyncState.ERROR
    first == SyncState.PENDING || second == SyncState.PENDING -> SyncState.PENDING
    first == SyncState.OFFLINE || second == SyncState.OFFLINE -> SyncState.OFFLINE
    else -> SyncState.SYNCED
}
