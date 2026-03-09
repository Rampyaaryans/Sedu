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

        // Minimum RMS energy — very low to not miss anything
        private const val MIN_WAKE_RMS = 80.0

        // English patterns that Vosk outputs when you say "सेडू" / "Sedu"
        // Free-form recognition — no grammar, match output with regex
        private val EN_PATTERNS = listOf(
            Regex("\\bsa[iy]d?\\s*d[uo]\\b", RegexOption.IGNORE_CASE),      // say do, said do, say du
            Regex("\\bse[ea]?d?\\s*[dt][uo]o?\\b", RegexOption.IGNORE_CASE), // se do, see do, sed do, see too
            Regex("\\bse[ea]?\\s*d[uo]\\b", RegexOption.IGNORE_CASE),        // se du, sea do
            Regex("\\bset?\\s*d[uo]o?\\b", RegexOption.IGNORE_CASE),         // set do, set due
            Regex("\\bsaid?\\s*(you|due|dew|two)\\b", RegexOption.IGNORE_CASE), // said you, say due
            Regex("\\bced[ae]r?\\b", RegexOption.IGNORE_CASE),               // cedar (common mishear)
            Regex("\\bsedu\\b", RegexOption.IGNORE_CASE),                     // direct "sedu" if model knows it
            Regex("\\bsay\\s*d[uo]o?\\b", RegexOption.IGNORE_CASE),          // say doo, say du
            Regex("\\bso\\s*d[uo]o?\\b", RegexOption.IGNORE_CASE),           // so do, so due
            Regex("\\bsat?\\s*d[uo]o?\\b", RegexOption.IGNORE_CASE),         // sat do, sa du
            Regex("\\bs[ae]d[uo]\\b", RegexOption.IGNORE_CASE),              // sadu, sedu joined
        )

        // Hindi patterns — what Hindi Vosk model outputs for "सेडू" sound
        // Since सेडू is not a real word, the model maps it to close real Hindi words/syllables
        private val HI_PATTERNS = listOf(
            Regex("से\\s*[डद][ूु]"),            // से डू, से डु, सेदू, सेदु
            Regex("सेड[ूु]"),                    // सेडू, सेडु (if model has it)
            Regex("सै[डद][ूु]"),                 // सैडू, सैडु
            Regex("सी[डद][ूु]"),                 // सीडू, सीडु
            Regex("से\\s*[तट]\\s*[डद][ूु]"),     // सेट डू
            Regex("सेठ[ूु]"),                     // सेठू (close sound)
            Regex("से\\s*द[ोू]"),                 // से दो, से दू
            Regex("छेड़?[ूु]"),                   // छेडू, छेड़ू
            Regex("सेर[ूु]"),                     // सेरू
            Regex("से\\s*र[ूु]"),                 // से रू
            Regex("शेड[ूु]"),                     // शेडू
            Regex("चेद[ूु]"),                     // चेदू
            Regex("से\\s*ड"),                     // से ड (partial match ok)
            Regex("स[ेै]\\s*[डदतट]"),             // broad: से/सै + ड/द/त/ट
        )
    }

    interface WakeWordCallback {
        fun onWakeWordDetected(audioData: ShortArray)
        fun onError(error: String)
    }

    fun start() {
        if (isListening) return

        try {
            // English recognizer — FREE-FORM (no grammar constraint)
            val enModel = VoskModelHolder.getEnglishModel(englishModelPath)
            engRecognizer = Recognizer(enModel, SAMPLE_RATE.toFloat())

            // Hindi recognizer — FREE-FORM (no grammar constraint)
            if (hindiModelPath != null) {
                try {
                    val hiModel = VoskModelHolder.getHindiModel(hindiModelPath)
                    hinRecognizer = Recognizer(hiModel, SAMPLE_RATE.toFloat())
                    Log.d(TAG, "Hindi free-form recognizer loaded")
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
                Log.d(TAG, "Wake word listening started (dual free-form: EN + HI)")
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

                                // === ENGLISH MODEL ===
                                val enFinal = engRecognizer?.acceptWaveForm(bytes, bytes.size) == true
                                if (enFinal) {
                                    val text = extractFinalText(engRecognizer?.result ?: "")
                                    if (text.isNotEmpty()) {
                                        Log.d(TAG, "EN final: '$text'")
                                        if (matchesEnPattern(text) && rms >= MIN_WAKE_RMS) {
                                            triggerWakeWord("EN-final:'$text'", rms)
                                            return@Thread
                                        }
                                    }
                                } else {
                                    // Check partial results too — faster detection
                                    val partial = extractPartialText(engRecognizer?.partialResult ?: "")
                                    if (partial.length >= 4 && matchesEnPattern(partial) && rms >= MIN_WAKE_RMS) {
                                        Log.d(TAG, "EN partial match: '$partial'")
                                        triggerWakeWord("EN-partial:'$partial'", rms)
                                        return@Thread
                                    }
                                }

                                // === HINDI MODEL ===
                                if (hinRecognizer != null) {
                                    val hiFinal = hinRecognizer?.acceptWaveForm(bytes, bytes.size) == true
                                    if (hiFinal) {
                                        val text = extractFinalText(hinRecognizer?.result ?: "")
                                        if (text.isNotEmpty()) {
                                            Log.d(TAG, "HI final: '$text'")
                                            if (matchesHiPattern(text) && rms >= MIN_WAKE_RMS) {
                                                triggerWakeWord("HI-final:'$text'", rms)
                                                return@Thread
                                            }
                                        }
                                    } else {
                                        val partial = extractPartialText(hinRecognizer?.partialResult ?: "")
                                        if (partial.length >= 2 && matchesHiPattern(partial) && rms >= MIN_WAKE_RMS) {
                                            Log.d(TAG, "HI partial match: '$partial'")
                                            triggerWakeWord("HI-partial:'$partial'", rms)
                                            return@Thread
                                        }
                                    }
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
                    audioRecord = null
                    try { engRecognizer?.close() } catch (_: Exception) {}
                    try { hinRecognizer?.close() } catch (_: Exception) {}
                    engRecognizer = null
                    hinRecognizer = null
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

    private fun triggerWakeWord(source: String, rms: Double) {
        Log.d(TAG, "*** WAKE WORD DETECTED: $source (RMS=$rms) ***")
        isListening = false
        val capturedAudio = getCapturedAudio()
        callback.onWakeWordDetected(capturedAudio)
    }

    fun stop() {
        isListening = false
        // Do NOT call audioRecord.stop()/release() here — the listen thread's
        // finally block handles cleanup. Calling from both threads causes
        // IllegalStateException and double-release crashes.
        // Just wait briefly for the thread to exit naturally.
        try {
            listenThread?.join(500)
        } catch (_: InterruptedException) {}
        listenThread = null
    }

    /** Check if wake word engine is still actively listening */
    fun isAlive(): Boolean = isListening && listenThread?.isAlive == true

    private fun extractFinalText(json: String): String {
        return try {
            JSONObject(json).optString("text", "").trim()
        } catch (_: Exception) { "" }
    }

    private fun extractPartialText(json: String): String {
        return try {
            JSONObject(json).optString("partial", "").trim()
        } catch (_: Exception) { "" }
    }

    /** Check if English free-form output matches any "Sedu"-like pattern */
    private fun matchesEnPattern(text: String): Boolean {
        if (text.isEmpty()) return false
        return EN_PATTERNS.any { it.containsMatchIn(text) }
    }

    /** Check if Hindi free-form output matches any "सेडू"-like pattern */
    private fun matchesHiPattern(text: String): Boolean {
        if (text.isEmpty()) return false
        return HI_PATTERNS.any { it.containsMatchIn(text) }
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
