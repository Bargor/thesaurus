package pl.bargor.thesaurus.data.firebase

import org.junit.Assert.assertEquals
import org.junit.Test
import pl.bargor.thesaurus.data.model.SyncState

class SyncStateTest {
    @Test
    fun `pending writes take priority over cached state`() {
        assertEquals(SyncState.PENDING, syncState(true, true))
    }

    @Test
    fun `cached serverless value is offline`() {
        assertEquals(SyncState.OFFLINE, syncState(false, true))
    }

    @Test
    fun `server snapshot is synced`() {
        assertEquals(SyncState.SYNCED, syncState(false, false))
    }
}
