package pl.bargor.thesaurus.di

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import dagger.hilt.android.qualifiers.ApplicationContext
import pl.bargor.thesaurus.data.firebase.FirebaseAuthFactory
import pl.bargor.thesaurus.data.firebase.FirebaseFirestoreFactory
import javax.inject.Inject
import javax.inject.Singleton

/** A named, explicitly configured demo project. No default FirebaseApp is ever consulted. */
@Singleton
class FirebaseBackend @Inject constructor(@ApplicationContext context: Context) {
    private val app = FirebaseApp.initializeApp(
        context,
        FirebaseOptions.Builder()
            .setApplicationId("1:1234567890:android:dev-thesaurus")
            .setApiKey("fake-api-key-for-emulator-only")
            .setProjectId("demo-thesaurus")
            .build(),
        "dev-thesaurus",
    )

    private val emulatorAuth: FirebaseAuth by lazy {
        FirebaseAuthFactory.create(app).also { FirebaseAuthFactory.connectToLocalEmulator(it) }
    }
    private val emulatorFirestore: FirebaseFirestore by lazy {
        FirebaseFirestoreFactory.create(app, emulatorHost = "10.0.2.2")
    }

    fun auth(): FirebaseAuth = emulatorAuth
    fun firestore(): FirebaseFirestore = emulatorFirestore
}
