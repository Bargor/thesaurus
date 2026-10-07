package pl.bargor.thesaurus.testfixtures

/** These values are test-only and cannot select production or the developer demo namespace. */
internal data class FirebaseIntegrationPolicy(
    val projectId: String = PROJECT_ID,
    val host: String = "10.0.2.2",
    val authPort: Int = 9099,
    val firestorePort: Int = 8080,
    val apiKey: String = "fake-api-key-integration",
    val applicationId: String = "1:1234567890:android:integration",
) {
    init {
        require(projectId == PROJECT_ID) { "Integration fixtures require the separate emulator-only project" }
        require(host == "10.0.2.2" && authPort == 9099 && firestorePort == 8080) {
            "Integration fixtures require the literal Android emulator endpoints"
        }
        require(apiKey == "fake-api-key-integration" && applicationId == "1:1234567890:android:integration") {
            "Integration fixtures require fake Firebase identifiers"
        }
    }

    fun appName(label: String, suffix: String): String {
        require(Regex("[A-Za-z][A-Za-z0-9-]*").matches(label)) { "Invalid integration fixture label" }
        require(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}").matches(suffix)) {
            "Integration fixtures require a UUID suffix"
        }
        return "integration-$label-$suffix"
    }

    companion object {
        const val PROJECT_ID = "demo-thesaurus-integration"
    }
}
