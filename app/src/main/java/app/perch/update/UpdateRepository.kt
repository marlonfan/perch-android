package app.perch.update

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal interface UpdateSource {
    suspend fun latest(): UpdateRelease
    suspend fun download(release: UpdateRelease, progress: (Long) -> Unit): VerifiedDownload
}
internal data class VerifiedDownload(val file: File, val sha256: String)

internal class UpdateRepository(private val cache: File) : UpdateSource {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(10, TimeUnit.MINUTES).build()

    override suspend fun latest(): UpdateRelease = UpdatePolicy.release(readText(UpdatePolicy.LATEST, UpdatePolicy.MAX_METADATA))

    override suspend fun download(release: UpdateRelease, progress: (Long) -> Unit): VerifiedDownload {
        val sums = readText(release.checksum.url, UpdatePolicy.MAX_CHECKSUM, release.checksum.size)
        val expected = UpdatePolicy.checksum(sums, release.apk.name)
        cache.mkdirs()
        // Keep recent handed-off APKs while the system installer may still read their URI.
        val staleBefore = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2)
        cache.listFiles()?.filter { it.isFile && it.lastModified() < staleBefore }?.forEach { it.delete() }
        val partial = File.createTempFile("download-", ".part", cache)
        try {
            partial.outputStream().use { stream -> transfer(release.apk.url, release.apk.size, release.apk.size, stream, progress) }
            val digest = MessageDigest.getInstance("SHA-256")
            partial.inputStream().use { input ->
                val bytes = ByteArray(65536)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(bytes)
                    if (n < 0) break
                    digest.update(bytes, 0, n)
                }
            }
            UpdatePolicy.verifyDigest(digest.digest(), expected)
            return VerifiedDownload(partial, expected)
        } catch (error: Throwable) {
            partial.delete()
            throw error
        }
    }

    private suspend fun readText(url: String, limit: Long, expected: Long? = null): String {
        val output = ByteArrayOutputStream()
        transfer(url, limit, expected, output) {}
        return output.toString(Charsets.UTF_8.name())
    }

    private suspend fun transfer(initial: String, limit: Long, expected: Long?, output: OutputStream, progress: (Long) -> Unit) =
        suspendCancellableCoroutine<Unit> { continuation ->
            // One callback owns the stream until completion; cancellation closes the active call.
            val lock = Any()
            var active: Call? = null
            continuation.invokeOnCancellation { synchronized(lock) { active?.cancel() } }
            fun request(url: okhttp3.HttpUrl, redirects: Int) {
                if (!continuation.isActive) return
                val call = client.newCall(Request.Builder().url(url).header("Accept", if (initial == UpdatePolicy.LATEST) "application/vnd.github+json" else "application/octet-stream")
                    .header("User-Agent", "Perch-Android-Updater").header("Accept-Encoding", "identity").build())
                synchronized(lock) { active = call; if (!continuation.isActive) call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                    override fun onResponse(call: Call, response: Response) {
                        try {
                            response.use {
                                if (it.code in setOf(301, 302, 303, 307, 308)) {
                                    requireUpdate(redirects < 5, "下载跳转次数过多")
                                    val next = UpdatePolicy.redirect(initial, url, it.header("Location") ?: throw UpdateException("下载跳转缺少地址"))
                                    request(next, redirects + 1)
                                    return
                                }
                                if (it.code != 200) throw UpdatePolicy.httpFailure(it.code)
                                val body = it.body ?: throw UpdateException("服务器未返回文件")
                                val length = body.contentLength()
                                requireUpdate(length <= limit && (expected == null || length < 0 || length == expected), "下载文件大小与发布信息不一致")
                                var received = 0L
                                body.byteStream().use { input ->
                                    val buffer = ByteArray(65536)
                                    while (continuation.isActive) {
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        received += count
                                        requireUpdate(received <= limit, "下载文件超过大小限制")
                                        output.write(buffer, 0, count)
                                        progress(received)
                                    }
                                }
                                requireUpdate(expected == null || received == expected, "下载不完整，请重试")
                                if (continuation.isActive) continuation.resume(Unit)
                            }
                        } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
                    }
                })
            }
            request(initial.toHttpUrl(), 0)
        }
}
