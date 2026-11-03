package dev.owner.ownlog.service

import android.content.Context
import android.util.Log
import dev.owner.ownlog.data.EventRepository
import dev.owner.ownlog.data.Settings
import dev.owner.ownlog.data.SettingsStore
import dev.owner.ownlog.domain.ClickSnap
import dev.owner.ownlog.domain.ConsolidatedUi
import dev.owner.ownlog.domain.Engagement
import dev.owner.ownlog.domain.EngagementDedupe
import dev.owner.ownlog.domain.FeedCoalescer
import dev.owner.ownlog.domain.FeedScreen
import dev.owner.ownlog.domain.GeoFix
import dev.owner.ownlog.domain.JsonObj
import dev.owner.ownlog.domain.LocationPolicy
import dev.owner.ownlog.domain.NotificationSnap
import dev.owner.ownlog.domain.OcrPolicy
import dev.owner.ownlog.domain.OcrReason
import dev.owner.ownlog.domain.OcrSimilarity
import dev.owner.ownlog.domain.ScreenChangeFilter
import dev.owner.ownlog.domain.ScreenText
import dev.owner.ownlog.domain.TextNode
import dev.owner.ownlog.domain.decideClick
import dev.owner.ownlog.domain.jsonStringArray
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class EventPipeline(
    private val app: Context,
    private val repo: EventRepository,
    private val settingsStore: SettingsStore,
    private val screens: ScreenChangeFilter = ScreenChangeFilter(),
    private val ocrScreens: ScreenChangeFilter = ScreenChangeFilter(sameWindowMinGapMs = 0),
    private val notifications: NotificationDedupeHolder = NotificationDedupeHolder(),
    private val ocrRunner: OcrRunner = OcrRunner(),
) {
    private val serial: CoroutineContext = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ownlog-pipeline")
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + serial)

    private var foregroundPkg: String = ""
    private var lastForegroundPkg: String? = null
    private var lastForegroundTitle: String? = null
    private var screenChangedAt: Long = 0L
    private var lastUsefulTextAt: Long? = null
    private var lastTextSignature: String? = null
    private var lastOcrAt: Long? = null
    private val lastOcrText = HashMap<String, String>()
    private val lastInteractionOcrAt = HashMap<String, Long>()
    private var ocrInFlight: Boolean = false
    private val feeds = FeedCoalescer()
    private var feedJob: Job? = null
    private var pendingFeed: PendingFeed? = null
    private var screenInteractive: Boolean = true
    private var ocrTimer: Job? = null
    private var interactionJob: Job? = null
    private var pendingInteractionPkg: String? = null
    private var pendingInteractionAt: Long = 0L
    private var lastFix: GeoFix? = null
    private var lastScreenPackage: String = ""
    private var lastScreenTitle: String? = null
    private var lastScreenNodes: List<TextNode> = emptyList()
    private val engagementDedupe = EngagementDedupe()

    init {
        scope.launch {
            lastFix = LocationPrefs.load(app)
        }
    }

    fun onForeground(packageName: String, windowTitle: String?, now: Long) {
        scope.launch { handleForeground(packageName, windowTitle, now) }
    }

    @Suppress("UNUSED_PARAMETER")
    fun onScreenOff(now: Long) {
        scope.launch {
            screenInteractive = false
            ocrTimer?.cancel()
            interactionJob?.cancel()
            interactionJob = null
            pendingInteractionPkg = null
            foregroundPkg = ""
            lastScreenPackage = ""
            lastScreenTitle = null
            lastScreenNodes = emptyList()
        }
    }

    fun onScreenOn(now: Long) {
        scope.launch {
            screenInteractive = true
            val pkg = lastForegroundPkg ?: return@launch
            handleForeground(pkg, lastForegroundTitle, now)
        }
    }

    fun onScreen(
        packageName: String,
        windowKey: String,
        windowTitle: String?,
        nodes: List<TextNode>,
        stateChange: Boolean,
        now: Long,
    ) {
        scope.launch {
            val settings = settingsStore.get()
            if (packageName == app.packageName) return@launch
            lastScreenPackage = packageName
            if (!windowTitle.isNullOrBlank()) lastScreenTitle = windowTitle
            lastScreenNodes = nodes
            if (stateChange) {
                screenChangedAt = now
                foregroundPkg = packageName
            }
            val consolidated = if (settings.deepMode && settings.enableUiText) {
                FeedScreen.consolidate(packageName, nodes, windowTitle)
            } else {
                null
            }
            val extracted = ScreenText.extract(nodes, windowTitle)
            if (packageName == foregroundPkg && extracted.signature.isNotEmpty() && extracted.signature != lastTextSignature) {
                lastTextSignature = extracted.signature
                screenChangedAt = now
            }
            if (consolidated != null) {
                offerFeed(packageName, consolidated, windowTitle, extracted, settings, now)
            } else if (settings.deepMode && settings.enableUiText && extracted.signature.isNotEmpty() &&
                screens.shouldRecord(packageName, windowKey, extracted.signature, now)
            ) {
                val extras = JsonObj()
                    .str("window_title", windowTitle)
                    .lng("node_count", extracted.keptLines.toLong())
                    .lng("dropped_password_fields", extracted.droppedPasswordFields.toLong())
                    .raw("view_ids", jsonStringArray(extracted.viewIds))
                    .build()
                repo.record(
                    type = "ui_text",
                    occurredAt = now,
                    packageName = packageName,
                    appLabel = AppLabels.label(app, packageName),
                    title = extracted.title,
                    content = extracted.content,
                    extrasJson = extras,
                    deviceId = settings.deviceId,
                )
                Log.i(TAG, "record type=ui_text pkg=$packageName")
            }
            val useful = consolidated?.content?.isNotBlank() == true || extracted.content.isNotBlank()
            if (useful && packageName == foregroundPkg) {
                lastUsefulTextAt = now
            }
            maybeScheduleOcr(settings, now)
            armOcrTimer(settings)
        }
    }

    fun onClick(packageName: String, snap: ClickSnap, now: Long) {
        scope.launch {
            val settings = settingsStore.get()
            if (!settings.deepMode || packageName == app.packageName) return@launch
            if (settings.enableTap) {
                val decision = decideClick(snap)
                if (decision.record) {
                    val extras = JsonObj()
                        .str("view_id", snap.viewId)
                        .str("class_name", snap.className)
                        .bool("password_skipped", decision.passwordSkipped)
                        .build()
                    repo.record(
                        type = "tap",
                        occurredAt = now,
                        packageName = packageName,
                        appLabel = AppLabels.label(app, packageName),
                        title = if (decision.passwordSkipped) "密码框" else decision.content?.take(80),
                        content = decision.content,
                        extrasJson = extras,
                        deviceId = settings.deviceId,
                    )
                    Log.i(TAG, "record type=tap pkg=$packageName password=${decision.passwordSkipped}")
                }
            }
            if (settings.enableEngagement) {
                recordEngagement(packageName, snap, settings, now)
            }
        }
    }

    fun onInteraction(packageName: String, scrolled: Boolean, now: Long) {
        val readyAt = if (scrolled) now + OcrPolicy.SCROLL_SETTLE_MS else now
        scope.launch { armInteraction(packageName, readyAt) }
    }

    fun onNotification(snap: NotificationSnap) {
        scope.launch {
            val settings = settingsStore.get()
            if (!settings.enableNotification || snap.packageName == app.packageName) return@launch
            val signature = listOfNotNull(snap.title, snap.content).joinToString("\n")
            if (!notifications.shouldRecord(snap.key, signature, snap.postedAt)) return@launch
            val extras = JsonObj()
                .str("channel_id", snap.channelId)
                .str("category", snap.category)
                .str("sub_text", snap.subText)
                .lng("notification_id", snap.notificationId.toLong())
                .bool("ongoing", snap.ongoing)
                .build()
            repo.record(
                type = "notification",
                occurredAt = snap.postedAt,
                packageName = snap.packageName,
                appLabel = AppLabels.label(app, snap.packageName),
                title = snap.title,
                content = snap.content,
                extrasJson = extras,
                deviceId = settings.deviceId,
            )
            Log.i(TAG, "record type=notification pkg=${snap.packageName}")
        }
    }

    fun onLocation(lat: Double, lng: Double, accuracy: Double, timeMillis: Long, provider: String?) {
        scope.launch {
            val settings = settingsStore.get()
            if (!settings.enableLocation) return@launch
            val next = GeoFix(lat, lng, accuracy, timeMillis)
            if (!LocationPolicy.shouldStore(lastFix, next)) return@launch
            lastFix = next
            LocationPrefs.save(app, next)
            val extras = JsonObj()
                .num("lat", lat, 7)
                .num("lng", lng, 7)
                .num("accuracy_m", accuracy, 1)
                .str("provider", provider)
                .build()
            repo.record(
                type = "location",
                occurredAt = timeMillis,
                packageName = null,
                appLabel = null,
                title = null,
                content = String.format(java.util.Locale.US, "%.5f, %.5f", lat, lng),
                extrasJson = extras,
                deviceId = settings.deviceId,
            )
            Log.i(TAG, "record type=location")
        }
    }

    private suspend fun recordEngagement(packageName: String, snap: ClickSnap, settings: Settings, now: Long) {
        val sameScreen = lastScreenPackage == packageName
        val hit = Engagement.detect(
            packageName = packageName,
            snap = snap,
            windowTitle = if (sameScreen) lastScreenTitle else null,
            nodes = if (sameScreen) lastScreenNodes else emptyList(),
        ) ?: return
        if (!engagementDedupe.shouldRecord(packageName, hit.action, Engagement.dedupeTarget(hit), now)) return
        repo.record(
            type = Engagement.TYPE,
            occurredAt = now,
            packageName = packageName,
            appLabel = AppLabels.label(app, packageName),
            title = hit.title,
            content = hit.content,
            extrasJson = Engagement.extrasJson(hit),
            deviceId = settings.deviceId,
        )
        Log.i(TAG, "record type=engagement pkg=$packageName action=${hit.action}")
    }

    private suspend fun handleForeground(packageName: String, windowTitle: String?, now: Long) {
        if (packageName == app.packageName) return
        screenInteractive = true
        if (packageName != pendingInteractionPkg) {
            interactionJob?.cancel()
            interactionJob = null
            pendingInteractionPkg = null
        }
        val settings = settingsStore.get()
        lastForegroundPkg = packageName
        if (!windowTitle.isNullOrBlank()) lastForegroundTitle = windowTitle
        foregroundPkg = packageName
        screenChangedAt = now
        if (settings.deepMode) armOcrTimer(settings)
    }

    private fun maybeScheduleOcr(settings: Settings, now: Long) {
        if (!ocrAllowed(settings) || foregroundPkg.isBlank()) return
        val reason = OcrPolicy.decide(
            packageName = foregroundPkg,
            ocrPackages = settings.ocrPackages,
            lastUsefulTextAt = lastUsefulTextAt,
            lastOcrAt = lastOcrAt,
            screenChangedAt = screenChangedAt,
            now = now,
        ) ?: return
        beginOcr(settings, foregroundPkg, reason, interaction = false)
    }

    private fun ocrAllowed(settings: Settings): Boolean {
        return settings.deepMode && settings.enableOcr && screenInteractive && !ocrInFlight
    }

    private fun armInteraction(packageName: String, readyAt: Long) {
        if (!screenInteractive || packageName.isBlank() || packageName == app.packageName) return
        val settings = settingsStore.get()
        if (!settings.deepMode || !settings.enableOcr || packageName !in settings.ocrPackages) return
        foregroundPkg = packageName
        pendingInteractionPkg = packageName
        pendingInteractionAt = readyAt
        interactionJob?.cancel()
        interactionJob = scope.launch {
            var due = readyAt
            var waits = 0
            while (waits < 8) {
                val waitMs = (due + OcrPolicy.INTERACTION_DELAY_MS - System.currentTimeMillis()).coerceAtLeast(0L)
                delay(waitMs)
                if (pendingInteractionPkg != packageName || pendingInteractionAt != readyAt) return@launch
                if (!screenInteractive || foregroundPkg != packageName) return@launch
                val latest = settingsStore.get()
                if (!latest.deepMode || !latest.enableOcr || packageName !in latest.ocrPackages) return@launch
                val now = System.currentTimeMillis()
                val gap = OcrPolicy.clampInteractionGap(latest.interactionOcrGapMs)
                val previous = lastInteractionOcrAt[packageName]
                if (previous != null && now - previous < gap) {
                    due = previous + gap - OcrPolicy.INTERACTION_DELAY_MS
                    waits += 1
                    continue
                }
                if (ocrInFlight) {
                    due = now + 400L - OcrPolicy.INTERACTION_DELAY_MS
                    waits += 1
                    continue
                }
                val reason = OcrPolicy.decideInteraction(
                    packageName = packageName,
                    ocrPackages = latest.ocrPackages,
                    lastInteractionOcrAt = previous,
                    interactionAt = pendingInteractionAt,
                    now = now,
                    gapMs = latest.interactionOcrGapMs,
                ) ?: return@launch
                beginOcr(latest, packageName, reason, interaction = true)
                return@launch
            }
        }
    }

    private fun beginOcr(settings: Settings, pkg: String, reason: OcrReason, interaction: Boolean) {
        if (pkg.isBlank() || ocrInFlight || !screenInteractive) return
        ocrInFlight = true
        val deviceId = settings.deviceId
        scope.launch(Dispatchers.IO) {
            var error: String? = null
            var ran = false
            try {
                val allowed = withContext(serial) { screenInteractive && foregroundPkg == pkg }
                if (!allowed) return@launch
                ran = true
                val frame = CaptureBridge.capture()
                if (frame == null) {
                    error = "截屏失败。请重新授权屏幕采集，或确认无障碍服务已开启。"
                } else {
                    val read = ocrRunner.read(frame.bitmap)
                    if (read != null && read.lines.isNotEmpty()) {
                        val extracted = ScreenText.extract(
                            read.lines.map { TextNode(it, null, null, false, 0) },
                            windowTitle = null,
                        )
                        val stamp = System.currentTimeMillis()
                        val nearDuplicate = OcrSimilarity.isNearDuplicate(lastOcrText[pkg], extracted.content)
                        if (!nearDuplicate && extracted.signature.isNotEmpty() &&
                            ocrScreens.shouldRecord(pkg, "ocr", extracted.signature, stamp)
                        ) {
                            lastOcrText[pkg] = extracted.content
                            val extras = JsonObj()
                                .str("reason", reason.wire)
                                .str("capture_method", frame.method)
                                .lng("width", frame.width.toLong())
                                .lng("height", frame.height.toLong())
                            if (read.confidence != null) extras.num("confidence", read.confidence, 3)
                            repo.record(
                                type = "screen_ocr",
                                occurredAt = stamp,
                                packageName = pkg,
                                appLabel = AppLabels.label(app, pkg),
                                title = extracted.title,
                                content = extracted.content,
                                extrasJson = extras.build(),
                                deviceId = deviceId,
                            )
                            Log.i(TAG, "record type=screen_ocr pkg=$pkg method=${frame.method} reason=${reason.wire}")
                        }
                    }
                }
            } catch (errorSeen: Exception) {
                if (errorSeen is CancellationException) throw errorSeen
                error = "文字识别失败：${errorSeen.message ?: errorSeen.javaClass.simpleName}"
            } finally {
                withContext(serial) {
                    ocrInFlight = false
                    if (ran) {
                        val stamp = System.currentTimeMillis()
                        lastOcrAt = stamp
                        if (interaction) lastInteractionOcrAt[pkg] = stamp
                    }
                }
                if (ran) settingsStore.setCollectorError(error)
            }
        }
    }

    private fun offerFeed(
        packageName: String,
        screen: ConsolidatedUi,
        windowTitle: String?,
        extracted: dev.owner.ownlog.domain.ExtractedScreen,
        settings: Settings,
        now: Long,
    ) {
        val key = "$packageName|${screen.screenHint}"
        val sample = FeedCoalescer.Sample(key, screen.signature, now)
        val displaced = feeds.offer(sample)
        val previous = pendingFeed
        val next = PendingFeed(key, screen, windowTitle, extracted, settings.deviceId, packageName, now)
        pendingFeed = next
        if (displaced != null && previous != null && previous.key == displaced.key && previous.screen.signature == displaced.signature) {
            recordFeed(previous)
        }
        feedJob?.cancel()
        feedJob = scope.launch {
            delay(FeedScreen.QUIET_MS)
            val ready = feeds.takeReady(System.currentTimeMillis(), force = true) ?: return@launch
            val payload = pendingFeed ?: return@launch
            if (payload.key != ready.key || payload.screen.signature != ready.signature) return@launch
            pendingFeed = null
            recordFeed(payload)
        }
    }

    private fun recordFeed(payload: PendingFeed) {
        val screen = payload.screen
        val extras = JsonObj()
            .str("window_title", payload.windowTitle)
            .str("screen_hint", screen.screenHint)
            .str("author", screen.author)
            .lng("node_count", payload.extracted.keptLines.toLong())
            .lng("dropped_password_fields", payload.extracted.droppedPasswordFields.toLong())
            .build()
        scope.launch {
            repo.record(
                type = "ui_text",
                occurredAt = payload.at,
                packageName = payload.packageName,
                appLabel = AppLabels.label(app, payload.packageName),
                title = screen.title,
                content = screen.content,
                extrasJson = extras,
                deviceId = payload.deviceId,
            )
        }
        Log.i(TAG, "record type=ui_text pkg=${payload.packageName} hint=${screen.screenHint}")
    }

    private fun armOcrTimer(settings: Settings) {
        if (ocrTimer?.isActive == true) return
        if (!settings.deepMode || !settings.enableOcr || !screenInteractive || foregroundPkg.isBlank()) return
        val inList = foregroundPkg in settings.ocrPackages
        val now = System.currentTimeMillis()
        val silenceAnchor = maxOf(lastUsefulTextAt ?: Long.MIN_VALUE, screenChangedAt)
        val noText = now - silenceAnchor >= OcrPolicy.NO_TEXT_DELAY_MS
        if (!inList && !noText) return
        ocrTimer = scope.launch {
            delay(5_000)
            ocrTimer = null
            val latest = settingsStore.get()
            maybeScheduleOcr(latest, System.currentTimeMillis())
            armOcrTimer(settingsStore.get())
        }
    }

    private companion object {
        const val TAG = "OwnLog"
    }
}

private data class PendingFeed(
    val key: String,
    val screen: ConsolidatedUi,
    val windowTitle: String?,
    val extracted: dev.owner.ownlog.domain.ExtractedScreen,
    val deviceId: String,
    val packageName: String,
    val at: Long,
)

internal class NotificationDedupeHolder {
    private val delegate = dev.owner.ownlog.domain.NotificationDedupe()
    fun shouldRecord(key: String, signature: String, now: Long): Boolean = delegate.shouldRecord(key, signature, now)
}
