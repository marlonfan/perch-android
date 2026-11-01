package app.perch

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PriorityAlertsTest {
    private val server = Server("https://one.example", enabled = true)
    private val topic = Topic("alerts", joined = 90, server = server.url, persistentAlert = true)
    private val message = Message("one", topic.name, "Title", "Body", 101, priority = 5, server = server.url)
    private fun snapshot(t: Topic = topic, m: Message = message, s: Server = server) = Snapshot(s, listOf(t), listOf(m))

    @Test fun defaultsAndLegacyMigrationNeverEnablePersistentAlerts() {
        assertFalse(Topic("new").persistentAlert)
        assertEquals(2, Topic("new").vibrationVersion)
        val legacy = JSONObject().put("url", server.url).put("enabled", true)
            .put("topics", JSONArray().put(JSONObject().put("name", topic.name)))
        val migrated = SnapshotCodec.decode(legacy).topics.single()
        assertFalse(migrated.persistentAlert)
        assertEquals(1, migrated.vibrationVersion)
        assertEquals(migrated, SnapshotCodec.decode(SnapshotCodec.encode(snapshot(migrated))).topics.single())
    }
    @Test fun topicAlertPreferencesRoundTripIndependentlyByServer() {
        val other = server.copy(url = "https://two.example")
        val original = snapshot().copy(servers = listOf(server, other), topics = listOf(topic, topic.copy(server = other.url, persistentAlert = false, vibrationVersion = 1)))
        assertEquals(original, SnapshotCodec.decode(SnapshotCodec.encode(original)))
    }
    @Test fun highAndMaxHaveDifferentFiniteVibrationPatterns() {
        assertNull(PriorityAlerts.vibration(3))
        assertArrayEquals(longArrayOf(0, 250, 150, 250), PriorityAlerts.vibration(4))
        val max = PriorityAlerts.vibration(5)!!
        assertEquals(3500L, max.sum())
        assertTrue(max.sum() > PriorityAlerts.vibration(4)!!.sum())
    }
    @Test fun persistentAlertRequiresOptInHighestPriorityAndLiveTimestamp() {
        assertTrue(PriorityAlerts.persistent(snapshot(), message, true, 100))
        assertFalse(PriorityAlerts.persistent(snapshot(topic.copy(persistentAlert = false)), message, true, 100))
        assertFalse(PriorityAlerts.persistent(snapshot(), message.copy(priority = 4), true, 100))
        assertFalse(PriorityAlerts.persistent(snapshot(), message, false, 100))
        assertFalse(PriorityAlerts.persistent(snapshot(), message, true, null))
        assertFalse(PriorityAlerts.persistent(snapshot(), message, true, 101))
        assertFalse(PriorityAlerts.persistent(snapshot(), message, true, 102))
    }
    @Test fun readMuteRemovalAndServerPauseMakeAlertIneligible() {
        assertTrue(PriorityAlerts.eligible(snapshot(), message))
        assertFalse(PriorityAlerts.eligible(snapshot(), message.copy(read = true)))
        assertFalse(PriorityAlerts.eligible(snapshot(topic.copy(muted = true)), message))
        assertFalse(PriorityAlerts.eligible(snapshot().copy(topics = emptyList()), message))
        assertFalse(PriorityAlerts.eligible(snapshot(s = server.copy(enabled = false)), message))
        assertFalse(PriorityAlerts.eligible(snapshot(s = server.copy(lockedToken = "encrypted")), message))
        assertFalse(PriorityAlerts.eligible(snapshot().copy(servers = emptyList()), message))
    }
    @Test fun filtersAndJoinTimeStillApplyToHighestPriority() {
        assertFalse(PriorityAlerts.eligible(snapshot(topic.copy(joined = 102)), message))
        assertFalse(PriorityAlerts.eligible(snapshot(topic.copy(notifyTags = "warning")), message))
        assertTrue(PriorityAlerts.eligible(snapshot(topic.copy(notifyTags = "warning")), message.copy(tags = listOf("warning"))))
        assertFalse(PriorityAlerts.eligible(snapshot(), message.copy(priority = 1)))
        assertFalse(PriorityAlerts.eligible(snapshot(), message.copy(event = "message_clear")))
    }
    @Test fun oneServersTopicCannotEnableAnotherServersAlert() {
        val other = server.copy(url = "https://two.example")
        val both = snapshot().copy(servers = listOf(server, other), topics = listOf(topic, topic.copy(server = other.url, persistentAlert = false)))
        assertTrue(PriorityAlerts.persistent(both, message, true, 100))
        assertFalse(PriorityAlerts.persistent(both, message.copy(server = other.url), true, 100))
    }
    @Test fun onlyValidOpenEventsProvideServerWatermark() {
        assertEquals(100L, PriorityAlerts.openTime("{\"event\":\"open\",\"time\":100}"))
        assertNull(PriorityAlerts.openTime("{\"event\":\"keepalive\",\"time\":100}"))
        assertNull(PriorityAlerts.openTime("{\"event\":\"open\",\"time\":0}"))
        assertNull(PriorityAlerts.openTime("not json"))
    }
}
