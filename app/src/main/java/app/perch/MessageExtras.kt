package app.perch

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

@Composable internal fun MessageExtras(m: Message) {
    val context = LocalContext.current
    var feedback by remember(m.key) { mutableStateOf("") }
    val labels = Tags.labels(m.tags)
    val showMessageLink = m.click.isNotBlank() && m.actions.none { it.action == "view" && it.url == m.click }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        if (m.lockedActions.isNotBlank()) Text("操作凭证无法解密，原始加密数据已保留，暂时无法执行按钮。", color = MaterialTheme.colorScheme.error)
        if (labels.isNotEmpty()) Text(labels.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        m.attachment?.let { AttachmentCard(m, it) }
        if (showMessageLink || m.actions.isNotEmpty()) {
            Text("相关操作", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column {
                    if (showMessageLink) MessageActionRow("打开消息链接", Icons.Outlined.Link, linkHost(m.click), external = true) {
                        feedback = ""
                        runCatching { Interactions.open(context, m.click) }.onFailure { feedback = "链接无法打开，请检查地址或安装对应应用" }
                    }
                    m.actions.forEachIndexed { index, action ->
                        if (showMessageLink || index > 0) HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        val icon = when (action.action) {
                            "view" -> Icons.Outlined.Link
                            "http" -> Icons.Outlined.Send
                            "copy" -> Icons.Outlined.ContentCopy
                            else -> Icons.Outlined.Sensors
                        }
                        val detail = when (action.state) {
                            "running" -> "正在执行…"
                            "success" -> "操作成功"
                            "failed" -> action.error.ifBlank { "执行失败" } + " · 点按重试"
                            else -> when (action.action) {
                                "view" -> linkHost(action.url)
                                "http" -> action.method + " · " + linkHost(action.url)
                                "copy" -> if (action.value.isEmpty()) "没有可复制的内容" else "复制到剪贴板"
                                else -> "发送 Android 广播"
                            }
                        }
                        MessageActionRow(action.label, icon, detail, action.state, enabled = action.state != "running" && (action.action != "copy" || action.value.isNotEmpty()), external = action.action == "view") {
                            feedback = ""
                            Interactions.execute(context, m, action) { feedback = it.orEmpty() }
                        }
                    }
                }
            }
        }
        if (feedback.isNotBlank()) Text(feedback, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

private fun linkHost(url: String): String = runCatching { Uri.parse(url).host }.getOrNull().orEmpty().ifBlank { "在其他应用中打开" }

@Composable private fun MessageActionRow(label: String, icon: ImageVector, detail: String, state: String = "", enabled: Boolean = true, external: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val accent = if (state == "failed") colors.error else if (!enabled && state != "running") colors.onSurface.copy(alpha = .38f) else colors.primary
    Surface(onClick = onClick, enabled = enabled, color = colors.surfaceContainerLow, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).semantics { role = Role.Button }) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, Modifier.size(20.dp), tint = accent)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(label, fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium, color = if (!enabled && state != "running") colors.onSurface.copy(alpha = .38f) else colors.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(detail, fontSize = 11.sp, lineHeight = 17.sp, color = if (state == "failed") colors.error else colors.onSurfaceVariant, maxLines = if (state == "failed") Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
            }
            if (state == "running") CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(when (state) { "success" -> Icons.Outlined.Check; "failed" -> Icons.Outlined.Refresh; else -> if (external) Icons.Outlined.OpenInNew else Icons.Outlined.ChevronRight }, null, Modifier.size(18.dp), tint = if (state == "success" || state == "failed") accent else colors.onSurfaceVariant)
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
            if (file == null) OutlinedButton(shape = RoundedCornerShape(10.dp), modifier = Modifier.heightIn(min = 48.dp), enabled = !busy && !expired, onClick = {
                busy = true; error = ""
                scope.launch {
                    val result = runCatching { Resources.download(context, m, a) }
                    file = result.getOrNull(); error = result.exceptionOrNull()?.message.orEmpty()
                    if (file != null) bitmap = withContext(Dispatchers.IO) { Resources.bitmap(file!!) }
                    busy = false
                }
            }) { Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (busy) "正在下载…" else "下载附件") }
            if (file != null) {
                Text("已下载至应用缓存，可离线打开", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(shape = RoundedCornerShape(10.dp), modifier = Modifier.heightIn(min = 48.dp), onClick = { runCatching { Resources.open(context, file!!, Resources.type(a)) }.onFailure { error = "未找到可打开此文件的应用" } }) { Icon(Icons.Outlined.OpenInNew, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("打开") }
                    OutlinedButton(shape = RoundedCornerShape(10.dp), modifier = Modifier.heightIn(min = 48.dp), onClick = { runCatching { Resources.open(context, file!!, Resources.type(a), share = true) }.onFailure { error = "无法分享此文件" } }) { Icon(Icons.Outlined.Share, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("分享") }
                    if (Resources.type(a).startsWith("image/") || bitmap != null) OutlinedButton(shape = RoundedCornerShape(10.dp), modifier = Modifier.heightIn(min = 48.dp), onClick = { scope.launch { bitmap = withContext(Dispatchers.IO) { Resources.bitmap(file!!) }; if (bitmap == null) error = "无法解码此图片" } }) { Icon(Icons.Outlined.Image, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("预览") }
                }
            }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable internal fun ServerManager(s: Snapshot, store: Store, dismiss: () -> Unit, saved: () -> Unit) {
    var editing by remember { mutableStateOf<Server?>(if (s.servers.isEmpty()) Server() else null) }
    var removing by remember { mutableStateOf<Server?>(null) }
    var removalError by remember { mutableStateOf("") }
    val context = LocalContext.current
    if (editing != null) {
        ServerDialog(editing!!, { editing = null; if (s.servers.isEmpty()) dismiss() }) { server ->
            val existing = s.servers.any { it.url == server.url }
            val success = if (existing) store.updateServer(server) else store.server(server)
            if (success) { editing = null; if (existing) { NotificationService.sync(context); dismiss() } else saved() }
        }
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
                            TextButton(onClick = { removalError = ""; removing = server }) { Text("移除") }
                        }
                    }
                }
            }
            OutlinedButton(onClick = { editing = Server() }) { Text("添加服务") }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("完成") } })
    removing?.let { server -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("移除服务？") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("停止此服务的接收并移除订阅。本地消息保留，重新添加服务和主题后可查看。")
        if (removalError.isNotBlank()) Text(removalError, color = MaterialTheme.colorScheme.error)
    } }, confirmButton = { TextButton(onClick = {
        // Store reconciles notifications only after persistence succeeds. A failed removal
        // leaves reception, alerts and the confirmation in place so the user can retry.
        if (store.removeServer(server.url)) {
            NotificationService.sync(context)
            removing = null
        } else removalError = store.storageError.value.ifBlank { "移除失败，请稍后重试。" }
    }) { Text("移除") } }, dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } }) }
}
