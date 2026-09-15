package pl.bargor.thesaurus.data.firebase

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HouseholdOnboardingIntegrationTest {
    @Test
    fun firstHouseholdIsCompleteAndRetryDoesNotCreateAnotherOne() = runBlocking {
        val suffix = UUID.randomUUID().toString()
        val app = FirebaseApp.initializeApp(
            InstrumentationRegistry.getInstrumentation().targetContext,
            FirebaseOptions.Builder()
                .setApplicationId("1:1234567890:android:onboarding-$suffix")
                .setApiKey("fake-api-key-$suffix")
                .setProjectId("demo-thesaurus")
                .build(),
            "onboarding-$suffix",
        )
        val auth = FirebaseAuthFactory.create(app)
        FirebaseAuthFactory.connectToLocalEmulator(auth)
        val firestore = FirebaseFirestoreFactory.create(app, emulatorHost = "10.0.2.2")

        try {
            val email = "onboarding-$suffix@example.test"
            val firebaseUser = auth.createUserWithEmailAndPassword(email, "test-password-123")
                .await().user!!
            val identity = OnboardingIdentity(
                uid = firebaseUser.uid,
                email = email,
                displayName = "Ala",
            )
            val repository: OnboardingRepository = FirestoreRepositories(firestore)

            val created = repository.createFirstHousehold(identity, "  Nasz dom  ")
            assertTrue(created is FirstHouseholdResult.Created)
            val householdId = (created as FirstHouseholdResult.Created).householdId

            val profile = firestore.collection(FirestorePaths.USERS).document(firebaseUser.uid).get().await()
            assertEquals(householdId, profile.getString("householdId"))
            val household = firestore.collection(FirestorePaths.HOUSEHOLDS).document(householdId)
            assertEquals("Nasz dom", household.get().await().getString("name"))
            assertEquals(
                "OWNER",
                household.collection(FirestorePaths.MEMBERS).document(firebaseUser.uid)
                    .get().await().getString("role"),
            )

            val categories = household.collection(FirestorePaths.CATEGORIES).get().await()
            assertEquals(10, categories.size())
            assertEquals(
                "INCOME",
                categories.documents.single { it.id == "wplywy" }.getString("defaultEntryType"),
            )
            assertTrue(
                household.collection(FirestorePaths.CATEGORIES).document("inne")
                    .collection(FirestorePaths.SUBCATEGORIES).get().await().isEmpty,
            )

            val retried = repository.createFirstHousehold(identity, "Drugi dom")
            assertEquals(FirstHouseholdResult.Existing(householdId), retried)
        } finally {
            runCatching { firestore.terminate().await() }
            app.delete()
        }
    }
}
