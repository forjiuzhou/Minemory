package dev.owner.ownlog.inbox

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.File
import java.util.UUID

internal data class VoiceTake(
    val file: File,
    val transcript: String?,
)

internal class VoiceSession(private val context: Context) {
    private var recorder: android.media.MediaRecorder? = null
    private var recognizer: SpeechRecognizer? = null
    private var file: File? = null
    private val transcript = StringBuilder()
    private var partial: String? = null

    val isRecording: Boolean get() = recorder != null

    fun start(): Boolean {
        if (recorder != null) return true
        val dir = File(context.filesDir, "inbox").apply { mkdirs() }
        val out = File(dir, "${UUID.randomUUID()}.m4a")
        val media = if (Build.VERSION.SDK_INT >= 31) {
            android.media.MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            android.media.MediaRecorder()
        }
        try {
            media.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            media.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            media.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
            media.setAudioEncodingBitRate(64_000)
            media.setAudioSamplingRate(16_000)
            @Suppress("DEPRECATION")
            media.setOutputFile(out.absolutePath)
            media.prepare()
            media.start()
        } catch (_: Exception) {
            media.release()
            out.delete()
            return false
        }
        recorder = media
        file = out
        transcript.clear()
        partial = null
        startRecognizer()
        return true
    }

    fun stop(): VoiceTake? {
        val media = recorder ?: return null
        val out = file
        recorder = null
        file = null
        try {
            media.stop()
        } catch (_: Exception) {
            // A very short hold can fail to produce a frame.
        }
        media.release()
        stopRecognizer()
        if (out == null || !out.exists() || out.length() <= 0L) {
            out?.delete()
            return null
        }
        val text = transcript.toString().trim().ifBlank { partial?.trim() }.orEmpty()
        return VoiceTake(out, text.ifBlank { null })
    }

    fun cancel() {
        val media = recorder
        recorder = null
        try {
            media?.stop()
        } catch (_: Exception) {
        }
        media?.release()
        stopRecognizer()
        file?.delete()
        file = null
    }

    private fun startRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return
        val speech = try {
            SpeechRecognizer.createSpeechRecognizer(context)
        } catch (_: Exception) {
            return
        }
        recognizer = speech
        speech.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                append(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) partial = text
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            speech.startListening(intent)
        } catch (_: Exception) {
            stopRecognizer()
        }
    }

    private fun append(text: String?) {
        val clean = text?.trim().orEmpty()
        if (clean.isEmpty()) return
        if (transcript.isNotEmpty()) transcript.append('\n')
        transcript.append(clean)
    }

    private fun stopRecognizer() {
        val speech = recognizer ?: return
        recognizer = null
        try {
            speech.stopListening()
        } catch (_: Exception) {
        }
        try {
            speech.destroy()
        } catch (_: Exception) {
        }
    }
}
