package app.perch

import android.app.Application
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class PerchApp : Application() {
    lateinit var store: Store
    override fun onCreate() { super.onCreate(); store = Store(this) }
}
data class Topic(val name: String, val label: String = name, val muted: Boolean = false, val joined: Long = System.currentTimeMillis() / 1000)
data class Server(val url: String = "", val token: String = "", val enabled: Boolean = false)
data class Message(val id: String, val topic: String, val title: String, val body: String, val time: Long, val priority: Int = 3, val read: Boolean = false, val server: String = "", val markdown: Boolean = true, val tags: List<String> = emptyList())
data class Snapshot(val server: Server, val topics: List<Topic>, val messages: List<Message>, val appearance: String = "system")

object Protocol {
    fun server(raw: String): String? {
        val u = raw.trim().trimEnd('/').toHttpUrlOrNull() ?: return null
        if (u.username.isNotEmpty() || u.password.isNotEmpty() || u.query != null || u.fragment != null) return null
        return u.toString().trimEnd('/')
    }
    fun topic(raw: String) = raw.matches(Regex("[A-Za-z0-9_-]{1,64}")) && raw !in setOf("docs", "static", "file", "app", "account", "settings", "v1")
    fun parse(line: String, server: String): Message? {
        val o = JSONObject(line)
        if (o.optString("event") != "message") return null
        val id = o.optString("id"); val topic = o.optString("topic")
        if (id.isBlank() || topic.isBlank()) return null
        val tags = o.optJSONArray("tags")
        return Message(id, topic, o.optString("title").ifBlank { "新消息" }, o.optString("message"), o.optLong("time"), o.optInt("priority", 3).coerceIn(1, 5), server = server, markdown = o.optBoolean("markdown", false) || o.optString("content_type").startsWith("text/markdown"), tags = if (tags == null) emptyList() else List(tags.length()) { tags.getString(it) })
    }
    fun preview(body: String) = body.replace(Regex("""```[\s\S]*?```"""), "[代码]").replace(Regex("[#!>*_`~|]"), "").replace(Regex("""\[([^]]+)]\([^)]+\)"""), "$1").replace(Regex("""\s+"""), " ").trim()
}

class Vault {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (ks.getKey("perch-token", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("perch-token", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun encrypt(value: String): String {
        if (value.isBlank()) return ""
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(c.iv + c.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    fun decrypt(value: String): String {
        if (value.isBlank()) return ""
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return c.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }
}

class Store(context: Context) {
    private val prefs = context.getSharedPreferences("perch", Context.MODE_PRIVATE)
    private val vault = Vault()
    private val state = MutableStateFlow(load())
    val flow = state.asStateFlow()
    val status = MutableStateFlow("尚未连接")
    val value get() = state.value
    private fun load(): Snapshot {
        val root = runCatching { JSONObject(prefs.getString("data", "{}")!!) }.getOrDefault(JSONObject())
        val topics = root.optJSONArray("topics") ?: JSONArray()
        val messages = root.optJSONArray("messages") ?: JSONArray()
        val token = runCatching { vault.decrypt(prefs.getString("token", "")!!) }.getOrDefault("")
        return Snapshot(Server(root.optString("url"), token, root.optBoolean("enabled")), List(topics.length()) { topics.getJSONObject(it).let { t -> Topic(t.getString("name"), t.optString("label"), t.optBoolean("muted"), t.optLong("joined")) } }, List(messages.length()) { messages.getJSONObject(it).let { m -> Message(m.getString("id"), m.getString("topic"), m.getString("title"), m.getString("body"), m.getLong("time"), m.optInt("priority", 3), m.optBoolean("read"), m.optString("server"), m.optBoolean("markdown", true)) } }, root.optString("appearance", "system"))
    }
    @Synchronized private fun update(change: (Snapshot) -> Snapshot) {
        val s = change(value)
        val root = JSONObject().put("url", s.server.url).put("enabled", s.server.enabled).put("appearance", s.appearance)
        root.put("topics", JSONArray().apply { s.topics.forEach { put(JSONObject().put("name", it.name).put("label", it.label).put("muted", it.muted).put("joined", it.joined)) } })
        root.put("messages", JSONArray().apply { s.messages.forEach { put(JSONObject().put("id", it.id).put("topic", it.topic).put("title", it.title).put("body", it.body).put("time", it.time).put("priority", it.priority).put("read", it.read).put("server", it.server).put("markdown", it.markdown)) } })
        prefs.edit().putString("data", root.toString()).apply()
        state.value = s
    }
    @Synchronized fun server(server: Server) {
        prefs.edit().putString("token", vault.encrypt(server.token)).apply()
        update { it.copy(server = server) }
    }
    fun enable(enabled: Boolean) = update { it.copy(server = it.server.copy(enabled = enabled)) }
    fun appearance(value: String) = update { it.copy(appearance = value) }
    fun topic(topic: Topic) = update { it.copy(topics = it.topics.filterNot { t -> t.name == topic.name } + topic) }
    fun removeTopic(name: String) = update { it.copy(topics = it.topics.filterNot { t -> t.name == name }) }
    fun read(id: String, server: String) = update { it.copy(messages = it.messages.map { m -> if (m.id == id && m.server == server) m.copy(read = true) else m }) }
    fun readAll() = update { it.copy(messages = it.messages.map { m -> if (m.server == it.server.url) m.copy(read = true) else m }) }
    @Synchronized fun insert(message: Message): Boolean {
        if (value.messages.any { it.server == message.server && it.id == message.id }) return false
        update { it.copy(messages = (it.messages + message).sortedByDescending { m -> m.time }.take(1000)) }
        return true
    }
    fun cursor(server: String, topic: String): Long = prefs.getLong("cursor:$server:$topic", 0)
    @Synchronized fun advance(server: String, topic: String, time: Long) {
        if (time > cursor(server, topic)) prefs.edit().putLong("cursor:$server:$topic", time).apply()
    }
}

object Demo {
    private val now = System.currentTimeMillis() / 1000
    val topics = listOf(Topic("studio", "工作室"), Topic("homelab", "自托管"), Topic("daily", "日常"))
    val messages = listOf(
        Message("demo-1", "studio", "新的想法，已经准备就绪。", "通知客户端的最新进展。\n\n## 项目进展\n\n- **通知客户端**：第一版界面已完成\n- **消息阅读**：支持 Markdown 排版\n- **下一步**：连接你的 ntfy 服务\n\n> 消息保存在本机，可离线阅读。\n\n### 接入只需一条命令\n\n```bash\ncurl -d 'Hello from Perch' \\\n  https://ntfy.sh/your-private-topic\n```\n\n### 一眼看清进度\n\n| 模块 | 状态 |\n| :--- | :--- |\n| 收件箱 | 已完成 |\n| 主题订阅 | 已完成 |\n| 消息推送 | 等待连接 |\n\n更多用法见 [ntfy 文档](https://docs.ntfy.sh)。", now - 180),
        Message("demo-2", "homelab", "服务器需要你看一眼", "NAS 存储空间使用率达到 **85%**。\n\n建议清理旧备份，或调整保留策略。\n\n> 当前仍有 240 GB 可用空间，服务运行正常。", now - 1260, 4),
        Message("demo-3", "studio", "部署完成，一切顺利", "**website / production**\n\n新版本已经上线，所有健康检查均已通过。\n\n```text\nBuild     passed\nDeploy    healthy\nDuration  42s\n```", now - 3540, read = true),
        Message("demo-4", "daily", "留一点时间，给生活。", "下午的事情都安排好了。合上电脑，出门走走吧。", now - 7200, read = true),
        Message("demo-5", "homelab", "昨晚的数据，已妥善保存", "每日备份已完成。\n\n- 新增备份：2.4 GB\n- 保留副本：7 份\n- 下次备份：明天 03:00", now - 86400, read = true)
    )
}
