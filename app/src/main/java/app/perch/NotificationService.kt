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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class NotificationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val store get() = (application as PerchApp).store
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
    private val network = MutableStateFlow<Network?>(null)
    private val resumeGeneration = MutableStateFlow(0)
    private lateinit var connectivity: ConnectivityManager
    private var registered = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(value: Network) { network.value = value }
        override fun onLost(value: Network) { if (network.value == value) network.value = null }
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
        if (!s.server.enabled || s.server.url.isBlank() || s.topics.isEmpty()) { stopSelf(); return START_NOT_STICKY }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(1, NotificationCompat.Builder(this, "connection").setSmallIcon(R.drawable.ic_notification).setContentTitle("后台接收已开启").setContentText("${s.topics.size} 个订阅").setContentIntent(open).setOngoing(true).setSilent(true).setCategory(NotificationCompat.CATEGORY_SERVICE).build())
        if (job?.isActive == true) return START_STICKY
        job = scope.launch {
            val configurations = store.flow.map { Connection(it.server, it.topics.map { topic -> topic.name }.sorted()) }.distinctUntilChanged()
            combine(configurations, network, resumeGeneration) { configuration, currentNetwork, _ -> configuration to currentNetwork }.collectLatest { (configuration, currentNetwork) ->
                if (!configuration.server.enabled || configuration.server.url.isBlank() || configuration.names.isEmpty()) { stopSelf(); return@collectLatest }
                if (currentNetwork == null) { store.status.value = "等待网络恢复"; return@collectLatest }
                var attempt = 0
                while (currentCoroutineContext().isActive) {
                val started = SystemClock.elapsedRealtime()
                var code: Int? = null
                var retryAfter: Long? = null
                try {
                    store.status.value = "正在连接…"
                    stream(configuration).collect { event ->
                        if (event is StreamEvent.Open) store.status.value = "已连接"
                        if (event is StreamEvent.Line) {
                            val m = runCatching { Protocol.parse(event.text, configuration.server.url) }.getOrNull() ?: return@collect
                            val topic = store.value.topics.find { it.name == m.topic } ?: return@collect
                            val fresh = store.insert(m)
                            if (fresh && !topic.muted && m.time >= topic.joined && m.priority >= 2) notify(this@NotificationService, m, topic)
                            store.advance(configuration.server.url, m.topic, m.time)
                        }
                    }
                    store.status.value = "连接中断，正在重连"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    store.status.value = if (e is ConnectionFailure) e.message!! else "网络不可用，稍后自动重连"
                    if (e is ConnectionFailure) { code = e.statusCode; retryAfter = e.retryAfter }
                }
                if (SystemClock.elapsedRealtime() - started >= 30_000) attempt = 0
                val wait = ReceptionPolicy.retryMillis(attempt++, code, retryAfter)
                // Positive jitter never retries before the server's Retry-After.
                delay(wait + Random.nextLong(0, (wait / 5).coerceAtLeast(1)))
                }
            }
        }
        return START_STICKY
    }
    private fun stream(configuration: Connection): Flow<StreamEvent> = callbackFlow {
        val since = configuration.names.minOf { name -> store.cursor(configuration.server.url, name).let { if (it > 0) (it - 1).coerceAtLeast(1) else 1L } }
        val url = (configuration.server.url + "/${configuration.names.joinToString(",")}/json").toHttpUrl().newBuilder().addQueryParameter("since", since.toString()).build()
        val request = Request.Builder().url(url).apply { if (configuration.server.token.isNotBlank()) header("Authorization", "Bearer ${configuration.server.token}") }.build()
        val activeCall = client.newCall(request)
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
        // Closing idle TLS sockets may send close_notify. Service callbacks run on main.
        // This executor survives cancellation of the reception scope.
        client.dispatcher.executorService.execute { client.connectionPool.evictAll() }
        store.status.value = "已暂停"; super.onDestroy()
    }
    private class ConnectionFailure(message: String, val statusCode: Int? = null, val retryAfter: Long? = null) : Exception(message)
    companion object {
        fun sync(context: Context) {
            val s = (context.applicationContext as PerchApp).store.value
            if (s.server.enabled && s.server.url.isNotBlank() && s.topics.isNotEmpty()) ContextCompat.startForegroundService(context, Intent(context, NotificationService::class.java))
            else { context.stopService(Intent(context, NotificationService::class.java)); (context.applicationContext as PerchApp).store.status.value = if (s.topics.isEmpty()) "等待添加订阅" else "已暂停" }
        }
        fun channels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("connection", "后台连接", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        }
        fun notify(context: Context, m: Message, topic: Topic) {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val importance = when { m.priority >= 4 -> NotificationManager.IMPORTANCE_HIGH; m.priority <= 2 -> NotificationManager.IMPORTANCE_LOW; else -> NotificationManager.IMPORTANCE_DEFAULT }
            val channel = "topic_${topic.name}_${m.priority}"
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(channel, "${topic.label} · ${if (m.priority >= 4) "重要" else if (m.priority <= 2) "安静" else "标准"}", importance))
            val key = "${m.server}:${m.id}"
            val intent = Intent(context, MainActivity::class.java).putExtra("message_id", m.id).putExtra("message_server", m.server).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pending = PendingIntent.getActivity(context, key.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            NotificationManagerCompat.from(context).notify(key, 2, NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_notification).setContentTitle(m.title).setContentText(Protocol.preview(m.body)).setSubText(topic.label).setStyle(NotificationCompat.BigTextStyle().bigText(Protocol.preview(m.body))).setContentIntent(pending).setAutoCancel(true).setWhen(m.time * 1000).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
        }
    }
}
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { if (intent.action == Intent.ACTION_BOOT_COMPLETED) runCatching { NotificationService.sync(context) } }
}
