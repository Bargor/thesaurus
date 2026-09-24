package pl.bargor.thesaurus

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class AuthenticationSmokeTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun signedOutAppShowsGoogleSignIn() {
        if (InstrumentationRegistry.getInstrumentation().targetContext.packageName.endsWith(".dev")) return
        composeTestRule.onNodeWithTag("google-sign-in").assertIsDisplayed()
    }
}
