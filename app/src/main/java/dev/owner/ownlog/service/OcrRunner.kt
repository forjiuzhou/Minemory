package dev.owner.ownlog.service

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

internal data class OcrRead(val lines: List<String>, val confidence: Double?)

internal class OcrRunner {
    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    suspend fun read(source: Bitmap): OcrRead? {
        val scaled = scaleDown(source, 720)
        return try {
            suspendCancellableCoroutine { continuation ->
                val image = InputImage.fromBitmap(scaled, 0)
                recognizer.process(image)
                    .addOnSuccessListener { result ->
                        val lines = result.textBlocks.flatMap { block -> block.lines.map { it.text } }
                        val confidences = result.textBlocks
                            .flatMap { it.lines }
                            .map { it.confidence }
                            .filter { it > 0f }
                        val average = if (confidences.isEmpty()) null else confidences.average()
                        if (continuation.isActive) continuation.resume(OcrRead(lines, average))
                    }
                    .addOnFailureListener {
                        if (continuation.isActive) continuation.resume(null)
                    }
            }
        } catch (_: Exception) {
            null
        } finally {
            if (scaled !== source) scaled.recycle()
            source.recycle()
        }
    }

    private fun scaleDown(source: Bitmap, maxWidth: Int): Bitmap {
        val software = if (source.config == Bitmap.Config.HARDWARE) {
            source.copy(Bitmap.Config.ARGB_8888, false) ?: source
        } else {
            source
        }
        if (software.width <= maxWidth) return software
        val height = (software.height * (maxWidth.toFloat() / software.width)).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(software, maxWidth, height, true)
        if (software !== source && software !== scaled) software.recycle()
        return scaled
    }
}
