package app.perch

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

data class Topic(val name: String, val label: String = name, val muted: Boolean = false, val joined: Long = System.currentTimeMillis() / 1000, val server: String = "", val minPriority: Int = 1, val notifyTags: String = "", val allowBroadcast: Boolean = false)
data class Server(val url: String = "", val token: String = "", val enabled: Boolean = false)
data class Attachment(val name: String, val url: String, val type: String = "", val size: Long = 0, val expires: Long = 0)
data class MessageAction(val id: String, val action: String, val label: String, val url: String = "", val method: String = "POST", val headers: Map<String, String> = emptyMap(), val body: String = "", val clear: Boolean = false, val intent: String = "io.heckel.ntfy.USER_ACTION", val extras: Map<String, String> = emptyMap(), val value: String = "", val state: String = "", val error: String = "")
data class Message(val id: String, val topic: String, val title: String, val body: String, val time: Long, val priority: Int = 3, val read: Boolean = false, val server: String = "", val markdown: Boolean = true, val tags: List<String> = emptyList(), val click: String = "", val icon: String = "", val actions: List<MessageAction> = emptyList(), val attachment: Attachment? = null, val sequenceId: String = id, val event: String = "message")
data class MessageKey(val server: String, val id: String)
val Message.key get() = MessageKey(server, id)
val Message.notificationTag get() = JSONArray(listOf(server, topic, sequenceId)).toString()
data class SequenceState(val server: String, val topic: String, val sequenceId: String, val time: Long, val id: String, val event: String)
data class Snapshot(val server: Server, val topics: List<Topic>, val messages: List<Message>, val appearance: String = "system", val deleted: Set<MessageKey> = emptySet(), val servers: List<Server> = if (server.url.isBlank()) emptyList() else listOf(server), val processed: Set<MessageKey> = emptySet(), val sequences: List<SequenceState> = emptyList())
val Snapshot.activeTopics get() = topics.filter { it.server == server.url || it.server.isEmpty() }
fun Snapshot.topicFor(m: Message) = topics.find { it.name == m.topic && (it.server == m.server || it.server.isEmpty() && m.server == server.url) }
fun Topic.shouldNotify(m: Message) = !muted && m.priority >= minPriority && (notifyTags.isBlank() || notifyTags.split(',').map { it.trim() }.any { it in m.tags })

internal object MessageArchive {
    fun delete(snapshot: Snapshot, keys: Set<MessageKey>): Snapshot {
        val removed = snapshot.messages.filter { it.key in keys }.map { it.key }.toSet()
        return snapshot.copy(messages = snapshot.messages.filterNot { it.key in removed }, deleted = snapshot.deleted + removed)
    }
    fun accepts(snapshot: Snapshot, message: Message) = message.key !in snapshot.deleted && message.key !in snapshot.processed && snapshot.messages.none { it.key == message.key }
    fun apply(snapshot: Snapshot, message: Message): Snapshot {
        if (!accepts(snapshot, message)) return snapshot
        val previous = snapshot.sequences.find { it.server == message.server && it.topic == message.topic && it.sequenceId == message.sequenceId }
        val processed = snapshot.processed + message.key
        if (previous != null && message.time < previous.time) return snapshot.copy(processed = processed)
        val matching = snapshot.messages.filter { it.server == message.server && it.topic == message.topic && it.sequenceId == message.sequenceId }
        val locallyDeleted = (previous != null && MessageKey(previous.server, previous.id) in snapshot.deleted) || MessageKey(message.server, message.sequenceId) in snapshot.deleted
        val records = when (message.event) {
            "message_delete" -> snapshot.messages - matching.toSet()
            "message_clear" -> snapshot.messages.map { if (it in matching) it.copy(read = true) else it }
            else -> if (locallyDeleted) snapshot.messages else (snapshot.messages - matching.toSet() + message).sortedByDescending { it.time }.take(1000)
        }
        val sequence = SequenceState(message.server, message.topic, message.sequenceId, message.time, message.id, message.event)
        return snapshot.copy(messages = records, processed = processed, sequences = snapshot.sequences.filterNot { it.server == sequence.server && it.topic == sequence.topic && it.sequenceId == sequence.sequenceId } + sequence, deleted = if (locallyDeleted) snapshot.deleted + message.key else snapshot.deleted)
    }
    fun decodeDeleted(array: JSONArray?): Set<MessageKey> = array.objects().mapNotNull { item -> MessageKey(item.optString("server"), item.optString("id")).takeIf { it.id.isNotBlank() } }.toSet()
    fun encodeDeleted(keys: Set<MessageKey>) = JSONArray().apply { keys.forEach { put(JSONObject().put("server", it.server).put("id", it.id)) } }
}

internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).mapNotNull { opt(it) as? String }
private fun JSONObject.stringMap(key: String): Map<String, String> = optJSONObject(key)?.let { o -> o.keys().asSequence().mapNotNull { k -> (o.opt(k) as? String)?.let { k to it } }.toMap() } ?: emptyMap()

object Protocol {
    fun server(raw: String): String? {
        val u = raw.trim().trimEnd('/').toHttpUrlOrNull() ?: return null
        if (u.username.isNotEmpty() || u.password.isNotEmpty() || u.query != null || u.fragment != null) return null
        return u.toString().trimEnd('/')
    }
    fun topic(raw: String) = raw.matches(Regex("[A-Za-z0-9_-]{1,64}")) && raw !in setOf("docs", "static", "file", "app", "account", "settings", "v1")
    fun webUrl(raw: String): Boolean = raw.toHttpUrlOrNull()?.let { it.username.isEmpty() && it.password.isEmpty() } == true
    fun canAuthenticate(resource: String, server: String): Boolean {
        val target = resource.toHttpUrlOrNull() ?: return false
        val base = server.toHttpUrlOrNull() ?: return false
        return webUrl(resource) && target.scheme == base.scheme && target.host == base.host && target.port == base.port && target.encodedPath.startsWith(base.encodedPath.trimEnd('/') + "/")
    }
    fun action(o: JSONObject, index: Int): MessageAction? {
        val kind = o.optString("action")
        val label = o.optString("label")
        if (kind !in setOf("view", "http", "broadcast", "copy") || label.isBlank()) return null
        val url = o.optString("url")
        if (kind == "http" && !webUrl(url)) return null
        if (kind == "view" && url.isBlank()) return null
        return MessageAction(o.optString("id").ifBlank { "action-" + index }, kind, label, url, o.optString("method", "POST").uppercase(java.util.Locale.ROOT), o.stringMap("headers"), o.optString("body"), o.optBoolean("clear"), o.optString("intent", "io.heckel.ntfy.USER_ACTION"), o.stringMap("extras"), o.optString("value"), o.optString("state"), o.optString("error"))
    }
    fun parse(line: String, server: String): Message? {
        val o = JSONObject(line)
        if (o.optString("event") !in setOf("message", "message_clear", "message_delete")) return null
        if (o.optString("id").isBlank() || !topic(o.optString("topic"))) return null
        return MessageCodec.decode(o.put("server", server), wire = true)
    }
    fun preview(body: String) = body.replace(Regex("""```[\s\S]*?```"""), "[代码]").replace(Regex("[#!>*_`~|]"), "").replace(Regex("""\[([^]]+)]\([^)]+\)"""), "$1").replace(Regex("""\s+"""), " ").trim()
}

internal object MessageCodec {
    fun decode(o: JSONObject, wire: Boolean = false): Message {
        val a = o.optJSONObject("attachment")?.takeIf { Protocol.webUrl(it.optString("url")) }?.let { Attachment(it.optString("name", "附件"), it.getString("url"), it.optString("type"), it.optLong("size"), it.optLong("expires")) }
        val actions = o.optJSONArray("actions").objects().mapIndexedNotNull { i, action -> runCatching { Protocol.action(action, i) }.getOrNull() }.distinctBy { it.id }
        return Message(id = o.getString("id"), topic = o.getString("topic"), title = o.optString("title").ifBlank { "新消息" }, body = o.optString(if (wire) "message" else "body"), time = o.optLong("time"), priority = o.optInt("priority", 3).coerceIn(1, 5), read = o.optBoolean("read"), server = o.optString("server"), markdown = o.optBoolean("markdown", !wire) || o.optString("content_type").startsWith("text/markdown"), tags = o.optJSONArray("tags").strings(), click = o.optString("click"), icon = o.optString("icon"), actions = actions, attachment = a, sequenceId = o.optString("sequence_id").ifBlank { o.getString("id") }, event = o.optString("event", "message"))
    }
    fun actions(actions: List<MessageAction>) = JSONArray().apply { actions.forEach { a -> put(JSONObject().put("id", a.id).put("action", a.action).put("label", a.label).put("url", a.url).put("method", a.method).put("headers", JSONObject(a.headers)).put("body", a.body).put("clear", a.clear).put("intent", a.intent).put("extras", JSONObject(a.extras)).put("value", a.value).put("state", a.state).put("error", a.error)) } }
    fun encode(m: Message): JSONObject = JSONObject().put("id", m.id).put("topic", m.topic).put("title", m.title).put("body", m.body).put("time", m.time).put("priority", m.priority).put("read", m.read).put("server", m.server).put("markdown", m.markdown).put("tags", JSONArray(m.tags)).put("click", m.click).put("icon", m.icon).put("actions", actions(m.actions)).put("sequence_id", m.sequenceId).put("event", m.event).apply {
        m.attachment?.let { a -> put("attachment", JSONObject().put("name", a.name).put("url", a.url).put("type", a.type).put("size", a.size).put("expires", a.expires)) }
    }
}

internal object SnapshotCodec {
    fun decode(root: JSONObject, legacyToken: String = "", decrypt: (String) -> String = { it }): Snapshot {
        val legacy = Server(root.optString("url"), legacyToken, root.optBoolean("enabled"))
        val servers = root.optJSONArray("servers").objects().mapNotNull { o -> Protocol.server(o.optString("url"))?.let { Server(it, runCatching { decrypt(o.optString("token")) }.getOrDefault(""), o.optBoolean("enabled")) } }.distinctBy { it.url }.ifEmpty { if (legacy.url.isBlank()) emptyList() else listOf(legacy) }
        val selected = servers.find { it.url == legacy.url } ?: servers.firstOrNull() ?: Server()
        val topics = root.optJSONArray("topics").objects().mapNotNull { t -> t.optString("name").takeIf(Protocol::topic)?.let { Topic(it, t.optString("label").ifBlank { it }, t.optBoolean("muted"), t.optLong("joined"), t.optString("server").ifBlank { legacy.url }, t.optInt("min_priority", 1).coerceIn(1, 5), t.optString("notify_tags"), t.optBoolean("allow_broadcast")) } }
        val messages = root.optJSONArray("messages").objects().mapNotNull { m -> runCatching {
            if (m.has("protected_actions")) m.put("actions", runCatching { JSONArray(decrypt(m.getString("protected_actions"))) }.getOrDefault(JSONArray()))
            MessageCodec.decode(m).let { it.copy(actions = it.actions.map { a -> if (a.state == "running") a.copy(state = "failed", error = "操作被中断，请确认服务端结果后重试") else a }) }
        }.getOrNull() }
        val sequences = root.optJSONArray("sequences").objects().mapNotNull { o -> runCatching { SequenceState(o.getString("server"), o.getString("topic"), o.getString("sequence_id"), o.getLong("time"), o.getString("id"), o.getString("event")) }.getOrNull() }
        return Snapshot(selected, topics, messages, root.optString("appearance", "system"), MessageArchive.decodeDeleted(root.optJSONArray("deleted")), servers, MessageArchive.decodeDeleted(root.optJSONArray("processed")) + if (root.optInt("schema") < 2) messages.map { it.key }.toSet() else emptySet(), if (root.optInt("schema") < 2) messages.map { SequenceState(it.server, it.topic, it.sequenceId, it.time, it.id, it.event) } else sequences)
    }
    fun encode(s: Snapshot, encrypt: (String) -> String = { it }): JSONObject = JSONObject().put("schema", 2).put("url", s.server.url).put("enabled", s.server.enabled).put("appearance", s.appearance).apply {
        put("servers", JSONArray().apply { s.servers.forEach { put(JSONObject().put("url", it.url).put("enabled", it.enabled).put("token", encrypt(it.token))) } })
        put("topics", JSONArray().apply { s.topics.forEach { t -> put(JSONObject().put("name", t.name).put("label", t.label).put("muted", t.muted).put("joined", t.joined).put("server", t.server).put("min_priority", t.minPriority).put("notify_tags", t.notifyTags).put("allow_broadcast", t.allowBroadcast)) } })
        put("messages", JSONArray().apply { s.messages.forEach { m -> put(MessageCodec.encode(m).apply { if (m.actions.isNotEmpty()) { put("protected_actions", encrypt(MessageCodec.actions(m.actions).toString())); remove("actions") } }) } })
        put("deleted", MessageArchive.encodeDeleted(s.deleted)); put("processed", MessageArchive.encodeDeleted(s.processed))
        put("sequences", JSONArray().apply { s.sequences.forEach { put(JSONObject().put("server", it.server).put("topic", it.topic).put("sequence_id", it.sequenceId).put("time", it.time).put("id", it.id).put("event", it.event)) } })
    }
}
