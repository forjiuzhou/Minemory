package dev.owner.ownlog.share

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.owner.ownlog.MainActivity
import dev.owner.ownlog.OwnLogApp
import dev.owner.ownlog.domain.isImageMime
import dev.owner.ownlog.domain.sourceLabel
import dev.owner.ownlog.inbox.CaptureStore
import dev.owner.ownlog.inbox.InboxFiles
import dev.owner.ownlog.ui.OwnLogTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val incoming = readIncomingShare()
        setContent {
            OwnLogTheme {
                ShareSheet(
                    incoming = incoming,
                    onDismiss = { finish() },
                    onOpenSettings = {
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .putExtra(MainActivity.EXTRA_SETTINGS, true)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                        finish()
                    },
                )
            }
        }
    }
}

private data class PreparedFile(val file: File, val mime: String, val image: Boolean)

@Composable
private fun ShareSheet(
    incoming: IncomingShare,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as OwnLogApp
    val scope = rememberCoroutineScope()
    val settings by app.settings.flow.collectAsStateWithLifecycle()
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var files by remember { mutableStateOf<List<PreparedFile>>(emptyList()) }
    var copying by remember { mutableStateOf(incoming.streams.isNotEmpty()) }
    val sheetClicks = remember { MutableInteractionSource() }

    LaunchedEffect(incoming) {
        if (incoming.streams.isEmpty()) {
            copying = false
            return@LaunchedEffect
        }
        val copied = withContext(Dispatchers.IO) {
            incoming.streams.mapNotNull { uri ->
                val mime = context.contentResolver.getType(uri) ?: incoming.mime ?: "application/octet-stream"
                val file = InboxFiles.copy(context, uri, mime) ?: return@mapNotNull null
                PreparedFile(file, mime, isImageMime(mime))
            }
        }
        files = copied
        copying = false
        if (copied.isEmpty()) status = "文件没有读到。可以只保存文字。"
    }

    val preview = incoming.text ?: incoming.subject
    val canSave = !busy && !copying && (!preview.isNullOrBlank() || files.isNotEmpty())

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .clickable(interactionSource = sheetClicks, indication = null, onClick = {})
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("记到自记", style = MaterialTheme.typography.titleLarge)
            Text(
                "来源：${sourceLabel(incoming.sourcePackage)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                copying -> Text("正在读取文件…")
                files.isNotEmpty() -> {
                    val kinds = files.joinToString("、") { if (it.image) "图片" else "文件" }
                    Text("$kinds · ${files.size} 个", style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (!preview.isNullOrBlank()) {
                Text(preview, style = MaterialTheme.typography.bodyLarge, maxLines = 8)
            }
            if (!settings.enableShare) {
                Text("接收分享关着。打开设置里的开关后再丢进来。", color = MaterialTheme.colorScheme.error)
                Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) { Text("去设置") }
            } else {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("备注，可以空着") },
                    minLines = 2,
                )
                Button(
                    onClick = {
                        if (!canSave) return@Button
                        busy = true
                        scope.launch {
                            val saved = withContext(Dispatchers.IO) {
                                saveShare(CaptureStore(app), incoming, files, note)
                            }
                            busy = false
                            if (saved > 0) onDismiss() else status = "没有可保存的内容"
                        }
                    },
                    enabled = canSave,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (busy) "保存中" else "保存") }
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("不保存") }
        }
    }
}

private suspend fun saveShare(
    store: CaptureStore,
    incoming: IncomingShare,
    files: List<PreparedFile>,
    note: String,
): Int {
    if (files.isEmpty()) {
        val id = store.addShare(
            subject = incoming.subject,
            text = incoming.text,
            note = note,
            explicitPackage = incoming.sourcePackage,
            file = null,
            mime = null,
            image = false,
        )
        return if (id.isBlank()) 0 else 1
    }
    var count = 0
    for (file in files) {
        val id = store.addShare(
            subject = incoming.subject,
            text = incoming.text,
            note = note,
            explicitPackage = incoming.sourcePackage,
            file = file.file,
            mime = file.mime,
            image = file.image,
        )
        if (id.isNotBlank()) count++
    }
    return count
}
