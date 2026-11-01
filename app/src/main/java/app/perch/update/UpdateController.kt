package app.perch.update

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Available(val release: UpdateRelease, val newer: Boolean) : UpdateState
    data class Downloading(val release: UpdateRelease, val received: Long) : UpdateState
    data class Verifying(val release: UpdateRelease) : UpdateState
    data class Ready(val release: UpdateRelease, val download: VerifiedDownload) : UpdateState
    data class PermissionNeeded(val ready: Ready, val denied: Boolean = false) : UpdateState
    data class PermissionPending(val ready: Ready) : UpdateState
    data class LaunchInstaller(val ready: Ready) : UpdateState
    data class InstallerOpened(val ready: Ready) : UpdateState
    data class Failed(val message: String, val release: UpdateRelease? = null) : UpdateState
}

/** Screen-scoped jobs; no persisted permission or install intent can replay after process death. */
internal class UpdateController(
    private val source: UpdateSource,
    private val verifier: UpdateVerifier,
    private val scope: CoroutineScope,
    private val installedVersion: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutable = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state = mutable.asStateFlow()
    private var job: Job? = null
    @Volatile private var generation = 0
    private var held: VerifiedDownload? = null
    private var handedOff = false

    fun check() {
        cancel()
        releaseHeld()
        mutable.value = UpdateState.Checking
        val operation = generation
        job = scope.launch {
            try {
                val release = withContext(io) { source.latest() }
                if (generation == operation) mutable.value = UpdateState.Available(release, ReleaseVersion.parse(release.version) > ReleaseVersion.parse(installedVersion))
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { if (generation == operation) mutable.value = UpdateState.Failed(message(error)) }
        }
    }

    fun download() {
        val release = when (val value = mutable.value) {
            is UpdateState.Available -> value.release.takeIf { value.newer }
            is UpdateState.Failed -> value.release
            else -> null
        } ?: return
        cancel()
        releaseHeld()
        val operation = generation
        mutable.value = UpdateState.Downloading(release, 0)
        job = scope.launch {
            var downloaded: VerifiedDownload? = null
            var kept = false
            try {
                val ready = withContext(io) {
                    val result = source.download(release) { received ->
                        // Old callbacks cannot overwrite a cancelled/retried operation.
                        scope.launch {
                            val before = mutable.value
                            if (generation == operation && before is UpdateState.Downloading) mutable.value = before.copy(received = received)
                        }
                    }
                    downloaded = result
                    ensureActive()
                    scope.launch {
                        if (generation == operation && mutable.value is UpdateState.Downloading) mutable.value = UpdateState.Verifying(release)
                    }.join()
                    verifier.verify(result, release)
                    ensureActive()
                    val apk = File(result.file.parentFile, result.file.nameWithoutExtension + ".apk")
                    requireUpdate(result.file.renameTo(apk), "无法保存安装包，请检查可用空间")
                    result.copy(file = apk).also { downloaded = it }
                }
                if (generation == operation) {
                    held = ready
                    kept = true
                    mutable.value = UpdateState.Ready(release, ready)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { if (generation == operation) mutable.value = UpdateState.Failed(message(error), release) }
            finally { if (!kept) downloaded?.file?.delete() }
        }
    }

    fun needPermission() {
        (mutable.value as? UpdateState.Ready)?.let { mutable.value = UpdateState.PermissionNeeded(it) }
    }

    fun permissionStarted() {
        (mutable.value as? UpdateState.PermissionNeeded)?.let { mutable.value = UpdateState.PermissionPending(it.ready) }
    }

    fun permissionReturned(granted: Boolean) {
        val pending = mutable.value as? UpdateState.PermissionPending ?: return
        mutable.value = if (granted) pending.ready else UpdateState.PermissionNeeded(pending.ready, denied = true)
    }

    fun install() {
        val ready = when (val value = mutable.value) {
            is UpdateState.Ready -> value
            is UpdateState.InstallerOpened -> value.ready
            else -> null
        } ?: return
        val operation = generation
        mutable.value = UpdateState.Verifying(ready.release)
        job = scope.launch {
            try {
                withContext(io) { verifier.verify(ready.download, ready.release) }
                if (generation == operation) mutable.value = UpdateState.LaunchInstaller(ready)
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                if (generation == operation) {
                    releaseHeld()
                    mutable.value = UpdateState.Failed(message(error), ready.release)
                }
            }
        }
    }

    fun installPermissionRevoked() {
        (mutable.value as? UpdateState.LaunchInstaller)?.let { mutable.value = UpdateState.PermissionNeeded(it.ready) }
    }

    fun installerOpened() {
        (mutable.value as? UpdateState.LaunchInstaller)?.let {
            handedOff = true
            mutable.value = UpdateState.InstallerOpened(it.ready)
        }
    }

    fun handoffFailed() {
        val ready = when (val value = mutable.value) {
            is UpdateState.LaunchInstaller -> value.ready
            is UpdateState.PermissionPending -> value.ready
            else -> return
        }
        releaseHeld()
        mutable.value = UpdateState.Failed("无法打开系统安装或授权页面，请检查系统限制后重试", ready.release)
    }

    fun cancel() {
        generation++
        job?.cancel()
        job = null
        val release = when (val value = mutable.value) {
            is UpdateState.Downloading -> value.release
            is UpdateState.Verifying -> value.release
            else -> null
        }
        mutable.value = release?.let { UpdateState.Available(it, true) } ?: UpdateState.Idle
        releaseHeld()
    }

    fun close() = cancel()

    private fun releaseHeld() {
        // The system installer may still be reading a granted URI after this screen disappears.
        if (!handedOff) held?.file?.delete()
        held = null
        handedOff = false
    }

    private fun message(error: Exception) = (error as? UpdateException)?.reason
        ?: if (error is org.json.JSONException) "GitHub 发布信息格式异常，请稍后重试" else "更新请求失败，请检查网络或可用空间后重试"
}
