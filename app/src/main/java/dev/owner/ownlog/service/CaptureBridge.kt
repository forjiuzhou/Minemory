package dev.owner.ownlog.service

import android.graphics.Bitmap
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CapturedFrame(
    val bitmap: Bitmap,
    val method: String,
    val width: Int,
    val height: Int,
)

internal object CaptureBridge {
    @Volatile
    private var projection: ScreenOcrService? = null

    @Volatile
    private var accessibility: OwnAccessibilityService? = null

    private val projectionState = MutableStateFlow(false)
    val projectionActive: StateFlow<Boolean> = projectionState.asStateFlow()

    private val accessibilityState = MutableStateFlow(false)
    val accessibilityActive: StateFlow<Boolean> = accessibilityState.asStateFlow()

    fun attachProjection(service: ScreenOcrService) {
        projection = service
    }

    fun detachProjection(service: ScreenOcrService) {
        if (projection === service) projection = null
        projectionState.value = false
    }

    fun setProjectionActive(active: Boolean) {
        projectionState.value = active
    }

    fun attachAccessibility(service: OwnAccessibilityService) {
        accessibility = service
        accessibilityState.value = true
    }

    fun detachAccessibility(service: OwnAccessibilityService) {
        if (accessibility === service) {
            accessibility = null
            accessibilityState.value = false
        }
    }

    fun accessibilityService(): OwnAccessibilityService? = accessibility

    suspend fun capture(): CapturedFrame? {
        val fromProjection = projection?.takeIf { projectionState.value }?.captureFrame()
        if (fromProjection != null) {
            return CapturedFrame(fromProjection, "media_projection", fromProjection.width, fromProjection.height)
        }
        val service = accessibility
        if (service != null && Build.VERSION.SDK_INT >= 30) {
            val bitmap = service.captureViaAccessibility()
            if (bitmap != null) {
                return CapturedFrame(bitmap, "accessibility", bitmap.width, bitmap.height)
            }
        }
        return null
    }
}
