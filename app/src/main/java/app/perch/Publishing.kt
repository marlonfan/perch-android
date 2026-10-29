@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.perch

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class IncomingShare(val text: String = "", val title: String = "", val uri: Uri? = null)
internal data class UploadInfo(val name: String, val size: Long)
internal fun uploadInfo(context: Context, uri: Uri): UploadInfo {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) return UploadInfo(cursor.getString(0) ?: "attachment", if (cursor.isNull(1)) -1 else cursor.getLong(1))
    }
    return UploadInfo("attachment", -1)
}
internal object Publisher {
    private val client = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
    suspend fun send(context: Context, server: Server, topic: String, title: String, message: String, priority: Int, tags: String, markdown: Boolean, attachment: String, file: Uri?) = withContext(Dispatchers.IO) {
        require(Protocol.topic(topic)) { "主题名无效" }
        require(file != null || message.isNotBlank() || attachment.isNotBlank()) { "请输入正文或添加附件" }
        require(attachment.isBlank() || Protocol.webUrl(attachment)) { "附件地址需为 HTTP / HTTPS" }
        val request = if (file == null) {
            val json = JSONObject().put("topic", topic).put("title", title).put("message", message).put("priority", priority).put("tags", JSONArray(tags.split(',').map { it.trim() }.filter { it.isNotEmpty() })).put("markdown", markdown)
            if (attachment.isNotBlank()) json.put("attach", attachment)
            Request.Builder().url(server.url + "/").post(json.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        } else {
            require(file.scheme == "content") { "只能上传通过系统选择的文件" }
            val info = uploadInfo(context, file)
            require(info.size <= Resources.MAX_FILE) { "上传文件不能超过 25 MB" }
            val url = (server.url + "/" + topic).toHttpUrl().newBuilder().addQueryParameter("title", title).addQueryParameter("message", message).addQueryParameter("priority", priority.toString()).addQueryParameter("tags", tags).addQueryParameter("markdown", if (markdown) "yes" else "no").addQueryParameter("filename", info.name).build()
            val requestBody = object : RequestBody() {
                override fun contentType() = (context.contentResolver.getType(file) ?: "application/octet-stream").toMediaType()
                override fun contentLength() = info.size
                override fun writeTo(sink: BufferedSink) {
                    context.contentResolver.openInputStream(file)?.use { input ->
                        val buffer = ByteArray(8192); var total = 0L
                        while (true) { val count = input.read(buffer); if (count < 0) break; total += count; require(total <= Resources.MAX_FILE) { "上传文件不能超过 25 MB" }; sink.write(buffer, 0, count) }
                    } ?: error("文件已不可读，请重新选择")
                }
            }
            Request.Builder().url(url).put(requestBody)
        }
        if (server.token.isNotBlank()) request.header("Authorization", "Bearer " + server.token)
        client.newCall(request.build()).execute().use { response -> check(response.isSuccessful) { "HTTP " + response.code } }
    }
}

@Composable internal fun PublishDialog(s: Snapshot, initial: IncomingShare = IncomingShare(), dismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var serverUrl by rememberSaveable { mutableStateOf(s.server.url) }
    val server = s.servers.find { it.url == serverUrl } ?: s.server
    val topics = s.topics.filter { it.server == server.url }
    var topic by rememberSaveable(server.url) { mutableStateOf(topics.firstOrNull()?.name.orEmpty()) }
    var title by rememberSaveable { mutableStateOf(initial.title) }
    var body by rememberSaveable { mutableStateOf(initial.text) }
    var priority by rememberSaveable { mutableIntStateOf(3) }
    var tags by rememberSaveable { mutableStateOf("") }
    var markdown by rememberSaveable { mutableStateOf(false) }
    var attachment by rememberSaveable { mutableStateOf("") }
    var file by rememberSaveable { mutableStateOf(initial.uri?.toString()) }
    var error by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) { file = uri.toString(); attachment = "" } }
    Dialog(onDismissRequest = { if (!sending) dismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !sending, dismissOnClickOutside = false)) {
        Scaffold(topBar = { TopAppBar(title = { Text(if (sent) "发送成功" else "发送消息") }, navigationIcon = { IconButton(onClick = dismiss, enabled = !sending) { Icon(Icons.Outlined.Close, "关闭发送") } }) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (sent) {
                    Text("已发送至 " + server.url + "/" + topic)
                    Button(onClick = dismiss) { Text("完成") }
                } else {
                    Text("发送到", style = MaterialTheme.typography.titleMedium)
                    s.servers.forEach { item -> FilterChip(selected = item.url == server.url, onClick = { if (!sending) serverUrl = item.url }, label = { Text(item.url) }) }
                    OutlinedTextField(topic, { topic = it }, enabled = !sending, label = { Text("主题名") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    if (topics.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { topics.forEach { t -> SuggestionChip(onClick = { if (!sending) topic = t.name }, label = { Text(t.label) }) } }
                    OutlinedTextField(title, { title = it }, enabled = !sending, label = { Text("标题（可选）") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(body, { body = it }, enabled = !sending, label = { Text("正文") }, minLines = 4, modifier = Modifier.fillMaxWidth())
                    Row { Checkbox(markdown, { markdown = it }, enabled = !sending); Text("Markdown", Modifier.padding(top = 12.dp)) }
                    Text("优先级：" + priority)
                    Slider(priority.toFloat(), { priority = it.toInt() }, valueRange = 1f..5f, steps = 3, enabled = !sending)
                    OutlinedTextField(tags, { tags = it }, enabled = !sending, label = { Text("标签，用逗号分隔") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(attachment, { attachment = it; file = null }, enabled = !sending, label = { Text("附件 URL（可选）") }, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !sending) { Icon(Icons.Outlined.AttachFile, null); Text("选择文件（最多 25 MB）") }
                    file?.let { path ->
                        val info = remember(path) { runCatching { uploadInfo(context, Uri.parse(path)).name }.getOrDefault("附件") }
                        Text(info); TextButton(onClick = { file = null }, enabled = !sending) { Text("移除附件") }
                    }
                    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                    Button(enabled = !sending && server.url.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
                        sending = true; error = ""
                        scope.launch {
                            val result = runCatching { Publisher.send(context, server, topic.trim(), title, body, priority, tags, markdown, attachment.trim(), file?.let(Uri::parse)) }
                            sending = false
                            if (result.isSuccess) sent = true else error = "发送失败：" + (result.exceptionOrNull()?.message ?: "请检查连接与发布权限") + "。网络中断时结果可能未知，请确认后再重试。"
                        }
                    }) { Text(if (sending) "正在发送…" else "发送至此主题") }
                }
            }
        }
    }
}
