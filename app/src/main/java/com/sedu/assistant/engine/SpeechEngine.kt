package com.sedu.assistant.engine

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * CONTINUOUS speech recognition engine.
 * Destroys and recreates SpeechRecognizer between sessions to avoid CLIENT errors,
 * but mutes all beeps so the user never hears the cycle.
 * Auto-restarts immediately after each result — seamless from user perspective.
 */
class SpeechEngine(
    private val context: Context,
    private val callback: SpeechCallback,
    private val biasNames: List<String> = emptyList()
) {
    private var speechRecognizer: SpeechRecognizer? = null
    @Volatile
    private var isListening = false
    @Volatile
    private var isStopped = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null
    private var retryCount = 0
    private var lastPartialResult = ""
    private var currentTimeoutMs: Long = 20_000
    private var speechWasDetected = false
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    companion object {
        private const val TAG = "SpeechEngine"
        private const val MAX_RETRIES = 2  // Limited restarts to prevent ON/OFF cycling
        private const val SPEECH_TIMEOUT_MS = 35_000L  // Extended timeout when user is speaking
    }

    interface SpeechCallback {
        fun onResult(text: String)
        fun onPartial(text: String)
        fun onTimeout()
        fun onError(error: String)
    }

    private fun createRecognizer() {
        destroyRecognizer()
        Log.d(TAG, "Creating fresh SpeechRecognizer")
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer?.setRecognitionListener(recognitionListener)
    }

    private fun createIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-IN")
            putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("hi-IN", "en-US"))
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // PATIENT silence detection — let user speak slowly, like GPT voice mode
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3000L)
            // VOICE_RECOGNITION audio source
            putExtra("android.speech.extra.AUDIO_SOURCE", 6)
            if (biasNames.isNotEmpty()) {
                val phrases = biasNames.take(200).flatMap { name ->
                    listOf("call $name", "$name ko call", name)
                }
                putExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(phrases))
            }
        }
    }

    /**
     * Start listening. Mutes system sounds to suppress Google beep,
     * creates a fresh recognizer, and starts.
     */
    fun startListening(timeoutMs: Long = 20_000) {
        if (isListening) return
        isStopped = false
        isListening = true
        lastPartialResult = ""
        speechWasDetected = false
        currentTimeoutMs = timeoutMs

        mainHandler.post {
            if (isStopped) return@post
            try {
                muteStreams()
                createRecognizer()
                speechRecognizer?.startListening(createIntent())
                Log.d(TAG, "Listening started, retry=$retryCount")
                resetTimeout(timeoutMs)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start recognizer", e)
                isListening = false
                handleStartError(timeoutMs)
            }
        }
    }

    /**
     * Quick restart — destroy old recognizer, mute, create fresh, start.
     * The mute hides the beep so user perceives zero gap.
     */
    private fun continuousRestart() {
        if (isStopped) return
        isListening = true
        lastPartialResult = ""
        speechWasDetected = false

        mainHandler.post {
            if (isStopped) return@post
            try {
                muteStreams()
                createRecognizer()
                speechRecognizer?.startListening(createIntent())
                Log.d(TAG, "Continuous restart (fresh recognizer, muted)")
                resetTimeout(currentTimeoutMs)
            } catch (e: Exception) {
                Log.e(TAG, "Continuous restart failed", e)
                isListening = false
                mainHandler.postDelayed({
                    if (!isStopped) startListening(currentTimeoutMs)
                }, 150)
            }
        }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Ready for speech — LISTENING")
            // Unmute AFTER recognizer is ready — beep window has passed
            mainHandler.postDelayed({ unmuteStreams() }, 150)
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "Speech detected!")
            speechWasDetected = true
            // Extend timeout — user is speaking, give them plenty of time
            resetTimeout(SPEECH_TIMEOUT_MS)
        }

        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d(TAG, "Speech ended, processing...")
        }

        override fun onError(error: Int) {
            val errorMsg = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Audio error"
                SpeechRecognizer.ERROR_CLIENT -> "Client error"
                SpeechRecognizer.ERROR_NETWORK -> "Network error"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                SpeechRecognizer.ERROR_NO_MATCH -> "No match"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                SpeechRecognizer.ERROR_SERVER -> "Server error"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "No permission"
                else -> "Error $error"
            }
            Log.e(TAG, "Recognition error: $errorMsg ($error), partial='$lastPartialResult'")
            cancelTimeout()

            if (isStopped) { unmuteStreams(); return }

            // If we had partial results, deliver them as the final result
            if (lastPartialResult.isNotEmpty()) {
                val text = lastPartialResult
                Log.d(TAG, "Using partial as final: '$text'")
                isListening = false
                lastPartialResult = ""
                retryCount = 0
                callback.onResult(text)
                return
            }

            // For NO_MATCH / SPEECH_TIMEOUT — silent restart (keep listening forever)
            if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                isListening = false
                if (retryCount < MAX_RETRIES) {
                    retryCount++
                    Log.d(TAG, "No speech, silently restarting (attempt $retryCount)")
                    continuousRestart()
                } else {
                    retryCount = 0
                    unmuteStreams()
                    callback.onTimeout()
                }
                return
            }

            // Any other error — destroy and restart quickly
            isListening = false
            destroyRecognizer()
            if (retryCount < MAX_RETRIES) {
                retryCount++
                val delay = if (error == SpeechRecognizer.ERROR_CLIENT) 200L else 150L
                mainHandler.postDelayed({
                    if (!isStopped) startListening(currentTimeoutMs)
                }, delay)
            } else {
                unmuteStreams()
                callback.onError(errorMsg)
            }
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            cancelTimeout()
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            var text = matches?.firstOrNull()?.trim() ?: ""
            Log.d(TAG, "RESULT: '$text'")

            if (text.isEmpty() && lastPartialResult.isNotBlank()) {
                text = lastPartialResult
            }

            lastPartialResult = ""
            if (text.isNotEmpty()) {
                retryCount = 0
                callback.onResult(text)
            } else {
                // Empty result — limited restart to prevent cycling
                retryCount++
                if (retryCount <= MAX_RETRIES) {
                    continuousRestart()
                } else {
                    unmuteStreams()
                    callback.onTimeout()
                }
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull()?.trim() ?: ""
            if (text.isNotEmpty()) {
                Log.d(TAG, "PARTIAL: '$text'")
                lastPartialResult = text
                // Keep extending timeout while user is still speaking
                if (speechWasDetected) resetTimeout(SPEECH_TIMEOUT_MS)
                callback.onPartial(text)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun destroyRecognizer() {
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying recognizer", e)
        }
        speechRecognizer = null
    }

    fun stop() {
        isStopped = true
        isListening = false
        retryCount = 0
        lastPartialResult = ""
        cancelTimeout()
        unmuteStreams()
        mainHandler.post { destroyRecognizer() }
    }

    fun restartForNextCommand() {
        if (isStopped) return
        isListening = false
        retryCount = 0
        continuousRestart()
    }

    private fun resetTimeout(timeoutMs: Long) {
        cancelTimeout()
        timeoutRunnable = Runnable {
            if (isListening && !isStopped) {
                Log.d(TAG, "Timeout reached, partial='$lastPartialResult'")
                isListening = false
                try { speechRecognizer?.cancel() } catch (_: Exception) {}
                if (lastPartialResult.isNotEmpty()) {
                    val text = lastPartialResult
                    lastPartialResult = ""
                    callback.onResult(text)
                } else {
                    callback.onTimeout()
                }
            }
        }
        mainHandler.postDelayed(timeoutRunnable!!, timeoutMs)
    }

    private fun cancelTimeout() {
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null
    }

    private fun handleStartError(timeoutMs: Long) {
        destroyRecognizer()
        if (retryCount < 2) {
            retryCount++
            mainHandler.postDelayed({ if (!isStopped) startListening(timeoutMs) }, 300)
        } else {
            unmuteStreams()
            callback.onError("SpeechRecognizer init failed")
        }
    }

    private var savedMusicVol = -1
    private var savedSystemVol = -1
    private var savedNotifVol = -1

    private fun muteStreams() {
        try {
            if (savedMusicVol < 0) {
                savedMusicVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                savedSystemVol = audioManager.getStreamVolume(AudioManager.STREAM_SYSTEM)
                savedNotifVol = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
            }
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, 0)
            audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Mute error", e)
        }
    }

    private fun unmuteStreams() {
        if (savedMusicVol < 0) return
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVol, 0)
            audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, savedSystemVol, 0)
            audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, savedNotifVol, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Unmute error", e)
        }
        savedMusicVol = -1
        savedSystemVol = -1
        savedNotifVol = -1
    }
}
