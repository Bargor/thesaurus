package pl.bargor.thesaurus.ui.auth

import android.app.Activity
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import pl.bargor.thesaurus.data.auth.AuthRepository
import pl.bargor.thesaurus.data.auth.GoogleConfigurationMissingException
import pl.bargor.thesaurus.data.firebase.FirstHouseholdResult
import pl.bargor.thesaurus.data.firebase.OnboardingIdentity
import pl.bargor.thesaurus.data.firebase.OnboardingRepository
import javax.inject.Inject

sealed interface AuthUiState {
    data object CheckingSession : AuthUiState
    data object SignedOut : AuthUiState
    data object SigningIn : AuthUiState
    data object GoogleConfigurationRequired : AuthUiState
    data class NeedsHousehold(val identity: OnboardingIdentity) : AuthUiState
    data class CreatingHousehold(val identity: OnboardingIdentity) : AuthUiState
    data class Ready(val identity: OnboardingIdentity, val householdId: String) : AuthUiState
    data class Error(val identity: OnboardingIdentity?, val duringOnboarding: Boolean) : AuthUiState
}

/** Keeps the UI from treating locally queued onboarding writes as a completed household. */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val onboardingRepository: OnboardingRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow<AuthUiState>(AuthUiState.CheckingSession)
    val state: StateFlow<AuthUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            authRepository.identities.collectLatest { identity ->
                if (identity == null) {
                    mutableState.value = AuthUiState.SignedOut
                } else {
                    refresh(identity)
                }
            }
        }
    }

    fun signIn(activity: Activity) {
        viewModelScope.launch {
            mutableState.value = AuthUiState.SigningIn
            val result = authRepository.signIn(activity)
            val identity = result.getOrNull()
            if (identity != null) {
                refresh(identity)
            } else {
                mutableState.value = if (result.exceptionOrNull() is GoogleConfigurationMissingException) {
                    AuthUiState.GoogleConfigurationRequired
                } else if (result.exceptionOrNull() is GetCredentialCancellationException) {
                    AuthUiState.SignedOut
                } else {
                    AuthUiState.Error(identity = null, duringOnboarding = false)
                }
            }
        }
    }

    fun createHousehold(name: String) {
        val needsHousehold = mutableState.value as? AuthUiState.NeedsHousehold ?: return
        if (name.trim().isEmpty()) {
            mutableState.value = AuthUiState.NeedsHousehold(needsHousehold.identity)
            return
        }
        viewModelScope.launch {
            mutableState.value = AuthUiState.CreatingHousehold(needsHousehold.identity)
            runCatching {
                onboardingRepository.createFirstHousehold(needsHousehold.identity, name)
            }.onSuccess { result ->
                val householdId = when (result) {
                    is FirstHouseholdResult.Created -> result.householdId
                    is FirstHouseholdResult.Existing -> result.householdId
                }
                mutableState.value = AuthUiState.Ready(needsHousehold.identity, householdId)
            }.onFailure {
                // A transaction failure leaves all onboarding documents unapplied; retry is safe.
                mutableState.value = AuthUiState.Error(needsHousehold.identity, duringOnboarding = true)
            }
        }
    }

    fun retry() {
        when (val current = mutableState.value) {
            is AuthUiState.Error -> current.identity?.let { identity ->
                viewModelScope.launch { refresh(identity) }
            } ?: run { mutableState.value = AuthUiState.SignedOut }
            is AuthUiState.GoogleConfigurationRequired -> mutableState.value = AuthUiState.SignedOut
            else -> Unit
        }
    }

    fun signOut() {
        viewModelScope.launch {
            authRepository.signOut()
            mutableState.value = AuthUiState.SignedOut
        }
    }

    private suspend fun refresh(identity: OnboardingIdentity) {
        mutableState.value = AuthUiState.CheckingSession
        runCatching { onboardingRepository.householdIdFor(identity.uid) }
            .onSuccess { householdId ->
                mutableState.value = householdId?.let { AuthUiState.Ready(identity, it) }
                    ?: AuthUiState.NeedsHousehold(identity)
            }
            .onFailure {
                mutableState.value = AuthUiState.Error(identity, duringOnboarding = false)
            }
    }
}
