package app.perch.update

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest

internal class UpdateException(val reason: String) : IOException(reason)
internal fun requireUpdate(ok: Boolean, reason: String) { if (!ok) throw UpdateException(reason) }
internal data class ReleaseAsset(val name: String, val url: String, val size: Long)
internal data class UpdateRelease(val version: String, val notes: String, val apk: ReleaseAsset, val checksum: ReleaseAsset)
internal data class ReleaseVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
    companion object {
        fun parse(value: String): ReleaseVersion {
            requireUpdate(Regex("(0|[1-9][0-9]*)[.](0|[1-9][0-9]*)[.](0|[1-9][0-9]*)").matches(value), "版本格式不受支持")
            val parts = value.split('.').map { it.toIntOrNull() ?: throw UpdateException("版本号过大") }
            return ReleaseVersion(parts[0], parts[1], parts[2])
        }
    }
}

internal object UpdatePolicy {
    const val REPOSITORY = "marlonfan/perch-android"
    const val LATEST = "https://api.github.com/repos/$REPOSITORY/releases/latest"
    const val MAX_APK = 150L * 1024 * 1024
    const val MAX_METADATA = 1024L * 1024
    const val MAX_CHECKSUM = 64L * 1024

    fun release(json: String): UpdateRelease {
        val root = JSONObject(json)
        requireUpdate(!root.getBoolean("draft") && !root.getBoolean("prerelease"), "仅支持正式发布版本")
        val tag = root.getString("tag_name")
        requireUpdate(tag.startsWith('v'), "发布标签无效")
        val version = tag.removePrefix("v")
        ReleaseVersion.parse(version)
        val assets = root.getJSONArray("assets")
        fun asset(name: String, limit: Long): ReleaseAsset {
            val matches = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.optString("name") == name }
            requireUpdate(matches.size == 1, "发布缺少唯一的 $name 文件")
            val item = matches.single()
            val size = item.getLong("size")
            requireUpdate(item.optString("state") == "uploaded" && size in 1..limit, "发布文件未就绪或大小异常")
            val url = item.getString("browser_download_url")
            requireUpdate(url == "https://github.com/$REPOSITORY/releases/download/$tag/$name", "发布文件来源不受信任")
            return ReleaseAsset(name, url, size)
        }
        return UpdateRelease(version, root.optString("body").take(12000).ifBlank { "此版本未提供更新说明。" }, asset("Perch-$version.apk", MAX_APK), asset("SHA256SUMS.txt", MAX_CHECKSUM))
    }

    fun checksum(text: String, apkName: String): String {
        val matches = text.lineSequence().mapNotNull { Regex("^([a-fA-F0-9]{64}) [ *](.+)$").matchEntire(it.trimEnd()) }
            .filter { it.groupValues[2] == apkName }.toList()
        requireUpdate(matches.size == 1, "校验清单缺少唯一的 APK SHA-256")
        return matches.single().groupValues[1].lowercase()
    }

    fun verifyDigest(actual: ByteArray, expected: String) {
        val hex = actual.joinToString("") { "%02x".format(it) }
        requireUpdate(MessageDigest.isEqual(hex.toByteArray(), expected.toByteArray()), "安装包校验失败，请重新下载")
    }

    // Redirects may only leave the exact repository asset for GitHub's release CDN.
    // No suffix matching, raw URLs, API asset endpoints, credentials or HTTP fallback.
    fun redirect(initial: String, from: HttpUrl, location: String): HttpUrl {
        val next = from.resolve(location) ?: throw UpdateException("下载跳转无效")
        val start = initial.toHttpUrlOrNull() ?: throw UpdateException("下载地址无效")
        val secure = next.scheme == "https" && next.port == 443 && next.username.isEmpty() && next.password.isEmpty() && next.fragment == null
        val allowed = if (initial == LATEST) next == start else
            (next.host == "github.com" && next.encodedPath == start.encodedPath && next.query == null) ||
                (next.host == "release-assets.githubusercontent.com" && next.encodedPath.startsWith("/github-production-release-asset/"))
        requireUpdate(secure && allowed, "下载跳转来源不受信任")
        return next
    }

    fun validateArchive(packageName: String, versionName: String?, versionCode: Long, installedCode: Long, release: UpdateRelease) {
        requireUpdate(packageName == "app.perch", "安装包不属于栖信")
        requireUpdate(versionName == release.version, "安装包版本与发布信息不一致")
        requireUpdate(versionCode > installedCode, "已安装相同或更新版本，不能降级")
    }

    fun signingAllowed(installed: Set<String>, candidate: Set<String>, candidateHistory: Set<String>, supportsRotation: Boolean): Boolean {
        if (installed.isEmpty() || candidate.isEmpty()) return false
        if (installed == candidate) return true
        return supportsRotation && installed.size == 1 && candidate.size == 1 && candidateHistory.containsAll(installed) && candidateHistory.containsAll(candidate)
    }

    fun httpFailure(code: Int): UpdateException = UpdateException(when (code) {
        404 -> "暂时无法访问公开版本；仓库可能尚未公开或尚无正式发布"
        403, 429 -> "GitHub 请求受限，请稍后重试"
        else -> "GitHub 请求失败（HTTP $code），请稍后重试"
    })
}
