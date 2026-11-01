package app.perch

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

internal object ActionRequests {
    fun build(action: MessageAction): Request {
        require(Protocol.webUrl(action.url)) { "操作地址无效" }
        val method = action.method.uppercase(java.util.Locale.ROOT)
        require(method in setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")) { "不支持此 HTTP 方法" }
        val body = if (method in setOf("GET", "HEAD")) null else action.body.toRequestBody()
        return Request.Builder().url(action.url).method(method, body).apply { action.headers.forEach { (name, value) -> header(name, value) } }.build()
    }
}

internal object Interactions {
    private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    fun launch(context: Context, m: Message, actionId: String? = null, click: Boolean = false) {
        context.startActivity(Intent(context, InteractionActivity::class.java).putExtra("message_id", m.id).putExtra("message_server", m.server).putExtra("action_id", actionId).putExtra("click", click).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun open(context: Context, url: String) {
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase().orEmpty()
        require(scheme.isNotBlank() && scheme !in setOf("file", "content", "intent", "javascript", "data", "perch-local")) { "不支持此链接类型" }
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun execute(context: Context, m: Message, a: MessageAction, finished: (String?) -> Unit) {
        val store = (context.applicationContext as PerchApp).store
        if (!store.action(m.key, a.id, "running")) { finished("操作正在执行或消息已失效"); return }
        if (a.action == "http") {
            scope.launch {
                val error = try {
                    client.newCall(ActionRequests.build(a)).execute().use { response -> if (!response.isSuccessful) error("HTTP " + response.code) }
                    null
                } catch (_: java.net.SocketTimeoutException) { "请求超时，结果未知，请确认后重试" }
                catch (e: Exception) { if (e is IllegalArgumentException) "操作参数无效" else if (e is IllegalStateException) e.message else "请求失败，请检查网络和操作接口" }
                complete(context, m, a, error)
                withContext(Dispatchers.Main) { finished(error) }
            }
        } else {
            val error = runCatching {
                when (a.action) {
                    "view" -> open(context, a.url)
                    "copy" -> { require(a.value.isNotEmpty()) { "没有可复制的内容" }; context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(a.label, a.value)) }
                    "broadcast" -> {
                        check(store.value.topicFor(m)?.allowBroadcast == true) { "请先在订阅设置中允许广播操作" }
                        require(a.intent.isNotBlank()) { "广播名称为空" }
                        context.sendBroadcast(Intent(a.intent).apply { a.extras.forEach { (name, value) -> putExtra(name, value) } })
                    }
                    else -> error("不支持此操作")
                }
            }.exceptionOrNull()?.let { if (it is android.content.ActivityNotFoundException) "未找到可打开链接的应用" else it.message ?: "操作失败" }
            complete(context, m, a, error); finished(error)
        }
    }
    private fun complete(context: Context, m: Message, a: MessageAction, error: String?) {
        val store = (context.applicationContext as PerchApp).store
        if (!store.action(m.key, a.id, if (error == null) "success" else "failed", error.orEmpty())) return
        val current = store.value.messages.find { it.key == m.key } ?: return
        if (error == null && a.clear) NotificationService.cancel(context, m)
        else if (!current.read) store.value.topicFor(current)?.let { NotificationService.notify(context, current, it, alert = false) }
    }
}

class InteractionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = (application as PerchApp).store
        val m = store.value.messages.find { it.id == intent.getStringExtra("message_id") && it.server == intent.getStringExtra("message_server") }
        if (m == null) { finish(); return }
        if (intent.getBooleanExtra("click", false)) {
            runCatching { Interactions.open(this, m.click) }.onSuccess { if (store.read(m.id, m.server)) NotificationService.cancel(this, m) }.onFailure { showDetail(m, "链接无法打开，请在消息详情重试") }
            finish(); return
        }
        val action = m.actions.find { it.id == intent.getStringExtra("action_id") }
        if (action == null) { finish(); return }
        if (savedInstanceState != null) { showDetail(m, "操作已中断，请先确认服务端结果再重试"); finish(); return }
        val progress = if (action.action == "http") android.app.AlertDialog.Builder(this).setMessage("正在执行“" + action.label + "”…").setCancelable(false).show() else null
        Interactions.execute(this, m, action) { error ->
            if (!isFinishing && !isDestroyed) {
                progress?.dismiss()
                if (error != null) showDetail(m, error)
                finish()
            }
        }
    }
    private fun showDetail(m: Message, error: String) {
        startActivity(Intent(this, MainActivity::class.java).putExtra("message_id", m.id).putExtra("message_server", m.server).putExtra("interaction_error", error).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
}
