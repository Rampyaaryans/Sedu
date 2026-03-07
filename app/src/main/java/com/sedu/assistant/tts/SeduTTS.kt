package com.sedu.assistant.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
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

    private var tts: TextToSpeech? = null
    private var isReady = false
    private val pendingQueue = mutableListOf<Pair<String, (() -> Unit)?>>()

    // Store both voices for switching
    private var hindiVoice: Voice? = null
    private var englishVoice: Voice? = null

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                logAllVoices()
                selectVoices()

                tts?.setPitch(0.82f)
                tts?.setSpeechRate(0.95f)

                isReady = true
                Log.d(TAG, "TTS ready, hindi=${hindiVoice?.name}, english=${englishVoice?.name}")

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
     * Select both Hindi and English voices for hybrid switching.
     */
    private fun selectVoices() {
        try {
            val voices = tts?.voices ?: return
            val voiceMap = voices.associateBy { it.name }

            // Hindi voice preferences (for Hindi/Hinglish text)
            val hindiPrefs = listOf(
                "hi-in-x-hie-local",    // Hindi male E (smoother)
                "hi-in-x-hic-local",    // Hindi male C
            )

            // English voice preferences (for pure English text)
            val englishPrefs = listOf(
                "en-in-x-enc-local",    // Indian English male C (clear)
                "en-in-x-end-local",    // Indian English male D
                "en-in-x-ene-local",    // Indian English male E
            )

            for (name in hindiPrefs) {
                val v = voiceMap[name]
                if (v != null) { hindiVoice = v; break }
            }

            for (name in englishPrefs) {
                val v = voiceMap[name]
                if (v != null) { englishVoice = v; break }
            }

            // Set Hindi as default since most Sedu responses are Hinglish
            val defaultVoice = hindiVoice ?: englishVoice
            if (defaultVoice != null) {
                tts?.voice = defaultVoice
            }

            Log.d(TAG, "Hindi voice: ${hindiVoice?.name}, English voice: ${englishVoice?.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Voice selection error", e)
        }
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
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {
                    if (id == utteranceId) onComplete()
                }
                @Deprecated("Deprecated")
                override fun onError(id: String?) {
                    if (id == utteranceId) onComplete()
                }
            })
        }

        // Pick voice based on text language
        val useHindi = isHindiText(text)
        val targetVoice = if (useHindi) hindiVoice else englishVoice
        if (targetVoice != null && tts?.voice?.name != targetVoice.name) {
            tts?.voice = targetVoice
            Log.d(TAG, "Switched to ${if (useHindi) "HINDI" else "ENGLISH"} voice for: $text")
        }

        // Apply pronunciation fixes only for English voice (Hindi voice handles Hindi natively)
        val spokenText = if (!useHindi) fixPronunciation(text) else text

        tts?.speak(spokenText, TextToSpeech.QUEUE_ADD, null, utteranceId)
        Log.d(TAG, "Speaking [${if (useHindi) "HI" else "EN"}]: $spokenText")
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
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }
}
