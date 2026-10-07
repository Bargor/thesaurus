package pl.bargor.thesaurus.data.firebase

import com.google.firebase.firestore.FirebaseFirestore
import javax.inject.Inject

/** Injected compatibility facade; each delegate owns one persistence responsibility. */
class FirestoreRepositories @Inject constructor(firestore: FirebaseFirestore) :
    UserRepository by FirestoreUserRepository(firestore),
    HouseholdRepository by FirestoreHouseholdRepository(firestore),
    LedgerRepository by FirestoreLedgerRepository(firestore),
    TaxonomyRepository by FirestoreTaxonomyRepository(firestore),
    InvitationRepository by FirestoreInvitationRepository(firestore),
    OnboardingRepository by FirestoreOnboardingRepository(firestore),
    OpeningBalanceRepository {
    private val openingBalance = FirestoreOpeningBalanceRepository(firestore)

    override suspend fun saveOpeningBalance(householdId: String, amountGrosze: Long) =
        openingBalance.saveOpeningBalance(householdId, amountGrosze)

    override suspend fun saveCurrentBalance(householdId: String, currentBalanceGrosze: Long) =
        openingBalance.saveCurrentBalance(householdId, currentBalanceGrosze)

    // Preserve the revision-fenced preparation/commit hooks used by integration coverage.
    internal suspend fun prepareCurrentBalance(householdId: String, currentBalanceGrosze: Long): PreparedCurrentBalance =
        openingBalance.prepareCurrentBalance(householdId, currentBalanceGrosze)

    internal suspend fun commitCurrentBalance(prepared: PreparedCurrentBalance) =
        openingBalance.commitCurrentBalance(prepared)
}
