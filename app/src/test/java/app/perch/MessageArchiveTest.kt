package app.perch

import org.junit.Assert.*
import org.junit.Test

class MessageArchiveTest {
    private val first = Message("same-id", "one", "First", "Body", 10, server = "https://one.example")
    private val other = first.copy(server = "https://two.example")
    private val snapshot = Snapshot(Server(first.server, enabled = true), listOf(Topic("one")), listOf(first, other))

    @Test fun deletionIsScopedToServerAndDoesNotChangeSubscriptions() {
        val removed = MessageArchive.delete(snapshot, setOf(first.key))
        assertEquals(listOf(other), removed.messages)
        assertEquals(snapshot.server, removed.server)
        assertEquals(snapshot.topics, removed.topics)
        assertEquals(setOf(first.key), removed.deleted)
    }
    @Test fun deletedMessagesStayDeletedAfterMarkerRoundTripAndReplay() {
        val removed = MessageArchive.delete(snapshot, setOf(first.key))
        val reloaded = removed.copy(deleted = MessageArchive.decodeDeleted(MessageArchive.encodeDeleted(removed.deleted)))
        assertFalse(MessageArchive.accepts(reloaded, first))
        assertFalse(MessageArchive.accepts(reloaded, other))
        assertTrue(MessageArchive.accepts(reloaded, first.copy(id = "new-id")))
    }
    @Test fun batchDeletionIsIdempotentAndOldSnapshotsNeedNoMigration() {
        val removed = MessageArchive.delete(snapshot, setOf(first.key, other.key))
        assertTrue(removed.messages.isEmpty())
        assertEquals(removed, MessageArchive.delete(removed, setOf(first.key, other.key)))
        assertTrue(MessageArchive.decodeDeleted(null).isEmpty())
        assertTrue(snapshot.deleted.isEmpty())
    }
}
