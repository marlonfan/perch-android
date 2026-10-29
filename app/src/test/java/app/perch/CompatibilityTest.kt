package app.perch

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompatibilityTest {
    private val server = Server("https://one.example/prefix", "secret", true)
    private fun message(id: String = "first", event: String = "message", time: Long = 100, topic: String = "alerts", origin: String = server.url) = Message(id, topic, "Title", "Body", time, server = origin, sequenceId = "incident", event = event)
    private fun empty() = Snapshot(server, listOf(Topic("alerts", server = server.url)), emptyList())

    @Test fun parsesCompleteMessageAndIgnoresOnlyMalformedActions() {
        val json = JSONObject().put("event", "message").put("id", "one").put("time", 10).put("topic", "alerts").put("message", "Hello").put("tags", JSONArray(listOf("warning", "custom"))).put("click", "https://example.org/order").put("icon", "https://example.org/icon.png").put("sequence_id", "incident")
            .put("attachment", JSONObject().put("url", "https://example.org/file").put("name", "report.pdf").put("size", 123).put("type", "application/pdf").put("expires", 200))
            .put("actions", JSONArray().put(JSONObject().put("action", "http").put("label", "确认").put("url", "https://api.example/ack").put("clear", true).put("headers", JSONObject().put("X-Token", "secret"))).put(JSONObject().put("action", "unknown").put("label", "Future action")))
        val m = Protocol.parse(json.toString(), server.url)!!
        assertEquals(listOf("warning", "custom"), m.tags)
        assertEquals("https://example.org/order", m.click); assertEquals("incident", m.sequenceId)
        assertEquals("report.pdf", m.attachment!!.name); assertEquals(123L, m.attachment.size)
        assertEquals(1, m.actions.size); assertEquals("POST", m.actions.single().method); assertTrue(m.actions.single().clear)
        assertEquals("secret", m.actions.single().headers["X-Token"])
    }
    @Test fun allActionKindsRoundTripWithPayloadAndProgress() {
        val actions = listOf(MessageAction("v", "view", "查看", url = "geo:0,0?q=Shanghai"), MessageAction("h", "http", "确认", url = "https://api.example/ack", method = "PUT", headers = mapOf("Authorization" to "secret"), body = "ok", clear = true), MessageAction("c", "copy", "复制", value = "123456"), MessageAction("b", "broadcast", "自动化", extras = mapOf("cmd" to "test")))
        val m = message().copy(tags = listOf("warning"), actions = actions, click = "https://example.org", icon = "https://example.org/icon", attachment = Attachment("file", "https://example.org/file"))
        assertEquals(m, MessageCodec.decode(MessageCodec.encode(m)))
    }
    @Test fun legacySnapshotPreservesTokensMessagesSubscriptionsAndDeletions() {
        val legacy = JSONObject().put("url", server.url).put("enabled", true).put("topics", JSONArray().put(JSONObject().put("name", "alerts").put("joined", 50))).put("messages", JSONArray().put(MessageCodec.encode(message()))).put("deleted", MessageArchive.encodeDeleted(setOf(MessageKey(server.url, "deleted"))))
        val migrated = SnapshotCodec.decode(legacy, "old-token")
        assertEquals("old-token", migrated.server.token); assertEquals(1, migrated.servers.size)
        assertEquals(server.url, migrated.topics.single().server); assertEquals(50L, migrated.topics.single().joined)
        assertEquals(listOf(message()), migrated.messages); assertTrue(MessageKey(server.url, "deleted") in migrated.deleted)
        assertEquals(migrated, SnapshotCodec.decode(SnapshotCodec.encode(migrated)))
    }
    @Test fun secretsUseEncryptionBoundaryAndServerIdentitySurvivesSelection() {
        val m = message().copy(actions = listOf(MessageAction("a", "http", "确认", url = "https://api.example", headers = mapOf("Authorization" to "action-secret"))))
        val s = empty().copy(servers = listOf(server, Server("https://two.example", "other-secret", true)), messages = listOf(m))
        val encrypted = SnapshotCodec.encode(s) { java.util.Base64.getEncoder().encodeToString(it.toByteArray()) }
        assertFalse(encrypted.toString().contains("action-secret")); assertFalse(encrypted.toString().contains("other-secret"))
        assertEquals(s, SnapshotCodec.decode(encrypted, decrypt = { String(java.util.Base64.getDecoder().decode(it)) }))
    }
    @Test fun interruptedActionBecomesRetryableWithoutAutomaticExecution() {
        val s = empty().copy(messages = listOf(message().copy(actions = listOf(MessageAction("c", "copy", "复制", state = "running")))))
        val a = SnapshotCodec.decode(SnapshotCodec.encode(s)).messages.single().actions.single()
        assertEquals("failed", a.state); assertTrue(a.error.contains("中断"))
    }
    @Test fun updatesReplaceOnlySameServerAndTopicSequence() {
        val other = message("other", origin = "https://two.example")
        val anotherTopic = message("another", topic = "other")
        var s = MessageArchive.apply(empty(), message())
        s = MessageArchive.apply(s, other); s = MessageArchive.apply(s, anotherTopic)
        s = MessageArchive.apply(s, message("update", time = 101))
        assertEquals(setOf("other", "another", "update"), s.messages.map { it.id }.toSet())
        assertEquals(s, MessageArchive.apply(s, message()))
    }
    @Test fun clearAndDeleteStayEffectiveAfterRestartAndSameSecondReplay() {
        var s = MessageArchive.apply(empty(), message())
        s = MessageArchive.apply(s, message("clear", "message_clear"))
        assertTrue(s.messages.single().read)
        s = SnapshotCodec.decode(SnapshotCodec.encode(s))
        assertEquals(s, MessageArchive.apply(s, message()))
        s = MessageArchive.apply(s, message("delete", "message_delete"))
        assertTrue(s.messages.isEmpty())
        s = SnapshotCodec.decode(SnapshotCodec.encode(s))
        assertEquals(s, MessageArchive.apply(s, message()))
        assertEquals(s, MessageArchive.apply(s, message("clear", "message_clear")))
    }
    @Test fun olderOutOfOrderUpdatesDoNotReplaceLatest() {
        var s = MessageArchive.apply(empty(), message("new", time = 200))
        s = MessageArchive.apply(s, message("old", time = 100))
        assertEquals("new", s.messages.single().id)
    }
    @Test fun localDeletionAlsoSuppressesFutureUpdatesOfDeletedSequence() {
        var s = MessageArchive.apply(empty(), message())
        s = MessageArchive.delete(s, setOf(message().key))
        s = MessageArchive.apply(s, message("next", time = 101))
        s = SnapshotCodec.decode(SnapshotCodec.encode(s))
        s = MessageArchive.apply(s, message("next-again", time = 102))
        assertTrue(s.messages.isEmpty())
    }
    @Test fun filtersAffectNotificationsWithoutDroppingMessage() {
        val topic = Topic("alerts", minPriority = 4, notifyTags = "warning,fire")
        assertFalse(topic.shouldNotify(message().copy(priority = 3, tags = listOf("warning"))))
        assertFalse(topic.shouldNotify(message().copy(priority = 4)))
        assertTrue(topic.shouldNotify(message().copy(priority = 5, tags = listOf("fire"))))
        assertFalse(topic.copy(muted = true).shouldNotify(message().copy(priority = 5, tags = listOf("fire"))))
    }
    @Test fun actionsUseExplicitHeadersAndCorrectMethodsOnly() {
        val a = MessageAction("a", "http", "确认", url = "https://api.example/ack", headers = mapOf("X-Receipt" to "yes"), body = "ok")
        val request = ActionRequests.build(a)
        assertEquals("POST", request.method); assertEquals("yes", request.header("X-Receipt")); assertNull(request.header("Authorization"))
        assertNotNull(request.body); assertNull(ActionRequests.build(a.copy(method = "GET")).body)
        assertNull(ActionRequests.build(a.copy(method = "HEAD")).body)
    }
    @Test fun resourceAuthenticationCannotCrossOriginOrServerPrefix() {
        assertTrue(Protocol.canAuthenticate("https://one.example/prefix/file/image.png", server.url))
        listOf("https://two.example/prefix/file", "https://one.example.evil/prefix/file", "https://one.example/prefix-other/file", "http://one.example/prefix/file", "https://one.example:444/prefix/file", "https://one.example/prefix/../private/file").forEach { assertFalse(it, Protocol.canAuthenticate(it, server.url)) }
        assertFalse(Protocol.webUrl("file:///private/file")); assertFalse(Protocol.webUrl("https://user:password@example.org/file"))
    }
    @Test fun invalidOptionalAttachmentDoesNotDiscardText() {
        val m = Protocol.parse("""{"id":"1","topic":"alerts","event":"message","message":"hello","attachment":{"url":"file:///secret"}}""", server.url)!!
        assertEquals("hello", m.body); assertNull(m.attachment)
    }
}
