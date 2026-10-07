package pl.bargor.thesaurus

import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test

class AppPlatformEffectsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun familyShareDispatchesChooserBeforeAcknowledgementAndDoesNotRepeatOnRecomposition() {
        val dispatched = mutableListOf<Intent>()
        val shareUrl = mutableStateOf<String?>(null)
        val revision = mutableStateOf(0)
        var acknowledgements = 0
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun startActivity(intent: Intent) { dispatched += intent }
        }
        compose.setContent {
            revision.value
            CompositionLocalProvider(LocalContext provides context) {
                FamilyShareEffect(shareUrl.value) {
                    // The request must remain available until Android receives it.
                    assertEquals(acknowledgements + 1, dispatched.size)
                    acknowledgements++
                }
            }
        }
        compose.runOnIdle { assertEquals(0, acknowledgements); shareUrl.value = "https://example.test/invitation" }
        compose.runOnIdle {
            assertEquals(1, acknowledgements)
            assertEquals(Intent.ACTION_CHOOSER, dispatched.single().action)
            @Suppress("DEPRECATION")
            val send = dispatched.single().getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("text/plain", send.type)
            assertEquals(shareUrl.value, send.getStringExtra(Intent.EXTRA_TEXT))
            revision.value++
        }
        compose.runOnIdle { assertEquals(1, acknowledgements); shareUrl.value = null }
        compose.runOnIdle { assertEquals(1, acknowledgements); shareUrl.value = "https://example.test/next" }
        compose.runOnIdle { assertEquals(2, acknowledgements) }
    }

    @Test fun googleSignInActivityLookupUnwrapsContextAndRejectsApplicationContext() {
        val activity = compose.activity
        assertSame(activity, ContextWrapper(ContextWrapper(activity)).findActivity())
        assertNull(InstrumentationRegistry.getInstrumentation().targetContext.findActivity())
    }
}
