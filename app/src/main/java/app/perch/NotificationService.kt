package app.perch

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.net.ConnectivityManager
import android.net.Network
import android.net.LinkProperties
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import okhttp3.ConnectionPool
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class NotificationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val store get() = (application as PerchApp).store
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
    private val networkState = DefaultNetworkState<Network, LinkProperties>()
    private val resumeGeneration = MutableStateFlow(0)
    private lateinit var connectivity: ConnectivityManager
    private var registered = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(value: Network) { networkState.available(value) }
        override fun onLost(value: Network) { networkState.lost(value) }
        override fun onLinkPropertiesChanged(value: Network, properties: LinkProperties) {
            networkState.linkPropertiesChanged(value, properties)
        }
    }
    private val idleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!getSystemService(PowerManager::class.java).isDeviceIdleMode) resumeGeneration.value += 1
        }
    }
    private data class Connection(val server: Server, val names: List<String>)
    private sealed interface StreamEvent {
        data object Open : StreamEvent
        data class Line(val text: String) : StreamEvent
    }
    override fun onCreate() {
        super.onCreate()
        connectivity = getSystemService(ConnectivityManager::class.java)
        connectivity.registerDefaultNetworkCallback(callback)
        registered = true
        // The callback supplies the current default network without a polling timer.
        ContextCompat.registerReceiver(this, idleReceiver, IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        channels(this)
        val s = store.value
        if (s.servers.none { server -> server.enabled && server.lockedToken.isBlank() && s.topics.any { it.server == server.url } }) { stopSelf(); return START_NOT_STICKY }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(1, NotificationCompat.Builder(this, "connection").setSmallIcon(R.drawable.ic_notification).setContentTitle("后台接收已开启").setContentText("${s.topics.size} 个订阅 · ${s.servers.count { it.enabled }} 个服务").setContentIntent(open).setOngoing(true).setSilent(true).setCategory(NotificationCompat.CATEGORY_SERVICE).build())
        if (job?.isActive == true) return START_STICKY
        job = scope.launch {
            val configurations = store.flow.map { s -> s.servers.filter { server -> server.enabled && server.lockedToken.isBlank() && s.topics.any { it.server == server.url } }.sortedBy { it.url }.map { server -> Connection(server, s.topics.filter { it.server == server.url }.map { it.name }.sorted()) } }.distinctUntilChanged()
            combine(configurations, networkState.flow, resumeGeneration) { configuration, currentNetwork, _ -> configuration to currentNetwork }.collectLatest { (configurations, currentNetwork) ->
                if (configurations.isEmpty()) { stopSelf(); return@collectLatest }
                if (currentNetwork == null) { configurations.forEach { report(it.server.url, "等待网络恢复") }; return@collectLatest }
                // Let Android apply the application's current VPN, DNS and proxy policy.
                // Only replace the pool; explicitly binding sockets/resolution is unnecessary
                // for default-network traffic and may behave differently across VPN clients.
                val sessionClient = client.newBuilder()
                    .connectionPool(ConnectionPool())
                    .build()
                try {
                    coroutineScope {
                        configurations.forEach { configuration ->
                            launch {
                                var attempt = 0
                                while (currentCoroutineContext().isActive) {
                                    val started = SystemClock.elapsedRealtime()
                                    var code: Int? = null
                                    var retryAfter: Long? = null
                                    try {
                                        report(configuration.server.url, "正在连接…")
                                        var liveAfter: Long? = null
                                        stream(configuration, sessionClient).collect { event ->
                                            if (event is StreamEvent.Open) report(configuration.server.url, "已连接")
                                            if (event is StreamEvent.Line) {
                                                PriorityAlerts.openTime(event.text)?.let { liveAfter = it }
                                                val m = runCatching { Protocol.parse(event.text, configuration.server.url) }.getOrNull() ?: return@collect
                                                val topic = store.value.topicFor(m) ?: return@collect
                                                val known = !MessageArchive.accepts(store.value, m)
                                                val before = store.value.messages.filter { it.server == m.server && it.topic == m.topic && it.sequenceId == m.sequenceId }
                                                val fresh = store.insert(m)
                                                if (!known && store.value.sequences.any { it.server == m.server && it.topic == m.topic && it.sequenceId == m.sequenceId && it.id == m.id } && (fresh || m.event != "message")) {
                                                    before.forEach { cancel(this@NotificationService, it) }
                                                    if (m.event != "message") cancel(this@NotificationService, m)
                                                    if (fresh && topic.shouldNotify(m) && m.time >= topic.joined && m.priority >= 2) notify(this@NotificationService, m, topic, liveAfter = liveAfter)
                                                }
                                                store.advance(configuration.server.url, m.topic, m.time)
                                            }
                                        }
                                        report(configuration.server.url, "连接中断，正在重连")
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        val status = if (e is ConnectionFailure) e.message!! else ReceptionPolicy.failureStatus(e)
                                        store.connectionDiagnostics.update { it + (configuration.server.url to status) }
                                        report(configuration.server.url, status)
                                        if (e is ConnectionFailure) { code = e.statusCode; retryAfter = e.retryAfter }
                                    }
                                    if (SystemClock.elapsedRealtime() - started >= 30_000) attempt = 0
                                    val wait = ReceptionPolicy.retryMillis(attempt++, code, retryAfter)
                                    // Positive jitter never retries before the server's Retry-After.
                                    delay(wait + Random.nextLong(0, (wait / 5).coerceAtLeast(1)))
                                }
                            }
                        }
                    }
                } finally {
                    // collectLatest waits for stream cancellation before disposing this pool.
                    sessionClient.connectionPool.evictAll()
                }
            }
        }
        return START_STICKY
    }
    private fun report(server: String, status: String) {
        store.connectionStatuses.update { it + (server to status) }
        if (store.value.server.url == server) store.status.value = status
    }
    private fun stream(configuration: Connection, sessionClient: OkHttpClient): Flow<StreamEvent> = callbackFlow {
        val since = configuration.names.minOf { name -> store.cursor(configuration.server.url, name).let { if (it > 0) (it - 1).coerceAtLeast(1) else 1L } }
        val url = (configuration.server.url + "/${configuration.names.joinToString(",")}/json").toHttpUrl().newBuilder().addQueryParameter("since", since.toString()).build()
        val request = Request.Builder().url(url).apply { if (configuration.server.token.isNotBlank()) header("Authorization", "Bearer ${configuration.server.token}") }.build()
        val activeCall = sessionClient.newCall(request)
        val reader = launch(Dispatchers.IO) {
            try {
                activeCall.execute().use { response ->
                    if (!response.isSuccessful) throw ConnectionFailure(if (response.code in listOf(401, 403)) "鉴权失败，请检查令牌和主题权限" else "服务器返回 HTTP ${response.code}", response.code, response.header("Retry-After")?.toLongOrNull())
                    val source = response.body?.source() ?: throw ConnectionFailure("服务器未返回消息流")
                    send(StreamEvent.Open)
                    while (isActive) {
                        val line = source.readUtf8Line() ?: break
                        if (line.length <= 1024 * 1024) send(StreamEvent.Line(line))
                    }
                }
                close()
            } catch (e: Exception) { close(e) }
        }
        // Cancellation closes the socket immediately, even when a blocking read is idle.
        awaitClose { activeCall.cancel(); reader.cancel() }
    }
    override fun onDestroy() {
        if (registered) { connectivity.unregisterNetworkCallback(callback); unregisterReceiver(idleReceiver) }
        job?.cancel(); scope.cancel()
        stopPersistent(this)
        // Each session disposes its own pool in the IO coroutine's finally block.
        store.status.value = "已暂停"; super.onDestroy()
    }
    private class ConnectionFailure(message: String, val statusCode: Int? = null, val retryAfter: Long? = null) : Exception(message)
    companion object {
        fun sync(context: Context) {
            val s = (context.applicationContext as PerchApp).store.value
            if (s.servers.any { server -> server.enabled && server.lockedToken.isBlank() && s.topics.any { it.server == server.url } }) ContextCompat.startForegroundService(context, Intent(context, NotificationService::class.java))
            else { stopPersistent(context); context.stopService(Intent(context, NotificationService::class.java)); (context.applicationContext as PerchApp).store.status.value = if (s.topics.isEmpty()) "等待添加订阅" else "已暂停" }
        }
        fun channels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("connection", "后台连接", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        }
        fun cancel(context: Context, m: Message) {
            NotificationManagerCompat.from(context).cancel(m.notificationTag, 2)
            NotificationManagerCompat.from(context).cancel(m.server + ":" + m.id, 2)
        }
        // Called after a durable write; never posts notifications.
        fun reconcile(context: Context, snapshot: Snapshot) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.activeNotifications.filter { it.id == 2 }.forEach { active ->
                val message = snapshot.messages.find { it.notificationTag == active.tag }
                val repeating = active.notification.flags and Notification.FLAG_INSISTENT != 0
                if (message == null || !PriorityAlerts.eligible(snapshot, message) ||
                    repeating && snapshot.topicFor(message)?.persistentAlert != true) nm.cancel(active.tag, 2)
            }
        }
        fun stopPersistent(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.activeNotifications.filter { it.id == 2 && it.notification.flags and Notification.FLAG_INSISTENT != 0 }
                .forEach { nm.cancel(it.tag, it.id) }
        }
        private fun channel(context: Context, m: Message, topic: Topic): String {
            val nm = context.getSystemService(NotificationManager::class.java)
            val legacyId = "topic_" + Resources.digest(m.server + "/" + topic.name).take(16) + "_" + m.priority
            val pattern = PriorityAlerts.vibration(m.priority).takeIf { topic.vibrationVersion >= 2 }
            val id = legacyId + if (pattern != null) "_v2" else ""
            if (nm.getNotificationChannel(id) != null) return id
            val legacy = nm.getNotificationChannel(legacyId)
            val importance = legacy?.importance ?: when {
                m.priority >= 4 -> NotificationManager.IMPORTANCE_HIGH
                m.priority <= 2 -> NotificationManager.IMPORTANCE_LOW
                else -> NotificationManager.IMPORTANCE_DEFAULT
            }
            val label = when (m.priority) { 5 -> "最高"; 4 -> "高"; 2 -> "安静"; else -> "标准" }
            nm.createNotificationChannel(NotificationChannel(id, topic.label + " · " + label + if (pattern != null) " · 振动" else "", importance).apply {
                // Explicit migration preserves non-vibration settings, especially blocked/silent.
                if (legacy != null) {
                    setSound(legacy.sound, legacy.audioAttributes)
                    setShowBadge(legacy.canShowBadge())
                    enableLights(legacy.shouldShowLights())
                    lightColor = legacy.lightColor
                    lockscreenVisibility = legacy.lockscreenVisibility
                    group = legacy.group
                }
                if (pattern != null) { enableVibration(true); vibrationPattern = pattern }
            })
            return id
        }
        fun notify(context: Context, m: Message, topic: Topic, alert: Boolean = true, largeIcon: android.graphics.Bitmap? = null, liveAfter: Long? = null) {
            val store = (context.applicationContext as PerchApp).store
            store.withSnapshot { snapshot ->
                val current = snapshot.messages.find { it.key == m.key && it.notificationTag == m.notificationTag } ?: return@withSnapshot
                val currentTopic = snapshot.topicFor(current) ?: return@withSnapshot
                if (!PriorityAlerts.eligible(snapshot, current)) return@withSnapshot
                post(context, current, currentTopic, snapshot, alert, largeIcon, liveAfter)
            }
        }
        private fun post(context: Context, m: Message, topic: Topic, snapshot: Snapshot, alert: Boolean, largeIcon: android.graphics.Bitmap?, liveAfter: Long?) {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val nm = context.getSystemService(NotificationManager::class.java)
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
            val active = nm.activeNotifications.find { it.tag == m.notificationTag && it.id == 2 }
            // A slow update cannot recreate a dismissed notification or restart playback.
            // Leave an insistent notification untouched by image-only updates.
            if (!alert && (active == null || largeIcon != null && active.notification.flags and Notification.FLAG_INSISTENT != 0)) return
            val channel = channel(context, m, topic)
            val intent = if (m.click.isNotBlank()) Intent(context, InteractionActivity::class.java).putExtra("click", true) else Intent(context, MainActivity::class.java)
            intent.putExtra("message_id", m.id).putExtra("message_server", m.server).setData(android.net.Uri.parse("perch-local://message/" + Resources.digest(m.notificationTag))).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val failed = m.actions.firstOrNull { it.state == "failed" }?.let { "操作失败：" + it.error }
            val body = listOfNotNull(Tags.labels(m.tags).takeIf { it.isNotEmpty() }?.joinToString(" · "), Protocol.preview(m.body), m.attachment?.let { "附件：" + it.name }, failed).joinToString("\n")
            val builder = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_notification).setContentTitle(Tags.title(m.title, m.tags)).setContentText(body).setSubText(topic.label).setStyle(NotificationCompat.BigTextStyle().bigText(body)).setContentIntent(pending).setAutoCancel(true).setWhen(m.time * 1000).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setOnlyAlertOnce(true).setSilent(!alert)
            if (largeIcon != null) builder.setLargeIcon(largeIcon)
            m.actions.take(3).forEach { action ->
                val target = Intent(context, InteractionActivity::class.java).putExtra("message_id", m.id).putExtra("message_server", m.server).putExtra("action_id", action.id).setData(android.net.Uri.parse("perch-local://action/" + Resources.digest(m.notificationTag + ":" + action.id)))
                val pi = PendingIntent.getActivity(context, 0, target, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                builder.addAction(0, action.label + if (action.state == "running") "…" else "", pi)
            }
            val notification = builder.build()
            if (active == null && PriorityAlerts.persistent(snapshot, m, alert, liveAfter)) notification.flags = notification.flags or Notification.FLAG_INSISTENT
            NotificationManagerCompat.from(context).notify(m.notificationTag, 2, notification)
            if (largeIcon == null && Protocol.webUrl(m.icon)) Resources.loadNotificationIcon(context, m, topic)
        }
    }
}
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { if (intent.action == Intent.ACTION_BOOT_COMPLETED) runCatching { NotificationService.sync(context) } }
}
