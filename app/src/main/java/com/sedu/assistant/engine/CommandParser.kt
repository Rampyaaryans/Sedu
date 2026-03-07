package com.sedu.assistant.engine

import com.sedu.assistant.model.SeduCommand

class CommandParser {

    companion object {
        val GOODBYE_WORDS = listOf(
            "bye", "by", "bye bye", "bye now", "tata", "bye tata", "goodbye", "good bye",
            "alvida", "band karo", "band kar", "band ho", "band ho ja", "theek hai", "ok bye",
            "chup", "you done", "i'm done", "done", "shut down", "shut up", "stop",
            "बाय", "बाय बाय", "टाटा", "अलविदा", "बंद करो", "बंद कर", "बंद हो जा",
            "ठीक है", "चुप", "ओके बाय"
        )

        val GREETING_WORDS = listOf(
            "hello", "halo", "hi", "hey", "namaste", "namaskar", "kya haal",
            "how are you", "good morning", "good evening"
        )
    }

    fun isGoodbye(text: String): Boolean {
        val lower = text.lowercase().trim()
        return GOODBYE_WORDS.any { lower == it || lower.contains(it) }
    }

    fun isGreeting(text: String): Boolean {
        val lower = text.lowercase().trim()
        // Only match if it's purely a greeting (short text)
        if (lower.split(" ").size > 4) return false
        return GREETING_WORDS.any { lower == it || lower.contains(it) }
    }

    fun parse(rawText: String): SeduCommand {
        var text = rawText.trim()

        // Remove wake word from start
        val wakeWords = listOf("sedu", "said you", "say do", "see do", "se do", "said do",
            "so do", "sure do", "सेडु", "सेदु", "सेडू", "सेदू", "सीडू", "सीडु")
        for (w in wakeWords) {
            if (text.lowercase().startsWith(w)) {
                text = text.substring(w.length).trimStart(',', ' ')
            }
        }

        val lower = text.lowercase().trim()
        if (lower.isBlank()) return SeduCommand.Unknown(rawText)

        // Check goodbye first
        if (isGoodbye(text)) return SeduCommand.Goodbye

        // Check greeting (only short phrases)
        if (isGreeting(text)) return SeduCommand.Unknown("greeting")

        // Try structured parsing
        return parseCall(lower)
            ?: parseSms(lower)
            ?: parseWhatsApp(lower)
            ?: parseOpenApp(lower)
            ?: parseDeviceControl(lower, text)
            ?: parseMedia(lower)
            ?: parseInfo(lower, text)
            ?: parseNavigation(lower)
            ?: parseTimer(lower)
            ?: parseScreenRead(lower, text)
            ?: parseHindiPatterns(lower, text)
            ?: SeduCommand.Unknown(rawText)
    }

    private fun parseCall(lower: String): SeduCommand? {
        // "call papa", "call mom", "phone papa"
        val patterns = listOf(
            Regex("^(?:call|phone|dial|ring)\\s+(.+)"),
            Regex("^(.+?)\\s+ko\\s+(?:call|phone|fon)\\s*(?:kar|laga|karo)?"),
            Regex("^(.+?)\\s+ko\\s+(?:call|phone)$"),
        )
        for (p in patterns) {
            p.find(lower)?.let {
                val name = it.groupValues[1].trim()
                if (name.isNotBlank()) return SeduCommand.CallContact(name)
            }
        }
        return null
    }

    private fun parseSms(lower: String): SeduCommand? {
        val patterns = listOf(
            Regex("^(?:send\\s+)?(?:sms|message|text|msg)\\s+(?:to\\s+)?(.+?)\\s+(?:saying|that|ki|ke)\\s+(.+)"),
            Regex("^(?:send\\s+)?(?:sms|message|text|msg)\\s+(?:to\\s+)?(.+)"),
            Regex("^(.+?)\\s+ko\\s+(?:sms|message|msg)\\s+(?:bhej|kar|send)\\s+(?:ki\\s+|ke\\s+)?(.+)"),
            Regex("^(.+?)\\s+ko\\s+(?:sms|message|msg)\\s+(?:bhej|kar|send)"),
        )
        for (p in patterns) {
            p.find(lower)?.let {
                val contact = it.groupValues[1].trim()
                val msg = if (it.groupValues.size > 2) it.groupValues[2].trim() else ""
                if (contact.isNotBlank()) return SeduCommand.SendSms(contact, msg)
            }
        }
        return null
    }

    private fun parseWhatsApp(lower: String): SeduCommand? {
        val patterns = listOf(
            Regex("^(?:send\\s+)?whatsapp\\s+(?:to\\s+|message\\s+(?:to\\s+)?)?(.+?)\\s+(?:saying|that|ki)\\s+(.+)"),
            Regex("^(?:send\\s+)?whatsapp\\s+(?:to\\s+|message\\s+(?:to\\s+)?)?(.+)"),
            Regex("^(.+?)\\s+ko\\s+(?:whatsapp|whats\\s*app|watsapp)\\s+(?:kar|bhej|send)\\s*(.*)"),
        )
        for (p in patterns) {
            p.find(lower)?.let {
                val contact = it.groupValues[1].trim()
                val msg = if (it.groupValues.size > 2) it.groupValues[2].trim() else ""
                if (contact.isNotBlank()) return SeduCommand.SendWhatsApp(contact, msg)
            }
        }
        return null
    }

    private fun parseOpenApp(lower: String): SeduCommand? {
        val patterns = listOf(
            Regex("^open\\s+(.+)"),
            Regex("^launch\\s+(.+)"),
            Regex("^start\\s+(.+)"),
            Regex("^(.+?)\\s+(?:khol|kholna|kholo|open\\s+kar|chalu\\s+kar|start\\s+kar)"),
        )
        for (p in patterns) {
            p.find(lower)?.let {
                val name = it.groupValues[1].trim()
                if (name.isNotBlank() && name.length < 30) return SeduCommand.OpenApp(name)
            }
        }

        // Direct app name detection
        val appNames = listOf("youtube", "instagram", "facebook", "whatsapp", "telegram",
            "twitter", "snapchat", "chrome", "gmail", "camera", "gallery", "photos",
            "spotify", "netflix", "calculator", "calendar", "clock", "maps", "settings",
            "amazon", "flipkart", "paytm", "gpay", "phonepe", "zomato", "swiggy")
        for (app in appNames) {
            if (lower == app || lower == "$app khol" || lower == "$app open") {
                return SeduCommand.OpenApp(app)
            }
        }
        return null
    }

    private fun parseDeviceControl(lower: String, text: String): SeduCommand? {
        // Volume
        if (lower.contains("volume up") || lower.contains("louder") ||
            lower.contains("awaaz badha") || lower.contains("awaz badha") ||
            lower.contains("volume badha")) return SeduCommand.VolumeUp

        if (lower.contains("volume down") || lower.contains("quieter") || lower.contains("softer") ||
            lower.contains("awaaz kam") || lower.contains("awaz kam") ||
            lower.contains("volume kam")) return SeduCommand.VolumeDown

        // Mute / Silent
        if (lower.contains("mute") || lower.contains("silent") || lower.contains("chup kar") ||
            lower.contains("silent mode") || lower.contains("do not disturb")) return SeduCommand.Mute

        // Torch
        if (lower.contains("torch") || lower.contains("flashlight") || lower.contains("flash")) {
            return if (lower.contains("off") || lower.contains("band") || lower.contains("bujha")) {
                SeduCommand.TorchOff
            } else {
                SeduCommand.TorchOn
            }
        }

        // WiFi
        if (lower.contains("wifi") || lower.contains("wi-fi") || lower.contains("wi fi")) {
            return if (lower.contains("off") || lower.contains("band") || lower.contains("disable")) {
                SeduCommand.WifiOff
            } else {
                SeduCommand.WifiOn
            }
        }

        // Bluetooth
        if (lower.contains("bluetooth") || lower.contains("bt ") || lower.contains("blue tooth")) {
            return if (lower.contains("off") || lower.contains("band") || lower.contains("disable")) {
                SeduCommand.BluetoothOff
            } else {
                SeduCommand.BluetoothOn
            }
        }

        // Brightness
        if (lower.contains("brightness") || lower.contains("roshni") || lower.contains("chamak")) {
            return if (lower.contains("up") || lower.contains("badha") || lower.contains("high") ||
                lower.contains("zyada") || lower.contains("increase")) {
                SeduCommand.BrightnessUp
            } else {
                SeduCommand.BrightnessDown
            }
        }

        return null
    }

    private fun parseMedia(lower: String): SeduCommand? {
        // Play music / video
        if (lower.startsWith("play ")) {
            return SeduCommand.PlayMusic(lower.removePrefix("play ").trim())
        }
        val playPatterns = listOf(
            Regex("^(.+?)\\s+(?:chala|chalao|bajao|play\\s+kar|suna|sunao)"),
        )
        for (p in playPatterns) {
            p.find(lower)?.let {
                val q = it.groupValues[1].trim()
                if (q.isNotBlank()) return SeduCommand.PlayMusic(q)
            }
        }

        // Camera / Photo
        if (lower.contains("photo") || lower.contains("selfie") || lower.contains("picture") ||
            lower.contains("camera") || lower.contains("tasveer") || lower.contains("foto")) {
            return SeduCommand.TakePhoto
        }

        // Search
        if (lower.startsWith("search ") || lower.startsWith("google ")) {
            val q = lower.removePrefix("search ").removePrefix("google ").removePrefix("for ").trim()
            return SeduCommand.SearchWeb(q)
        }
        val searchPatterns = listOf(
            Regex("^(.+?)\\s+(?:google\\s+kar|search\\s+kar|dhundh|khoj)"),
        )
        for (p in searchPatterns) {
            p.find(lower)?.let {
                return SeduCommand.SearchWeb(it.groupValues[1].trim())
            }
        }

        return null
    }

    private fun parseInfo(lower: String, text: String): SeduCommand? {
        // Time
        if (lower.contains("time") || lower.contains("samay") || lower.contains("baj") ||
            lower.contains("waqt") || lower.contains("kitne baje") ||
            text.contains("टाइम") || text.contains("समय") || text.contains("बज")) {
            return SeduCommand.GetTime
        }

        // Date
        if (lower.contains("date") || lower.contains("tarikh") || lower.contains("din") ||
            text.contains("तारीख") || text.contains("डेट")) {
            return SeduCommand.GetDate
        }
        if (lower.contains("aaj") && (lower.contains("kya") || lower.contains("bata"))) {
            return SeduCommand.GetDate
        }

        // Battery
        if (lower.contains("battery") || lower.contains("charge") || lower.contains("power") ||
            text.contains("बैटरी") || text.contains("चार्ज")) {
            return SeduCommand.GetBattery
        }

        return null
    }

    private fun parseNavigation(lower: String): SeduCommand? {
        val navPatterns = listOf(
            Regex("^(?:navigate|direction|go)\\s+(?:to\\s+)?(.+)"),
            Regex("^(.+?)\\s+(?:le\\s+chal|ka\\s+rasta|le\\s+ja|navigate\\s+kar)"),
            Regex("^map\\s+(?:pe\\s+|mein\\s+)?(.+)"),
        )
        for (p in navPatterns) {
            p.find(lower)?.let {
                val dest = it.groupValues[1].trim()
                if (dest.isNotBlank()) return SeduCommand.Navigate(dest)
            }
        }
        return null
    }

    private fun parseTimer(lower: String): SeduCommand? {
        // Alarm
        val alarmPatterns = listOf(
            Regex("(?:set\\s+)?alarm\\s+(?:at\\s+|for\\s+)?(\\d{1,2})(?::(\\d{2}))?\\s*(?:am|pm|baje)?"),
            Regex("(\\d{1,2})\\s*(?:baje|:?\\s*(\\d{2})?\\s*(?:am|pm)?)\\s+(?:ka\\s+|pe\\s+)?alarm"),
            Regex("alarm\\s+laga\\s+(?:do\\s+)?(\\d{1,2})\\s*(?:baje|:?\\s*(\\d{2})?)"),
        )
        for (p in alarmPatterns) {
            p.find(lower)?.let {
                val hour = it.groupValues[1].toIntOrNull() ?: 0
                val min = if (it.groupValues.size > 2) it.groupValues[2].toIntOrNull() ?: 0 else 0
                return SeduCommand.SetAlarm(hour, min)
            }
        }

        // Timer
        val timerPatterns = listOf(
            Regex("(?:set\\s+)?timer\\s+(?:for\\s+)?(\\d+)\\s*(?:min|minute|minutes)"),
            Regex("(\\d+)\\s*(?:min|minute|minutes?)\\s+(?:ka\\s+)?timer"),
            Regex("timer\\s+laga\\s+(?:do\\s+)?(\\d+)\\s*(?:min|minute)?"),
        )
        for (p in timerPatterns) {
            p.find(lower)?.let {
                val mins = it.groupValues[1].toIntOrNull() ?: 1
                return SeduCommand.SetTimer(mins)
            }
        }

        return null
    }

    private fun parseHindiPatterns(lower: String, text: String): SeduCommand? {
        // Hindi Devanagari Call
        val hiCallPatterns = listOf(
            Regex("(.+?)\\s+को\\s+(?:फोन\\s+लगा|कॉल\\s+कर|फ़ोन\\s+लगा|कॉल\\s+लगा)"),
            Regex("(.+?)\\s+को\\s+(?:फोन|कॉल)")
        )
        for (p in hiCallPatterns) {
            p.find(text)?.let { return SeduCommand.CallContact(it.groupValues[1].trim()) }
        }

        // Hindi Devanagari SMS
        val hiSmsPatterns = listOf(
            Regex("(.+?)\\s+को\\s+(?:एसएमएस|मैसेज|मेसेज)\\s+(?:कर|भेज)\\s+(?:कि\\s+)?(.+)"),
            Regex("(.+?)\\s+को\\s+(?:एसएमएस|मैसेज|मेसेज)\\s+(?:कर|भेज)")
        )
        for (p in hiSmsPatterns) {
            p.find(text)?.let {
                val contact = it.groupValues[1].trim()
                val msg = if (it.groupValues.size > 2) it.groupValues[2].trim() else ""
                return SeduCommand.SendSms(contact, msg)
            }
        }

        // Hindi Devanagari WhatsApp
        val hiWaPatterns = listOf(
            Regex("(.+?)\\s+को\\s+(?:व्हाट्सएप|वॉट्सएप)\\s+(?:कर|भेज)\\s+(?:कि\\s+)?(.+)"),
            Regex("(.+?)\\s+को\\s+(?:व्हाट्सएप|वॉट्सएप)\\s+(?:कर|भेज)")
        )
        for (p in hiWaPatterns) {
            p.find(text)?.let {
                val contact = it.groupValues[1].trim()
                val msg = if (it.groupValues.size > 2) it.groupValues[2].trim() else ""
                return SeduCommand.SendWhatsApp(contact, msg)
            }
        }

        // Hindi Devanagari Open
        val hiOpenPatterns = listOf(
            Regex("(.+?)\\s+(?:खोल|खोलो|ओपन\\s+कर|चालू\\s+कर)")
        )
        for (p in hiOpenPatterns) {
            p.find(text)?.let { return SeduCommand.OpenApp(it.groupValues[1].trim()) }
        }

        // Hindi search/music
        if (text.contains("गूगल") || text.contains("सर्च") || text.contains("ढूंढ") || text.contains("खोज")) {
            return SeduCommand.SearchWeb(text)
        }
        if (text.contains("चला") || text.contains("बजा") || text.contains("सुना")) {
            return SeduCommand.PlayMusic(text)
        }

        // Hindi settings
        if (lower.contains("settings") || lower.contains("setting") || text.contains("सेटिंग")) {
            return SeduCommand.OpenSettings("")
        }

        return null
    }

    private fun parseScreenRead(lower: String, text: String): SeduCommand? {
        // "read screen", "screen padh", "screen kya hai", "screen dekh", "what's on screen"
        if (lower.contains("screen") && (lower.contains("read") || lower.contains("padh") ||
            lower.contains("kya") || lower.contains("dekh") || lower.contains("bata") ||
            lower.contains("what"))) {
            return SeduCommand.ReadScreen
        }
        if (lower.contains("kya dikh raha") || lower.contains("kya hai screen") ||
            lower.contains("screen batao") || lower.contains("screen padho") ||
            lower.contains("screen dekho")) {
            return SeduCommand.ReadScreen
        }
        // Devanagari
        if (text.contains("स्क्रीन") && (text.contains("पढ़") || text.contains("देख") ||
            text.contains("बता") || text.contains("क्या"))) {
            return SeduCommand.ReadScreen
        }
        return null
    }
}
