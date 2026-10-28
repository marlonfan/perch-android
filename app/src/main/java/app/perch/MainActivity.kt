@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.perch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.TextStyle
import androidx.core.view.WindowCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import android.provider.Settings
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val Light = lightColorScheme(primary = Color(0xFF365C48), onPrimary = Color.White, primaryContainer = Color(0xFFE6EDDF), onPrimaryContainer = Color(0xFF294535), secondary = Color(0xFF6F7868), background = Color(0xFFF8F9F5), surface = Color(0xFFF8F9F5), surfaceContainer = Color(0xFFEEF0E9), surfaceContainerLow = Color(0xFFF1F3ED), surfaceContainerLowest = Color.White, surfaceContainerHigh = Color(0xFFE9ECE3), onSurface = Color(0xFF242D27), onSurfaceVariant = Color(0xFF72796F), outline = Color(0xFFB8BEB2), outlineVariant = Color(0xFFE5E9DF), error = Color(0xFFAD5140))
private val Dark = darkColorScheme(primary = Color(0xFFB3CEAD), onPrimary = Color(0xFF203823), primaryContainer = Color(0xFF314C37), onPrimaryContainer = Color(0xFFD6E6CB), secondary = Color(0xFFB8C4AE), background = Color(0xFF121813), surface = Color(0xFF121813), surfaceContainer = Color(0xFF202920), surfaceContainerLow = Color(0xFF1A221B), surfaceContainerLowest = Color(0xFF1A211B), surfaceContainerHigh = Color(0xFF2C362C), onSurface = Color(0xFFE1E7DC), onSurfaceVariant = Color(0xFFA3AF9E), outline = Color(0xFF687261), outlineVariant = Color(0xFF303B2F), error = Color(0xFFF0AE98))

class MainActivity : ComponentActivity() {
    private var target by mutableStateOf<Pair<String, String>?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge(); receive(intent)
        val store = (application as PerchApp).store
        setContent {
            val snapshot by store.flow.collectAsState()
            val dark = when (snapshot.appearance) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
            SideEffect { WindowCompat.getInsetsController(window, window.decorView).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark } }
            MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Typography(bodyLarge = TextStyle(fontSize = 14.sp, lineHeight = TextUnit.Unspecified))) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Perch(store, target) { target = null } }
            }
        }
        if (store.value.server.enabled) NotificationService.sync(this)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); receive(intent) }
    private fun receive(intent: Intent?) { target = intent?.getStringExtra("message_id")?.let { it to intent.getStringExtra("message_server").orEmpty() } }
}

@Composable private fun Perch(store: Store, target: Pair<String, String>?, consumeTarget: () -> Unit) {
    val s by store.flow.collectAsState()
    val context = LocalContext.current
    val status by store.status.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var opened by remember { mutableStateOf<Message?>(null) }
    var serverDialog by rememberSaveable { mutableStateOf(false) }
    var topicDialog by remember { mutableStateOf<Topic?>(null) }
    var addTopic by rememberSaveable { mutableStateOf(false) }
    var demoRead by rememberSaveable { mutableStateOf(listOf<String>()) }
    var demoDeleted by rememberSaveable { mutableStateOf(listOf<String>()) }
    var pendingDeletion by remember { mutableStateOf(emptyList<Message>()) }
    val demo = s.server.url.isBlank()
    val messages = if (demo) Demo.messages.filterNot { it.id in demoDeleted }.map { it.copy(read = it.read || it.id in demoRead) } else s.messages.filter { it.server == s.server.url && s.topics.any { t -> t.name == it.topic } }
    val topics = if (demo) Demo.topics else s.topics
    val unread = messages.count { !it.read }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        NotificationService.sync(context)
        if (!granted) scope.launch { snack.showSnackbar("通知权限未开启，消息仍会保存在收件箱。可在设置中开启提醒。") }
    }
    fun connect() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else NotificationService.sync(context)
    }
    fun open(m: Message) {
        opened = m.copy(read = true)
        if (demo) demoRead = demoRead + m.id else store.read(m.id, m.server)
        NotificationManagerCompat.from(context).cancel("${m.server}:${m.id}", 2)
    }
    LaunchedEffect(target, s.messages) {
        target?.let { key ->
            s.messages.find { it.id == key.first && it.server == key.second }?.let { open(it); consumeTarget() }
        }
    }
    BackHandler(opened != null) { opened = null }
    if (pendingDeletion.isNotEmpty()) AlertDialog(onDismissRequest = { pendingDeletion = emptyList() }, title = { Text(if (pendingDeletion.size == 1) "删除这条消息？" else "删除这 ${pendingDeletion.size} 条消息？") }, text = { Text("仅删除本机消息，不影响主题订阅或服务端记录。") }, confirmButton = { TextButton(onClick = {
        val keys = pendingDeletion.map { it.key }.toSet()
        if (demo) demoDeleted = (demoDeleted + pendingDeletion.map { it.id }).distinct() else store.delete(keys)
        keys.forEach { NotificationManagerCompat.from(context).cancel("${it.server}:${it.id}", 2) }
        if (opened?.key in keys) opened = null
        pendingDeletion = emptyList()
    }) { Text("删除", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { pendingDeletion = emptyList() }) { Text("取消") } })
    if (opened != null) { Detail(opened!!, topics.find { it.name == opened!!.topic }, { opened = null }, { pendingDeletion = listOf(opened!!) }); return }
    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Column(Modifier.navigationBarsPadding()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
                NavigationBar(modifier = Modifier.height(64.dp), windowInsets = WindowInsets(0, 0, 0, 0), containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                    listOf(Triple("收件箱", Icons.Outlined.Inbox, 0), Triple("订阅", Icons.Outlined.Layers, 1), Triple("设置", Icons.Outlined.Tune, 2)).forEach { (name, icon, index) ->
                        NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { if (index == 0 && unread > 0) BadgedBox(badge = { Badge(containerColor = MaterialTheme.colorScheme.primary) { Text(unread.toString()) } }) { Icon(icon, name) } else Icon(icon, name) }, alwaysShowLabel = false, colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer))
                    }
                }
            }
        }
    ) { padding ->
        AnimatedContent(targetState = tab, modifier = Modifier.padding(padding), label = "page") { selected ->
            when (selected) {
                0 -> Inbox(messages, topics, demo, status, { serverDialog = true }, ::open, { if (demo) demoRead = Demo.messages.map { it.id } else store.readAll() }, s.server.enabled, { tab = 2 }, { pendingDeletion = it })
                1 -> Subscriptions(topics, demo, s.server, status, { serverDialog = true }, { if (demo) serverDialog = true else addTopic = true }, { if (demo) serverDialog = true else topicDialog = it })
                else -> SettingsPage(s, status, demo, { serverDialog = true }, { enabled -> store.enable(enabled); if (enabled) connect() else NotificationService.sync(context) }, { store.appearance(it) }, { tab = 1 })
            }
        }
    }
    if (serverDialog) ServerDialog(s.server, { serverDialog = false }) { server ->
        runCatching { store.server(server.copy(enabled = true)) }.onSuccess {
            serverDialog = false
            if (s.topics.isEmpty()) { tab = 1; addTopic = true } else connect()
        }.onFailure { scope.launch { snack.showSnackbar("凭证保存失败，请重试。") } }
    }
    if (addTopic || topicDialog != null) TopicDialog(topicDialog, s.topics, { addTopic = false; topicDialog = null }, { topic ->
        store.topic(topic); addTopic = false; topicDialog = null; if (store.value.server.enabled) connect()
    }, { name -> store.removeTopic(name); topicDialog = null; NotificationService.sync(context) })
}

@Composable private fun PageTitle(title: String, action: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.3).sp)
        action()
    }
}
@Composable private fun Inbox(messages: List<Message>, topics: List<Topic>, demo: Boolean, status: String, configure: () -> Unit, open: (Message) -> Unit, readAll: () -> Unit, enabled: Boolean, settings: () -> Unit, delete: (List<Message>) -> Unit) {
    var onlyUnread by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<MessageKey>()) }
    val visible = messages.filter { (!onlyUnread || !it.read) && (filter.isEmpty() || it.topic == filter) }
    LaunchedEffect(messages.map { it.key }) {
        val retained = selected.intersect(messages.map { it.key }.toSet())
        if (selected.isNotEmpty() && retained.isEmpty()) selecting = false
        selected = retained
    }
    BackHandler(selecting) { selecting = false; selected = emptySet() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            if (demo) DemoBanner(configure) else if (!enabled || status != "已连接") ConnectionBanner(status, enabled, settings)
        }
        item {
            if (selecting) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { selecting = false; selected = emptySet() }) { Icon(Icons.Outlined.Close, "取消选择") }
                    Text(if (selected.isEmpty()) "选择消息" else "已选 ${selected.size} 条", Modifier.weight(1f).padding(start = 4.dp), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    IconButton(onClick = { selected = if (visible.all { it.key in selected }) emptySet() else visible.map { it.key }.toSet() }, enabled = visible.isNotEmpty()) { Icon(Icons.Outlined.SelectAll, "全选消息") }
                    IconButton(onClick = { delete(messages.filter { it.key in selected }) }, enabled = selected.isNotEmpty()) { Icon(Icons.Outlined.DeleteOutline, "删除所选消息", tint = if (selected.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error) }
                }
            } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    FilterPill("全部 ${messages.size}", !onlyUnread) { onlyUnread = false }
                    FilterPill("未读 ${messages.count { !it.read }}", onlyUnread) { onlyUnread = true }
                }
                Spacer(Modifier.weight(1f))
                Box {
                    TextButton(onClick = { menu = true }, contentPadding = PaddingValues(start = 8.dp)) { Text(topics.find { it.name == filter }?.label ?: "所有主题", fontSize = 12.sp, maxLines = 1); Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(16.dp)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("所有主题") }, onClick = { filter = ""; menu = false })
                        topics.forEach { topic -> DropdownMenuItem(text = { Text(topic.label) }, onClick = { filter = topic.name; menu = false }) }
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("全部标为已读") }, leadingIcon = { Icon(Icons.Outlined.DoneAll, null) }, enabled = messages.any { !it.read }, onClick = { readAll(); menu = false })
                        DropdownMenuItem(text = { Text("选择消息") }, leadingIcon = { Icon(Icons.Outlined.Checklist, null) }, enabled = visible.isNotEmpty(), onClick = { selecting = true; menu = false })
                    }
                }
            }
            }
        }
        if (visible.isEmpty()) item { EmptyState(if (onlyUnread) "暂时没有未读消息" else "让第一条消息，落在这里。", if (demo) "换个筛选条件，看看其他消息。" else "订阅主题后，发送到 ntfy 的消息会出现在这里。", Icons.Outlined.AllInbox) }
        val groups = visible.groupBy { dayLabel(it.time) }
        groups.forEach { (day, list) ->
            item { Row(Modifier.fillMaxWidth().padding(start = 26.dp, end = 26.dp, top = 2.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) { Text(day, fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(" · ${list.size} 条消息", fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .8f)) } }
            items(list, key = { it.server + it.id }) { m -> MessageCard(m, topics.find { it.name == m.topic }, selected = m.key in selected, onClick = { if (selecting) selected = if (m.key in selected) selected - m.key else selected + m.key else open(m) }, onLongClick = { selecting = true; selected = selected + m.key }) }
        }
    }
}
@Composable private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(20.dp), color = if (selected) MaterialTheme.colorScheme.surfaceContainerLowest else Color.Transparent, shadowElevation = if (selected) 1.dp else 0.dp) { Text(label, Modifier.padding(horizontal = 13.dp, vertical = 9.dp), fontSize = 13.sp, lineHeight = 20.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant) }
}
@Composable private fun DemoBanner(onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 20.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .7f)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CloudQueue, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Text("连接 ntfy", Modifier.weight(1f).padding(horizontal = 12.dp), fontSize = 14.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text("演示", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}
@Composable private fun ConnectionBanner(status: String, enabled: Boolean, onClick: () -> Unit) {
    val live = status == "已连接" && enabled
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 24.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(6.dp).clip(CircleShape).background(if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)); Text(if (enabled) status else "接收已暂停", Modifier.weight(1f).padding(start = 10.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Icon(if (live) Icons.Outlined.Wifi else Icons.Outlined.Info, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) } }
}
@Composable private fun TopicIcon(name: String, size: Int = 32) {
    val icon = when (name) { "homelab" -> Icons.Outlined.Dns; "daily" -> Icons.Outlined.WbSunny; else -> Icons.Outlined.Terminal }
    val c = when (name) { "homelab" -> MaterialTheme.colorScheme.secondary; "daily" -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.primary }
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size * .32f).dp)).background(c.copy(alpha = .09f)), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size((size * .5f).dp), tint = c) }
}
@Composable private fun MessageCard(m: Message, topic: Topic?, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp).clip(RoundedCornerShape(20.dp)).combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "选择消息"), shape = RoundedCornerShape(20.dp), color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .6f) else MaterialTheme.colorScheme.surfaceContainerLowest, border = BorderStroke(.75.dp, if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .5f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f))) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selected) Icon(Icons.Outlined.CheckCircle, "已选择", Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary) else TopicIcon(m.topic, 28)
                Text(topic?.label ?: m.topic, Modifier.weight(1f).padding(start = 9.dp), fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (m.priority >= 4) { Spacer(Modifier.width(8.dp)); Text("需要关注", Modifier.clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.error.copy(alpha = .07f)).padding(horizontal = 6.dp, vertical = 3.dp), fontSize = 10.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.error) }
                Text(timeLabel(m.time), Modifier.padding(start = 10.dp), fontSize = 11.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!m.read) Box(Modifier.padding(start = 8.dp).size(5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
            }
            Text(m.title, Modifier.padding(top = 12.dp), fontSize = 17.sp, lineHeight = 26.sp, fontWeight = if (m.read) FontWeight.Medium else FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(Protocol.preview(m.body), Modifier.padding(top = 8.dp), fontSize = 14.sp, lineHeight = 23.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
@Composable private fun EmptyState(title: String, subtitle: String, icon: ImageVector) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary) }
        Text(title, Modifier.padding(top = 22.dp), fontSize = 17.sp, fontWeight = FontWeight.Medium)
        Text(subtitle, Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 22.sp)
    }
}

@Composable private fun Detail(m: Message, topic: Topic?, back: () -> Unit, delete: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        TopAppBar(title = { Text("消息", fontSize = 14.sp) }, navigationIcon = { IconButton(onClick = back) { Icon(Icons.Outlined.ArrowBack, "返回") } }, actions = { IconButton(onClick = delete) { Icon(Icons.Outlined.DeleteOutline, "删除消息", Modifier.size(20.dp)) }; IconButton(onClick = { clipboard.setText(AnnotatedString(m.body)); copied = true }) { Icon(if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy, if (copied) "已复制" else "复制正文", Modifier.size(20.dp)) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
    }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(top = 8.dp, bottom = 40.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { TopicIcon(m.topic); Text(topic?.label ?: m.topic, Modifier.padding(start = 10.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium); Spacer(Modifier.weight(1f)); if (m.id.startsWith("demo-")) Text("演示消息", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            SelectionContainerCompat { Text(m.title, Modifier.padding(top = 18.dp), fontSize = 26.sp, lineHeight = 36.sp, letterSpacing = (-.4).sp, fontWeight = FontWeight.SemiBold) }
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Schedule, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Text("  " + SimpleDateFormat("M 月 d 日 · HH:mm", Locale.CHINA).format(Date(m.time * 1000)), fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); if (m.priority >= 4) Text("    /    高优先级", fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
            HorizontalDivider(Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .7f))
            MarkdownBody(m.body, m.markdown)
            HorizontalDivider(Modifier.padding(top = 36.dp, bottom = 18.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.CheckCircleOutline, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Text("  已保存在本机 · 可离线阅读", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
@Composable private fun SelectionContainerCompat(content: @Composable () -> Unit) { androidx.compose.foundation.text.selection.SelectionContainer { content() } }

@Composable private fun Subscriptions(topics: List<Topic>, demo: Boolean, server: Server, status: String, configure: () -> Unit, add: () -> Unit, edit: (Topic) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { PageTitle("订阅") { IconButton(onClick = add) { Icon(Icons.Outlined.Add, "添加订阅", tint = MaterialTheme.colorScheme.primary) } } }
        item {
            Surface(onClick = configure, modifier = Modifier.padding(24.dp).fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.CloudQueue, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary); Column(Modifier.weight(1f).padding(horizontal = 14.dp)) { Text(if (demo) "连接你的 ntfy" else server.url, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onPrimaryContainer); Text(if (demo) "沿用现有服务，无需迁移" else status, Modifier.padding(top = 6.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .7f)) }; Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.primary) }
            }
        }
        item { Text(if (demo) "演示主题  /  ${topics.size}" else "我的主题  /  ${topics.size}", Modifier.padding(start = 26.dp, bottom = 14.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp) }
        items(topics, key = { it.name }) { t ->
            Surface(onClick = { edit(t) }, modifier = Modifier.padding(horizontal = 20.dp, vertical = 5.dp).fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) { TopicIcon(t.name, 40); Column(Modifier.weight(1f).padding(horizontal = 14.dp)) { Text(t.label, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium); Text("# ${t.name}", Modifier.padding(top = 5.dp), fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace) }; Icon(if (t.muted) Icons.Outlined.NotificationsOff else Icons.Outlined.NotificationsNone, if (t.muted) "已静音" else "提醒已开启", Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        item { OutlinedButton(onClick = add, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp).height(50.dp), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("添加主题", fontSize = 13.sp) } }
        item { Text("主题是消息的投递地址。向同一主题发送消息，订阅它的设备就能收到通知。", Modifier.padding(horizontal = 28.dp), fontSize = 12.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable private fun SettingsPage(s: Snapshot, status: String, demo: Boolean, configure: () -> Unit, enable: (Boolean) -> Unit, appearance: (String) -> Unit, subscriptions: () -> Unit) {
    val context = LocalContext.current
    var appearanceDialog by remember { mutableStateOf(false) }
    var testStatus by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var permissionGranted by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    val power = context.getSystemService(PowerManager::class.java)
    var unrestricted by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    var powerSaving by remember { mutableStateOf(power.isPowerSaveMode) }
    var batteryDialog by remember { mutableStateOf(false) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) { permissionGranted = NotificationManagerCompat.from(context).areNotificationsEnabled(); unrestricted = power.isIgnoringBatteryOptimizations(context.packageName); powerSaving = power.isPowerSaveMode } }
        owner.lifecycle.addObserver(observer); onDispose { owner.lifecycle.removeObserver(observer) }
    }
    if (batteryDialog) AlertDialog(onDismissRequest = { batteryDialog = false }, title = { Text("锁屏接收") }, text = { Text("Android 可能在长时间锁屏后暂停网络。允许栖信不受电池优化限制，有助于持续接收，也会增加后台联网耗电。部分手机还需在系统中允许自启动。") }, confirmButton = { TextButton(onClick = {
        batteryDialog = false
        val intent = if (unrestricted) Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")) else Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        runCatching { context.startActivity(intent) }.onFailure { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }) { Text("去设置") } }, dismissButton = { TextButton(onClick = { batteryDialog = false }) { Text("取消") } })
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 36.dp)) {
        item { PageTitle("设置") }
        item { SectionLabel("连接与接收") }
        item { SettingsGroup {
            SettingRow(Icons.Outlined.CloudQueue, "消息服务", if (demo) "尚未配置 · 点击连接 ntfy" else s.server.url, configure)
            RowDivider()
            Row(Modifier.fillMaxWidth().clickable(enabled = !demo) { enable(!s.server.enabled) }.padding(20.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Sensors, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary); Column(Modifier.weight(1f).padding(horizontal = 14.dp)) { Text("后台接收", fontSize = 15.sp, lineHeight = 22.sp); Text(if (demo) "连接服务后开启" else status, Modifier.padding(top = 5.dp), fontSize = 12.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(checked = s.server.enabled && !demo, enabled = !demo, onCheckedChange = enable) }
            RowDivider(); SettingRow(Icons.Outlined.Layers, "订阅管理", if (demo) "添加你的第一个主题" else "${s.topics.size} 个主题", subscriptions)
        } }
        item { SectionLabel("通知与外观") }
        item { SettingsGroup {
            SettingRow(Icons.Outlined.NotificationsNone, "系统通知", if (permissionGranted) "已允许 · 管理提醒方式" else "未允许 · 点击开启", { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) })
            RowDivider(); SettingRow(Icons.Outlined.Palette, "外观", when (s.appearance) { "light" -> "浅色"; "dark" -> "深色"; else -> "跟随系统" }, { appearanceDialog = true })
            RowDivider(); SettingRow(Icons.Outlined.BatterySaver, "锁屏接收", (if (unrestricted) "已允许持续联网" else "可能受电池优化限制") + if (powerSaving) " · 省电模式开启" else "", { batteryDialog = true })
        } }
        if (!demo) item {
            SectionLabel("连接测试")
            SettingsGroup {
                SettingRow(Icons.Outlined.Send, if (testing) "正在发送…" else "发送测试通知", testStatus.ifBlank { "向第一个订阅主题发送一条测试消息" }, {
                    if (!testing) {
                        if (s.topics.isEmpty()) testStatus = "请先添加一个订阅主题"
                        else {
                            testing = true; testStatus = ""
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { runCatching {
                                    val req = Request.Builder().url(s.server.url + "/" + s.topics.first().name).header("Title", "Perch test").header("Markdown", "yes").apply { if (s.server.token.isNotBlank()) header("Authorization", "Bearer ${s.server.token}") }.post("这是一条来自 **栖信** 的测试通知。".toRequestBody()).build()
                                    OkHttpClient.Builder().callTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build().newCall(req).execute().use { if (!it.isSuccessful) error("HTTP ${it.code}") }
                                } }
                                testing = false; testStatus = if (result.isSuccess) "已发送，请查看收件箱与系统通知" else "发送失败：${result.exceptionOrNull()?.message?.take(80)}，请检查服务和发布权限"
                            }
                        }
                    }
                })
            }
        }
        item { Text("栖信 · ${BuildConfig.VERSION_NAME}", Modifier.fillMaxWidth().padding(top = 28.dp), fontSize = 12.sp, lineHeight = 18.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .7f)) }
    }
    if (appearanceDialog) AlertDialog(onDismissRequest = { appearanceDialog = false }, title = { Text("外观") }, text = { Column { listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (key, label) -> Row(Modifier.fillMaxWidth().clickable { appearance(key); appearanceDialog = false }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = s.appearance == key, onClick = { appearance(key); appearanceDialog = false }); Text(label) } } } }, confirmButton = { TextButton(onClick = { appearanceDialog = false }) { Text("完成") } })
}
@Composable private fun SectionLabel(text: String) { Text(text, Modifier.padding(start = 26.dp, top = 24.dp, bottom = 12.dp), fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) { Surface(Modifier.fillMaxWidth().padding(horizontal = 20.dp), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) { Column(content = content) } }
@Composable private fun RowDivider() { HorizontalDivider(Modifier.padding(start = 54.dp, end = 18.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .7f)) }
@Composable private fun SettingRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(20.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary); Column(Modifier.weight(1f).padding(horizontal = 14.dp)) { Text(title, fontSize = 15.sp, lineHeight = 22.sp); Text(subtitle, Modifier.padding(top = 5.dp), fontSize = 12.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis) }; Icon(Icons.Outlined.ChevronRight, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable private fun ServerDialog(server: Server, dismiss: () -> Unit, save: (Server) -> Unit) {
    var url by remember { mutableStateOf(server.url.ifBlank { "https://ntfy.sh" }) }
    var token by remember { mutableStateOf(server.token) }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, icon = { Icon(Icons.Outlined.CloudQueue, null) }, title = { Text("连接 ntfy") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("沿用你现有的服务。令牌通过 Android Keystore 加密保存在本机。", fontSize = 13.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value = url, onValueChange = { url = it; error = "" }, label = { Text("服务地址") }, placeholder = { Text("https://ntfy.example.com") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
        OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("访问令牌（可选）") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
        if (url.trim().startsWith("http://")) Text("HTTP 连接不会加密消息和令牌，建议使用 HTTPS。", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
    } }, confirmButton = { Button(onClick = { val normalized = Protocol.server(url); if (normalized == null) error = "请输入有效的 HTTP / HTTPS 地址，不包含账号、查询参数或片段。" else save(Server(normalized, token.trim(), true)) }) { Text("保存并连接") } }, dismissButton = { TextButton(onClick = dismiss) { Text("稍后") } })
}
@Composable private fun TopicDialog(topic: Topic?, topics: List<Topic>, dismiss: () -> Unit, save: (Topic) -> Unit, remove: (String) -> Unit) {
    var name by remember { mutableStateOf(topic?.name.orEmpty()) }
    var label by remember { mutableStateOf(topic?.label.orEmpty()) }
    var muted by remember { mutableStateOf(topic?.muted ?: false) }
    var error by remember { mutableStateOf("") }
    var confirmRemove by remember { mutableStateOf(false) }
    if (confirmRemove && topic != null) { AlertDialog(onDismissRequest = { confirmRemove = false }, title = { Text("移除订阅？") }, text = { Text("将停止接收「${topic.label}」的新消息。本地消息会保留，重新订阅后可继续查看。") }, confirmButton = { TextButton(onClick = { remove(topic.name) }) { Text("移除", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("取消") } }); return }
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (topic == null) "添加主题" else "编辑订阅") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("主题名应与发送消息时的地址一致。公共服务上的主题名建议足够随机，避免被他人猜到。", fontSize = 12.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value = name, onValueChange = { name = it; error = "" }, label = { Text("主题名") }, enabled = topic == null, singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("显示名称（可选）") }, singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("静音", fontSize = 14.sp); Text("保留消息，关闭该主题的系统提醒", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(checked = muted, onCheckedChange = { muted = it }) }
        if (topic != null) TextButton(onClick = { confirmRemove = true }) { Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.error); Text("  移除订阅", color = MaterialTheme.colorScheme.error) }
        if (error.isNotEmpty()) Text(error, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
    } }, confirmButton = { Button(onClick = { val n = name.trim(); when { !Protocol.topic(n) -> error = "主题名需为 1–64 位字母、数字、下划线或短横线，且不能是服务保留名称。"; topic == null && topics.any { it.name == n } -> error = "这个主题已经订阅过了。"; else -> save(Topic(n, label.trim().ifBlank { n }, muted, topic?.joined ?: System.currentTimeMillis() / 1000)) } }) { Text(if (topic == null) "订阅" else "保存") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}
private fun timeLabel(time: Long): String { val delta = System.currentTimeMillis() / 1000 - time; return when { delta < 60 -> "刚刚"; delta < 3600 -> "${delta / 60} 分钟前"; dayLabel(time) == "今天" -> SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(time * 1000)); else -> SimpleDateFormat("MM/dd", Locale.CHINA).format(Date(time * 1000)) } }
private fun dayLabel(time: Long): String { val fmt = SimpleDateFormat("yyyyMMdd", Locale.CHINA); val day = fmt.format(Date(time * 1000)); return when (day) { fmt.format(Date()) -> "今天"; fmt.format(Date(System.currentTimeMillis() - 86400000)) -> "昨天"; else -> SimpleDateFormat("M 月 d 日", Locale.CHINA).format(Date(time * 1000)) } }
