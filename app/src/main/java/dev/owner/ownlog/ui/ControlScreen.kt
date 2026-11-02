package dev.owner.ownlog.ui

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.owner.ownlog.OwnLogApp
import dev.owner.ownlog.domain.ControlCommand
import dev.owner.ownlog.domain.ControlCommands
import dev.owner.ownlog.domain.SwipeDirection
import dev.owner.ownlog.service.CaptureBridge
import dev.owner.ownlog.service.ControlBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ControlScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as OwnLogApp
    val scope = rememberCoroutineScope()
    val settings by app.settings.flow.collectAsStateWithLifecycle()
    val accessibilityOn by CaptureBridge.accessibilityActive.collectAsStateWithLifecycle()
    var line by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var last by remember { mutableStateOf<String?>(null) }
    val canRun = settings.enableControl && accessibilityOn && !busy

    fun launch(command: ControlCommand) {
        if (!settings.enableControl || !accessibilityOn || busy) return
        busy = true
        scope.launch {
            try {
                if (command.backgroundsFirst) {
                    (context as? Activity)?.moveTaskToBack(true)
                    delay(450)
                }
                val result = ControlBridge.execute(context, command)
                last = result.message
            } finally {
                busy = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("操作手机") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "采集照旧。这里只是试着让无障碍去点、滑、返回，或往当前焦点的普通输入框里写字。密码框会拒绝。点击、输入和滑动会先把自记退到后台。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("允许操作手机", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (settings.enableControl) "默认开着。关掉后下面的按钮不会执行" else "已关掉。采集还在，手机不会被操作",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.enableControl,
                    onCheckedChange = { enabled ->
                        scope.launch { app.settings.setControlEnabled(enabled) }
                    },
                )
            }
            Text(
                when {
                    !accessibilityOn -> "无障碍还没连上。系统里打开自记之后才能操作。"
                    busy -> "正在执行。"
                    else -> "无障碍已连接。"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!accessibilityOn) {
                OutlinedButton(
                    onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("打开无障碍设置") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { launch(ControlCommand.Back) }, enabled = canRun, modifier = Modifier.weight(1f)) {
                    Text("系统返回")
                }
                Button(onClick = { launch(ControlCommand.Home) }, enabled = canRun, modifier = Modifier.weight(1f)) {
                    Text("主页")
                }
                Button(onClick = { launch(ControlCommand.Recents) }, enabled = canRun, modifier = Modifier.weight(1f)) {
                    Text("最近")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { launch(ControlCommand.Swipe(SwipeDirection.UP)) },
                    enabled = canRun,
                    modifier = Modifier.weight(1f),
                ) { Text("上滑") }
                OutlinedButton(
                    onClick = { launch(ControlCommand.Swipe(SwipeDirection.DOWN)) },
                    enabled = canRun,
                    modifier = Modifier.weight(1f),
                ) { Text("下滑") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { launch(ControlCommand.Swipe(SwipeDirection.LEFT)) },
                    enabled = canRun,
                    modifier = Modifier.weight(1f),
                ) { Text("左滑") }
                OutlinedButton(
                    onClick = { launch(ControlCommand.Swipe(SwipeDirection.RIGHT)) },
                    enabled = canRun,
                    modifier = Modifier.weight(1f),
                ) { Text("右滑") }
            }
            OutlinedTextField(
                value = line,
                onValueChange = { line = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("一行命令") },
                placeholder = { Text("tap 搜索") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
            Button(
                onClick = {
                    val command = ControlCommands.parse(line)
                    if (command == null) {
                        last = "认不出这条命令"
                    } else {
                        launch(command)
                    }
                },
                enabled = canRun && line.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("执行") }
            last?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            Text(
                ControlCommands.examples,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
