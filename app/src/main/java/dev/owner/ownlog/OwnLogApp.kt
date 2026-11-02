package dev.owner.ownlog

import android.app.Application
import dev.owner.ownlog.data.AuthGate
import dev.owner.ownlog.data.AuthStore
import dev.owner.ownlog.data.AppDatabase
import dev.owner.ownlog.data.EventRepository
import dev.owner.ownlog.data.FileRepository
import dev.owner.ownlog.data.FileUploader
import dev.owner.ownlog.data.OwnerBinder
import dev.owner.ownlog.data.PayloadUpdater
import dev.owner.ownlog.data.SettingsStore
import dev.owner.ownlog.data.SupabaseApi
import dev.owner.ownlog.data.Uploader
import dev.owner.ownlog.service.CaptureReminder
import dev.owner.ownlog.service.EventPipeline
import dev.owner.ownlog.service.Notifier
import dev.owner.ownlog.service.UsageScheduler
import dev.owner.ownlog.upload.FileUploadScheduler
import dev.owner.ownlog.upload.UploadScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class OwnLogApp : Application() {
    internal lateinit var database: AppDatabase
        private set
    internal lateinit var settings: SettingsStore
        private set
    internal lateinit var authStore: AuthStore
        private set
    internal lateinit var authGate: AuthGate
        private set
    internal lateinit var repository: EventRepository
        private set
    internal lateinit var files: FileRepository
        private set
    internal lateinit var payloads: PayloadUpdater
        private set
    internal lateinit var pipeline: EventPipeline
        private set
    internal lateinit var uploader: Uploader
        private set
    internal lateinit var fileUploader: FileUploader
        private set

    internal val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this
        database = AppDatabase.create(this)
        settings = SettingsStore(this)
        runBlocking {
            withTimeoutOrNull(3_000) { settings.warm() }
        }
        settings.startCollecting(appScope)
        val api = SupabaseApi()
        authStore = AuthStore(this)
        authGate = AuthGate(settings, authStore, api)
        repository = EventRepository(database.events(), settings) {
            UploadScheduler.nudge(this, settings.get().uploadWifiOnly)
        }
        files = FileRepository(database.files()) {
            FileUploadScheduler.nudge(this, settings.get().uploadWifiOnly)
        }
        payloads = PayloadUpdater(database.events(), settings, authGate, api)
        val binder = OwnerBinder(database.files(), payloads)
        pipeline = EventPipeline(this, repository, settings)
        uploader = Uploader(database.events(), settings, authGate, binder, api)
        fileUploader = FileUploader(database.files(), settings, authGate, binder, api)
        Notifier.ensureChannel(this)
        val wifi = settings.get().uploadWifiOnly
        UploadScheduler.schedulePeriodic(this, wifi)
        FileUploadScheduler.schedulePeriodic(this, wifi)
        UsageScheduler.schedule(this)
        CaptureReminder.sync(this)
    }

    companion object {
        lateinit var instance: OwnLogApp
            private set
    }
}
