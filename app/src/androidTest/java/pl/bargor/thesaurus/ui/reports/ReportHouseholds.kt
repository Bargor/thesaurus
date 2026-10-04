package pl.bargor.thesaurus.ui.reports

import kotlinx.coroutines.flow.flowOf
import pl.bargor.thesaurus.data.firebase.HouseholdRepository
import pl.bargor.thesaurus.data.model.*

internal class ReportHouseholds : HouseholdRepository {
    override fun observeHousehold(householdId: String) = flowOf(SyncObservation(Household(householdId, "Dom", "actor"), SyncState.SYNCED))
    override fun observeMembers(householdId: String) = flowOf(SyncObservation(listOf(Member("actor", "actor@example.test", "Ala", MemberRole.OWNER)), SyncState.SYNCED))
    override suspend fun saveHousehold(household: Household) = Unit
    override suspend fun removeMember(householdId: String, memberId: String) = Unit
}
