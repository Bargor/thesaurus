package pl.bargor.thesaurus.testfixtures

import com.google.firebase.Timestamp
import com.google.firebase.firestore.Source
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

/** Admin seeding is available only for fixed owned paths in the disposable integration emulator. */
internal class EmulatorMalformedDocumentSeed private constructor(
    private val fixture: FirebaseIntegrationFixture,
    private val householdId: String,
    private val entryIds: Set<String>,
    private val ownerId: String,
) {
    suspend fun replaceEntry(entryId: String, fields: Map<String, Any?>) {
        require(entryId in entryIds) { "Entry is outside the seed allowlist" }
        replace("households/$householdId/entries/$entryId", fields)
    }

    suspend fun replaceHousehold(fields: Map<String, Any?>) = replace("households/$householdId", fields)

    private suspend fun replace(ownedPath: String, fields: Map<String, Any?>) {
        check(!fixture.isClosed && fixture.app.options.projectId == FirebaseIntegrationPolicy.PROJECT_ID)
        check(fixture.auth.currentUser?.uid == ownerId) { "Seed requires the original authenticated fixture owner" }
        val document = JSONObject().put("name", "projects/${FirebaseIntegrationPolicy.PROJECT_ID}/databases/(default)/documents/$ownedPath")
            .put("fields", fieldMap(fields))
        val body = JSONObject().put("writes", JSONArray().put(JSONObject().put("update", document)))
            .toString().toByteArray(Charsets.UTF_8)
        require(body.size <= 16_384) { "Owned malformed seed exceeds the request limit" }
        fixture.operation("admin replace owned test document", 10_000) {
            coroutineScope {
                val connection = AtomicReference<HttpURLConnection?>()
                suspendCancellableCoroutine<Unit> { continuation ->
                    val worker = launch(Dispatchers.IO) {
                        var request: HttpURLConnection? = null
                        try {
                            request = URL(ENDPOINT).openConnection(Proxy.NO_PROXY) as HttpURLConnection
                            connection.set(request)
                            ensureActive()
                            request.instanceFollowRedirects = false
                            request.connectTimeout = 5_000
                            request.readTimeout = 5_000
                            request.requestMethod = "POST"
                            request.doOutput = true
                            request.setRequestProperty("Authorization", "Bearer owner")
                            request.setRequestProperty("Content-Type", "application/json")
                            request.setFixedLengthStreamingMode(body.size)
                            request.outputStream.use { it.write(body) }
                            val status = request.responseCode
                            // Do not expose response bodies or seeded payloads in diagnostics.
                            if (status !in 200..299) {
                                request.errorStream?.close()
                                throw AssertionError("Owned malformed seed failed: HTTP $status")
                            }
                            request.inputStream.use { input ->
                                val buffer = ByteArray(1024)
                                var consumed = 0
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    consumed += read
                                    check(consumed <= 16_384) { "Owned seed response exceeds the limit" }
                                }
                            }
                            continuation.resume(Unit)
                        } catch (error: Throwable) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        } finally {
                            request?.disconnect()
                        }
                    }
                    continuation.invokeOnCancellation {
                        connection.get()?.disconnect()
                        worker.cancel()
                    }
                }
            }
        }
    }

    companion object {
        private const val ENDPOINT = "http://10.0.2.2:8080/v1/projects/demo-thesaurus-integration/databases/(default)/documents:commit"
        private val uuid = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        private val generatedHousehold = Regex("[A-Za-z0-9]{20}")

        suspend fun owned(fixture: FirebaseIntegrationFixture, householdId: String, entryIds: Set<String>): EmulatorMalformedDocumentSeed {
            check(!fixture.isClosed && fixture.app.options.projectId == FirebaseIntegrationPolicy.PROJECT_ID)
            require(uuid.matches(householdId) || generatedHousehold.matches(householdId)) { "Invalid owned household identifier" }
            require(entryIds.isNotEmpty() && entryIds.all(uuid::matches)) { "Entry seed allowlist must contain UUID identifiers" }
            val uid = requireNotNull(fixture.auth.currentUser?.uid) { "Seed requires an authenticated fixture owner" }
            fixture.operation("verify malformed seed household ownership") {
                val household = fixture.firestore.collection("households").document(householdId).get(Source.SERVER).await()
                check(household.exists() && household.getString("ownerId") == uid) { "Seed requires fixture-owned household" }
            }
            return EmulatorMalformedDocumentSeed(fixture, householdId, entryIds.toSet(), uid)
        }

        private fun fieldMap(fields: Map<String, Any?>): JSONObject = JSONObject().apply {
            fields.forEach { (key, value) -> put(key, fieldValue(value)) }
        }

        private fun fieldValue(value: Any?): JSONObject = when (value) {
            null -> JSONObject().put("nullValue", JSONObject.NULL)
            is String -> JSONObject().put("stringValue", value)
            is Boolean -> JSONObject().put("booleanValue", value)
            is Long -> JSONObject().put("integerValue", value.toString())
            is Double -> JSONObject().put("doubleValue", value)
            is Timestamp -> JSONObject().put("timestampValue", Instant.ofEpochSecond(value.seconds, value.nanoseconds.toLong()).toString())
            is List<*> -> JSONObject().put("arrayValue", JSONObject().put("values", JSONArray().apply {
                value.forEach { put(fieldValue(it)) }
            }))
            is Map<*, *> -> JSONObject().put("mapValue", JSONObject().put("fields", fieldMap(value.entries.associate {
                require(it.key is String) { "Unsupported seed map key" }
                it.key as String to it.value
            })))
            else -> throw IllegalArgumentException("Unsupported seed wire type")
        }
    }
}
