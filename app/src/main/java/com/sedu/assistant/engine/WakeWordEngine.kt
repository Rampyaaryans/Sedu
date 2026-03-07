package com.sedu.assistant.engine

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.sedu.assistant.util.VoskModelHolder
import org.vosk.Recognizer
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WakeWordEngine(
    private val englishModelPath: String,
    private val hindiModelPath: String?,
    private val callback: WakeWordCallback
) {
    private var audioRecord: AudioRecord? = null
    private var recognizer: Recognizer? = null
    @Volatile
    private var isListening = false
    private var listenThread: Thread? = null

    // Circular audio buffer — keeps last 2 seconds for speaker verification
    private val audioBuffer = ShortArray(BUFFER_SIZE)
    private var bufferWritePos = 0
    private var bufferFilled = false

    companion object {
        private const val TAG = "WakeWordEngine"
        private const val SAMPLE_RATE = 16000
        private const val BUFFER_SIZE = 32000 // 2 seconds at 16kHz

        // Tight Vosk grammar — only TWO-WORD "Sedu" transcriptions + [unk] catch-all
        // Every entry MUST be two words (two syllables) to reject single-syllable "S" sounds
        // [unk] absorbs ALL non-wake-word speech
        private const val GRAMMAR = "[\"say do\", \"said you\", \"see do\", \"said do\", \"se do\", \"so do\", \"say due\", \"so due\", \"say two\", \"so two\", \"set do\", \"[unk]\"]"

        // Exact wake word phrases — two-word only, Set for O(1) lookup
        private val WAKE_WORDS = setOf(
            "say do", "see do", "said do", "said you",
            "se do", "so do", "say due", "so due",
            "say two", "so two", "set do"
        )
        // Minimum RMS energy to accept — rejects quiet/ambient false triggers
        private const val MIN_WAKE_RMS = 300.0
    }

    interface WakeWordCallback {
        fun onWakeWordDetected(audioData: ShortArray)
        fun onError(error: String)
    }

    fun start() {
        if (isListening) return

        try {
            // Use pre-loaded model from holder — no reload delay
            val model = VoskModelHolder.getEnglishModel(englishModelPath)

            // Grammar-constrained recognition — outputs only wake word variants or [unk]
            // This eliminates false triggers from random speech or background noise
            recognizer = Recognizer(model, SAMPLE_RATE.toFloat(), GRAMMAR)

            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(8192)  // Larger buffer for far-field pickup

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,  // VOICE_RECOGNITION = hardware AGC + far-field
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 4  // Large buffer for continuous far-field pickup
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                callback.onError("Failed to initialize audio recorder")
                return
            }

            isListening = true
            audioRecord?.startRecording()

            // Reset circular buffer
            bufferWritePos = 0
            bufferFilled = false

            listenThread = Thread {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                Log.d(TAG, "Wake word listening started (grammar mode)")
                val buffer = ShortArray(2048)
                while (isListening) {
                    try {
                        val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                        if (read > 0 && isListening) {
                            // Store in circular buffer for speaker verification
                            for (i in 0 until read) {
                                audioBuffer[bufferWritePos] = buffer[i]
                                bufferWritePos = (bufferWritePos + 1) % BUFFER_SIZE
                                if (bufferWritePos == 0) bufferFilled = true
                            }

                            val bytes = shortsToBytes(buffer, read)

                            if (recognizer?.acceptWaveForm(bytes, bytes.size) == true) {
                                val result = recognizer?.result ?: ""
                                val text = extractText(result)
                                if (isWakeWord(text)) {
                                    // Validate audio energy — reject quiet/ambient false triggers
                                    val rms = computeRMS(buffer, read)
                                    if (rms < MIN_WAKE_RMS) {
                                        Log.d(TAG, "Wake word candidate '$text' rejected: RMS=$rms too low")
                                    } else {
                                        Log.d(TAG, "*** WAKE WORD DETECTED: '$text' (RMS=$rms) ***")
                                        isListening = false
                                        val capturedAudio = getCapturedAudio()
                                        callback.onWakeWordDetected(capturedAudio)
                                        return@Thread
                                    }
                                }
                            }
                            // NO partial result checking — partials are too unreliable
                            // and cause false triggers on any word starting with "S"
                        }
                    } catch (e: Exception) {
                        if (isListening) {
                            Log.e(TAG, "Error in listen loop", e)
                        }
                    }
                }
            }
            listenThread?.name = "SeduWakeWord"
            listenThread?.isDaemon = true
            listenThread?.start()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start wake word engine", e)
            callback.onError(e.message ?: "Unknown error")
        }
    }

    fun stop() {
        isListening = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio record", e)
        }
        audioRecord = null

        try {
            recognizer?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing recognizer", e)
        }
        recognizer = null

        listenThread = null
    }

    /** Check if wake word engine is still actively listening */
    fun isAlive(): Boolean = isListening && listenThread?.isAlive == true

    private fun extractText(json: String): String {
        return try {
            val obj = JSONObject(json)
            (obj.optString("text", "") + " " + obj.optString("partial", "")).trim()
        } catch (e: Exception) {
            ""
        }
    }

    private fun isWakeWord(text: String): Boolean {
        if (text.isEmpty()) return false
        val cleaned = text.lowercase().trim()
            .replace("[unk]", "").trim()
        if (cleaned.isEmpty()) return false
        // Must be exactly two words (two syllables = "se" + "du")
        // Rejects single-word false positives like "so", "say", "see" etc.
        val words = cleaned.split(" ").filter { it.isNotBlank() }
        if (words.size != 2) return false
        return WAKE_WORDS.contains(cleaned)
    }

    /** Compute RMS energy of audio buffer — higher = louder speech */
    private fun computeRMS(buffer: ShortArray, count: Int): Double {
        if (count == 0) return 0.0
        var sum = 0.0
        for (i in 0 until count) {
            sum += buffer[i].toDouble() * buffer[i].toDouble()
        }
        return Math.sqrt(sum / count)
    }

    /** Get the last ~2 seconds of audio from the circular buffer for speaker verification. */
    private fun getCapturedAudio(): ShortArray {
        val size = if (bufferFilled) BUFFER_SIZE else bufferWritePos
        if (size == 0) return ShortArray(0)
        val result = ShortArray(size)
        if (bufferFilled) {
            val firstChunk = BUFFER_SIZE - bufferWritePos
            System.arraycopy(audioBuffer, bufferWritePos, result, 0, firstChunk)
            System.arraycopy(audioBuffer, 0, result, firstChunk, bufferWritePos)
        } else {
            System.arraycopy(audioBuffer, 0, result, 0, bufferWritePos)
        }
        return result
    }

    private fun shortsToBytes(shorts: ShortArray, count: Int): ByteArray {
        val bytes = ByteArray(count * 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(shorts, 0, count)
        return bytes
    }
}
