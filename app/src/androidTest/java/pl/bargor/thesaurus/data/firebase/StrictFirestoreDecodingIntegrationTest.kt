package pl.bargor.thesaurus.data.firebase

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.firestore.Source
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pl.bargor.thesaurus.LocalNetworkPermissionRule
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.model.SyncObservation
import pl.bargor.thesaurus.data.model.SyncState
import pl.bargor.thesaurus.testfixtures.EmulatorMalformedDocumentSeed
import pl.bargor.thesaurus.testfixtures.FirebaseIntegrationFixture
import pl.bargor.thesaurus.ui.balance.GlobalAccountBalanceViewModel

@RunWith(AndroidJUnit4::class)
class StrictFirestoreDecodingIntegrationTest {
    @get:Rule val networkPermission = LocalNetworkPermissionRule()

    @Test fun malformedSeedRejectsUnownedPathsAndCannotOutliveItsFixture() = runBlocking<Unit> {
        val fixture = FirebaseIntegrationFixture.open("strict-seed-safety")
        lateinit var seed: EmulatorMalformedDocumentSeed
        val id = UUID.randomUUID().toString()
        try {
            fixture.scenario("malformed seed path and lifecycle guards") {
                val (_, home) = createHome(fixture)
                seed = EmulatorMalformedDocumentSeed.owned(fixture, home, setOf(id))
                assertTrue(runCatching { seed.replaceEntry(UUID.randomUUID().toString(), emptyMap()) }
                    .exceptionOrNull() is IllegalArgumentException)
                assertTrue(runCatching { EmulatorMalformedDocumentSeed.owned(fixture, "../foreign", setOf(id)) }
                    .exceptionOrNull() is IllegalArgumentException)
                assertTrue(runCatching { EmulatorMalformedDocumentSeed.owned(fixture, home, setOf("../../other")) }
                    .exceptionOrNull() is IllegalArgumentException)
                fixture.auth.signOut()
                assertTrue(runCatching { seed.replaceEntry(id, emptyMap()) }
                    .exceptionOrNull() is IllegalStateException)
            }
        } finally {
            fixture.close()
        }
        assertTrue(runCatching { seed.replaceEntry(id, emptyMap()) }.exceptionOrNull() is IllegalStateException)
    }

    @Test fun oneLedgerListenerRejectsQueryHiddenMalformedRecordsAndRecoversWithoutPartialSnapshots() = runBlocking<Unit> {
        val fixture = FirebaseIntegrationFixture.open("strict-ledger")
        try {
            fixture.scenario("strict ledger same-listener recovery and offline cache") {
                val (uid, home) = createHome(fixture)
                val repository = FirestoreRepositories(fixture.firestore)
                val entries = listOf(entry(home, uid, 1234), entry(home, uid, -234))
                entries.forEach { repository.save(it) }
                val reference = fixture.firestore.collection(FirestorePaths.HOUSEHOLDS).document(home)
                    .collection(FirestorePaths.ENTRIES).document(entries.first().id)
                val validFields = fixture.operation("read valid owned entry before malformed seeding") {
                    requireNotNull(reference.get(Source.SERVER).await().data)
                }
                val seed = EmulatorMalformedDocumentSeed.owned(fixture, home, entries.map { it.id }.toSet())
                val events = Channel<SyncObservation<List<LedgerEntry>>>(Channel.UNLIMITED)
                val listener = launch { repository.observeEntries(home).collect { events.send(it) } }
                var hasCompleteBaseline = false
                suspend fun next(stage: String, predicate: (SyncObservation<List<LedgerEntry>>) -> Boolean) =
                    fixture.operation(stage) {
                        var value = events.receive()
                        while (true) {
                            if (hasCompleteBaseline) value.value?.let { snapshot ->
                                assertEquals("No listener emission may contain a partial ledger", 2, snapshot.size)
                                assertEquals(entries.map { it.id }.toSet(), snapshot.map { it.id }.toSet())
                            }
                            if (value.state == SyncState.ERROR) assertNull(value.value)
                            if (predicate(value)) break
                            value = events.receive()
                        }
                        value
                    }
                try {
                    val initial = next("initial complete ledger snapshot") { it.state == SyncState.SYNCED && it.value?.size == 2 }
                    assertEquals(entries.map { it.id }.toSet(), initial.value!!.map { it.id }.toSet())
                    hasCompleteBaseline = true
                    val malformed = listOf(
                        Triple("date", FirestoreDecodeReason.MISSING_FIELD, validFields - "date"),
                        Triple("deleted", FirestoreDecodeReason.MISSING_FIELD, validFields - "deleted"),
                        Triple("amountGrosze", FirestoreDecodeReason.WRONG_TYPE, validFields + ("amountGrosze" to "not-money")),
                        // Numerically distinct too: watch streams may suppress an equal Long/Double change.
                        Triple("amountGrosze", FirestoreDecodeReason.WRONG_TYPE, validFields + ("amountGrosze" to 1234.5)),
                    )
                    for ((field, reason, fields) in malformed) {
                        seed.replaceEntry(entries.first().id, fields)
                        val failed = next("malformed $field invalidates the whole ledger") {
                            it.state == SyncState.ERROR && (it.error as? FirestoreDecodeException)?.field == field
                        }
                        assertNull("The valid sibling must not be emitted as a partial ledger", failed.value)
                        val error = failed.error as FirestoreDecodeException
                        assertEquals(FirestoreDocumentType.LEDGER_ENTRY, error.documentType)
                        assertEquals(reason, error.reason)
                        assertTrue(error.documentFingerprint?.matches(Regex("[a-fA-F0-9]{64}")) == true)
                        assertFalse(error.message.orEmpty().contains(home))
                        assertFalse(error.message.orEmpty().contains(entries.first().id))
                        assertFalse(error.message.orEmpty().contains("not-money"))
                        assertTrue("Decoding failures must not terminate the listener", listener.isActive)
                        seed.replaceEntry(entries.first().id, validFields)
                        val corrected = next("corrected $field recovers the existing listener") {
                            it.state == SyncState.SYNCED && it.value?.size == 2 && it.error == null
                        }
                        val correctedEntries = requireNotNull(corrected.value)
                        assertEquals(entries.map { it.id }.toSet(), correctedEntries.map { it.id }.toSet())
                        assertEquals(1000L, correctedEntries.sumOf { it.amountGrosze })
                    }

                    seed.replaceEntry(entries.first().id, validFields + ("amountGrosze" to "cached-invalid"))
                    next("server malformed value reaches the live cache") { it.state == SyncState.ERROR }
                    fixture.disableNetwork()
                    val cached = fixture.operation("confirm malformed field is stored in the offline SDK cache") {
                        reference.get(Source.CACHE).await()
                    }
                    assertEquals("cached-invalid", cached.getString("amountGrosze"))
                    val cachedError = fixture.awaitFlow("new offline listener validates cached records", repository.observeEntries(home)) {
                        it.state == SyncState.ERROR && it.error is FirestoreDecodeException
                    }
                    assertNull(cachedError.value)
                    assertEquals("amountGrosze", (cachedError.error as FirestoreDecodeException).field)
                    fixture.enableNetwork()
                    seed.replaceEntry(entries.first().id, validFields)
                    next("original listener recovers after cached-malformed correction") {
                        it.state == SyncState.SYNCED && it.value?.size == 2 && it.error == null
                    }
                    assertTrue(listener.isActive)
                } finally {
                    listener.cancelAndJoin()
                    events.close()
                }
            }
        } finally {
            fixture.close()
        }
    }

    @Test fun currentBalancePreparationRejectsMalformedRecordsWithoutMutatingTheHousehold() = runBlocking<Unit> {
        val fixture = FirebaseIntegrationFixture.open("strict-current-balance")
        try {
            fixture.scenario("current balance preparation validates every record before writes") {
                val (uid, home) = createHome(fixture)
                val repository = FirestoreRepositories(fixture.firestore)
                val entries = listOf(entry(home, uid, 1234), entry(home, uid, -234))
                entries.forEach { repository.save(it) }
                val household = fixture.firestore.collection(FirestorePaths.HOUSEHOLDS).document(home)
                val validHome = fixture.operation("read current balance settings baseline") {
                    requireNotNull(household.get(Source.SERVER).await().data)
                }
                val validEntry = fixture.operation("read current balance entry baseline") {
                    requireNotNull(household.collection(FirestorePaths.ENTRIES).document(entries.first().id)
                        .get(Source.SERVER).await().data)
                }
                val seed = EmulatorMalformedDocumentSeed.owned(fixture, home, entries.map { it.id }.toSet())
                for ((field, malformed) in listOf(
                    "date" to (validEntry - "date"),
                    "deleted" to (validEntry - "deleted"),
                    "amountGrosze" to (validEntry + ("amountGrosze" to "invalid-money")),
                    "amountGrosze" to (validEntry + ("amountGrosze" to 1234.0)),
                )) {
                    val wholeDouble = malformed["amountGrosze"] is Double
                    if (wholeDouble) {
                        // Keep the integral Double regression, but force a distinct numeric transition
                        // before corrupting/restoring it so the emulator and SDK both acknowledge types.
                        seed.replaceEntry(entries.first().id, validEntry + ("amountGrosze" to 1235L))
                    }
                    seed.replaceEntry(entries.first().id, malformed)
                    val error = fixture.operation("reject malformed $field during current balance preparation") {
                        runCatching { repository.prepareCurrentBalance(home, 5000L) }.exceptionOrNull()
                    }
                    assertTrue(error is FirestoreDecodeException)
                    assertEquals(field, (error as FirestoreDecodeException).field)
                    val after = fixture.operation("verify rejected ledger preparation did not mutate household") {
                        household.get(Source.SERVER).await().data
                    }
                    assertEquals(validHome, after)
                    if (wholeDouble) seed.replaceEntry(entries.first().id, validEntry + ("amountGrosze" to 1235L))
                    seed.replaceEntry(entries.first().id, validEntry)
                }
                val malformedHome = validHome + ("openingBalanceGrosze" to 0.0)
                seed.replaceHousehold(validHome + ("openingBalanceGrosze" to 1L))
                seed.replaceHousehold(malformedHome)
                val error = fixture.operation("reject malformed household during current balance preparation") {
                    runCatching { repository.prepareCurrentBalance(home, 5000L) }.exceptionOrNull()
                }
                assertTrue(error is FirestoreDecodeException)
                assertEquals("openingBalanceGrosze", (error as FirestoreDecodeException).field)
                val after = fixture.operation("verify rejected household preparation did not write settings") {
                    household.get(Source.SERVER).await().data
                }
                assertEquals(malformedHome, after)
                seed.replaceHousehold(validHome + ("openingBalanceGrosze" to 1L))
                seed.replaceHousehold(validHome)
                val prepared = fixture.operation("current balance preparation recovers after corrections") {
                    repository.prepareCurrentBalance(home, 5000L)
                }
                assertEquals(4000L, prepared.derivedOpeningBalanceGrosze)
                assertEquals(validHome, fixture.operation("successful preparation alone never writes settings") {
                    household.get(Source.SERVER).await().data
                })
            }
        } finally {
            fixture.close()
        }
    }

    @Test fun realBalanceViewModelHidesMalformedLedgerAndHouseholdAmountsThenRecovers() = runBlocking<Unit> {
        val fixture = FirebaseIntegrationFixture.open("strict-balance")
        try {
            fixture.scenario("real balance fails closed on typed decoding errors") {
                val (uid, home) = createHome(fixture)
                val repository = FirestoreRepositories(fixture.firestore)
                val entries = listOf(entry(home, uid, 1234), entry(home, uid, -234))
                entries.forEach { repository.save(it) }
                val household = fixture.firestore.collection(FirestorePaths.HOUSEHOLDS).document(home)
                val validHome = fixture.operation("read valid balance settings") { requireNotNull(household.get(Source.SERVER).await().data) }
                val validEntry = fixture.operation("read valid balance entry") {
                    requireNotNull(household.collection(FirestorePaths.ENTRIES).document(entries.first().id).get(Source.SERVER).await().data)
                }
                val seed = EmulatorMalformedDocumentSeed.owned(fixture, home, entries.map { it.id }.toSet())
                lateinit var vm: GlobalAccountBalanceViewModel
                withContext(Dispatchers.Main) {
                    vm = GlobalAccountBalanceViewModel(repository, repository)
                    fixture.store.put("balance", vm)
                    vm.start(home, uid)
                }
                suspend fun ready(stage: String) = fixture.awaitFlow(stage, vm.state) {
                    !it.isLoading && !it.hasError && it.syncState == SyncState.SYNCED && it.amountGrosze == 1000.toBigInteger()
                }
                ready("initial balance includes both complete records")
                seed.replaceEntry(entries.first().id, validEntry + ("amountGrosze" to 1234.5))
                val invalidLedger = fixture.awaitFlow("balance hides invalid monetary type", vm.state) { it.hasError }
                assertEquals(SyncState.ERROR, invalidLedger.syncState)
                assertFalse(invalidLedger.isLoading)
                assertNull(invalidLedger.amountGrosze)
                seed.replaceEntry(entries.first().id, validEntry)
                ready("balance recovers after valid ledger correction")
                seed.replaceHousehold(validHome + ("openingBalanceGrosze" to 0.5))
                val invalidHome = fixture.awaitFlow("balance hides malformed opening balance", vm.state) { it.hasError }
                assertEquals(SyncState.ERROR, invalidHome.syncState)
                assertNull(invalidHome.amountGrosze)
                seed.replaceHousehold(validHome)
                ready("balance recovers after valid household correction")
            }
        } finally {
            fixture.close()
        }
    }

    private suspend fun createHome(fixture: FirebaseIntegrationFixture): Pair<String, String> {
        val email = "strict-${UUID.randomUUID()}@example.test"
        val uid = fixture.auth.createUserWithEmailAndPassword(email, "test-password-123").await().user!!.uid
        val repository = FirestoreRepositories(fixture.firestore)
        val home = (repository.createFirstHousehold(OnboardingIdentity(uid, email, "Strict fixture"), "Strict fixture")
            as FirstHouseholdResult.Created).householdId
        return uid to home
    }

    private fun entry(home: String, uid: String, amount: Long) = LedgerEntry(UUID.randomUUID().toString(), home, amount,
        LocalDate.of(2026, 9, 1), categoryId = "jedzenie", authorId = uid, updatedById = uid)
}
