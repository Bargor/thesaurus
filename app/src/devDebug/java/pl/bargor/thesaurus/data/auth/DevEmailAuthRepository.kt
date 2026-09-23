package pl.bargor.thesaurus.data.auth

import android.app.Activity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import pl.bargor.thesaurus.data.firebase.OnboardingIdentity
import javax.inject.Inject
import javax.inject.Singleton

/** Emulator email accounts are created on first login and retained until the emulator is reset. */
@Singleton
class DevEmailAuthRepository @Inject constructor(private val auth: FirebaseAuth) : AuthRepository {
    override val identities: Flow<OnboardingIdentity?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.toDevIdentity()) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    override suspend fun signIn(activity: Activity): Result<OnboardingIdentity> =
        Result.failure(UnsupportedOperationException("Use the developer email form"))

    override suspend fun signInWithEmail(email: String, password: String): Result<OnboardingIdentity> = runCatching {
        require(email.isNotBlank() && password.isNotBlank())
        val user = try {
            auth.createUserWithEmailAndPassword(email, password).await().user
        } catch (_: FirebaseAuthUserCollisionException) {
            auth.signInWithEmailAndPassword(email, password).await().user
        }
        requireNotNull(user?.toDevIdentity())
    }

    override suspend fun signOut() = auth.signOut()
}

private fun FirebaseUser.toDevIdentity(): OnboardingIdentity? =
    email?.trim()?.takeIf { it.isNotEmpty() }?.let { OnboardingIdentity(uid, it, null) }
