package pl.bargor.thesaurus.data.auth

import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import pl.bargor.thesaurus.data.firebase.OnboardingIdentity
import javax.inject.Inject
import javax.inject.Singleton

interface AuthRepository {
    val identities: Flow<OnboardingIdentity?>
    suspend fun signIn(activity: Activity): Result<OnboardingIdentity>
    suspend fun signOut()
}

/**
 * The OAuth web client id is read only from the generated Google Services resource. It is absent
 * until Google is enabled in Firebase, so this class deliberately has no fallback id or secret.
 */
@Singleton
class FirebaseGoogleAuthRepository @Inject constructor(
    @param:ApplicationContext private val applicationContext: Context,
    private val firebaseAuth: FirebaseAuth,
) : AuthRepository {
    private val credentialManager = CredentialManager.create(applicationContext)

    override val identities: Flow<OnboardingIdentity?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { auth ->
            trySend(auth.currentUser?.toIdentity())
        }
        firebaseAuth.addAuthStateListener(listener)
        awaitClose { firebaseAuth.removeAuthStateListener(listener) }
    }

    override suspend fun signIn(activity: Activity): Result<OnboardingIdentity> = runCatching {
        val resourceId = applicationContext.resources.getIdentifier(
            "default_web_client_id",
            "string",
            applicationContext.packageName,
        )
        if (resourceId == 0) throw GoogleConfigurationMissingException()
        val serverClientId = applicationContext.getString(resourceId)
        // A quiet authorized-account request makes returning users frictionless. If there is no
        // authorized account, the explicit tap may offer every Google account on the device.
        val credential = try {
            googleIdCredential(activity, serverClientId, filterByAuthorizedAccounts = true)
        } catch (_: NoCredentialException) {
            googleIdCredential(activity, serverClientId, filterByAuthorizedAccounts = false)
        }
        check(credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            "Nieprawidłowa odpowiedź logowania Google."
        }
        val token = GoogleIdTokenCredential.createFrom(credential.data).idToken
        firebaseAuth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
        requireNotNull(firebaseAuth.currentUser?.toIdentity()) { "Nie udało się odczytać zalogowanego użytkownika." }
    }

    override suspend fun signOut() {
        firebaseAuth.signOut()
        runCatching { credentialManager.clearCredentialState(ClearCredentialStateRequest()) }
    }

    private suspend fun googleIdCredential(
        activity: Activity,
        serverClientId: String,
        filterByAuthorizedAccounts: Boolean,
    ): androidx.credentials.Credential {
        val googleIdOption = GetGoogleIdOption.Builder()
            .setServerClientId(serverClientId)
            .setFilterByAuthorizedAccounts(filterByAuthorizedAccounts)
            .build()
        return credentialManager.getCredential(
            context = activity,
            request = GetCredentialRequest.Builder().addCredentialOption(googleIdOption).build(),
        ).credential
    }
}

private fun com.google.firebase.auth.FirebaseUser.toIdentity(): OnboardingIdentity? {
    val verifiedEmail = email?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return OnboardingIdentity(uid = uid, email = verifiedEmail, displayName = displayName)
}

class GoogleConfigurationMissingException : IllegalStateException()
