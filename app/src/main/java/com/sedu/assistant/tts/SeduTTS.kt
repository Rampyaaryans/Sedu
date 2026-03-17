package com.sedu.assistant.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.sedu.assistant.UserPrefs
import java.util.Locale
import java.util.UUID

class SeduTTS(context: Context) {

    companion object {
        private const val TAG = "SeduTTS"

        // Common Hindi/Hinglish words that Sedu speaks — if text contains these, use Hindi voice
        private val HINDI_MARKERS = setOf(
            "haan", "ji", "boliye", "hoon", "raha", "rahi", "karo", "karta", "karti",
            "kar", "ke", "ka", "ki", "ko", "mein", "hai", "hain", "se", "pe", "nahi",
            "nhi", "mat", "kya", "kaise", "kab", "kahan", "kyun", "kaun", "kisko",
            "theek", "accha", "acha", "suno", "bolo", "batao", "bata", "dikha",
            "dikhao", "khol", "khole", "band", "bandh", "bhej", "bheja", "laga",
            "lagao", "chalu", "shuru", "ruk", "ruko", "chal", "chalo",
            "abhi", "aaj", "kal", "parso", "subah", "shaam", "raat", "din",
            "baj", "baje", "bajte", "minute", "ghanta",
            "mila", "mili", "mile", "nahi", "socho", "samajh", "dobara",
            "sorry", "maaf", "shukriya", "dhanyavaad",
            "khol", "khole", "install", "phone", "call", "message",
            "contacts", "alarm", "timer", "battery", "volume",
            "kam", "zyada", "badha", "badhi", "ghata",
            "sun", "suno", "suniye", "sunna", "sunaao",
            "diya", "diye", "kiya", "kiye", "gaya", "gayi", "gaye",
            "liya", "liye", "aaya", "aayi", "aaye",
            "wala", "wali", "wale", "chahiye", "chahein", "bulana",
            "raha", "rahe", "rahi", "sakta", "sakti", "sakte",
            "hoon", "ho", "hun", "hu",
            "bahut", "thoda", "bohot", "ek", "do", "teen", "char",
            "percent", "settings", "torch", "wifi", "bluetooth",
            "khush", "udaas", "bura", "achha",
            "bhai", "bhaiya", "didi", "papa", "mama", "chacha",
            "screenshot", "brightness", "silent",
            "dhoondh", "search", "result",
            "bajata", "direction", "deta", "navigation"
        )
    }

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var isReady = false
    private val pendingQueue = mutableListOf<Pair<String, (() -> Unit)?>>()

    // Store both voices for switching
    private var hindiVoice: Voice? = null
    private var hindiMaleVoice: Voice? = null
    private var hindiFemaleVoice: Voice? = null

    // Callback map — set listener ONCE, dispatch by utteranceId
    private val completionCallbacks = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()

    /** True when TTS is actively speaking — used for interrupt detection */
    @Volatile
    var isSpeaking = false
        private set

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                logAllVoices()
                selectVoices()

                // Set Hindi locale for pure Hindi output
                tts?.language = Locale("hi", "IN")
                tts?.setSpeechRate(0.95f)
                applyUserVoicePrefs()

                // Set listener ONCE — never overwrite
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        isSpeaking = true
                    }
                    override fun onDone(utteranceId: String?) {
                        isSpeaking = false
                        utteranceId?.let { completionCallbacks.remove(it)?.invoke() }
                    }
                    @Deprecated("Deprecated")
                    override fun onError(utteranceId: String?) {
                        isSpeaking = false
                        utteranceId?.let { completionCallbacks.remove(it)?.invoke() }
                    }
                })

                isReady = true
                Log.d(TAG, "TTS ready, hindi=${hindiVoice?.name}")

                pendingQueue.forEach { speak(it.first, it.second) }
                pendingQueue.clear()
            } else {
                Log.e(TAG, "TTS init failed: $status")
            }
        }
    }

    private fun logAllVoices() {
        try {
            val voices = tts?.voices ?: return
            val hiEnVoices = voices.filter {
                it.locale.language in listOf("hi", "en") && !it.isNetworkConnectionRequired
            }
            for (v in hiEnVoices) {
                Log.d(TAG, "VOICE: ${v.name} | ${v.locale} | quality=${v.quality} | net=${v.isNetworkConnectionRequired}")
            }
        } catch (_: Exception) {}
    }

    /**
     * Select Hindi voice for pure Hindi output.
     */
    private fun selectVoices() {
        try {
            val voices = tts?.voices ?: return
            val voiceMap = voices.associateBy { it.name }
            val hindiOffline = voices.filter {
                it.locale.language == "hi" && !it.isNetworkConnectionRequired
            }

            val hindiMalePrefs = listOf(
                "hi-in-x-hie-local",    // Hindi male E (best male)
                "hi-in-x-hic-local",    // Hindi male C
                "hi-in-x-hib-local",    // Hindi B
            )

            val hindiFemalePrefs = listOf(
                "hi-in-x-hid-local",    // Hindi female D
                "hi-in-x-hia-local",    // Hindi female A
            )

            for (name in hindiMalePrefs) {
                val v = voiceMap[name]
                if (v != null) { hindiMaleVoice = v; break }
            }

            for (name in hindiFemalePrefs) {
                val v = voiceMap[name]
                if (v != null) { hindiFemaleVoice = v; break }
            }

            // Heuristic fallback by voice name tags if preferred IDs are unavailable.
            if (hindiFemaleVoice == null) {
                hindiFemaleVoice = hindiOffline.firstOrNull {
                    val n = it.name.lowercase()
                    n.contains("hid") || n.contains("hia") || n.contains("female") || n.contains("fem")
                }
            }
            if (hindiMaleVoice == null) {
                hindiMaleVoice = hindiOffline.firstOrNull {
                    val n = it.name.lowercase()
                    n.contains("hie") || n.contains("hic") || n.contains("hib") || n.contains("male")
                }
            }

            hindiVoice = hindiMaleVoice ?: hindiFemaleVoice

            // Fallback: any offline Hindi voice
            if (hindiVoice == null) {
                hindiVoice = voices.firstOrNull {
                    it.locale.language == "hi" && !it.isNetworkConnectionRequired
                }
            }

            if (hindiMaleVoice == null) hindiMaleVoice = hindiVoice

            // If a distinct female Hindi voice is unavailable on device, choose any different
            // Hindi offline voice first; only then fall back to male voice.
            if (hindiFemaleVoice == null) {
                hindiFemaleVoice = hindiOffline.firstOrNull { it.name != hindiMaleVoice?.name } ?: hindiVoice
            }

            tts?.voice = hindiVoice

            Log.d(TAG, "Hindi voice selected: ${hindiVoice?.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Voice selection error", e)
        }
    }

    private fun applyUserVoicePrefs() {
        val prefs = appContext.getSharedPreferences(UserPrefs.PREFS_NAME, Context.MODE_PRIVATE)
        val style = prefs.getString(UserPrefs.KEY_TTS_VOICE_STYLE, UserPrefs.VOICE_STYLE_MALE)
        val pitchMode = prefs.getString(UserPrefs.KEY_TTS_PITCH_MODE, UserPrefs.PITCH_MEDIUM)

        val selectedVoice = if (style == UserPrefs.VOICE_STYLE_FEMALE) {
            hindiFemaleVoice ?: hindiVoice
        } else {
            hindiMaleVoice ?: hindiVoice
        }

        selectedVoice?.let { tts?.voice = it }

        val basePitch = when (pitchMode) {
            UserPrefs.PITCH_LOW -> 0.88f
            UserPrefs.PITCH_HIGH -> 1.15f
            else -> 1.0f
        }

        // Ensure female style sounds clearly different even when device lacks a proper female voice.
        val finalPitch = if (style == UserPrefs.VOICE_STYLE_FEMALE) {
            (basePitch + 0.18f).coerceAtMost(1.35f)
        } else {
            basePitch
        }
        tts?.setPitch(finalPitch)
    }

    /**
     * Detect if text is Hindi/Hinglish based on word analysis.
     */
    private fun isHindiText(text: String): Boolean {
        // If text contains Devanagari characters, definitely Hindi
        if (text.any { it.code in 0x0900..0x097F }) return true

        val words = text.lowercase().split(Regex("[\\s,!?.]+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return false

        val hindiCount = words.count { it in HINDI_MARKERS }
        val ratio = hindiCount.toFloat() / words.size

        // If more than 30% of words are Hindi markers, use Hindi voice
        return ratio > 0.3f
    }

    fun speak(text: String, onComplete: (() -> Unit)? = null) {
        if (!isReady) {
            pendingQueue.add(text to onComplete)
            return
        }

        val utteranceId = UUID.randomUUID().toString()

        if (onComplete != null) {
            completionCallbacks[utteranceId] = onComplete
        }

        // Re-apply user-selected Hindi voice and pitch before each utterance.
        applyUserVoicePrefs()

        // Strip emojis and special symbols — TTS reads them as English words
        val cleanText = text.replace(Regex("[\\x{1F600}-\\x{1F64F}\\x{1F300}-\\x{1F5FF}\\x{1F680}-\\x{1F6FF}\\x{1F1E0}-\\x{1F1FF}\\x{2600}-\\x{27BF}\\x{2300}-\\x{23FF}\\x{2B50}\\x{FE00}-\\x{FE0F}\\x{200D}\\x{20E3}\\x{E0020}-\\x{E007F}]"), "").trim()

        tts?.speak(cleanText, TextToSpeech.QUEUE_ADD, null, utteranceId)
        Log.d(TAG, "Speaking [HI]: $cleanText")
    }

    /**
     * Fix Hindi/Hinglish words that English TTS mispronounces.
     * Only needed when using the English voice.
     */
    private fun fixPronunciation(text: String): String {
        return text
            .replace("\\bband\\b".toRegex(RegexOption.IGNORE_CASE), "bund")
            .replace("\\bbandh\\b".toRegex(RegexOption.IGNORE_CASE), "bund")
            .replace("\\bkaro\\b".toRegex(RegexOption.IGNORE_CASE), "kuh-ro")
            .replace("\\bnahi\\b".toRegex(RegexOption.IGNORE_CASE), "nuhee")
            .replace("\\bmila\\b".toRegex(RegexOption.IGNORE_CASE), "milla")
            .replace("\\bsamajh\\b".toRegex(RegexOption.IGNORE_CASE), "samuj")
            .replace("\\bdobara\\b".toRegex(RegexOption.IGNORE_CASE), "doe-baara")
            .replace("\\bkhol\\b".toRegex(RegexOption.IGNORE_CASE), "khole")
            .replace("\\bbhej\\b".toRegex(RegexOption.IGNORE_CASE), "bhayj")
            .replace("\\bkar raha\\b".toRegex(RegexOption.IGNORE_CASE), "kur raha")
    }

    fun stop() {
        tts?.stop()
        isSpeaking = false
        completionCallbacks.clear()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
        isSpeaking = false
        completionCallbacks.clear()
    }
}
