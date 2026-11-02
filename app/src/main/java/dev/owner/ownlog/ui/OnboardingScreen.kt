package dev.owner.ownlog.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.owner.ownlog.OwnLogApp
import dev.owner.ownlog.service.CaptureBridge

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun OnboardingScreen(
    onRequestProjection: () -> Unit,
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as OwnLogApp
    val settings by app.settings.flow.collectAsStateWithLifecycle()
    val projectionOn by CaptureBridge.projectionActive.collectAsStateWithLifecycle()
    val accessibilityLive by CaptureBridge.accessibilityActive.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumeTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val permissions = remember(resumeTick, accessibilityLive) { PermissionChecks.read(context) }
    val accessibilityOn = permissions.accessibility || accessibilityLive
    val captureReady = !settings.enableOcr || projectionOn
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("先开深度模式") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "深度模式默认开着。它会记下窗口里的文字和点击，并在抖音、微信视频号、小红书上截屏识别。密码框里的字不记。",
                style = MaterialTheme.typography.bodyLarge,
            )
            if (Build.VERSION.SDK_INT >= 33) {
                Step(
                    index = "1",
                    title = "允许通知",
                    body = "重启后截屏授权会失效，自记要用一条常驻通知提醒你重新允许。",
                    action = if (permissions.notifications) "已允许" else "允许通知",
                    enabled = !permissions.notifications,
                    onClick = { notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS) },
                )
                Step(
                    index = "2",
                    title = "允许受限设置",
                    body = "侧载应用要先在应用详情右上角菜单里允许受限设置，无障碍开关才会出现。",
                    action = "打开应用详情",
                    enabled = true,
                    onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    },
                )
            }
            Step(
                index = if (Build.VERSION.SDK_INT >= 33) "3" else "1",
                title = "打开无障碍",
                body = if (accessibilityOn) {
                    "无障碍已经开着。界面文字和点击会从这里读。"
                } else {
                    "到系统无障碍里打开「自记」。它只读窗口，不会替你点屏幕。"
                },
                action = if (accessibilityOn) "已开启" else "打开无障碍设置",
                enabled = !accessibilityOn,
                onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            )
            Step(
                index = if (Build.VERSION.SDK_INT >= 33) "4" else "2",
                title = "授权截屏",
                body = if (projectionOn) {
                    "屏幕采集已授权。手机会在重启后清掉这次授权，通知里可以再点一次。"
                } else {
                    "选整个屏幕。系统会显示正在录屏的标志。授权在重启后要重新给一次。"
                },
                action = if (projectionOn) "已授权" else "授权截屏",
                enabled = !projectionOn,
                onClick = onRequestProjection,
            )
            Button(
                onClick = onFinished,
                enabled = accessibilityOn && captureReady,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("进入自记") }
            TextButton(onClick = onFinished, modifier = Modifier.fillMaxWidth()) {
                Text("稍后再说")
            }
        }
    }
}

@Composable
private fun Step(
    index: String,
    title: String,
    body: String,
    action: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("$index  $title", style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (enabled) {
            OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(action) }
        } else {
            Text(action, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}
