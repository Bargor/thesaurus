package pl.bargor.thesaurus.data.firebase

import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings

/** Persistent local cache is enabled explicitly. Emulator routing is opt-in at creation time. */
object FirebaseFirestoreFactory {
    fun create(
        app: FirebaseApp = FirebaseApp.getInstance(),
        emulatorHost: String? = null,
        emulatorPort: Int = 8080,
    ): FirebaseFirestore =
        FirebaseFirestore.getInstance(app).also { firestore ->
            emulatorHost?.let { firestore.useEmulator(it, emulatorPort) }
            firestore.firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(PersistentCacheSettings.newBuilder().build())
                .build()
            firestore.persistentCacheIndexManager?.enableIndexAutoCreation()
        }
}

object FirebaseAuthFactory {
    fun create(app: FirebaseApp = FirebaseApp.getInstance()): FirebaseAuth =
        FirebaseAuth.getInstance(app)

    fun connectToLocalEmulator(
        auth: FirebaseAuth,
        host: String = "10.0.2.2",
        port: Int = 9099,
    ) {
        auth.useEmulator(host, port)
    }
}
