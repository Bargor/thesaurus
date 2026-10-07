package pl.bargor.thesaurus.testfixtures

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FirebaseIntegrationPolicyTest {
    @Test fun onlySeparateDemoNamespaceLiteralEndpointsAndFakeIdentifiersAreAccepted() {
        val policy = FirebaseIntegrationPolicy()
        assertEquals("demo-thesaurus-integration", policy.projectId)
        for (project in listOf("thesaurus", "demo-thesaurus", "demo-other", "", "https://demo-thesaurus-integration")) {
            assertThrows(IllegalArgumentException::class.java) { FirebaseIntegrationPolicy(projectId = project) }
        }
        for (host in listOf("localhost", "127.0.0.1", "firebase.googleapis.com", "10.0.2.2.evil.test", "https://10.0.2.2")) {
            assertThrows(IllegalArgumentException::class.java) { FirebaseIntegrationPolicy(host = host) }
        }
        assertThrows(IllegalArgumentException::class.java) { FirebaseIntegrationPolicy(authPort = 443) }
        assertThrows(IllegalArgumentException::class.java) { FirebaseIntegrationPolicy(firestorePort = 443) }
        assertThrows(IllegalArgumentException::class.java) { FirebaseIntegrationPolicy(apiKey = "real-key") }
        assertThrows(IllegalArgumentException::class.java) { FirebaseIntegrationPolicy(applicationId = "1:real:android:real") }
    }

    @Test fun defaultAppAndAmbiguousFixtureNamesCannotBeSelected() {
        val policy = FirebaseIntegrationPolicy()
        val uuid = "00000000-0000-0000-0000-000000000000"
        assertEquals("integration-Summary-$uuid", policy.appName("Summary", uuid))
        for (label in listOf("[DEFAULT]", "", "a/b", "a b")) {
            assertThrows(IllegalArgumentException::class.java) { policy.appName(label, uuid) }
        }
        assertThrows(IllegalArgumentException::class.java) { policy.appName("Summary", "same-every-test") }
    }
}
