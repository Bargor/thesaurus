package pl.bargor.thesaurus.ui.family

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
import pl.bargor.thesaurus.FamilyInvitationLink
import pl.bargor.thesaurus.data.firebase.HouseholdRepository
import pl.bargor.thesaurus.data.firebase.InvitationRepository
import pl.bargor.thesaurus.data.firebase.OnboardingIdentity
import pl.bargor.thesaurus.data.model.Household
import pl.bargor.thesaurus.data.model.Invitation
import pl.bargor.thesaurus.data.model.InvitationStatus
import pl.bargor.thesaurus.data.model.Member
import pl.bargor.thesaurus.data.model.MemberRole
import pl.bargor.thesaurus.data.model.SyncObservation
import pl.bargor.thesaurus.data.model.SyncState
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class FamilyInvitationViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `owner creates an email-bound seven day invitation and can remove a member`() = runTest(dispatcher) {
        val households = FakeHouseholds(ownerMembers())
        val invitations = FakeInvitations()
        val viewModel = FamilyViewModel(households, invitations)
        viewModel.start("house", "owner")
        advanceUntilIdle()

        viewModel.createInvitation("  Guest@Example.test ")
        advanceUntilIdle()

        val created = invitations.created.single()
        assertEquals("guest@example.test", created.email)
        assertEquals("owner", created.invitedBy)
        assertTrue(created.expiresAt.isAfter(Instant.now().plusSeconds(6 * 24 * 60 * 60)))
        assertTrue(viewModel.state.value.shareUrl!!.startsWith("https://thesaurus-cef84.web.app/zaproszenie/house/"))

        viewModel.removeMember("member")
        advanceUntilIdle()
        assertEquals(listOf("house" to "member"), households.removed)
    }

    @Test
    fun `non-owner cannot create invitations or remove members`() = runTest(dispatcher) {
        val households = FakeHouseholds(listOf(Member("member", "member@example.test", null, MemberRole.MEMBER)))
        val invitations = FakeInvitations()
        val viewModel = FamilyViewModel(households, invitations)
        viewModel.start("house", "member")
        advanceUntilIdle()

        viewModel.createInvitation("guest@example.test")
        viewModel.removeMember("other")
        advanceUntilIdle()

        assertTrue(invitations.created.isEmpty())
        assertTrue(households.removed.isEmpty())
    }

    @Test
    fun `accepting a matching pending invitation creates member atomically through repository`() = runTest(dispatcher) {
        val invitation = invitation()
        val repository = FakeInvitations(invitation)
        val viewModel = InvitationAcceptViewModel(repository)
        val identity = OnboardingIdentity("guest", "guest@example.test", "Gość")
        viewModel.start(FamilyInvitationLink("house", invitation.id), identity)
        advanceUntilIdle()

        viewModel.accept()
        advanceUntilIdle()

        assertEquals(InvitationAcceptUiState.Accepted, viewModel.state.value)
        assertEquals("guest", repository.accepted.single().second.uid)
        assertEquals(invitation.id, repository.accepted.single().second.invitationId)
    }

    @Test
    fun `expired revoked and mismatched invitations are never available for acceptance`() = runTest(dispatcher) {
        val identity = OnboardingIdentity("guest", "guest@example.test", null)
        val expired = invitation(expiresAt = Instant.now().minusSeconds(1))
        val expiredViewModel = InvitationAcceptViewModel(FakeInvitations(expired))
        expiredViewModel.start(FamilyInvitationLink("house", expired.id), identity)
        advanceUntilIdle()
        assertEquals(InvitationAcceptUiState.Expired, expiredViewModel.state.value)

        val revoked = invitation(status = InvitationStatus.REVOKED)
        val revokedViewModel = InvitationAcceptViewModel(FakeInvitations(revoked))
        revokedViewModel.start(FamilyInvitationLink("house", revoked.id), identity)
        advanceUntilIdle()
        assertEquals(InvitationAcceptUiState.Revoked, revokedViewModel.state.value)

        val wrongEmail = invitation(email = "other@example.test")
        val mismatchViewModel = InvitationAcceptViewModel(FakeInvitations(wrongEmail))
        mismatchViewModel.start(FamilyInvitationLink("house", wrongEmail.id), identity)
        advanceUntilIdle()
        assertEquals(InvitationAcceptUiState.WrongEmail, mismatchViewModel.state.value)
    }

    @Test
    fun `opening another link with the same account restarts invitation observation`() = runTest(dispatcher) {
        val invitation = invitation()
        val repository = FakeInvitations(invitation)
        val viewModel = InvitationAcceptViewModel(repository)
        val identity = OnboardingIdentity("guest", "guest@example.test", null)

        viewModel.start(FamilyInvitationLink("house", "first"), identity)
        advanceUntilIdle()
        viewModel.start(FamilyInvitationLink("house", "second"), identity)
        advanceUntilIdle()

        assertEquals(listOf("first", "second"), repository.observedIds)
    }

    @Test
    fun `pending local invitation cannot be accepted before server confirmation`() = runTest(dispatcher) {
        val invitation = invitation()
        val repository = FakeInvitations(invitation, SyncState.PENDING)
        val viewModel = InvitationAcceptViewModel(repository)
        viewModel.start(FamilyInvitationLink("house", invitation.id), OnboardingIdentity("guest", "guest@example.test", null))
        advanceUntilIdle()

        viewModel.accept()
        advanceUntilIdle()

        assertTrue(repository.accepted.isEmpty())
    }

    private fun ownerMembers() = listOf(
        Member("owner", "owner@example.test", "Właściciel", MemberRole.OWNER),
        Member("member", "member@example.test", "Członek", MemberRole.MEMBER),
    )

    private fun invitation(
        email: String = "guest@example.test",
        expiresAt: Instant = Instant.now().plusSeconds(24 * 60 * 60),
        status: InvitationStatus = InvitationStatus.PENDING,
    ) = Invitation("invite", "house", email, "owner", expiresAt, status)
}

private class FakeHouseholds(members: List<Member>) : HouseholdRepository {
    val members = MutableStateFlow(SyncObservation(value = members, state = SyncState.SYNCED))
    val removed = mutableListOf<Pair<String, String>>()
    override fun observeHousehold(householdId: String): Flow<SyncObservation<Household>> = flowOf(SyncObservation(state = SyncState.SYNCED))
    override fun observeMembers(householdId: String): Flow<SyncObservation<List<Member>>> = members
    override suspend fun saveHousehold(household: Household) = Unit
    override suspend fun removeMember(householdId: String, memberId: String) { removed += householdId to memberId }
}

private class FakeInvitations(invitation: Invitation? = null, syncState: SyncState = SyncState.SYNCED) : InvitationRepository {
    val created = mutableListOf<Invitation>()
    val accepted = mutableListOf<Pair<Invitation, Member>>()
    val observedIds = mutableListOf<String>()
    private val current = MutableStateFlow(SyncObservation(value = invitation, state = syncState))
    override fun observeInvitations(householdId: String): Flow<SyncObservation<List<Invitation>>> =
        flowOf(SyncObservation(value = current.value.value?.let(::listOf).orEmpty(), state = SyncState.SYNCED))
    override fun observeInvitation(householdId: String, invitationId: String): Flow<SyncObservation<Invitation>> {
        observedIds += invitationId
        return current
    }
    override suspend fun create(invitation: Invitation) { created += invitation }
    override suspend fun revoke(householdId: String, invitationId: String) = Unit
    override suspend fun accept(invitation: Invitation, member: Member) { accepted += invitation to member }
}
