package app.perch.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal fun interface UpdateVerifier {
    suspend fun verify(download: VerifiedDownload, release: UpdateRelease)
}

internal class ApkVerifier(context: Context) : UpdateVerifier {
    private val manager = context.applicationContext.packageManager

    @Suppress("DEPRECATION")
    override suspend fun verify(download: VerifiedDownload, release: UpdateRelease) {
        requireUpdate(download.file.isFile && download.file.length() == release.apk.size, "安装包已丢失或损坏，请重新下载")
        val digest = MessageDigest.getInstance("SHA-256")
        download.file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        UpdatePolicy.verifyDigest(digest.digest(), download.sha256)
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = manager.getPackageInfo("app.perch", flags)
        val archive = manager.getPackageArchiveInfo(download.file.absolutePath, flags) ?: throw UpdateException("无法验证安装包，请重新下载")
        fun version(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        UpdatePolicy.validateArchive(archive.packageName, archive.versionName, version(archive), version(installed), release)
        requireUpdate((archive.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) <= Build.VERSION.SDK_INT, "新版本不支持当前 Android 系统")
        val current = signers(installed)
        val candidate = signers(archive)
        val history = if (Build.VERSION.SDK_INT >= 28) archive.signingInfo?.signingCertificateHistory?.map { it.toCharsString() }?.toSet().orEmpty() else emptySet()
        requireUpdate(UpdatePolicy.signingAllowed(current, candidate, history, Build.VERSION.SDK_INT >= 28), "安装包签名与当前应用不兼容，无法覆盖安装（调试版与正式版可能使用不同签名）")
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> =
        (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)?.map { it.toCharsString() }?.toSet().orEmpty()
}
