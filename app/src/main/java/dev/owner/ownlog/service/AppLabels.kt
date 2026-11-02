package dev.owner.ownlog.service

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

internal object AppLabels {
    private val cache = ConcurrentHashMap<String, String>()

    fun label(context: Context, packageName: String): String {
        cache[packageName]?.let { return it }
        val resolved = try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info)?.toString().orEmpty().ifBlank { packageName }
        } catch (_: Exception) {
            packageName
        }
        cache[packageName] = resolved
        return resolved
    }
}
