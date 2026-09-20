package pl.bargor.thesaurus

import android.content.Intent
import android.net.Uri

/** The only invitation URL accepted by the app. The token is a Firestore document id, never an email. */
data class FamilyInvitationLink(val householdId: String, val invitationId: String) {
    fun url(): String = "https://$HOST$PATH/$householdId/$invitationId"

    companion object {
        const val HOST = "thesaurus-cef84.web.app"
        const val PATH = "/zaproszenie"

        fun fromIntent(intent: Intent?): FamilyInvitationLink? =
            intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data?.toInvitationLink()

        fun fromUri(uri: Uri?): FamilyInvitationLink? = uri?.toInvitationLink()

        private fun Uri.toInvitationLink(): FamilyInvitationLink? {
            if (scheme != "https" || host != HOST) return null
            if (query != null || fragment != null) return null
            val parts = pathSegments
            if (parts.size != 3 || parts[0] != PATH.removePrefix("/")) return null
            val householdId = parts[1]
            val invitationId = parts[2]
            if (!householdId.isSafeId() || !invitationId.isSafeId()) return null
            return FamilyInvitationLink(householdId, invitationId)
        }

        private fun String.isSafeId(): Boolean = isNotBlank() && length <= 128 &&
            all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }
}
