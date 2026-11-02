package dev.owner.ownlog.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.owner.ownlog.OwnLogApp
import dev.owner.ownlog.data.EventEntity
import dev.owner.ownlog.service.CaptureBridge
import dev.owner.ownlog.data.FileEntity
import dev.owner.ownlog.domain.AUTH_SIGNED_OUT
import dev.owner.ownlog.domain.isImageMime
import dev.owner.ownlog.inbox.CaptureStore
import dev.owner.ownlog.inbox.InboxFiles
import dev.owner.ownlog.inbox.VoiceSession
import dev.owner.ownlog.inbox.VoiceTake
import dev.owner.ownlog.service.syncPassiveCollectors
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InboxScreen(onOpenSettings: () -> Unit, onRequestProjection: () -> Unit = {}) {
    val context = LocalContext.current
    val app = context.applicationContext as OwnLogApp
    val scope = rememberCoroutineScope()
    val settings by app.settings.flow.collectAsStateWithLifecycle()
    val auth by app.authStore.flow.collectAsStateWithLifecycle()
    val projectionOn by CaptureBridge.projectionActive.collectAsStateWithLifecycle()
    val captures by app.database.events().recentInbox(80).collectAsStateWithLifecycle(emptyList())
    val files by app.database.files().observeAll().collectAsStateWithLifecycle(emptyList())
    val filesByEvent = remember(files) { files.associateBy { it.eventId } }
    var text by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var recording by remember { mutableStateOf(false) }
    val voice = remember { VoiceSession(context) }
    val noteNow = rememberUpdatedState(note)
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) syncPassiveCollectors(context)
            if (event == Lifecycle.Event.ON_PAUSE && voice.isRecording) {
                val take = voice.stop()
                recording = false
                if (take != null) {
                    scope.launch { message = saveVoice(app, take, noteNow.value) }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            voice.cancel()
        }
    }

    fun saveVoiceTake(take: VoiceTake?) {
        if (take == null) {
            message = "没有录到声音"
            return
        }
        scope.launch { message = saveVoice(app, take, noteNow.value) }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        message = if (granted) "可以录音了，再按一次" else "没有麦克风权限"
    }
    var cameraTarget by remember { mutableStateOf<File?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = cameraTarget
        cameraTarget = null
        if (!ok || file == null || !file.exists() || file.length() <= 0L) {
            file?.delete()
            message = "没有拍到照片"
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            withContext(Dispatchers.IO) { CaptureStore(app).addPhoto(file, "image/jpeg", noteNow.value) }
            message = "已收下照片，文字识别在后台进行"
        }
    }
    fun launchCamera() {
        val dir = File(context.filesDir, "inbox").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        cameraTarget = file
        takePicture.launch(uri)
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else message = "没有相机权限"
    }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                var count = 0
                for (uri in uris) {
                    val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                    val file = InboxFiles.copy(context, uri, mime) ?: continue
                    CaptureStore(app).addPhoto(file, if (isImageMime(mime)) mime else "image/jpeg", noteNow.value)
                    count++
                }
                count
            }
            message = if (saved > 0) "已收下 $saved 张，文字识别在后台进行" else "没有读到图片"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("自记") },
                actions = { TextButton(onClick = onOpenSettings) { Text("设置") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "一锅收藏夹。先丢进来，整理交给外面的代理。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (settings.deepMode && settings.enableOcr && !projectionOn) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("截屏授权不在了", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "重启后系统会清掉屏幕采集。深度模式要重新授权，才能继续识别抖音、微信视频号和小红书。",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Button(onClick = onRequestProjection, modifier = Modifier.fillMaxWidth()) {
                                Text("重新授权截屏")
                            }
                        }
                    }
                }
            }
            if (settings.supabaseUrl.isNotBlank() && settings.supabaseAnonKey.isNotBlank() && !auth.signedIn) {
                item {
                    Text(
                        "$AUTH_SIGNED_OUT 内容先留在手机上，到设置里登录后再传。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (settings.supabaseUrl.isBlank() || settings.supabaseAnonKey.isBlank()) {
                item {
                    Text(
                        "还没填 Supabase。内容先留在手机上，填好后再上传。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            settings.lastUploadError?.let { error ->
                item { Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            message?.let { line ->
                item { Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("写一条") },
                            minLines = 3,
                        )
                        OutlinedTextField(
                            value = note,
                            onValueChange = { note = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("备注，可以空着") },
                            singleLine = true,
                        )
                        Button(
                            onClick = {
                                val body = text
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) { CaptureStore(app).addNote(body, note) }
                                    if (ok) {
                                        text = ""
                                        message = "已记下"
                                    } else {
                                        message = "先写一点内容"
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("记下") }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = {
                                    pickImages.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                    )
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("相册") }
                            OutlinedButton(
                                onClick = {
                                    val granted = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.CAMERA,
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (granted) launchCamera() else cameraPermission.launch(Manifest.permission.CAMERA)
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("拍照") }
                        }
                        Surface(
                            color = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .pointerInput(Unit) {
                                    awaitEachGesture {
                                        awaitFirstDown()
                                        val downAt = System.currentTimeMillis()
                                        val wasRecording = voice.isRecording
                                        if (!wasRecording) {
                                            val granted = ContextCompat.checkSelfPermission(
                                                context,
                                                Manifest.permission.RECORD_AUDIO,
                                            ) == PackageManager.PERMISSION_GRANTED
                                            if (!granted) {
                                                micPermission.launch(Manifest.permission.RECORD_AUDIO)
                                                waitForUpOrCancellation()
                                                return@awaitEachGesture
                                            }
                                            if (!voice.start()) {
                                                message = "录音没有开始"
                                                waitForUpOrCancellation()
                                                return@awaitEachGesture
                                            }
                                            recording = true
                                        }
                                        waitForUpOrCancellation()
                                        val elapsed = System.currentTimeMillis() - downAt
                                        if (wasRecording || elapsed >= 350) {
                                            val take = voice.stop()
                                            recording = false
                                            saveVoiceTake(take)
                                        }
                                    }
                                },
                        ) {
                            Text(
                                if (recording) "正在录音，松手或再点一下保存" else "按住说话，或点一下开始",
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
            item {
                val waitingFiles = files.count { !it.uploaded && !it.abandoned }
                Text(
                    "最近 ${captures.size} 条 · 文件待传 $waitingFiles",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (captures.isEmpty()) {
                item {
                    Text(
                        "还是空的。写一句、拍一张，或从微信、抖音、小红书分享到自记。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(captures, key = { it.id }) { event ->
                    CaptureRow(event, filesByEvent[event.id])
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                }
            }
        }
    }
}

@Composable
private fun CaptureRow(event: EventEntity, file: FileEntity?) {
    val line = event.title?.takeIf { it.isNotBlank() }
        ?: event.content?.lineSequence()?.firstOrNull { it.isNotBlank() }
        ?: captureFallback(event.type)
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            "${formatClock(event.occurredAt)}  ${captureLabel(event.type)}  ${captureStatus(event, file)}",
            style = MaterialTheme.typography.labelMedium,
        )
        Text(line.take(120), style = MaterialTheme.typography.bodyMedium)
    }
}

private suspend fun saveVoice(app: OwnLogApp, take: VoiceTake, note: String): String {
    withContext(Dispatchers.IO) {
        CaptureStore(app).addVoice(take.file, take.transcript, note)
    }
    return if (take.transcript.isNullOrBlank()) "已收下语音，转写留给代理" else "已收下语音"
}

private fun captureStatus(event: EventEntity, file: FileEntity?): String = when {
    event.abandoned || file?.abandoned == true -> "失败"
    file != null && !file.uploaded && event.uploaded -> "文件待传"
    !event.uploaded || (file != null && !file.uploaded) -> "待上传"
    else -> "已上传"
}

private fun captureLabel(type: String): String = when (type) {
    "note" -> "笔记"
    "photo" -> "照片"
    "voice" -> "语音"
    "share" -> "分享"
    else -> type
}

private fun captureFallback(type: String): String = when (type) {
    "photo" -> "照片"
    "voice" -> "语音"
    else -> "没有文字"
}

private val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

private fun formatClock(millis: Long): String {
    return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(clock)
}

internal fun openUsageAccess(context: android.content.Context) {
    val targeted = Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    val plain = Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
    for (intent in listOf(targeted, plain)) {
        try {
            context.startActivity(intent)
            return
        } catch (_: Exception) {
        }
    }
}
