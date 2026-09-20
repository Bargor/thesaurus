package pl.bargor.thesaurus.data.firebase

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.Source
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pl.bargor.thesaurus.LocalNetworkPermissionRule
import pl.bargor.thesaurus.data.model.Invitation
import pl.bargor.thesaurus.data.model.InvitationStatus
import pl.bargor.thesaurus.data.model.Member
import pl.bargor.thesaurus.data.model.MemberRole
import pl.bargor.thesaurus.data.model.SyncState

@RunWith(AndroidJUnit4::class)
class FamilySharingIntegrationTest {
    @get:Rule val localNetworkPermissionRule = LocalNetworkPermissionRule()

    @Test
    fun verifiedGoogleRecipientAcceptsAndCanRejoinAfterOwnerRemoval() = runBlocking {
        val suffix = UUID.randomUUID().toString()
        val app = FirebaseApp.initializeApp(
            InstrumentationRegistry.getInstrumentation().targetContext,
            FirebaseOptions.Builder()
                .setApplicationId("1:1234567890:android:family-$suffix")
                .setApiKey("fake-api-key-$suffix")
                .setProjectId("demo-thesaurus")
                .build(),
            "family-$suffix",
        )
        val auth = FirebaseAuthFactory.create(app)
        FirebaseAuthFactory.connectToLocalEmulator(auth)
        val firestore = FirebaseFirestoreFactory.create(app, emulatorHost = "10.0.2.2")

        suspend fun signInGoogle(subject: String, email: String): String {
            val mockGoogleToken = """{"sub":"$subject","email":"$email","email_verified":true}"""
            val user = auth.signInWithCredential(GoogleAuthProvider.getCredential(mockGoogleToken, null))
                .await().user!!
            assertTrue(user.isEmailVerified)
            return user.uid
        }

        try {
            val ownerEmail = "family-owner-$suffix@example.test"
            val recipientEmail = "family-recipient-$suffix@example.test"
            val ownerId = signInGoogle("owner-$suffix", ownerEmail)
            val repository = FirestoreRepositories(firestore)
            val created = repository.createFirstHousehold(
                OnboardingIdentity(ownerId, ownerEmail, "Właściciel"), "Dom rodzinny",
            ) as FirstHouseholdResult.Created
            val householdId = created.householdId

            fun invitation(id: String) = Invitation(
                id = id,
                householdId = householdId,
                email = recipientEmail,
                invitedBy = ownerId,
                expiresAt = Instant.now().plus(7, ChronoUnit.DAYS).minusSeconds(30),
            )

            val first = invitation("first-$suffix")
            val revoked = invitation("revoked-$suffix")
            repository.create(first)
            repository.create(revoked)
            repository.revoke(householdId, revoked.id)

            auth.signOut()
            val recipientId = signInGoogle("recipient-$suffix", recipientEmail)
            val visible = withTimeout(15_000) {
                repository.observeInvitation(householdId, first.id).first {
                    it.state == SyncState.SYNCED && it.value?.id == first.id
                }
            }
            assertEquals(InvitationStatus.PENDING, visible.value?.status)
            val revokedVisible = withTimeout(15_000) {
                repository.observeInvitation(householdId, revoked.id).first {
                    it.state == SyncState.SYNCED && it.value?.status == InvitationStatus.REVOKED
                }
            }
            assertEquals(InvitationStatus.REVOKED, revokedVisible.value?.status)

            repository.accept(
                first,
                Member(recipientId, recipientEmail, "Członek", MemberRole.MEMBER, invitationId = first.id),
            )
            val household = firestore.collection(FirestorePaths.HOUSEHOLDS).document(householdId)
            assertEquals(
                MemberRole.MEMBER.name,
                household.collection(FirestorePaths.MEMBERS).document(recipientId)
                    .get(Source.SERVER).await().getString("role"),
            )
            assertEquals(InvitationStatus.ACCEPTED.name,
                household.collection(FirestorePaths.INVITATIONS).document(first.id)
                    .get(Source.SERVER).await().getString("status"))

            auth.signOut()
            assertEquals(ownerId, signInGoogle("owner-$suffix", ownerEmail))
            repository.removeMember(householdId, recipientId)
            val second = invitation("second-$suffix")
            repository.create(second)

            auth.signOut()
            assertEquals(recipientId, signInGoogle("recipient-$suffix", recipientEmail))
            val lostAccess = runCatching { household.get(Source.SERVER).await() }
            assertTrue("Removed member must lose server access", lostAccess.isFailure)
            repository.accept(
                second,
                Member(recipientId, recipientEmail, "Członek", MemberRole.MEMBER, invitationId = second.id),
            )
            assertFalse(household.get(Source.SERVER).await().data.isNullOrEmpty())
        } finally {
            runCatching { firestore.terminate().await() }
            // Keep named FirebaseApp alive for the duration of the instrumentation process.
        }
    }
}
