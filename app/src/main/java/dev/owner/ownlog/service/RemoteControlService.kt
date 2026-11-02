package dev.owner.ownlog.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import dev.owner.ownlog.OwnLogApp
import dev.owner.ownlog.data.PhoneCommandApi
import dev.owner.ownlog.data.PrepareResult
import dev.owner.ownlog.domain.JsonValue
import dev.owner.ownlog.domain.PhoneClaim
import dev.owner.ownlog.domain.asObject
import dev.owner.ownlog.domain.isoTimestamp
import dev.owner.ownlog.domain.parseJson
import dev.owner.ownlog.domain.parseRemoteCommands
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * While accessibility is connected and 「允许远程控制」 is on, poll phone_commands
 * as the signed-in user. There is no listening port and no service-role key.
 */
class RemoteControlService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        RemoteControlLoop.start(this)
        return START_STICKY
    }

    private fun startInForeground() {
        val notification = Notifier.ongoing(
            this,
            "自记可以远程操作",
            "代理可以打开应用、点按、滑动，并读取当前界面文字。设置里可以关掉。",
        )
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                Notifier.REMOTE_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(Notifier.REMOTE_ID, notification)
        }
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, RemoteControlService::class.java)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (error: Exception) {
                Log.w(TAG, "startForegroundService", error)
                RemoteControlLoop.start(context.applicationContext)
            }
        }

        fun stop(context: Context) {
            RemoteControlLoop.stop()
            context.stopService(Intent(context, RemoteControlService::class.java))
        }

        private const val TAG = "OwnLogRemote"
    }
}

internal object RemoteControlLoop {
    private const val POLL_MS = 1_500L
    private const val TAG = "OwnLogRemote"

    @Volatile
    private var job: Job? = null

    fun start(context: Context) {
        val app = context.applicationContext as? OwnLogApp ?: return
        synchronized(this) {
            if (job?.isActive == true) return
            job = app.appScope.launch(Dispatchers.IO) { run(app) }
        }
    }

    fun stop() {
        synchronized(this) {
            job?.cancel()
            job = null
        }
    }

    private suspend fun run(app: OwnLogApp) {
        val api = PhoneCommandApi()
        while (kotlin.coroutines.coroutineContext.isActive) {
            val keepGoing = try {
                tick(app, api)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "tick", error)
                true
            }
            if (!keepGoing) {
                app.stopService(Intent(app, RemoteControlService::class.java))
                break
            }
            delay(POLL_MS)
        }
    }

    private suspend fun tick(app: OwnLogApp, api: PhoneCommandApi): Boolean {
        val settings = app.settings.get()
        if (!settings.enableRemote) {
            if (app.authStore.flow.value.signedIn) {
                report(app, api, a11y = CaptureBridge.accessibilityService() != null, remote = false)
            }
            return false
        }
        val service = CaptureBridge.accessibilityService()
        if (service == null) {
            if (app.authStore.flow.value.signedIn) {
                report(app, api, a11y = false, remote = true)
            }
            return true
        }
        if (!app.authStore.flow.value.signedIn) return true
        val prepared = app.authGate.prepare()
        if (prepared !is PrepareResult.Ready) return true
        val now = System.currentTimeMillis()
        val nowIso = isoTimestamp(now)
        val deviceId = settings.deviceId.ifBlank { app.settings.ensureDeviceId() }
        val foreground = withContext(Dispatchers.Main) {
            try {
                AccessibilityActuator(service).foregroundPackage()
            } catch (_: Exception) {
                null
            }
        }
        api.upsertPresence(
            projectUrl = settings.supabaseUrl,
            apiKey = prepared.apiKey,
            accessToken = prepared.accessToken,
            userId = prepared.userId,
            deviceId = deviceId,
            a11yConnected = true,
            remoteEnabled = true,
            foregroundPackage = foreground,
            nowIso = nowIso,
        )
        api.cancelExpired(settings.supabaseUrl, prepared.apiKey, prepared.accessToken, prepared.userId, nowIso)
        val staleBefore = isoTimestamp(now - PhoneClaim.RUNNING_STALE_MS)
        api.failStale(
            settings.supabaseUrl,
            prepared.apiKey,
            prepared.accessToken,
            prepared.userId,
            staleBefore,
            nowIso,
        )
        val listed = api.listPending(
            settings.supabaseUrl,
            prepared.apiKey,
            prepared.accessToken,
            prepared.userId,
            nowIso,
        ) ?: return true
        val next = parseRemoteCommands(listed).firstOrNull {
            PhoneClaim.acceptable(it.status, it.expiresAtMillis, now, it.deviceId, deviceId)
        } ?: return true
        val claimedBody = api.claim(
            settings.supabaseUrl,
            prepared.apiKey,
            prepared.accessToken,
            prepared.userId,
            next.id,
            deviceId,
            isoTimestamp(System.currentTimeMillis()),
        ) ?: return true
        val claimed = parseRemoteCommands(claimedBody).firstOrNull() ?: return true
        val argsJson = extractArgs(claimedBody) ?: "{}"
        val resultJson = RemoteExecutor(service).execute(claimed.action, argsJson)
        val ok = parseJson(resultJson).asObject()?.get("ok") == JsonValue.Bool(true)
        val status = if (ok) "done" else "failed"
        val finished = api.finish(
            settings.supabaseUrl,
            prepared.apiKey,
            prepared.accessToken,
            prepared.userId,
            claimed.id,
            status,
            resultJson,
            isoTimestamp(System.currentTimeMillis()),
        )
        Log.i(TAG, "action=${claimed.action} ok=$ok posted=$finished")
        return true
    }

    private suspend fun report(app: OwnLogApp, api: PhoneCommandApi, a11y: Boolean, remote: Boolean) {
        val settings = app.settings.get()
        val prepared = app.authGate.prepare()
        if (prepared !is PrepareResult.Ready) return
        val deviceId = settings.deviceId.ifBlank { return }
        api.upsertPresence(
            projectUrl = settings.supabaseUrl,
            apiKey = prepared.apiKey,
            accessToken = prepared.accessToken,
            userId = prepared.userId,
            deviceId = deviceId,
            a11yConnected = a11y,
            remoteEnabled = remote,
            foregroundPackage = null,
            nowIso = isoTimestamp(System.currentTimeMillis()),
        )
    }

    private fun extractArgs(body: String): String? {
        val marker = "\"args\":"
        val start = body.indexOf(marker)
        if (start < 0) return null
        var index = start + marker.length
        while (index < body.length && body[index].isWhitespace()) index++
        if (index >= body.length) return null
        if (body[index] == 'n') return "{}"
        if (body[index] != '{') return null
        var depth = 0
        val begin = index
        while (index < body.length) {
            val ch = body[index]
            if (ch == '"') {
                index++
                while (index < body.length) {
                    if (body[index] == '\\') {
                        index += 2
                        continue
                    }
                    if (body[index] == '"') break
                    index++
                }
            } else if (ch == '{') {
                depth++
            } else if (ch == '}') {
                depth--
                if (depth == 0) return body.substring(begin, index + 1)
            }
            index++
        }
        return null
    }
}
