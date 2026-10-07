package pl.bargor.thesaurus.data.firebase

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import pl.bargor.thesaurus.data.model.Invitation
import pl.bargor.thesaurus.data.model.InvitationStatus
import pl.bargor.thesaurus.data.model.Member
import pl.bargor.thesaurus.data.model.MemberRole
import pl.bargor.thesaurus.data.model.SyncObservation
import java.util.Locale

internal class FirestoreInvitationRepository(private val firestore: FirebaseFirestore) : InvitationRepository {
    private fun household(id: String) = firestore.collection(FirestorePaths.HOUSEHOLDS).document(id)

    override fun observeInvitations(householdId: String): Flow<SyncObservation<List<Invitation>>> =
        household(householdId)
            .collection(FirestorePaths.INVITATIONS)
            .observations(DocumentSnapshot::toInvitation)

    override fun observeInvitation(
        householdId: String,
        invitationId: String,
    ): Flow<SyncObservation<Invitation>> =
        household(householdId)
            .collection(FirestorePaths.INVITATIONS)
            .document(invitationId)
            .observations(DocumentSnapshot::toInvitation)

    override suspend fun create(invitation: Invitation) {
        household(invitation.householdId).collection(FirestorePaths.INVITATIONS)
            .document(invitation.id).set(invitation.toDocument()).await()
    }

    override suspend fun revoke(householdId: String, invitationId: String) {
        household(householdId)
            .collection(FirestorePaths.INVITATIONS)
            .document(invitationId)
            .update("status", InvitationStatus.REVOKED.name).await()
    }

    override suspend fun accept(invitation: Invitation, member: Member) {
        require(invitation.status == InvitationStatus.PENDING)
        require(member.invitationId == invitation.id)
        require(member.role == MemberRole.MEMBER)
        val household = household(invitation.householdId)
        val profile = firestore.collection(FirestorePaths.USERS).document(member.uid)
        val existingProfile = profile.get().await()
        firestore.runBatch { batch ->
            if (existingProfile.exists()) {
                // A removed member keeps the immutable household profile. Re-inviting them to the
                // same household must not try to rewrite createdAt or householdId.
                batch.update(
                    profile,
                    mapOf(
                        "displayName" to member.displayName?.trim()?.takeIf(String::isNotEmpty),
                        "updatedAt" to FieldValue.serverTimestamp(),
                    ),
                )
            } else {
                batch.set(
                    profile,
                    mapOf(
                        "email" to member.email.trim().lowercase(Locale.ROOT),
                        "displayName" to member.displayName?.trim()?.takeIf(String::isNotEmpty),
                        "householdId" to invitation.householdId,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp(),
                    ),
                )
            }
            batch.update(
                household.collection(FirestorePaths.INVITATIONS).document(invitation.id),
                mapOf("status" to InvitationStatus.ACCEPTED.name, "acceptedBy" to member.uid),
            )
            batch.set(household.collection(FirestorePaths.MEMBERS).document(member.uid), member.toDocument())
        }.await()
    }
}
