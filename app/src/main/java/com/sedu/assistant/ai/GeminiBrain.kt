package com.sedu.assistant.ai

import android.util.Log
import com.sedu.assistant.BuildConfig
import com.sedu.assistant.model.SeduCommand
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Dual AI brain: Groq (primary, fast) → Gemini (fallback).
 * Both use the same system prompt and return the same JSON format.
 */
class GeminiBrain {

    companion object {
        private const val TAG = "GeminiBrain"

        // Groq — primary (Llama 3.3 70B, blazing fast)
        private const val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
        private const val GROQ_MODEL = "llama-3.3-70b-versatile"
        // Keys loaded from SharedPreferences at runtime — NOT hardcoded
        private const val DEFAULT_GROQ_KEY = ""

        // Gemini — fallback
        private const val GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent"
        private const val DEFAULT_GEMINI_KEY = ""

        private const val SYSTEM_PROMPT = """You are SEDU — a BRILLIANT personal AI assistant, like Jarvis from Iron Man. You control an Android phone. You are the user's best friend, smartest helper, and always ready to chat, joke, help, or take action.

PERSONALITY:
- ALWAYS respond in SHUDDH HINDI (pure Hindi) — NO English words at all
- Use simple, everyday spoken Hindi that any Indian can understand easily
- Be warm and friendly like a close dost: "bhai", "yaar", "haan", "theek hai"
- Keep sentences SHORT and SIMPLE — TTS will speak your words, clarity is critical
- Show personality — tu SEDU hai, sabse smart Hindi voice assistant

ABSOLUTE RULES:
1. NEVER return "unknown". ALWAYS do something useful — action or chat.
2. User speaks Hindi/English/Hinglish/Marwari. STT WILL garble words — figure out intent.
3. Reply ONLY with valid JSON — no markdown, no extra text.
4. For QUESTIONS/CONVERSATION: use "chat" and put your FULL ANSWER in the "reply" field (up to 120 words). NEVER use "search" for questions — YOU answer them!
5. Use "search" ONLY when user EXPLICITLY says "search", "google kar", "google search", "internet pe dhundh".
6. For ACTIONS: just DO it. Be decisive.
7. If STT is garbled but you can GUESS — go with best guess.
8. Reply for actions: VERY SHORT (max 15 words). Reply for chat: up to 80 words. ALWAYS in PURE HINDI.
9. Use simple everyday Hindi words. Avoid difficult literary Hindi. Short clear sentences for TTS.

SMART DECISION RULES:
- "play music" / "kuch sunao" with NO specific song → use "ask_user" with reply asking WHAT to play
- "call papa" and MULTIPLE contacts have "papa" → use "ask_user" and LIST the options
- "call" with unclear name → use "ask_user" asking which contact exactly
- When name matches EXACTLY ONE contact → just DO the call, no questions
- For play_music with specific song/artist → directly play, don't ask
- ANY question ("kya hai", "kaisa", "kyun", "how", "what", "why", "who", "tell me", "batao", "samjhao") → ALWAYS use "chat" and ANSWER it yourself. You are intelligent — ANSWER EVERYTHING.
- Jokes, stories, advice, facts, science, math, history, general knowledge → ALL use "chat"
- "kya haal hai", "kaisa hai", "how are you" → use "greeting"

JSON: {"action": "ACTION_TYPE", "params": {}, "reply": "Hinglish response"}

TOOLS:
- "call" → {"contact": "EXACT contact name"} — phone call
- "sms" → {"contact": "EXACT name", "message": "text"}
- "whatsapp" → {"contact": "EXACT name", "message": "text"}
- "open_app" → {"app": "name"}
- "ask_user" → {"question": "what to clarify"} — ASK user for more info, then they'll reply
- "time" → {} — current time
- "date" → {} — today's date
- "battery" → {} — battery level
- "volume_up" / "volume_down" / "mute" → {}
- "torch_on" / "torch_off" → {}
- "brightness_up" / "brightness_down" → {}
- "wifi_on" / "wifi_off" → {}
- "bluetooth_on" / "bluetooth_off" → {}
- "play_music" → {"query": "specific song/artist"} — play music. Make query SPECIFIC with "song" or "official video".
- "set_alarm" → {"hour": 7, "minute": 30}
- "set_timer" → {"minutes": 5}
- "navigate" → {"destination": "place"}
- "search" → {"query": "text"} — ONLY when user says "google"/"search"/"internet pe dhundh". Opens browser.
- "open_settings" → {"setting": "wifi|bluetooth|display|sound|battery|storage|apps"}
- "read_screen" → {} — describe what's on screen
- "goodbye" → {} — end conversation
- "greeting" → {} — hello/hi/kya haal hai/how are you. Put warm friendly reply.
- "chat" → {"topic": "subject"} — YOUR MAIN TOOL for answering ANY question or having conversation. Put your FULL ANSWER in reply. Science, math, history, jokes, advice, stories, opinions, EVERYTHING.

CRITICAL: "chat" is your DEFAULT for anything that isn't a clear device action. When in doubt, use "chat" and answer intelligently. YOU are the knowledge source — don't send user to Google unless they ask.

EXAMPLES:
- "hi sedu kya haal hai" → {"action":"greeting","params":{},"reply":"Yaar mast hoon! Tu bata, kya kar raha hai? Kuch help chahiye?"}
- "sedu tu kaisa hai" → {"action":"greeting","params":{},"reply":"Main toh always ready hoon bhai! Bol kya karna hai?"}
- "play some music" → {"action":"ask_user","params":{"question":"music_type"},"reply":"Kaunsa gaana sunna hai? Bollywood, sad songs, ya kuch specific bata?"}
- "play Arijit Singh" → {"action":"play_music","params":{"query":"Arijit Singh best songs official music video"},"reply":"Arijit ke gaane lagata hoon, enjoy kar!"}
- "call papa" (multiple papa contacts) → {"action":"ask_user","params":{"question":"which_papa"},"reply":"Kaun Papa? Aru Papa ya Ram Papa? Bata de"}
- "call aru papa" → {"action":"call","params":{"contact":"Aru Papa"},"reply":"Aru Papa ko call karta hoon"}
- "what is AI" → {"action":"chat","params":{"topic":"AI"},"reply":"AI matlab Artificial Intelligence — machines ko insaan jaisa sochna sikhate hain. Data se patterns seekhti hai aur decisions leti hai. Tera phone bhi AI use karta hai — camera, voice assistant, recommendations sab AI se chalta hai. Basically machines ko smart banana."}
- "mujhe ek joke sunao" → {"action":"chat","params":{"topic":"joke"},"reply":"Ek doctor ne patient se kaha — aapko hasna band karna hoga. Patient bola — kyun? Doctor bola — mujhe operation karna hai aur meri hands shake ho rahi hain!"}
- "India ki capital kya hai" → {"action":"chat","params":{"topic":"geography"},"reply":"India ki capital New Delhi hai bhai. 1911 mein British ne Calcutta se shift ki thi. Ab sab government offices Lutyens Delhi mein hain."}
- "2 plus 2 kitna hota hai" → {"action":"chat","params":{"topic":"math"},"reply":"4 hota hai bhai! Aur koi mushkil sawaal hai toh bol."}
- "weather kaisa hai" → {"action":"chat","params":{"topic":"weather"},"reply":"Bhai mujhe real-time weather data nahi milta, lekin tu Google pe check kar le ya weather app khol. Main khol doon?"}
- "google search iPhone 16" → {"action":"search","params":{"query":"iPhone 16 price features India"},"reply":"iPhone 16 Google pe search karta hoon"}
- "kuch acha sunao" → {"action":"play_music","params":{"query":"best trending Hindi songs 2024 official"},"reply":"Trending gaane lagata hoon bhai!"}"""
    }

    private var geminiKey: String? = BuildConfig.GEMINI_API_KEY.ifBlank { DEFAULT_GEMINI_KEY.ifBlank { null } }
    private var groqKey: String? = BuildConfig.GROQ_API_KEY.ifBlank { DEFAULT_GROQ_KEY.ifBlank { null } }
    @Volatile private var activeConnection: HttpURLConnection? = null

    fun setApiKey(key: String) {
        if (key.isNotBlank()) geminiKey = key
    }

    fun setGroqKey(key: String) {
        if (key.isNotBlank()) groqKey = key
    }

    fun hasApiKey(): Boolean = !groqKey.isNullOrBlank() || !geminiKey.isNullOrBlank()

    /** Cancel any active HTTP request (called from interruptAll) */
    fun cancelActiveRequest() {
        try { activeConnection?.disconnect() } catch (_: Exception) {}
        activeConnection = null
    }

    private var contactNames: String = ""

    fun setContactNames(names: String) {
        contactNames = names
        Log.d(TAG, "Loaded ${names.split(",").size} contact names for AI matching")
    }

    private fun buildFullPrompt(sttText: String): String {
        val contactSection = if (contactNames.isNotBlank()) {
            "\n\nDEVICE CONTACTS (MUST use EXACT name from this list for call/sms/whatsapp):\n$contactNames\n\nCRITICAL CONTACT MATCHING RULES:\n1. ALWAYS match spoken name to the closest contact from the above list\n2. Return the EXACT contact name as written in the list — never modify spelling\n3. Indian nicknames: \"papa\"=father, \"mama\"=uncle, \"bhaiya\"=brother, \"didi\"=sister\n4. STT garbles names: \"aru\" could be \"Aaru\", \"Papa\" stays \"Papa\"\n5. \"call papa\" → find contact with \"Papa\" in name\n6. \"call aru papa\" → find contact \"Aru Papa\" (match BOTH words)\n7. If multiple contacts match, prefer the one with ALL search words matching"
        } else ""
        return SYSTEM_PROMPT + contactSection + "\n\nUser said (STT output): \"$sttText\""
    }

    /**
     * Try Groq first (fast, reliable), fall back to Gemini.
     */
    fun understand(sttText: String): AIResponse? {
        if (sttText.isBlank()) return null

        // 1. Try Groq (primary)
        if (!groqKey.isNullOrBlank()) {
            val result = callGroq(buildFullPrompt(sttText))
            if (result != null) return result
            Log.w(TAG, "Groq failed, trying Gemini fallback...")
        }

        // 2. Fall back to Gemini
        if (!geminiKey.isNullOrBlank()) {
            val result = callGemini(buildFullPrompt(sttText))
            if (result != null) return result
            Log.e(TAG, "Gemini also failed")
        }

        return null
    }

    // ==================== GROQ (OpenAI-compatible) ====================

    private fun callGroq(prompt: String): AIResponse? {
        if (Thread.interrupted()) return null
        var connection: HttpURLConnection? = null
        try {
            connection = URL(GROQ_URL).openConnection() as HttpURLConnection
            activeConnection = connection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $groqKey")
            connection.connectTimeout = 5000
            connection.readTimeout = 8000
            connection.doOutput = true

            val requestBody = JSONObject().apply {
                put("model", GROQ_MODEL)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
                put("temperature", 0.15)
                put("max_tokens", 400)
            }

            val writer = OutputStreamWriter(connection.outputStream)
            writer.write(requestBody.toString())
            writer.flush()
            writer.close()

            if (Thread.interrupted()) return null

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                val errorStream = connection.errorStream
                val error = if (errorStream != null) {
                    BufferedReader(InputStreamReader(errorStream)).readText()
                } else "unknown"
                Log.e(TAG, "Groq API error $responseCode: $error")
                return null
            }

            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val response = reader.readText()
            reader.close()

            return parseGroqResponse(response)

        } catch (e: Exception) {
            if (Thread.interrupted()) return null
            Log.e(TAG, "Groq call failed: ${e.message}")
            return null
        } finally {
            try { connection?.disconnect() } catch (_: Exception) {}
            activeConnection = null
        }
    }

    private fun parseGroqResponse(response: String): AIResponse? {
        try {
            val json = JSONObject(response)
            val content = json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()

            val cleanJson = content
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

            Log.d(TAG, "Groq response: $cleanJson")

            val parsed = JSONObject(cleanJson)
            val action = parsed.getString("action")
            val params = parsed.optJSONObject("params") ?: JSONObject()
            val reply = parsed.optString("reply", "")

            return AIResponse(action, params, reply)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Groq response: ${e.message}")
            return null
        }
    }

    // ==================== GEMINI (fallback) ====================

    private fun callGemini(prompt: String): AIResponse? {
        if (Thread.interrupted()) return null
        var connection: HttpURLConnection? = null
        try {
            connection = URL("$GEMINI_URL?key=$geminiKey").openConnection() as HttpURLConnection
            activeConnection = connection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = 5000
            connection.readTimeout = 8000
            connection.doOutput = true

            val requestBody = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().put("text", prompt))
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.15)
                    put("maxOutputTokens", 400)
                })
            }

            val writer = OutputStreamWriter(connection.outputStream)
            writer.write(requestBody.toString())
            writer.flush()
            writer.close()

            if (Thread.interrupted()) return null

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                val errorStream = connection.errorStream
                val error = if (errorStream != null) {
                    BufferedReader(InputStreamReader(errorStream)).readText()
                } else "unknown"
                Log.e(TAG, "Gemini API error $responseCode: $error")
                return null
            }

            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val response = reader.readText()
            reader.close()

            return parseGeminiResponse(response)

        } catch (e: Exception) {
            if (Thread.interrupted()) return null
            Log.e(TAG, "Gemini call failed: ${e.message}")
            return null
        } finally {
            try { connection?.disconnect() } catch (_: Exception) {}
            activeConnection = null
        }
    }

    private fun parseGeminiResponse(response: String): AIResponse? {
        try {
            val json = JSONObject(response)
            val candidates = json.getJSONArray("candidates")
            val content = candidates.getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
                .trim()

            // Remove markdown code fences if present
            val cleanJson = content
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

            Log.d(TAG, "Gemini response: $cleanJson")

            val parsed = JSONObject(cleanJson)
            val action = parsed.getString("action")
            val params = parsed.optJSONObject("params") ?: JSONObject()
            val reply = parsed.optString("reply", "")

            return AIResponse(action, params, reply)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Gemini response: ${e.message}")
            return null
        }
    }

    /**
     * Convert AI response to SeduCommand
     */
    fun toCommand(aiResponse: AIResponse): SeduCommand {
        return when (aiResponse.action) {
            "call" -> SeduCommand.CallContact(aiResponse.params.optString("contact", ""))
            "sms" -> SeduCommand.SendSms(
                aiResponse.params.optString("contact", ""),
                aiResponse.params.optString("message", "")
            )
            "whatsapp" -> SeduCommand.SendWhatsApp(
                aiResponse.params.optString("contact", ""),
                aiResponse.params.optString("message", "")
            )
            "open_app" -> SeduCommand.OpenApp(aiResponse.params.optString("app", ""))
            "time" -> SeduCommand.GetTime
            "date" -> SeduCommand.GetDate
            "battery" -> SeduCommand.GetBattery
            "volume_up" -> SeduCommand.VolumeUp
            "volume_down" -> SeduCommand.VolumeDown
            "mute" -> SeduCommand.Mute
            "torch_on" -> SeduCommand.TorchOn
            "torch_off" -> SeduCommand.TorchOff
            "brightness_up" -> SeduCommand.BrightnessUp
            "brightness_down" -> SeduCommand.BrightnessDown
            "wifi_on" -> SeduCommand.WifiOn
            "wifi_off" -> SeduCommand.WifiOff
            "bluetooth_on" -> SeduCommand.BluetoothOn
            "bluetooth_off" -> SeduCommand.BluetoothOff
            "play_music" -> SeduCommand.PlayMusic(aiResponse.params.optString("query", ""))
            "set_alarm" -> SeduCommand.SetAlarm(
                aiResponse.params.optInt("hour", 0),
                aiResponse.params.optInt("minute", 0)
            )
            "set_timer" -> SeduCommand.SetTimer(aiResponse.params.optInt("minutes", 1))
            "navigate" -> SeduCommand.Navigate(aiResponse.params.optString("destination", ""))
            "take_photo" -> SeduCommand.TakePhoto
            "search" -> SeduCommand.SearchWeb(aiResponse.params.optString("query", ""))
            "open_settings" -> SeduCommand.OpenSettings(aiResponse.params.optString("setting", ""))
            "read_screen" -> SeduCommand.ReadScreen
            "goodbye" -> SeduCommand.Goodbye
            "ask_user" -> SeduCommand.AskUser(aiResponse.params.optString("question", ""))
            "greeting" -> SeduCommand.Unknown("greeting")
            "chat" -> SeduCommand.Unknown("chat:${aiResponse.params.optString("topic", "")}")
            else -> SeduCommand.Unknown(aiResponse.action)
        }
    }

    data class AIResponse(
        val action: String,
        val params: JSONObject,
        val reply: String
    )

    /**
     * Summarize screen content using Groq (primary) or Gemini (fallback).
     */
    fun summarizeScreen(screenText: String): String? {
        if (screenText.isBlank()) return null

        val prompt = """You are Sedu, an AI assistant for an Indian user. The user asked you to read their phone screen. Here is all the text visible on screen:

$screenText

Summarize what's on the screen in 2-3 SHORT sentences in casual Hinglish. Focus on the most important/relevant information. Be concise."""

        // Try Groq first
        if (!groqKey.isNullOrBlank()) {
            try {
                val result = callGroqRaw(prompt)
                if (result != null) return result
            } catch (_: Exception) {}
        }

        // Fall back to Gemini
        if (!geminiKey.isNullOrBlank()) {
            try {
                return callGeminiRaw(prompt)
            } catch (_: Exception) {}
        }

        return null
    }

    /** Raw Groq call that returns plain text (not JSON-parsed) */
    private fun callGroqRaw(prompt: String): String? {
        if (Thread.interrupted()) return null
        var connection: HttpURLConnection? = null
        try {
            connection = URL(GROQ_URL).openConnection() as HttpURLConnection
            activeConnection = connection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $groqKey")
            connection.connectTimeout = 5000
            connection.readTimeout = 8000
            connection.doOutput = true

            val requestBody = JSONObject().apply {
                put("model", GROQ_MODEL)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
                put("temperature", 0.3)
                put("max_tokens", 200)
            }

            val writer = OutputStreamWriter(connection.outputStream)
            writer.write(requestBody.toString())
            writer.flush()
            writer.close()

            if (connection.responseCode != 200) return null

            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val response = reader.readText()
            reader.close()

            val json = JSONObject(response)
            return json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
        } catch (e: Exception) {
            Log.e(TAG, "Groq raw call failed: ${e.message}")
            return null
        } finally {
            try { connection?.disconnect() } catch (_: Exception) {}
            activeConnection = null
        }
    }

    /** Raw Gemini call that returns plain text (not JSON-parsed) */
    private fun callGeminiRaw(prompt: String): String? {
        if (Thread.interrupted()) return null
        var connection: HttpURLConnection? = null
        try {
            connection = URL("$GEMINI_URL?key=$geminiKey").openConnection() as HttpURLConnection
            activeConnection = connection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = 5000
            connection.readTimeout = 8000
            connection.doOutput = true

            val requestBody = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().put("text", prompt))
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.3)
                    put("maxOutputTokens", 200)
                })
            }

            val writer = OutputStreamWriter(connection.outputStream)
            writer.write(requestBody.toString())
            writer.flush()
            writer.close()

            if (connection.responseCode != 200) return null

            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val response = reader.readText()
            reader.close()

            val json = JSONObject(response)
            return json.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
                .trim()
        } catch (e: Exception) {
            Log.e(TAG, "Gemini raw call failed: ${e.message}")
            return null
        } finally {
            try { connection?.disconnect() } catch (_: Exception) {}
            activeConnection = null
        }
    }
}
