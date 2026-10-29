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
    override fun onCreate() { super.onCreate(); Tags.load(this); store = Store(this) }
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
    val storageError = MutableStateFlow("")
    private val state = MutableStateFlow(load())
    val flow = state.asStateFlow()
    val status = MutableStateFlow("尚未连接")
    val connectionStatuses = MutableStateFlow<Map<String, String>>(emptyMap())
    val value get() = state.value
    private fun load(): Snapshot {
        val root = runCatching { JSONObject(prefs.getString("data", "{}")!!) }.getOrDefault(JSONObject())
        val sealed = prefs.getString("token", "").orEmpty()
        val token = runCatching { vault.decrypt(sealed) }
        return SnapshotCodec.decode(root, token.getOrDefault(""), vault::decrypt, if (token.isFailure) sealed else "").also {
            if (it.servers.any { s -> s.lockedToken.isNotBlank() } || it.messages.any { m -> m.lockedActions.isNotBlank() }) storageError.value = "部分凭证无法解密，原始加密数据已保留。请在消息服务中重新输入令牌；相关按钮暂不可用。"
        }
    }
    @Synchronized private fun update(change: (Snapshot) -> Snapshot): Boolean {
        val s = change(value)
        val root = try { SnapshotCodec.encode(s, vault::encrypt) } catch (_: Exception) {
            storageError.value = "加密保存失败，已保留原数据，请稍后重试或重新输入凭证。"
            return false
        }
        prefs.edit().putString("data", root.toString()).remove("token").apply()
        state.value = s
        if (s.servers.none { it.lockedToken.isNotBlank() } && s.messages.none { it.lockedActions.isNotBlank() }) storageError.value = ""
        return true
    }
    fun updateServer(server: Server) = update { s -> s.copy(servers = s.servers.map { if (it.url == server.url) server else it }, server = if (s.server.url == server.url) server else s.server) }
    fun server(server: Server) = update { it.copy(server = server, servers = it.servers.filterNot { saved -> saved.url == server.url } + server) }
    fun selectServer(url: String) = update { s -> s.servers.find { it.url == url }?.let { s.copy(server = it) } ?: s }
    fun removeServer(url: String) = update { s ->
        val servers = s.servers.filterNot { it.url == url }
        s.copy(servers = servers, server = if (s.server.url == url) servers.firstOrNull() ?: Server() else s.server, topics = s.topics.filterNot { it.server == url })
    }
    fun serverEnabled(url: String, enabled: Boolean) = update { s -> s.copy(servers = s.servers.map { if (it.url == url) it.copy(enabled = enabled) else it }, server = if (s.server.url == url) s.server.copy(enabled = enabled) else s.server) }
    fun enable(enabled: Boolean) = server(value.server.copy(enabled = enabled))
    fun appearance(value: String) = update { it.copy(appearance = value) }
    fun topic(topic: Topic) = update { s -> val owned = topic.copy(server = topic.server.ifBlank { s.server.url }); s.copy(topics = s.topics.filterNot { t -> t.name == owned.name && t.server == owned.server } + owned) }
    fun removeTopic(name: String) = update { s -> s.copy(topics = s.topics.filterNot { t -> t.name == name && t.server == s.server.url }) }
    fun read(id: String, server: String) = update { it.copy(messages = it.messages.map { m -> if (m.id == id && m.server == server) m.copy(read = true) else m }) }
    fun readAll() = update { it.copy(messages = it.messages.map { m -> if (m.server == it.server.url) m.copy(read = true) else m }) }
    fun delete(keys: Set<MessageKey>) = update { MessageArchive.delete(it, keys) }
    @Synchronized fun insert(message: Message): Boolean {
        if (!MessageArchive.accepts(value, message)) return false
        check(update { MessageArchive.apply(it, message) }) { "消息无法加密保存" }
        return value.messages.any { it.key == message.key }
    }
    @Synchronized fun action(key: MessageKey, actionId: String, state: String, error: String = ""): Boolean {
        val message = value.messages.find { it.key == key } ?: return false
        val action = message.actions.find { it.id == actionId } ?: return false
        if (state == "running" && action.state == "running") return false
        return update { s -> s.copy(messages = s.messages.map { m -> if (m.key != key) m else m.copy(read = m.read || state == "success" && action.clear, actions = m.actions.map { a -> if (a.id == actionId) a.copy(state = state, error = error) else a }) }) }
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
