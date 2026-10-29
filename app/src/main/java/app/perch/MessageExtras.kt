package app.perch

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.io.File

@Composable internal fun MessageIcon(m: Message, size: Int = 32) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, m.server, m.icon) {
        if (Protocol.webUrl(m.icon)) value = runCatching { withContext(Dispatchers.IO) { Resources.bitmap(Resources.download(context, m, Attachment("icon", m.icon), Resources.MAX_IMAGE), 128) } }.getOrNull()
    }
    if (bitmap != null) Image(bitmap!!.asImageBitmap(), "消息图标", Modifier.size(size.dp).clip(RoundedCornerShape(8.dp)))
    else Icon(Icons.Outlined.NotificationsNone, null, Modifier.size(size.dp).padding(4.dp), tint = MaterialTheme.colorScheme.primary)
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable internal fun MessageExtras(m: Message) {
    val context = LocalContext.current
    var feedback by remember(m.key) { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        if (m.tags.isNotEmpty()) Text(m.tags.joinToString("  ") { Tags.display(it) }, color = MaterialTheme.colorScheme.primary)
        if (m.click.isNotBlank()) OutlinedButton(onClick = { runCatching { Interactions.open(context, m.click) }.onFailure { feedback = "链接无法打开，请检查地址或安装对应应用" } }) { Icon(Icons.Outlined.OpenInNew, null, Modifier.size(16.dp)); Text("  打开消息链接") }
        m.attachment?.let { AttachmentCard(m, it) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            m.actions.forEach { action ->
                OutlinedButton(enabled = action.state != "running", onClick = { feedback = ""; Interactions.execute(context, m, action) { feedback = it ?: "操作成功" } }) {
                    Text(action.label + when (action.state) { "running" -> " · 执行中"; "success" -> " ✓"; "failed" -> " · 重试"; else -> "" })
                }
            }
        }
        m.actions.filter { it.state == "failed" }.forEach { Text(it.label + "：" + it.error, color = MaterialTheme.colorScheme.error) }
        if (feedback.isNotBlank()) Text(feedback, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun AttachmentCard(m: Message, a: Attachment) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var file by remember(m.key, a.url) { mutableStateOf(Resources.cached(context, a.url, m.server, a.name).takeIf { it.isFile }) }
    var bitmap by remember(m.key, a.url) { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val expired = a.expires > 0 && a.expires <= System.currentTimeMillis() / 1000
    Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(a.name, style = MaterialTheme.typography.titleMedium)
            Text(listOf(a.type.ifBlank { "附件" }, if (a.size > 0) "%.1f MB".format(a.size / 1048576.0) else "大小未知", if (expired) "链接已过期" else if (a.expires > 0) "有效至 " + java.text.SimpleDateFormat("M/d HH:mm", java.util.Locale.CHINA).format(java.util.Date(a.expires * 1000)) else "").filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            bitmap?.let { Image(it.asImageBitmap(), "附件预览", Modifier.fillMaxWidth().heightIn(max = 360.dp)) }
            if (file == null) OutlinedButton(enabled = !busy && !expired, onClick = {
                busy = true; error = ""
                scope.launch {
                    val result = runCatching { Resources.download(context, m, a) }
                    file = result.getOrNull(); error = result.exceptionOrNull()?.message.orEmpty()
                    if (file != null) bitmap = withContext(Dispatchers.IO) { Resources.bitmap(file!!) }
                    busy = false
                }
            }) { Text(if (busy) "正在下载…" else "下载附件") }
            if (file != null) {
                Text("已下载至应用缓存，可离线打开", style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = { runCatching { Resources.open(context, file!!, Resources.type(a)) }.onFailure { error = "未找到可打开此文件的应用" } }) { Text("打开") }
                    TextButton(onClick = { runCatching { Resources.open(context, file!!, Resources.type(a), share = true) }.onFailure { error = "无法分享此文件" } }) { Text("分享") }
                    if (Resources.type(a).startsWith("image/") || bitmap != null) TextButton(onClick = { scope.launch { bitmap = withContext(Dispatchers.IO) { Resources.bitmap(file!!) }; if (bitmap == null) error = "无法解码此图片" } }) { Text("预览") }
                }
            }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable internal fun ServerManager(s: Snapshot, store: Store, dismiss: () -> Unit, saved: () -> Unit) {
    var editing by remember { mutableStateOf<Server?>(if (s.servers.isEmpty()) Server() else null) }
    var removing by remember { mutableStateOf<Server?>(null) }
    val context = LocalContext.current
    if (editing != null) {
        ServerDialog(editing!!, { editing = null; if (s.servers.isEmpty()) dismiss() }) { server -> store.server(server); editing = null; saved() }
        return
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text("消息服务") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("所有启用的服务都会在后台接收；选择服务可切换收件箱。")
            s.servers.forEach { server ->
                Surface(onClick = { store.selectServer(server.url); NotificationService.sync(context) }, shape = RoundedCornerShape(12.dp), color = if (server.url == s.server.url) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text((if (server.url == s.server.url) "✓ " else "") + server.url)
                        Row { Text(if (server.enabled) "接收已开启" else "接收已暂停", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); Switch(server.enabled, { store.serverEnabled(server.url, it); NotificationService.sync(context) }) }
                        Row {
                            TextButton(onClick = { editing = server }) { Text("编辑") }
                            TextButton(onClick = { removing = server }) { Text("移除") }
                        }
                    }
                }
            }
            OutlinedButton(onClick = { editing = Server() }) { Text("添加服务") }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("完成") } })
    removing?.let { server -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("移除服务？") }, text = { Text("停止此服务的接收并移除订阅。本地消息保留，重新添加服务和主题后可查看。") }, confirmButton = { TextButton(onClick = {
        s.messages.filter { it.server == server.url }.forEach { NotificationService.cancel(context, it) }; store.removeServer(server.url); NotificationService.sync(context); removing = null
    }) { Text("移除") } }, dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } }) }
}
