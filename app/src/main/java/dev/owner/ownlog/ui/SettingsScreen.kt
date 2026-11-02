package dev.owner.ownlog.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.owner.ownlog.OwnLogApp
import dev.owner.ownlog.data.EventEntity
import dev.owner.ownlog.data.PrepareResult
import dev.owner.ownlog.data.SupabaseApi
import dev.owner.ownlog.data.UploadOutcome
import dev.owner.ownlog.domain.AUTH_SIGNED_OUT
import dev.owner.ownlog.domain.SUPABASE_SECRET_KEY_ERROR
import dev.owner.ownlog.domain.isSupabaseSecretKey
import dev.owner.ownlog.domain.OcrPolicy
import dev.owner.ownlog.domain.startOfDayMillis
import dev.owner.ownlog.service.CaptureBridge
import dev.owner.ownlog.service.CaptureReminder
import dev.owner.ownlog.service.LocationService
import dev.owner.ownlog.service.ScreenOcrService
import dev.owner.ownlog.service.UsageScheduler
import dev.owner.ownlog.upload.FileUploadScheduler
import dev.owner.ownlog.upload.UploadScheduler
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val app = context.applicationContext as OwnLogApp
    val scope = rememberCoroutineScope()
    val settings by app.settings.flow.collectAsStateWithLifecycle()
    val todayStart = remember { startOfDayMillis(System.currentTimeMillis(), ZoneId.systemDefault()) }
    val todayCount by app.database.events().countSince(todayStart).collectAsStateWithLifecycle(0)
    val ocrToday by app.database.events().countOcrSince(todayStart).collectAsStateWithLifecycle(0)
    val uploadedBytes = if (settings.uploadedBytesDay == todayStart) settings.uploadedBytesToday else 0L
    val pendingCount by app.database.events().pendingCount().collectAsStateWithLifecycle(0)
    val recent by app.database.events().recent(30).collectAsStateWithLifecycle(emptyList())
    val projectionOn by CaptureBridge.projectionActive.collectAsState()
    val accessibilityLive by CaptureBridge.accessibilityActive.collectAsState()

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

    var url by remember(settings.supabaseUrl) { mutableStateOf(settings.supabaseUrl) }
    var anonKey by remember(settings.supabaseAnonKey) { mutableStateOf(settings.supabaseAnonKey) }
    var packages by remember(settings.ocrPackagesRaw) { mutableStateOf(settings.ocrPackagesRaw) }
    var gapSeconds by remember(settings.interactionOcrGapMs) {
        mutableFloatStateOf(settings.interactionOcrGapMs / 1000f)
    }
    var showKey by remember { mutableStateOf(false) }
    var testMessage by remember { mutableStateOf<String?>(null) }
    var keyError by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val auth by app.authStore.flow.collectAsStateWithLifecycle()
    var email by remember(auth.email) { mutableStateOf(auth.email) }
    var password by remember { mutableStateOf("") }
    var emailCode by remember { mutableStateOf("") }
    var authMessage by remember { mutableStateOf<String?>(null) }
    var authBusy by remember { mutableStateOf(false) }

    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        val ok = granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (ok) {
            startLocation(context)
        } else {
            scope.launch { app.settings.setLocationEnabled(false) }
        }
    }
    val backgroundPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == android.app.Activity.RESULT_OK && data != null) {
            startScreenCapture(context, result.resultCode, data)
            scope.launch { app.settings.setOcrEnabled(true) }
        }
        CaptureReminder.sync(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (onBack == null) "自记" else "设置") },
                navigationIcon = {
                    if (onBack != null) {
                        TextButton(onClick = onBack) { Text("返回") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "默认记下应用使用、通知、位置，以及你放进收件箱的内容。深度模式默认开着，会读界面文字、点击，并截屏识别抖音、微信视频号和小红书。密码框里的字不记。应用本身不整理、不做摘要。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionCard("今天") {
                Text(
                    "识别 $ocrToday 次 · 记下 $todayCount 条 · 约 ${formatApproxBytes(uploadedBytes)}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text("待上传 $pendingCount 条", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "上次上传 ${settings.lastUploadAt?.let(::formatClock) ?: "还没有"}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                settings.lastUploadError?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                settings.lastCollectorError?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "设备 ${settings.deviceId.ifBlank { "生成中" }}",
                        modifier = Modifier.weight(1f),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(
                        onClick = {
                            val id = settings.deviceId
                            if (id.isBlank()) return@TextButton
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            clipboard.setPrimaryClip(ClipData.newPlainText("device id", id))
                            Toast.makeText(context, "已复制设备 ID", Toast.LENGTH_SHORT).show()
                        },
                    ) { Text("复制") }
                }
                Button(
                    onClick = {
                        UploadScheduler.nudgeNow(context, settings.uploadWifiOnly)
                        FileUploadScheduler.nudgeNow(context, settings.uploadWifiOnly)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("立即上传") }
            }

            SectionCard("Supabase") {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Project URL") },
                    placeholder = { Text("https://xxxx.supabase.co") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = anonKey,
                    onValueChange = { anonKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("publishable / anon key") },
                    placeholder = { Text("sb_publishable_... 或 eyJ...") },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "隐藏" else "显示") }
                    },
                )
                Text(
                    "publishable key（sb_publishable_...）或旧的 anon key 都可以。不要填 sb_secret_ 或 service_role。留空则用 local.properties 里的值。文件传到 Storage 的 inbox 桶，请求头和事件一样。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                keyError?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = packages,
                    onValueChange = { packages = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("深度模式 OCR 包名，一行一个") },
                    minLines = 3,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (rejectSecretKey(anonKey) { keyError = it }) {
                                testMessage = null
                                return@Button
                            }
                            keyError = null
                            scope.launch {
                                app.settings.saveConnection(url, anonKey, packages)
                                testMessage = "已保存"
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("保存") }
                    OutlinedButton(
                        onClick = {
                            if (rejectSecretKey(anonKey) { keyError = it }) {
                                testMessage = null
                                return@OutlinedButton
                            }
                            keyError = null
                            scope.launch {
                                testing = true
                                app.settings.saveConnection(url, anonKey, packages)
                                val outcome = withContext(Dispatchers.IO) {
                                    val current = app.settings.get()
                                    when (val prepared = app.authGate.prepare()) {
                                        is PrepareResult.Ready -> SupabaseApi().ping(
                                            current.supabaseUrl,
                                            prepared.apiKey,
                                            prepared.accessToken,
                                        )
                                        is PrepareResult.Paused -> UploadOutcome.Failure(
                                            null,
                                            prepared.message,
                                            retryable = false,
                                            config = true,
                                        )
                                        is PrepareResult.Retry -> UploadOutcome.Failure(
                                            null,
                                            prepared.message,
                                            retryable = true,
                                            config = false,
                                        )
                                    }
                                }
                                testMessage = when (outcome) {
                                    UploadOutcome.Success -> "连接成功，events 表可以读。"
                                    is UploadOutcome.Failure -> "连接失败：${outcome.message}"
                                }
                                testing = false
                            }
                        },
                        enabled = !testing,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (testing) "测试中" else "测试连接") }
                }
                testMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }

            SectionCard("登录") {
                Text(
                    "先在 Supabase Dashboard → Authentication → Users 里建好账号，并关掉公开注册。然后在这里登录。没有登录时，上传会停住。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (auth.secureStoreFailed) {
                    Text("这台手机无法加密保存登录状态。", color = MaterialTheme.colorScheme.error)
                } else if (auth.signedIn) {
                    Text("已登录 ${auth.email.ifBlank { auth.userId }}", style = MaterialTheme.typography.bodyLarge)
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                app.authGate.signOut()
                                authMessage = AUTH_SIGNED_OUT
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("退出") }
                } else {
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("邮箱") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    Button(
                        onClick = {
                            if (rejectSecretKey(anonKey) { keyError = it }) return@Button
                            scope.launch {
                                authBusy = true
                                app.settings.saveConnection(url, anonKey, packages)
                                val error = withContext(Dispatchers.IO) {
                                    app.authGate.signInWithPassword(email, password)
                                }
                                authBusy = false
                                if (error == null) {
                                    password = ""
                                    authMessage = "已登录"
                                    UploadScheduler.nudgeNow(context, app.settings.get().uploadWifiOnly)
                                    FileUploadScheduler.nudgeNow(context, app.settings.get().uploadWifiOnly)
                                } else {
                                    authMessage = error
                                }
                            }
                        },
                        enabled = !authBusy && email.isNotBlank() && password.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (authBusy) "登录中" else "登录") }
                    OutlinedTextField(
                        value = emailCode,
                        onValueChange = { emailCode = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("邮箱验证码，可选") },
                        singleLine = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                if (rejectSecretKey(anonKey) { keyError = it }) return@OutlinedButton
                                scope.launch {
                                    authBusy = true
                                    app.settings.saveConnection(url, anonKey, packages)
                                    val error = withContext(Dispatchers.IO) { app.authGate.sendEmailCode(email) }
                                    authBusy = false
                                    authMessage = error ?: "如果这个邮箱已经建好，验证码会发到邮箱。"
                                }
                            },
                            enabled = !authBusy && email.contains("@"),
                            modifier = Modifier.weight(1f),
                        ) { Text("发送验证码") }
                        OutlinedButton(
                            onClick = {
                                if (rejectSecretKey(anonKey) { keyError = it }) return@OutlinedButton
                                scope.launch {
                                    authBusy = true
                                    app.settings.saveConnection(url, anonKey, packages)
                                    val error = withContext(Dispatchers.IO) {
                                        app.authGate.verifyEmailCode(email, emailCode)
                                    }
                                    authBusy = false
                                    if (error == null) {
                                        authMessage = "已登录"
                                        UploadScheduler.nudgeNow(context, app.settings.get().uploadWifiOnly)
                                        FileUploadScheduler.nudgeNow(context, app.settings.get().uploadWifiOnly)
                                    } else {
                                        authMessage = error
                                    }
                                }
                            },
                            enabled = !authBusy && emailCode.isNotBlank(),
                            modifier = Modifier.weight(1f),
                        ) { Text("验证码登录") }
                    }
                }
                authMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }

            SectionCard("采集") {
                ToggleRow("应用使用", "用使用情况访问记前台和停留，大约每 15 分钟汇总", settings.enableUsage) { enabled ->
                    scope.launch { app.settings.setUsage(enabled) }
                    if (enabled) UsageScheduler.kick(context)
                }
                ToggleRow("通知", "标题、正文、短信验证码都会留下", settings.enableNotification) {
                    scope.launch { app.settings.setNotification(it) }
                }
                ToggleRow("位置", "明显移动，或停留时大约每 8 分钟", settings.enableLocation) { enabled ->
                    scope.launch {
                        app.settings.setLocationEnabled(enabled)
                        if (!enabled) {
                            context.stopService(Intent(context, LocationService::class.java))
                        } else if (!PermissionChecks.locationGranted(context)) {
                            locationPermission.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                ),
                            )
                        } else {
                            if (Build.VERSION.SDK_INT >= 33 && !PermissionChecks.notificationsGranted(context)) {
                                notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            startLocation(context)
                        }
                    }
                }
                ToggleRow("接收分享", "从微信、抖音、小红书或浏览器丢进来", settings.enableShare) {
                    scope.launch { app.settings.setShareEnabled(it) }
                }
                ToggleRow("仅 Wi-Fi 上传", "默认任何网络都会传事件和文件", settings.uploadWifiOnly) { enabled ->
                    scope.launch {
                        app.settings.setUploadWifiOnly(enabled)
                        UploadScheduler.schedulePeriodic(context, enabled)
                        FileUploadScheduler.schedulePeriodic(context, enabled)
                    }
                }
                ToggleRow("深度模式", "记录界面文字、点击和截屏。新安装默认开启", settings.deepMode) { enabled ->
                    scope.launch {
                        app.settings.setDeepMode(enabled)
                        if (!enabled) context.stopService(Intent(context, ScreenOcrService::class.java))
                        CaptureReminder.sync(context)
                    }
                }
                if (settings.deepMode) {
                    ToggleRow("界面文字", "每个窗口的可见文字，重复内容会合并", settings.enableUiText) {
                        scope.launch { app.settings.setUiText(it) }
                    }
                    ToggleRow("点击", "控件上的文字或说明，不记坐标", settings.enableTap) {
                        scope.launch { app.settings.setTap(it) }
                    }
                    ToggleRow("截屏 OCR", "抖音、微信视频号、小红书，或几秒内没有可读文字时", settings.enableOcr) { enabled ->
                        scope.launch {
                            app.settings.setOcrEnabled(enabled)
                            if (!enabled) context.stopService(Intent(context, ScreenOcrService::class.java))
                            CaptureReminder.sync(context)
                        }
                    }
                    if (settings.enableOcr) {
                        Text(
                            "点按或滑动后，同一应用至少隔 ${gapSeconds.roundToInt()} 秒",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Slider(
                            value = gapSeconds,
                            onValueChange = { gapSeconds = it },
                            onValueChangeFinished = {
                                val seconds = gapSeconds.roundToInt().coerceIn(3, 60)
                                scope.launch { app.settings.setInteractionOcrGapMs(seconds * 1000L) }
                            },
                            valueRange = OcrPolicy.MIN_INTERACTION_GAP_MS / 1000f..OcrPolicy.MAX_INTERACTION_GAP_MS / 1000f,
                            steps = 56,
                        )
                    }
                    Text(
                        "名单里的应用：点按大约 1 秒后截一张；滑动停稳约 0.8 秒后再等 1 秒。这类截屏默认每 5 秒一次，可改成 3 到 60 秒，不受全局 20 秒限制。名单外仍要等画面没有可读文字。和上一条已保存的识别文字相似度超过 90% 会丢掉。息屏不截。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionCard("权限") {
                Text(
                    "默认需要使用情况访问、通知使用权、位置，以及深度模式用的无障碍和截屏。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PermissionRow(if (permissions.usage) "使用情况访问已开启" else "使用情况访问未开启", "去授权") {
                    openUsageAccess(context)
                }
                PermissionRow(if (permissions.listener) "通知使用权已开启" else "通知使用权未开启", "打开通知使用权") {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                PermissionRow(if (permissions.notifications) "通知权限已开启" else "通知权限未开启", "允许显示记录中的通知") {
                    if (Build.VERSION.SDK_INT >= 33) notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                PermissionRow(if (permissions.location) "定位权限已开启" else "定位权限未开启", "请求精确位置") {
                    locationPermission.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        ),
                    )
                }
                PermissionRow(
                    if (permissions.backgroundLocation) "后台定位已开启" else "后台定位未单独授权",
                    "服务在跑时通常够用，这个是额外保险",
                ) {
                    if (Build.VERSION.SDK_INT >= 29) {
                        backgroundPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }
                }
                PermissionRow(if (permissions.battery) "已忽略电池优化" else "仍受电池优化限制", "请求不受限制") {
                    try {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    } catch (_: Exception) {
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }
                if (settings.deepMode) {
                    Text(
                        "侧载应用在 Android 13 及以后要先允许受限设置，无障碍开关才会出现。应用详情右上角菜单里。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PermissionRow("受限设置", "打开应用详情") {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    }
                    PermissionRow(if (permissions.accessibility || accessibilityLive) "无障碍已开启" else "无障碍未开启", "打开无障碍设置") {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    PermissionRow(if (projectionOn) "屏幕采集已授权" else "屏幕采集未授权", "授权截屏") {
                        projectionLauncher.launch(screenCaptureIntent(context))
                    }
                    if (projectionOn) {
                        OutlinedButton(
                            onClick = { context.stopService(Intent(context, ScreenOcrService::class.java)) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("停止截屏授权") }
                    }
                }
            }

            SectionCard("最近记录") {
                if (recent.isEmpty()) {
                    Text(
                        "还没有记录。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    recent.forEachIndexed { index, event ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                        EventRow(event)
                    }
                }
            }

            Text(
                "自记 ${dev.owner.ownlog.BuildConfig.VERSION_NAME}。这是一个盒子：快收、存下、交给外面的代理整理。密码框只记“点过”，不记里面的字。支付、账单、验证码通知会记下来。数据只发往上面的 Supabase。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun PermissionRow(title: String, action: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onClick) { Text(action) }
    }
}

@Composable
private fun EventRow(event: EventEntity) {
    val line = event.title?.takeIf { it.isNotBlank() }
        ?: event.content?.lineSequence()?.firstOrNull().orEmpty()
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            "${formatClock(event.occurredAt)}  ${typeLabel(event.type)}  ${event.appLabel ?: event.packageName.orEmpty()}",
            style = MaterialTheme.typography.labelMedium,
        )
        if (line.isNotBlank()) {
            Text(line.take(90), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun rejectSecretKey(raw: String, show: (String) -> Unit): Boolean {
    if (!isSupabaseSecretKey(raw)) return false
    show(SUPABASE_SECRET_KEY_ERROR)
    return true
}

private fun startLocation(context: android.content.Context) {
    ContextCompat.startForegroundService(
        context,
        Intent(context, LocationService::class.java).putExtra(LocationService.EXTRA_ENABLE, true),
    )
}

private fun typeLabel(type: String): String = when (type) {
    "app_fg" -> "打开"
    "app_bg" -> "离开"
    "ui_text" -> "界面"
    "tap" -> "点击"
    "notification" -> "通知"
    "screen_ocr" -> "识别"
    "location" -> "位置"
    "share" -> "分享"
    "note" -> "笔记"
    "photo" -> "照片"
    "voice" -> "语音"
    else -> type
}

private fun formatApproxBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) {
        return if (kb < 10.0) String.format(Locale.US, "%.1f KB", kb) else "${kb.toLong()} KB"
    }
    val mb = kb / 1024.0
    return if (mb < 10.0) String.format(Locale.US, "%.1f MB", mb) else "${mb.toLong()} MB"
}

private val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

private fun formatClock(millis: Long): String {
    return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(clock)
}
