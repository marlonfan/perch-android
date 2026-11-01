package app.perch.update

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.perch.BuildConfig
import java.io.File
import java.util.Locale

@Composable
internal fun rememberUpdateController(): UpdateController {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val controller = remember(context, scope) {
        UpdateController(UpdateRepository(File(context.cacheDir, "updater")), ApkVerifier(context), scope, BuildConfig.VERSION_NAME)
    }
    DisposableEffect(controller) { onDispose { controller.close() } }
    return controller
}

@Composable
internal fun UpdateDialog(controller: UpdateController, dismiss: () -> Unit) {
    val context = LocalContext.current
    val state by controller.state.collectAsState()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        controller.permissionReturned(context.packageManager.canRequestPackageInstalls())
    }
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Returning or cancelling is not proof of installation and never triggers another launch.
    }
    LaunchedEffect(state) {
        when (val value = state) {
            is UpdateState.Ready -> if (context.packageManager.canRequestPackageInstalls()) controller.install() else controller.needPermission()
            is UpdateState.LaunchInstaller -> {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    controller.installPermissionRevoked()
                } else {
                    try {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", value.ready.download.file)
                        installer.launch(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("Perch update", uri) })
                        controller.installerOpened()
                    } catch (_: Exception) { controller.handoffFailed() }
                }
            }
            else -> Unit
        }
    }
    val release = when (val value = state) {
        is UpdateState.Available -> value.release
        is UpdateState.Downloading -> value.release
        is UpdateState.Verifying -> value.release
        is UpdateState.Ready -> value.release
        is UpdateState.PermissionNeeded -> value.ready.release
        is UpdateState.PermissionPending -> value.ready.release
        is UpdateState.LaunchInstaller -> value.ready.release
        is UpdateState.InstallerOpened -> value.ready.release
        is UpdateState.Failed -> value.release
        else -> null
    }
    val close = { controller.cancel(); dismiss() }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("应用更新") },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("当前版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）")
                release?.let { Text("最新正式版 ${it.version}", style = MaterialTheme.typography.titleSmall) }
                when (val value = state) {
                    UpdateState.Idle -> Text("从 GitHub 官方仓库检查正式版本。")
                    UpdateState.Checking -> { Text("正在检查 GitHub 最新版本…"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    is UpdateState.Available -> Text(if (value.newer) "发现新版本，下载 ${size(value.release.apk.size)} 后将打开系统安装页面。" else "当前已是最新或更新版本，无需更新。")
                    is UpdateState.Downloading -> {
                        Text("正在下载 ${size(value.received)} / ${size(value.release.apk.size)}")
                        LinearProgressIndicator(progress = { (value.received.toFloat() / value.release.apk.size).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    }
                    is UpdateState.Verifying -> { Text("正在验证安装包、版本和签名…"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    is UpdateState.PermissionNeeded -> Text(if (value.denied) "尚未允许安装。你可以再次授权，或关闭更新。" else "安装前需在系统中允许“安装未知应用”。授权返回后会继续打开安装页面，仍需你确认安装。")
                    is UpdateState.PermissionPending -> Text("等待系统安装权限，返回后继续。")
                    is UpdateState.InstallerOpened -> Text("已交给系统安装。请在系统页面确认；如已取消，可再次打开安装。")
                    is UpdateState.Failed -> Text(value.message, color = MaterialTheme.colorScheme.error)
                    else -> Text("准备打开系统安装页面…")
                }
                release?.let {
                    Text("更新说明", style = MaterialTheme.typography.titleSmall)
                    Text(it.notes, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            when (val value = state) {
                UpdateState.Idle -> TextButton(onClick = controller::check) { Text("检查更新") }
                is UpdateState.Available -> if (value.newer) TextButton(onClick = controller::download) { Text("下载并安装") }
                is UpdateState.Failed -> TextButton(onClick = { if (value.release == null) controller.check() else controller.download() }) { Text("重试") }
                is UpdateState.PermissionNeeded -> TextButton(onClick = {
                    controller.permissionStarted()
                    try { permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))) }
                    catch (_: Exception) { controller.handoffFailed() }
                }) { Text("允许安装") }
                is UpdateState.InstallerOpened -> TextButton(onClick = controller::install) { Text("再次打开安装") }
                else -> Unit
            }
        },
        dismissButton = { TextButton(onClick = close) { Text(if (state is UpdateState.Downloading || state is UpdateState.Verifying || state is UpdateState.Checking) "取消" else "关闭") } },
    )
}

private fun size(bytes: Long): String = String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
