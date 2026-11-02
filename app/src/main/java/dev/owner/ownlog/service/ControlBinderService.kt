package dev.owner.ownlog.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import dev.owner.ownlog.OwnLogApp
import java.lang.ref.WeakReference
import kotlinx.coroutines.launch

/**
 * Same-UID binder for a future on-device companion. Not exported, no intent
 * filter, and it does not listen on the network. See docs/control.md.
 *
 * Client sends [MSG_RUN] with [KEY_LINE]. The reply is [MSG_RESULT] and
 * [KEY_RESULT] in [dev.owner.ownlog.domain.ControlResult.wire] form.
 */
class ControlBinderService : Service() {
    private val messenger = Messenger(IncomingHandler(this))

    override fun onBind(intent: Intent?): IBinder? {
        if (Binder.getCallingUid() != Process.myUid()) return null
        return messenger.binder
    }

    private class IncomingHandler(service: ControlBinderService) : Handler(Looper.getMainLooper()) {
        private val serviceRef = WeakReference(service)

        override fun handleMessage(msg: Message) {
            if (Binder.getCallingUid() != Process.myUid()) return
            if (msg.what != MSG_RUN) return
            val line = msg.data?.getString(KEY_LINE).orEmpty()
            val replyTo = msg.replyTo ?: return
            val app = serviceRef.get()?.applicationContext as? OwnLogApp ?: return
            app.appScope.launch {
                val result = ControlBridge.executeLine(app, line)
                val reply = Message.obtain(null, MSG_RESULT)
                reply.data = Bundle().apply { putString(KEY_RESULT, result.wire()) }
                try {
                    replyTo.send(reply)
                } catch (_: RemoteException) {
                }
            }
        }
    }

    companion object {
        const val MSG_RUN = 1
        const val MSG_RESULT = 2
        const val KEY_LINE = "line"
        const val KEY_RESULT = "result"
    }
}
