package com.sedu.assistant.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sedu.assistant.MainActivity
import com.sedu.assistant.R
import com.sedu.assistant.SeduApp
import com.sedu.assistant.UserPrefs
import com.sedu.assistant.ai.GeminiBrain
import com.sedu.assistant.engine.ActionExecutor
import com.sedu.assistant.engine.CommandParser
import com.sedu.assistant.engine.ContactsHelper
import com.sedu.assistant.engine.SpeechEngine
import com.sedu.assistant.engine.WakeWordEngine
import com.sedu.assistant.memory.SeduMemory
import com.sedu.assistant.model.SeduCommand
import com.sedu.assistant.overlay.SeduOverlay
import com.sedu.assistant.tts.SeduTTS
import com.sedu.assistant.util.ModelManager
import com.sedu.assistant.util.VoskModelHolder
import com.sedu.assistant.voice.VoiceProfile

class SeduService : Service() {

    private var wakeWordEngine: WakeWordEngine? = null
    private var speechEngine: SpeechEngine? = null
    private var commandParser: CommandParser? = null
    private var actionExecutor: ActionExecutor? = null
    private var seduTTS: SeduTTS? = null
    private var overlay: SeduOverlay? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var geminiBrain: GeminiBrain? = null
    private var voiceProfile: VoiceProfile? = null
    private var contactsHelper: ContactsHelper? = null
    private var seduMemory: SeduMemory? = null
    private var inConversation = false
    private var lastActivityTime = 0L
    private val inactivityHandler = Handler(Looper.getMainLooper())
    private val INACTIVITY_TIMEOUT_MS = 45_000L // 45 seconds — give user time for multi-step
    @Volatile private var geminiThread: Thread? = null
    private var hasPromptedOnSilence = false
    private var speechErrorCount = 0
    private var wakeWordWatchdog: Runnable? = null
    private val WATCHDOG_INTERVAL_MS = 15_000L

    private val inactivityChecker = object : Runnable {
        override fun run() {
            if (inConversation && System.currentTimeMillis() - lastActivityTime > INACTIVITY_TIMEOUT_MS) {
                Log.d(TAG, "Inactivity timeout - ending conversation")
                seduTTS?.speak("Koi jawaab nahi aaya, band kar raha hoon") {
                    endConversation()
                }
            } else if (inConversation) {
                inactivityHandler.postDelayed(this, 10_000) // check every 10s
            }
        }
    }

    companion object {
        const val TAG = "SeduService"
        const val ACTION_GO_PASSIVE = "com.sedu.assistant.GO_PASSIVE"
        const val ACTION_GO_ACTIVE = "com.sedu.assistant.GO_ACTIVE"
        const val ACTION_REFRESH_CONFIG = "com.sedu.assistant.REFRESH_CONFIG"
        var isRunning = false
            private set
        var isPassive = false
            private set
        var currentState = SeduServiceState.IDLE
            private set
    }



    enum class SeduServiceState {
        IDLE,
        LISTENING_WAKE_WORD,
        LISTENING_COMMAND,
        PROCESSING,
        SPEAKING
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "SeduService created")

        // Clear any stale inactivity callbacks
        inConversation = false
        inactivityHandler.removeCallbacks(inactivityChecker)

        commandParser = CommandParser()
        seduTTS = SeduTTS(this)

        // Initialize Gemini AI brain (has default key, user can override)
        geminiBrain = GeminiBrain()
        reloadBrainConfig()
        Log.d(TAG, "Gemini AI brain initialized, hasKey=${geminiBrain?.hasApiKey()}")

        // Initialize RAG memory system
        seduMemory = SeduMemory.getInstance(this)
        geminiBrain?.setMemory(seduMemory!!)
        Log.d(TAG, "Memory system initialized")

        // ActionExecutor needs geminiBrain for screen reading
        actionExecutor = ActionExecutor(this, geminiBrain)

        // Load contacts and feed to Gemini for smart name matching
        contactsHelper = ContactsHelper(this)
        Thread {
            val contactNames = contactsHelper?.getContactNamesForPrompt() ?: ""
            if (contactNames.isNotBlank()) {
                geminiBrain?.setContactNames(contactNames)
            }
        }.start()

        // Initialize voice profile for speaker verification
        voiceProfile = VoiceProfile(this)
        Log.d(TAG, "Voice profile loaded, enrolled=${voiceProfile?.isEnrolled()}")

        // Acquire partial wake lock to keep service alive
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "sedu:wakelock"
        )
        wakeLock?.acquire(10 * 60 * 1000L) // 10-minute timeout — reacquire periodically

        // Init overlay if permission granted
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
            overlay = SeduOverlay(this)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "SeduService onStartCommand, action=${intent?.action}")

        startForeground(1, createNotification("Sedu is listening..."))

        if (intent?.action == ACTION_REFRESH_CONFIG) {
            reloadBrainConfig()
            updateNotification("नया कॉन्फ़िग लग गया")
            return START_STICKY
        }

        // Handle passive mode (STOP button → keep wake word only)
        if (intent?.action == ACTION_GO_PASSIVE) {
            goPassive()
            return START_STICKY
        }

        // Handle active mode (START button from passive)
        if (intent?.action == ACTION_GO_ACTIVE || isPassive) {
            isPassive = false
            isRunning = true
            updateNotification("Sedu is listening...")
            startWakeWordListening()
            return START_STICKY
        }

        // Guard: don't re-initialize if already running
        if (isRunning && wakeWordEngine != null) {
            Log.d(TAG, "Already running, skipping re-init")
            return START_STICKY
        }

        getSharedPreferences(UserPrefs.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(UserPrefs.KEY_USER_STOPPED, false)
            .apply()

        isRunning = true

        // Preload models into memory on service start for instant response
        Thread {
            // Auto-download Indian English model if missing (best for Hinglish)
            if (!ModelManager.isIndianEnglishModelReady(this)) {
                Log.d(TAG, "Indian English model missing, downloading...")
                try {
                    ModelManager.downloadIndianEnglishModel(this)
                    Log.d(TAG, "Indian English model downloaded!")
                } catch (e: Exception) {
                    Log.e(TAG, "Indian English model download failed: ${e.message}")
                }
            }

            val enPath = ModelManager.getEnglishModelPath(this)
            val hiPath = ModelManager.getHindiModelPath(this)
            val enInPath = ModelManager.getIndianEnglishModelPath(this)
            VoskModelHolder.preloadModels(enPath, hiPath, enInPath)
            Log.d(TAG, "Models preloaded into memory")

            // Start listening for wake word
            startWakeWordListening()
        }.start()

        return START_STICKY
    }

    private fun startWakeWordListening() {
        currentState = SeduServiceState.LISTENING_WAKE_WORD
        inConversation = false
        updateNotification("Say \"Sedu\" to activate")

        // Reacquire wakelock (it has a timeout)
        try {
            if (wakeLock?.isHeld != true) wakeLock?.acquire(10 * 60 * 1000L)
        } catch (_: Exception) {}

        val enPath = ModelManager.getEnglishModelPath(this)
        val hiPath = ModelManager.getHindiModelPath(this)

        if (enPath == null) {
            Log.e(TAG, "English model not found")
            stopSelf()
            return
        }

        speechEngine?.stop()
        speechEngine = null
        wakeWordEngine?.stop()
        wakeWordEngine = null

        // Small delay to ensure all audio resources are fully released
        Handler(Looper.getMainLooper()).postDelayed({
            if (inConversation) return@postDelayed  // Don't start if conversation already began

            wakeWordEngine = WakeWordEngine(enPath, hiPath, object : WakeWordEngine.WakeWordCallback {
                override fun onWakeWordDetected(audioData: ShortArray) {
                    Log.d(TAG, "*** WAKE WORD DETECTED ***")
                    // MUST run on main thread — TTS and SpeechRecognizer require it
                    Handler(Looper.getMainLooper()).post {
                        onWakeWord(audioData)
                    }
                }

                override fun onError(error: String) {
                    Log.e(TAG, "Wake word error: $error")
                    // Restart listening after error — on main thread
                    Handler(Looper.getMainLooper()).postDelayed({
                        startWakeWordListening()
                    }, 2000)
                }
            })
            wakeWordEngine?.start()
            Log.d(TAG, "Wake word engine started (dual free-form)")
        }, 150) // 150ms is enough for OS audio routing to settle; shorter = faster re-activation
    }

    private fun onWakeWord(audioData: ShortArray) {
        // Speaker verification — only respond to enrolled owner's voice
        if (voiceProfile?.isEnrolled() == true) {
            val isOwner = voiceProfile!!.isOwner(audioData)
            if (!isOwner) {
                Log.d(TAG, "Voice verification FAILED — not owner, ignoring")
                // Restart wake word with delay to let mic release
                Handler(Looper.getMainLooper()).postDelayed({
                    startWakeWordListening()
                }, 1000)
                return
            }
            Log.d(TAG, "Voice verification PASSED — owner confirmed")
        } else {
            Log.w(TAG, "Voice not enrolled — accepting all voices until training")
        }

        // FIRST: Stop wake word engine completely and release mic
        wakeWordEngine?.stop()
        wakeWordEngine = null

        // Wake screen if it's off
        wakeScreen()

        // If passive mode, transition to active
        if (isPassive) {
            Log.d(TAG, "Waking from passive mode")
            isPassive = false
            updateNotification("Sedu is listening...")
        }

        // If already in conversation, this is an interrupt — reset everything
        if (inConversation) {
            interruptAll()
            return
        }

        hasPromptedOnSilence = false
        speechErrorCount = 0
        stopWakeWordWatchdog()
        inConversation = true
        resetInactivityTimer()

        // Subtle vibrate only — no beep sound
        vibrate()

        // Show glow overlay
        try { overlay?.show() } catch (e: Exception) { Log.e(TAG, "Overlay error", e) }
        try { overlay?.setConversationMode() } catch (e: Exception) { }

        currentState = SeduServiceState.LISTENING_COMMAND
        updateNotification("Sun raha hoon...")

        // Wait 250ms for mic to fully release from WakeWordEngine, then speak greeting
        Handler(Looper.getMainLooper()).postDelayed({
            if (!inConversation) { endConversation(); return@postDelayed }
            seduTTS?.speak("राम राम ${UserPrefs.salutationByGender(this)}, क्या मदद चाहिए?") {
                // TTS done callback — marshal to main thread, start listening immediately
                Handler(Looper.getMainLooper()).post {
                    if (inConversation) startCommandListening()
                }
            }
        }, 250)
    }

    /**
     * Interrupt ALL ongoing tasks (TTS, speech, AI) and start listening fresh.
     * Called when user says "Sedu" while already in conversation.
     */
    private fun interruptAll() {
        Log.d(TAG, "*** INTERRUPT — Sedu detected, stopping all tasks ***")
        seduTTS?.stop()
        speechEngine?.stop()
        speechEngine = null
        geminiBrain?.cancelActiveRequest()
        geminiThread?.interrupt()
        geminiThread = null
        wakeWordEngine?.stop()
        wakeWordEngine = null

        hasPromptedOnSilence = false
        speechErrorCount = 0
        inConversation = true
        resetInactivityTimer()
        vibrate()

        try { overlay?.show() } catch (e: Exception) { }
        try { overlay?.setConversationMode() } catch (e: Exception) { }

        currentState = SeduServiceState.LISTENING_COMMAND
        updateNotification("Sun raha hoon...")

        // Wait for mic release, then speak greeting and start recognizer
        Handler(Looper.getMainLooper()).postDelayed({
            if (!inConversation) return@postDelayed
            seduTTS?.speak("हाँ बोलो ${UserPrefs.salutationByGender(this)}?") {
                Handler(Looper.getMainLooper()).postDelayed({
                    if (inConversation) startGoogleSpeechRecognizer()
                }, 100)
            }
        }, 250)
    }

    private fun startCommandListening() {
        if (!inConversation) {
            endConversation()
            return
        }

        currentState = SeduServiceState.LISTENING_COMMAND
        updateNotification("Aapki baat sun raha hoon...")
        try { overlay?.setConversationMode() } catch (e: Exception) { }

        // Stop wake word engine (it holds the mic)
        wakeWordEngine?.stop()
        wakeWordEngine = null

        // Reuse existing SpeechEngine if available — avoids audible mic restart
        if (speechEngine != null) {
            speechEngine?.restartForNextCommand()
            return
        }

        // First time or after error — create fresh SpeechEngine
        Handler(Looper.getMainLooper()).postDelayed({
            if (inConversation) {
                startGoogleSpeechRecognizer()
            }
        }, 100)
    }

    private val WAKE_WORDS_IN_SPEECH = listOf("sedu", "said you", "say do", "see do", "se do", "said do", "so do")

    private fun stripWakeWord(text: String): String {
        var cleaned = text.trim()
        for (w in WAKE_WORDS_IN_SPEECH) {
            if (cleaned.lowercase().startsWith(w)) {
                cleaned = cleaned.substring(w.length).trimStart(',', ' ')
            }
        }
        return cleaned.ifBlank { text }
    }

    private fun startGoogleSpeechRecognizer() {
        val contactNames = contactsHelper?.getContactNamesList() ?: emptyList()
        speechEngine = SpeechEngine(this, object : SpeechEngine.SpeechCallback {
            override fun onResult(text: String) {
                Log.d(TAG, "Command: $text")
                hasPromptedOnSilence = false
                speechErrorCount = 0
                resetInactivityTimer()
                // Strip wake word if user said "Sedu, call papa"
                val cleaned = stripWakeWord(text)
                processCommand(cleaned)
            }

            override fun onPartial(text: String) {
                updateNotification("Hearing: $text")
                resetInactivityTimer()
            }

            override fun onTimeout() {
                Log.d(TAG, "Speech timeout, conversation=$inConversation, prompted=$hasPromptedOnSilence")
                if (inConversation && !hasPromptedOnSilence) {
                    hasPromptedOnSilence = true
                    seduTTS?.speak("मैं सुन रहा हूँ, बोलो") {
                        if (inConversation) startCommandListening()
                    }
                } else {
                    seduTTS?.speak("कोई जवाब नहीं आया, चलो फिर मिलते हैं") {
                        endConversation()
                    }
                }
            }

            override fun onError(error: String) {
                Log.e(TAG, "Speech error: $error")
                speechEngine?.stop()
                speechEngine = null
                speechErrorCount++
                if (speechErrorCount <= 1 && inConversation) {
                    seduTTS?.speak("सुनने में थोड़ी दिक्कत आई, दोबारा कोशिश करता हूँ") {
                        if (inConversation) startCommandListening()
                    }
                } else {
                    seduTTS?.speak("थोड़ी दिक्कत आ रही है") {
                        endConversation()
                    }
                }
            }
        }, contactNames)
        speechEngine?.startListening() // Default 20s timeout, extends to 35s on speech
    }

    private fun processCommand(text: String) {
        currentState = SeduServiceState.PROCESSING
        updateNotification("Processing: $text")
        try { overlay?.setProcessingMode() } catch (e: Exception) { }

        val localCommand = commandParser?.parse(text) ?: SeduCommand.Unknown(text)
        Log.d(TAG, "Local parsed: $localCommand")

        // Simple device commands → execute INSTANTLY (no AI needed)
        if (isSimpleCommand(localCommand)) {
            executeCommand(localCommand, null)
            return
        }

        // Complex commands (calls, SMS, search, play, chat) → Gemini AI
        // Gemini has the contact list, understands garbled speech, and answers questions
        if (geminiBrain?.hasApiKey() == true) {
            Log.d(TAG, "Sending to Gemini AI: '$text'")
            updateNotification("AI thinking...")
            geminiThread = Thread {
                try {
                    val aiResponse = geminiBrain?.understandFast(text)
                    if (aiResponse != null) {
                        Log.d(TAG, "AI: ${aiResponse.action} → ${aiResponse.reply}")
                        // Save to memory for RAG context
                        geminiBrain?.saveToMemory(text, aiResponse)
                        val aiCommand = geminiBrain?.toCommand(aiResponse) ?: localCommand
                        runOnMainThread { executeCommand(aiCommand, aiResponse.reply) }
                    } else {
                        Log.w(TAG, "AI failed, using local: $localCommand")
                        runOnMainThread { executeCommand(localCommand, null) }
                    }
                } catch (_: InterruptedException) {
                    Log.d(TAG, "Gemini thread interrupted")
                }
                geminiThread = null
            }
            geminiThread?.start()
        } else {
            speakThenListen("अरे यार, फ्यूल ख़त्म हो गया, थोड़ा खाना दे। API key सेट कर दे")
        }
    }

    /** Simple commands that can execute instantly without AI */
    private fun isSimpleCommand(cmd: SeduCommand): Boolean {
        return cmd is SeduCommand.GetTime || cmd is SeduCommand.GetDate ||
            cmd is SeduCommand.GetBattery || cmd is SeduCommand.VolumeUp ||
            cmd is SeduCommand.VolumeDown || cmd is SeduCommand.Mute ||
            cmd is SeduCommand.TorchOn || cmd is SeduCommand.TorchOff ||
            cmd is SeduCommand.WifiOn || cmd is SeduCommand.WifiOff ||
            cmd is SeduCommand.BluetoothOn || cmd is SeduCommand.BluetoothOff ||
            cmd is SeduCommand.BrightnessUp || cmd is SeduCommand.BrightnessDown ||
            cmd is SeduCommand.TakePhoto || cmd is SeduCommand.ReadScreen ||
            cmd is SeduCommand.SetAlarm || cmd is SeduCommand.SetTimer ||
            cmd is SeduCommand.TakeScreenshot || cmd is SeduCommand.ReadNotifications ||
            cmd is SeduCommand.Goodbye || cmd is SeduCommand.OpenSettings ||
            cmd is SeduCommand.OpenApp  // Apps open instantly — no AI needed
    }

    private fun executeCommand(command: SeduCommand, aiReply: String?) {
        if (command is SeduCommand.Goodbye) {
            val reply = aiReply ?: "ठीक है ${UserPrefs.salutationByGender(this)}, अपना ध्यान रखना"
            seduTTS?.speak(reply) {
                endConversation()
            }
            return
        }

        // AskUser — Gemini wants to clarify something, speak question then listen for answer
        if (command is SeduCommand.AskUser) {
            val reply = aiReply ?: "बताओ क्या चाहिए?"
            speakThenListen(reply)
            return
        }

        // Greeting from AI
        if (command is SeduCommand.Unknown && command.rawText.startsWith("greeting")) {
            val reply = aiReply ?: "राम राम भाई, क्या मदद चाहिए?"
            speakThenListen(reply)
            return
        }

        // Chat response from AI
        if (command is SeduCommand.Unknown && command.rawText.startsWith("chat:")) {
            val reply = aiReply ?: "बोलो, मैं सुन रहा हूँ"
            speakThenListen(reply)
            return
        }

        // Any remaining Unknown — still give a useful response
        if (command is SeduCommand.Unknown) {
            val reply = aiReply ?: "एक बार और बोलो, ध्यान से सुनता हूँ"
            speakThenListen(reply)
            return
        }

        currentState = SeduServiceState.SPEAKING
        try { overlay?.setActiveMode() } catch (e: Exception) { }

        // If AI gave a custom reply, use that instead of default
        val executor = actionExecutor ?: return
        val tts = seduTTS ?: return
        if (aiReply != null && command !is SeduCommand.Unknown) {
            executor.execute(command, tts, aiReply) {
                if (inConversation) startCommandListening() else endConversation()
            }
        } else {
            executor.execute(command, tts) {
                if (inConversation) startCommandListening() else endConversation()
            }
        }
    }

    /**
     * Speak reply fully, then start listening. No simultaneous mic — avoids feedback loop.
     * Mic stays on after TTS finishes until user speaks or timeout.
     */
    private fun speakThenListen(reply: String) {
        currentState = SeduServiceState.SPEAKING
        updateNotification("Bol raha hoon...")
        seduTTS?.speak(reply) {
            // TTS finished — now start mic on main thread
            Handler(Looper.getMainLooper()).post {
                if (inConversation) {
                    startCommandListening()
                } else {
                    endConversation()
                }
            }
        }
    }

    /**
     * Go passive — stop everything except wake word listening.
     * Called when user taps STOP. Service stays alive, only listens for owner voice.
     */
    private fun goPassive() {
        Log.d(TAG, "Going passive — wake word only")
        isPassive = true
        isRunning = true
        inConversation = false
        inactivityHandler.removeCallbacks(inactivityChecker)

        // Stop everything that holds the mic
        speechEngine?.stop()
        speechEngine = null
        seduTTS?.stop()
        geminiThread?.interrupt()
        geminiThread = null
        wakeWordEngine?.stop()
        wakeWordEngine = null
        try { overlay?.hide() } catch (_: Exception) {}

        currentState = SeduServiceState.LISTENING_WAKE_WORD
        updateNotification("Sedu so raha hai — 'Sedu' bolke jagao")

        // Longer delay (2s) for Google SpeechRecognizer to fully release mic
        // then start wake word with aggressive watchdog
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isPassive) return@postDelayed  // Already went active
            startWakeWordListening()
            startWakeWordWatchdog()
        }, 2000)
    }

    /** Wake the device screen when wake word is detected */
    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isInteractive) {
                val wl = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                    "sedu:screenon"
                )
                wl.acquire(5000)
                Log.d(TAG, "Screen woken up")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Screen wake error", e)
        }
    }

    private fun runOnMainThread(action: () -> Unit) {
        android.os.Handler(mainLooper).post(action)
    }

    private fun endConversation() {
        Log.d(TAG, "Ending conversation")
        inConversation = false
        geminiBrain?.cancelActiveRequest()
        geminiThread?.interrupt()
        geminiThread = null
        inactivityHandler.removeCallbacks(inactivityChecker)
        seduTTS?.stop()
        speechEngine?.stop()
        speechEngine = null
        try { overlay?.hide() } catch (e: Exception) { Log.e(TAG, "Overlay hide error", e) }

        // Longer delay — SpeechRecognizer needs time to fully release mic
        // before WakeWordEngine can grab it for dual-model free-form recognition
        Handler(Looper.getMainLooper()).postDelayed({
            startWakeWordListening()
            startWakeWordWatchdog()
        }, 1500)
    }

    /** Watchdog — checks regularly that wake word engine is alive, restarts if dead */
    private fun startWakeWordWatchdog() {
        stopWakeWordWatchdog()
        var checkCount = 0
        wakeWordWatchdog = Runnable {
            if (!inConversation && isRunning) {
                val alive = wakeWordEngine?.isAlive() == true
                if (!alive) {
                    Log.w(TAG, "Watchdog: wake word engine dead (check #$checkCount), restarting")
                    startWakeWordListening()
                }
                checkCount++
            }
            // First 5 checks every 5s (aggressive), then back to 15s
            val interval = if (checkCount < 5) 5_000L else WATCHDOG_INTERVAL_MS
            inactivityHandler.postDelayed(wakeWordWatchdog!!, interval)
        }
        // First check after 3s — catch immediate failures fast
        inactivityHandler.postDelayed(wakeWordWatchdog!!, 3_000L)
    }

    private fun stopWakeWordWatchdog() {
        wakeWordWatchdog?.let { inactivityHandler.removeCallbacks(it) }
        wakeWordWatchdog = null
    }

    private fun resetInactivityTimer() {
        lastActivityTime = System.currentTimeMillis()
        inactivityHandler.removeCallbacks(inactivityChecker)
        inactivityHandler.postDelayed(inactivityChecker, 10_000)
    }

    private fun vibrate() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(VibratorManager::class.java)
                vibratorManager.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(
                        VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(150)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibrate error", e)
        }
    }



    private fun createNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, SeduApp.CHANNEL_ID)
            .setContentTitle("Sedu")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            val notification = createNotification(text)
            val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.notify(1, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Notification error", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "SeduService destroyed")
        stopWakeWordWatchdog()
        wakeWordEngine?.stop()
        wakeWordEngine = null
        speechEngine?.stop()
        speechEngine = null
        seduTTS?.shutdown()
        try { overlay?.hide() } catch (e: Exception) { }
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) { }
        isRunning = false
        isPassive = false
        currentState = SeduServiceState.IDLE

        val userStopped = getSharedPreferences(UserPrefs.PREFS_NAME, MODE_PRIVATE)
            .getBoolean(UserPrefs.KEY_USER_STOPPED, false)
        if (userStopped) {
            Log.d(TAG, "Not scheduling restart: user stopped Sedu manually")
            return
        }

        // Schedule restart only when not manually stopped.
        try {
            val restartIntent = Intent(this, SeduService::class.java)
            val pendingIntent = PendingIntent.getService(
                this, 99, restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
            alarmManager.set(
                android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                android.os.SystemClock.elapsedRealtime() + 2000,
                pendingIntent
            )
            Log.d(TAG, "Scheduled restart in 2 seconds")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule restart", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun reloadBrainConfig() {
        val prefs = getSharedPreferences(UserPrefs.PREFS_NAME, MODE_PRIVATE)

        // Only override embedded BuildConfig keys when user provided non-blank values.
        val groq = prefs.getString(UserPrefs.KEY_GROQ_API_KEY, "") ?: ""
        val mistral = prefs.getString(UserPrefs.KEY_MISTRAL_API_KEY, "") ?: ""
        val openai = prefs.getString(UserPrefs.KEY_OPENAI_API_KEY, "") ?: ""
        val gemini = prefs.getString(UserPrefs.KEY_GEMINI_API_KEY, "") ?: ""

        if (groq.isNotBlank()) geminiBrain?.setGroqKey(groq)
        if (mistral.isNotBlank()) geminiBrain?.setMistralKey(mistral)
        if (openai.isNotBlank()) geminiBrain?.setOpenAiKey(openai)
        if (gemini.isNotBlank()) geminiBrain?.setApiKey(gemini)

        geminiBrain?.setUserSalutation(UserPrefs.salutationByGender(this))
    }
}
