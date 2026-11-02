package dev.owner.ownlog

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.owner.ownlog.service.CaptureBridge
import dev.owner.ownlog.service.CaptureReminder
import dev.owner.ownlog.ui.ControlScreen
import dev.owner.ownlog.ui.InboxScreen
import dev.owner.ownlog.ui.OnboardingScreen
import dev.owner.ownlog.ui.OwnLogTheme
import dev.owner.ownlog.ui.PermissionChecks
import dev.owner.ownlog.ui.SettingsScreen
import dev.owner.ownlog.ui.screenCaptureIntent
import dev.owner.ownlog.ui.startScreenCapture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val grantRequests = MutableStateFlow(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_GRANT_CAPTURE, false)) {
            grantRequests.value = 1
        }
        val openSettings = intent.getBooleanExtra(EXTRA_SETTINGS, false)
        setContent {
            val context = LocalContext.current
            val app = context.applicationContext as OwnLogApp
            val scope = rememberCoroutineScope()
            val settings by app.settings.flow.collectAsStateWithLifecycle()
            val projectionOn by CaptureBridge.projectionActive.collectAsStateWithLifecycle()
            val accessibilityLive by CaptureBridge.accessibilityActive.collectAsStateWithLifecycle()
            val grants by grantRequests.collectAsState()
            val projectionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) { result ->
                val data = result.data
                if (result.resultCode == Activity.RESULT_OK && data != null) {
                    startScreenCapture(context, result.resultCode, data)
                    scope.launch { app.settings.setOcrEnabled(true) }
                }
                CaptureReminder.sync(context)
            }
            LaunchedEffect(grants) {
                if (grants > 0) projectionLauncher.launch(screenCaptureIntent(context))
            }
            LaunchedEffect(settings.deepMode, settings.onboardingDone, settings.enableOcr, projectionOn, accessibilityLive) {
                if (!settings.deepMode || settings.onboardingDone) return@LaunchedEffect
                val accessibilityOn = accessibilityLive || PermissionChecks.accessibilityEnabled(context)
                val captureReady = !settings.enableOcr || projectionOn
                if (accessibilityOn && captureReady) app.settings.setOnboardingDone()
            }
            LaunchedEffect(settings.onboardingDone, settings.deepMode, settings.enableOcr, projectionOn) {
                CaptureReminder.sync(context)
            }
            OwnLogTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var settingsOpen by rememberSaveable { mutableStateOf(openSettings) }
                    var controlOpen by rememberSaveable { mutableStateOf(false) }
                    val showOnboarding = settings.deepMode && !settings.onboardingDone
                    when {
                        showOnboarding -> OnboardingScreen(
                            onRequestProjection = { projectionLauncher.launch(screenCaptureIntent(context)) },
                            onFinished = {
                                scope.launch {
                                    app.settings.setOnboardingDone()
                                    CaptureReminder.sync(context)
                                }
                            },
                        )
                        controlOpen -> ControlScreen(onBack = { controlOpen = false })
                        settingsOpen -> SettingsScreen(
                            onBack = { settingsOpen = false },
                            onOpenControl = { controlOpen = true },
                        )
                        else -> InboxScreen(
                            onOpenSettings = { settingsOpen = true },
                            onRequestProjection = { projectionLauncher.launch(screenCaptureIntent(context)) },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_GRANT_CAPTURE, false)) {
            grantRequests.value = grantRequests.value + 1
        }
    }

    companion object {
        const val EXTRA_SETTINGS = "open_settings"
        const val EXTRA_GRANT_CAPTURE = "grant_capture"
    }
}
