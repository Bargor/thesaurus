package pl.bargor.thesaurus.ui.auth

import android.app.Activity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pl.bargor.thesaurus.data.auth.AuthRepository
import pl.bargor.thesaurus.data.firebase.FirstHouseholdResult
import pl.bargor.thesaurus.data.firebase.OnboardingIdentity
import pl.bargor.thesaurus.data.firebase.OnboardingRepository

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `signed in user without a household is prompted to create one`() = runTest(dispatcher) {
        val auth = FakeAuthRepository()
        val onboarding = FakeOnboardingRepository(householdId = null)
        val viewModel = AuthViewModel(auth, onboarding)
        val identity = OnboardingIdentity("uid", "ala@example.test", "Ala")

        auth.emit(identity)
        advanceUntilIdle()

        assertEquals(AuthUiState.NeedsHousehold(identity), viewModel.state.value)
    }

    @Test
    fun `first household creation changes state to ready`() = runTest(dispatcher) {
        val auth = FakeAuthRepository()
        val onboarding = FakeOnboardingRepository(householdId = null)
        val viewModel = AuthViewModel(auth, onboarding)
        val identity = OnboardingIdentity("uid", "ala@example.test", "Ala")
        auth.emit(identity)
        advanceUntilIdle()

        viewModel.createHousehold("  Nasz dom  ")
        advanceUntilIdle()

        assertEquals(listOf(identity to "  Nasz dom  "), onboarding.createdWith)
        assertEquals(AuthUiState.Ready(identity, "household-1"), viewModel.state.value)
    }

    @Test
    fun `blank household name stays on onboarding and does not write`() = runTest(dispatcher) {
        val auth = FakeAuthRepository()
        val onboarding = FakeOnboardingRepository(householdId = null)
        val viewModel = AuthViewModel(auth, onboarding)
        val identity = OnboardingIdentity("uid", "ala@example.test", "Ala")
        auth.emit(identity)
        advanceUntilIdle()

        viewModel.createHousehold("   ")
        advanceUntilIdle()

        assertEquals(AuthUiState.NeedsHousehold(identity), viewModel.state.value)
        assertTrue(onboarding.createdWith.isEmpty())
    }

    @Test
    fun `email login trims address and loads the account household`() = runTest(dispatcher) {
        val auth = FakeAuthRepository()
        val identity = OnboardingIdentity("dev-user", "member@example.test", null)
        auth.emailResult = Result.success(identity)
        val viewModel = AuthViewModel(auth, FakeOnboardingRepository(householdId = "household-1"))
        advanceUntilIdle()

        viewModel.signInWithEmail("  member@example.test  ", "dev-password-123")
        advanceUntilIdle()

        assertEquals("member@example.test" to "dev-password-123", auth.emailCredentials)
        assertEquals(AuthUiState.Ready(identity, "household-1"), viewModel.state.value)
    }

    private class FakeAuthRepository : AuthRepository {
        private val mutableIdentities = MutableStateFlow<OnboardingIdentity?>(null)
        override val identities: Flow<OnboardingIdentity?> = mutableIdentities
        var emailCredentials: Pair<String, String>? = null
        var emailResult: Result<OnboardingIdentity> = Result.failure(UnsupportedOperationException())

        fun emit(identity: OnboardingIdentity?) {
            mutableIdentities.value = identity
        }

        override suspend fun signIn(activity: Activity): Result<OnboardingIdentity> =
            Result.failure(UnsupportedOperationException())

        override suspend fun signInWithEmail(email: String, password: String): Result<OnboardingIdentity> {
            emailCredentials = email to password
            return emailResult
        }

        override suspend fun signOut() = Unit
    }

    private class FakeOnboardingRepository(
        private val householdId: String?,
    ) : OnboardingRepository {
        val createdWith = mutableListOf<Pair<OnboardingIdentity, String>>()

        override suspend fun householdIdFor(uid: String): String? = householdId

        override suspend fun createFirstHousehold(
            identity: OnboardingIdentity,
            householdName: String,
        ): FirstHouseholdResult {
            createdWith += identity to householdName
            return FirstHouseholdResult.Created("household-1")
        }
    }
}
