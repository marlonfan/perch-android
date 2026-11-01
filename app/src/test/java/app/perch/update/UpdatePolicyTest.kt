package app.perch.update

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class UpdatePolicyTest {
    private fun json(version: String = "0.6.0"): JSONObject {
        fun asset(name: String) = JSONObject().put("name", name).put("size", 123).put("state", "uploaded")
            .put("browser_download_url", "https://github.com/${UpdatePolicy.REPOSITORY}/releases/download/v$version/$name")
        return JSONObject().put("draft", false).put("prerelease", false).put("tag_name", "v$version").put("body", "Notes")
            .put("assets", JSONArray().put(asset("Perch-$version.apk")).put(asset("SHA256SUMS.txt")))
    }
    private fun reject(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: UpdateException) { }
    }
    @Test fun dottedVersionsCompareNumericallyAndRejectMalformedTags() {
        assertTrue(ReleaseVersion.parse("0.10.0") > ReleaseVersion.parse("0.6.0"))
        assertTrue(ReleaseVersion.parse("1.0.0") > ReleaseVersion.parse("0.99.99"))
        listOf("v0.6.0", "0.6", "00.6.0", "0x6x0", "0.6.0-beta", "0.6.0\n", "999999999999.0.0").forEach { reject { ReleaseVersion.parse(it) } }
    }
    @Test fun releaseRequiresExactTrustedAssetsAndFormalRelease() {
        val release = UpdatePolicy.release(json().toString())
        assertEquals("0.6.0", release.version)
        assertEquals("Perch-0.6.0.apk", release.apk.name)
        reject { UpdatePolicy.release(json().put("prerelease", true).toString()) }
        reject { UpdatePolicy.release(json().put("draft", true).toString()) }
        val wrong = json(); wrong.getJSONArray("assets").getJSONObject(0).put("browser_download_url", "https://evil.example/Perch-0.6.0.apk")
        reject { UpdatePolicy.release(wrong.toString()) }
        val duplicate = json(); duplicate.getJSONArray("assets").put(duplicate.getJSONArray("assets").getJSONObject(0))
        reject { UpdatePolicy.release(duplicate.toString()) }
        val huge = json(); huge.getJSONArray("assets").getJSONObject(0).put("size", UpdatePolicy.MAX_APK + 1)
        reject { UpdatePolicy.release(huge.toString()) }
        val empty = json(); empty.getJSONArray("assets").getJSONObject(0).put("size", 0)
        reject { UpdatePolicy.release(empty.toString()) }
    }
    @Test fun checksumRejectsWrongFilenameAmbiguityAndContentTampering() {
        val bytes = "verified".toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hash = digest.joinToString("") { "%02x".format(it) }
        val sums = "$hash  Perch-0.6.0.apk\n"
        assertEquals(hash, UpdatePolicy.checksum(sums, "Perch-0.6.0.apk"))
        UpdatePolicy.verifyDigest(digest, hash)
        reject { UpdatePolicy.checksum(sums, "wrong.apk") }
        reject { UpdatePolicy.checksum(sums + sums, "Perch-0.6.0.apk") }
        reject { UpdatePolicy.verifyDigest(MessageDigest.getInstance("SHA-256").digest("tampered".toByteArray()), hash) }
    }
    @Test fun redirectsOnlyAllowHttpsExactAssetAndGitHubReleaseCdn() {
        val asset = UpdatePolicy.release(json().toString()).apk.url
        val cdn = "https://release-assets.githubusercontent.com/github-production-release-asset/123/456?sig=example"
        assertEquals(cdn, UpdatePolicy.redirect(asset, asset.toHttpUrl(), cdn).toString())
        listOf("http://release-assets.githubusercontent.com/github-production-release-asset/a", "https://release-assets.githubusercontent.com.evil.example/github-production-release-asset/a", "https://user:password@release-assets.githubusercontent.com/github-production-release-asset/a", "https://github.com/other/repo/releases/download/v0.6.0/a.apk", "https://release-assets.githubusercontent.com:444/github-production-release-asset/a", "https://release-assets.githubusercontent.com/other/a").forEach { target -> reject { UpdatePolicy.redirect(asset, asset.toHttpUrl(), target) } }
        reject { UpdatePolicy.redirect(UpdatePolicy.LATEST, UpdatePolicy.LATEST.toHttpUrl(), cdn) }
    }
    @Test fun archiveIdentityVersionAndDowngradeAreChecked() {
        val release = UpdatePolicy.release(json().toString())
        UpdatePolicy.validateArchive("app.perch", "0.6.0", 11, 10, release)
        reject { UpdatePolicy.validateArchive("app.other", "0.6.0", 11, 10, release) }
        reject { UpdatePolicy.validateArchive("app.perch", "0.6.1", 11, 10, release) }
        reject { UpdatePolicy.validateArchive("app.perch", "0.6.0", 10, 10, release) }
    }
    @Test fun signerSetsAndSingleSignerRotationRequireInstalledTrust() {
        assertTrue(UpdatePolicy.signingAllowed(setOf("a"), setOf("a"), emptySet(), false))
        assertFalse(UpdatePolicy.signingAllowed(emptySet(), setOf("a"), emptySet(), true))
        assertFalse(UpdatePolicy.signingAllowed(setOf("a"), setOf("b"), setOf("b"), true))
        assertFalse(UpdatePolicy.signingAllowed(setOf("a"), setOf("b"), setOf("a", "b"), false))
        assertTrue(UpdatePolicy.signingAllowed(setOf("a"), setOf("b"), setOf("a", "b"), true))
        assertFalse(UpdatePolicy.signingAllowed(setOf("a", "c"), setOf("b"), setOf("a", "b", "c"), true))
    }
}
