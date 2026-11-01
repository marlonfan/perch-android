package app.perch.update

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateControllerTest {
    @get:Rule val temp = TemporaryFolder()
    private val release = UpdateRelease("0.6.0", "Notes", ReleaseAsset("Perch-0.6.0.apk", "https://example.invalid/a.apk", 4), ReleaseAsset("SHA256SUMS.txt", "https://example.invalid/sums", 1))

    private fun exercise(body: (UpdateController, FakeSource, () -> Int) -> Unit) {
        val source = FakeSource()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var verified = 0
        val controller = UpdateController(source, UpdateVerifier { _, _ ->
            verified++
            if (source.reject) throw UpdateException("签名不匹配")
        }, scope, "0.5.5", Dispatchers.Unconfined)
        try { body(controller, source) { verified } } finally { controller.close(); scope.cancel() }
    }

    private inner class FakeSource : UpdateSource {
        var checkGate: CompletableDeferred<UpdateRelease>? = null
        var reject = false
        var file: File? = null
        override suspend fun latest() = checkGate?.await() ?: release
        override suspend fun download(release: UpdateRelease, progress: (Long) -> Unit): VerifiedDownload {
            val output = temp.newFile().apply { writeText("data") }
            file = output
            progress(4)
            return VerifiedDownload(output, "checksum")
        }
    }
    @Test fun downloadVerifiesBeforeReadyAndRenamesOnlyValidatedFile() = exercise { controller, source, count ->
        controller.check()
        assertTrue((controller.state.value as UpdateState.Available).newer)
        controller.download()
        val ready = controller.state.value as UpdateState.Ready
        assertEquals(1, count())
        assertTrue(ready.download.file.isFile)
        assertTrue(ready.download.file.name.endsWith(".apk"))
        assertFalse(source.file!!.exists())
        controller.close()
        assertFalse(ready.download.file.exists())
    }
    @Test fun verificationFailureDeletesDownloadedFileAndPermitsRetry() = exercise { controller, source, count ->
        controller.check(); source.reject = true; controller.download()
        assertTrue(controller.state.value is UpdateState.Failed)
        assertFalse(source.file!!.exists())
        source.reject = false; controller.download()
        assertTrue(controller.state.value is UpdateState.Ready)
        assertEquals(2, count())
    }
    @Test fun cancellingCheckPreventsLateResultFromChangingState() = runBlocking {
        exercise { controller, source, _ ->
            val gate = CompletableDeferred<UpdateRelease>()
            source.checkGate = gate
            controller.check()
            assertTrue(controller.state.value is UpdateState.Checking)
            controller.cancel()
            gate.complete(release)
            assertEquals(UpdateState.Idle, controller.state.value)
            source.checkGate = null; controller.check()
            assertTrue(controller.state.value is UpdateState.Available)
        }
    }
    @Test fun permissionDenialNeverLaunchesInstallerOrCreatesAuthorizationLoop() = exercise { controller, _, _ ->
        controller.check(); controller.download(); controller.needPermission(); controller.permissionStarted()
        controller.permissionReturned(false)
        assertTrue((controller.state.value as UpdateState.PermissionNeeded).denied)
        controller.permissionReturned(true) // stale callback ignored without a new explicit request
        assertTrue(controller.state.value is UpdateState.PermissionNeeded)
        controller.permissionStarted(); controller.permissionReturned(true)
        assertTrue(controller.state.value is UpdateState.Ready)
    }
    @Test fun installRevalidatesAndHandoffKeepsFileReadableWithoutAutomaticRelaunch() = exercise { controller, _, count ->
        controller.check(); controller.download()
        val file = (controller.state.value as UpdateState.Ready).download.file
        controller.install()
        assertTrue(controller.state.value is UpdateState.LaunchInstaller)
        assertEquals(2, count())
        controller.installerOpened()
        assertTrue(controller.state.value is UpdateState.InstallerOpened)
        controller.close()
        assertTrue(file.exists())
        assertEquals(UpdateState.Idle, controller.state.value)
    }
    @Test fun permissionRevocationBetweenVerificationAndHandoffReturnsToPrompt() = exercise { controller, _, _ ->
        controller.check(); controller.download(); controller.install(); controller.installPermissionRevoked()
        assertTrue(controller.state.value is UpdateState.PermissionNeeded)
    }
    @Test fun installerLaunchFailureCleansFileAndOffersDownloadRetry() = exercise { controller, _, _ ->
        controller.check(); controller.download()
        val file = (controller.state.value as UpdateState.Ready).download.file
        controller.install(); controller.handoffFailed()
        assertFalse(file.exists())
        assertTrue(controller.state.value is UpdateState.Failed)
        controller.download()
        assertTrue(controller.state.value is UpdateState.Ready)
    }
}
