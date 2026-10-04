package pl.bargor.thesaurus.ui.reports

import kotlinx.coroutines.flow.MutableStateFlow
import pl.bargor.thesaurus.data.firebase.HouseholdRepository
import pl.bargor.thesaurus.data.model.*

internal class ReportHouseholds : HouseholdRepository {
    val home = MutableStateFlow(SyncObservation(listOf(Member("actor", "actor@example.test", "Ala", MemberRole.OWNER)), SyncState.SYNCED))
    val other = MutableStateFlow(SyncObservation(emptyList<Member>(), SyncState.SYNCED))
    val settings = MutableStateFlow(SyncObservation(Household("home", "Dom", "actor"), SyncState.SYNCED))
    val otherSettings = MutableStateFlow(SyncObservation(Household("other", "Drugi", "actor"), SyncState.SYNCED))
    override fun observeHousehold(householdId: String) = if (householdId == "home") settings else otherSettings
    override fun observeMembers(householdId: String) = if (householdId == "home") home else other
    override suspend fun saveHousehold(household: Household) = Unit
    override suspend fun removeMember(householdId: String, memberId: String) = Unit
}
