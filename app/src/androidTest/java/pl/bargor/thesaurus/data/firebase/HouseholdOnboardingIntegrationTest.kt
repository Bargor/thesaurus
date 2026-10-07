package pl.bargor.thesaurus.data.firebase

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pl.bargor.thesaurus.LocalNetworkPermissionRule
import java.util.UUID
import pl.bargor.thesaurus.testfixtures.FirebaseIntegrationFixture

@RunWith(AndroidJUnit4::class)
class HouseholdOnboardingIntegrationTest {
    @get:Rule
    val localNetworkPermissionRule = LocalNetworkPermissionRule()

    @Test
    fun firstHouseholdIsCompleteAndRetryDoesNotCreateAnotherOne() = runBlocking {
        val suffix = UUID.randomUUID().toString()
        val fixture = FirebaseIntegrationFixture.open("HouseholdOnboardingIntegrationTest")
        val auth = fixture.auth
        val firestore = fixture.firestore

        try {
            fixture.scenario("HouseholdOnboardingIntegrationTest scenario") {
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
            }
        } finally {
            fixture.close()
        }
    }
}
