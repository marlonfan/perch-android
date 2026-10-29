package app.perch

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal object Tags {
    private var emoji: Map<String, String> = emptyMap()
    fun load(context: Context) {
        emoji = runCatching {
            val json = org.json.JSONObject(context.assets.open("ntfy-emoji.json").bufferedReader().use { it.readText() })
            json.keys().asSequence().associateWith { json.getString(it) }
        }.getOrDefault(emptyMap())
    }
    fun title(title: String, tags: List<String>): String =
        (tags.mapNotNull { emoji[it] }.distinct() + title).joinToString(" ")
    fun labels(tags: List<String>): List<String> = tags.filterNot { it in emoji }
}

internal object Resources {
    const val MAX_FILE = 25L * 1024 * 1024
    const val MAX_IMAGE = 2L * 1024 * 1024
    private val client = OkHttpClient.Builder().callTimeout(40, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    fun cached(context: Context, url: String, server: String, name: String): File = File(File(context.cacheDir, "attachments/" + digest(server + "|" + url)), name.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").take(100).ifBlank { "attachment" }.let { if (it == "." || it == "..") "attachment" else it })
    suspend fun download(context: Context, m: Message, a: Attachment, limit: Long = MAX_FILE): File = withContext(Dispatchers.IO) {
        require(Protocol.webUrl(a.url)) { "附件地址无效" }
        require(a.size <= limit) { "文件超过下载上限（25 MB，图片预览 2 MB）" }
        val file = cached(context, a.url, m.server, a.name)
        if (file.isFile && file.length() <= limit) return@withContext file
        require(a.expires == 0L || a.expires > System.currentTimeMillis() / 1000) { "附件已过期" }
        val server = (context.applicationContext as PerchApp).store.value.servers.find { it.url == m.server }
        var target = a.url
        var redirects = 0
        var response = client.newCall(resourceRequest(target, server)).execute()
        while (response.code in setOf(301, 302, 303, 307, 308)) {
            val next = response.header("Location")?.let { target.toHttpUrlOrNull()?.resolve(it)?.toString() }
            response.close()
            require(++redirects <= 5 && next != null && Protocol.webUrl(next)) { "附件重定向无效" }
            require(!target.startsWith("https:") || next.startsWith("https:")) { "附件重定向降级为未加密连接" }
            target = next
            response = client.newCall(resourceRequest(target, server)).execute()
        }
        response.use { response ->
            check(response.isSuccessful) { "附件下载失败：HTTP " + response.code }
            val body = response.body ?: error("附件内容为空")
            require(body.contentLength() <= limit) { "文件超过下载上限" }
            file.parentFile!!.mkdirs()
            val temp = File.createTempFile("download-", ".tmp", file.parentFile)
            try {
                body.byteStream().use { input -> temp.outputStream().use { output ->
                    val buffer = ByteArray(8192); var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer); if (count < 0) break
                        total += count; require(total <= limit) { "文件超过下载上限" }; output.write(buffer, 0, count)
                    }
                } }
                check(temp.renameTo(file) || file.isFile) { "附件保存失败" }
            } finally { temp.delete() }
        }
        trim(context, file)
        file
    }
    private fun resourceRequest(url: String, server: Server?): Request = Request.Builder().url(url).apply {
        if (server != null && server.token.isNotBlank() && Protocol.canAuthenticate(url, server.url)) header("Authorization", "Bearer " + server.token)
    }.build()
    private fun trim(context: Context, retained: File) {
        val files = File(context.cacheDir, "attachments").walkTopDown().filter { it.isFile }.sortedBy { it.lastModified() }.toList()
        var size = files.sumOf { it.length() }
        for (file in files) if (size > 100L * 1024 * 1024 && file != retained) { size -= file.length(); file.delete() }
    }
    fun bitmap(file: File, maxDimension: Int = 1200): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
    fun type(a: Attachment): String = a.type.ifBlank { android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(a.name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream" }
    fun open(context: Context, file: File, type: String, share: Boolean = false) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val intent = if (share) Intent(Intent.ACTION_SEND).setType(type.ifBlank { "application/octet-stream" }).putExtra(Intent.EXTRA_STREAM, uri) else Intent(Intent.ACTION_VIEW).setDataAndType(uri, type.ifBlank { "application/octet-stream" })
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).clipData = android.content.ClipData.newRawUri("附件", uri)
        context.startActivity(Intent.createChooser(intent, if (share) "分享附件" else "打开附件"))
    }
    fun loadNotificationIcon(context: Context, m: Message, topic: Topic) {
        scope.launch {
            val bitmap = runCatching { bitmap(download(context, m, Attachment("icon", m.icon), MAX_IMAGE), 128) }.getOrNull() ?: return@launch
            val current = (context.applicationContext as PerchApp).store.value.messages.find { it.key == m.key && !it.read } ?: return@launch
            NotificationService.notify(context, current, topic, alert = false, largeIcon = bitmap)
        }
    }
}
