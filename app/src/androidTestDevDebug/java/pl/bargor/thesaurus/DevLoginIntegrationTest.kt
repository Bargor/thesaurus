package pl.bargor.thesaurus

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.util.UUID

class DevLoginIntegrationTest {
    private val composeTestRule = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules = RuleChain.outerRule(LocalNetworkPermissionRule()).around(composeTestRule)

    @Test
    fun demoFirebaseConfigurationAndDeveloperFormAreIsolated() {
        assumeDevVariant()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = FirebaseApp.getInstance("dev-thesaurus")

        assertEquals("demo-thesaurus", app.options.projectId)
        assertEquals("fake-api-key-for-emulator-only", app.options.apiKey)
        assertEquals("demo-thesaurus", FirebaseOptions.fromResource(context)?.projectId)
        assertFalse(FirebaseApp.getApps(context).any { it.name == FirebaseApp.DEFAULT_APP_NAME })
        FirebaseAuth.getInstance(app).signOut()
        composeTestRule.onNodeWithTag("dev-sign-in").assertIsDisplayed()
    }

    @Test
    fun fakeEmailCanCreateFirstHouseholdThroughApp() {
        assumeDevVariant()
        val app = FirebaseApp.getInstance("dev-thesaurus")
        FirebaseAuth.getInstance(app).signOut()
        val email = "owner-${UUID.randomUUID()}@example.test"

        composeTestRule.onNodeWithTag("dev-email").performTextReplacement(email)
        composeTestRule.onNodeWithTag("dev-sign-in").performClick()
        composeTestRule.waitUntil(30_000) {
            composeTestRule.onAllNodesWithTag("household-name").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("household-name").performTextReplacement("Dom testowy")
        composeTestRule.onNodeWithTag("create-household").performClick()
        composeTestRule.waitUntil(30_000) {
            composeTestRule.onAllNodesWithTag("navigation-entries").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("dev-sign-out").assertIsDisplayed()
    }

    private fun assumeDevVariant() {
        assumeTrue(InstrumentationRegistry.getInstrumentation().targetContext.packageName.endsWith(".dev"))
    }
}


