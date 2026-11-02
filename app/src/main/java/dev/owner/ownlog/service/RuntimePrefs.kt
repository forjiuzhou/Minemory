package dev.owner.ownlog.service

import android.content.Context
import dev.owner.ownlog.domain.GeoFix
import dev.owner.ownlog.domain.OpenSession

internal object SessionPrefs {
    private const val FILE = "ownlog_session"

    fun load(context: Context): OpenSession? {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val packageName = prefs.getString("pkg", null) ?: return null
        val started = prefs.getLong("started", 0L)
        val sessionId = prefs.getString("sid", null) ?: return null
        if (started <= 0L) return null
        return OpenSession(
            packageName = packageName,
            appLabel = prefs.getString("label", null),
            title = prefs.getString("title", null),
            startedAt = started,
            sessionId = sessionId,
        )
    }

    fun save(context: Context, session: OpenSession?) {
        val editor = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
        if (session == null) {
            editor.clear()
        } else {
            editor.putString("pkg", session.packageName)
                .putString("label", session.appLabel)
                .putString("title", session.title)
                .putLong("started", session.startedAt)
                .putString("sid", session.sessionId)
        }
        editor.commit()
    }
}

internal object UsagePrefs {
    private const val FILE = "ownlog_usage"

    fun cursor(context: Context): Long {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getLong("cursor", 0L)
    }

    fun saveCursor(context: Context, value: Long) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putLong("cursor", value).commit()
    }

    fun loadOpen(context: Context): OpenSession? {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val packageName = prefs.getString("pkg", null) ?: return null
        val started = prefs.getLong("started", 0L)
        val sessionId = prefs.getString("sid", null) ?: return null
        if (started <= 0L) return null
        return OpenSession(
            packageName = packageName,
            appLabel = prefs.getString("label", null),
            title = prefs.getString("title", null),
            startedAt = started,
            sessionId = sessionId,
        )
    }

    fun saveOpen(context: Context, session: OpenSession?) {
        val editor = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
        if (session == null) {
            editor.remove("pkg").remove("label").remove("title").remove("started").remove("sid")
        } else {
            editor.putString("pkg", session.packageName)
                .putString("label", session.appLabel)
                .putString("title", session.title)
                .putLong("started", session.startedAt)
                .putString("sid", session.sessionId)
        }
        editor.commit()
    }
}

internal object LocationPrefs {
    private const val FILE = "ownlog_location"

    fun load(context: Context): GeoFix? {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (!prefs.contains("lat")) return null
        return GeoFix(
            lat = Double.fromBits(prefs.getLong("lat", 0L)),
            lng = Double.fromBits(prefs.getLong("lng", 0L)),
            accuracyMeters = Double.fromBits(prefs.getLong("acc", 0L)),
            timeMillis = prefs.getLong("time", 0L),
        )
    }

    fun save(context: Context, fix: GeoFix) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putLong("lat", fix.lat.toRawBits())
            .putLong("lng", fix.lng.toRawBits())
            .putLong("acc", fix.accuracyMeters.toRawBits())
            .putLong("time", fix.timeMillis)
            .commit()
    }
}
