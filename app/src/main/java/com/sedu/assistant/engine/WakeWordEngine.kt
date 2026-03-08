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
    private var engRecognizer: Recognizer? = null
    private var hinRecognizer: Recognizer? = null
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

        // English phonetic grammar — maps "सेडू" sound to English word pairs
        private const val EN_GRAMMAR = "[\"say do\", \"said you\", \"see do\", \"said do\", \"se do\", \"so do\", \"say due\", \"so due\", \"say two\", \"so two\", \"set do\", \"said dew\", \"said due\", \"said two\", \"say you\", \"see due\", \"see two\", \"say dew\", \"see dew\", \"set due\", \"set two\", \"sat do\", \"sat due\", \"[unk]\"]"

        // Hindi Devanagari grammar — direct recognition of सेडू
        private const val HI_GRAMMAR = "[\"सेडू\", \"सेडु\", \"से डू\", \"से डु\", \"सैडू\", \"सैडु\", \"सेदू\", \"सेदु\", \"सीडू\", \"सीडु\", \"[unk]\"]"

        // English wake word set for O(1) lookup
        private val EN_WAKE_WORDS = setOf(
            "say do", "see do", "said do", "said you",
            "se do", "so do", "say due", "so due",
            "say two", "so two", "set do",
            "said dew", "said due", "said two",
            "say you", "see due", "see two",
            "say dew", "see dew", "set due", "set two",
            "sat do", "sat due"
        )

        // Hindi wake word set
        private val HI_WAKE_WORDS = setOf(
            "सेडू", "सेडु", "से डू", "से डु",
            "सैडू", "सैडु", "सेदू", "सेदु",
            "सीडू", "सीडु"
        )

        // Minimum RMS energy — lowered from 300 to catch normal speaking volume
        private const val MIN_WAKE_RMS = 150.0
    }

    interface WakeWordCallback {
        fun onWakeWordDetected(audioData: ShortArray)
        fun onError(error: String)
    }

    fun start() {
        if (isListening) return

        try {
            // English recognizer — phonetic matching
            val enModel = VoskModelHolder.getEnglishModel(englishModelPath)
            engRecognizer = Recognizer(enModel, SAMPLE_RATE.toFloat(), EN_GRAMMAR)

            // Hindi recognizer — direct Devanagari matching (much better for "सेडू")
            if (hindiModelPath != null) {
                try {
                    val hiModel = VoskModelHolder.getHindiModel(hindiModelPath)
                    hinRecognizer = Recognizer(hiModel, SAMPLE_RATE.toFloat(), HI_GRAMMAR)
                    Log.d(TAG, "Hindi wake word recognizer loaded")
                } catch (e: Exception) {
                    Log.w(TAG, "Hindi model not available, English-only mode", e)
                    hinRecognizer = null
                }
            }

            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(8192)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 4
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                callback.onError("Failed to initialize audio recorder")
                return
            }

            isListening = true
            audioRecord?.startRecording()

            bufferWritePos = 0
            bufferFilled = false

            listenThread = Thread {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                Log.d(TAG, "Wake word listening started (dual-model: EN + HI)")
                val buffer = ShortArray(2048)
                try {
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
                                val rms = computeRMS(buffer, read)

                                // Feed to BOTH recognizers and check each
                                var detected = false
                                var detectedText = ""

                                // Check English recognizer
                                if (engRecognizer?.acceptWaveForm(bytes, bytes.size) == true) {
                                    val text = extractText(engRecognizer?.result ?: "")
                                    if (isEnWakeWord(text)) {
                                        if (rms >= MIN_WAKE_RMS) {
                                            detected = true
                                            detectedText = "EN:'$text'"
                                        } else {
                                            Log.d(TAG, "EN candidate '$text' rejected: RMS=$rms too low")
                                        }
                                    }
                                }

                                // Check Hindi recognizer
                                if (!detected && hinRecognizer != null) {
                                    if (hinRecognizer?.acceptWaveForm(bytes, bytes.size) == true) {
                                        val text = extractText(hinRecognizer?.result ?: "")
                                        if (isHiWakeWord(text)) {
                                            if (rms >= MIN_WAKE_RMS) {
                                                detected = true
                                                detectedText = "HI:'$text'"
                                            } else {
                                                Log.d(TAG, "HI candidate '$text' rejected: RMS=$rms too low")
                                            }
                                        }
                                    }
                                }

                                if (detected) {
                                    Log.d(TAG, "*** WAKE WORD DETECTED: $detectedText (RMS=$rms) ***")
                                    isListening = false
                                    val capturedAudio = getCapturedAudio()
                                    callback.onWakeWordDetected(capturedAudio)
                                    return@Thread
                                }
                            }
                        } catch (e: Exception) {
                            if (isListening) {
                                Log.e(TAG, "Error in listen loop", e)
                            }
                        }
                    }
                } finally {
                    try { audioRecord?.stop() } catch (_: Exception) {}
                    try { audioRecord?.release() } catch (_: Exception) {}
                    try { engRecognizer?.close() } catch (_: Exception) {}
                    try { hinRecognizer?.close() } catch (_: Exception) {}
                    Log.d(TAG, "Wake word thread exiting, resources released")
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

        try { engRecognizer?.close() } catch (_: Exception) {}
        try { hinRecognizer?.close() } catch (_: Exception) {}
        engRecognizer = null
        hinRecognizer = null

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

    private fun isEnWakeWord(text: String): Boolean {
        if (text.isEmpty()) return false
        val cleaned = text.lowercase().trim()
            .replace("[unk]", "").trim()
        if (cleaned.isEmpty()) return false
        // Must be exactly two words (two syllables = "se" + "du")
        val words = cleaned.split(" ").filter { it.isNotBlank() }
        if (words.size != 2) return false
        return EN_WAKE_WORDS.contains(cleaned)
    }

    private fun isHiWakeWord(text: String): Boolean {
        if (text.isEmpty()) return false
        val cleaned = text.trim()
            .replace("[unk]", "").trim()
        if (cleaned.isEmpty()) return false
        return HI_WAKE_WORDS.contains(cleaned)
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
