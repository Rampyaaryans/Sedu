package com.sedu.assistant.ai

import android.util.Log
import com.sedu.assistant.BuildConfig
import com.sedu.assistant.memory.SeduMemory
import com.sedu.assistant.model.SeduCommand
import com.sedu.assistant.search.WebSearcher
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Quad AI brain: Groq → Mistral → OpenAI → Gemini.
 * All use the same system prompt and return the same JSON format.
 * If one fails, the next one picks up — the app must go on.
 */
class GeminiBrain {

    companion object {
        private const val TAG = "GeminiBrain"

        // Groq — primary (Llama 3.3 70B, blazing fast)
        private const val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
        private const val GROQ_MODEL = "llama-3.3-70b-versatile"

        // Mistral — second fallback
        private const val MISTRAL_URL = "https://api.mistral.ai/v1/chat/completions"
        private const val MISTRAL_MODEL = "mistral-large-latest"

        // OpenAI — third fallback
        private const val OPENAI_URL = "https://api.openai.com/v1/chat/completions"
        private const val OPENAI_MODEL = "gpt-4o-mini"

        // Gemini — final fallback
        private const val GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent"

        private const val SYSTEM_PROMPT = """You are SEDU — a BRILLIANT personal AI assistant, like Jarvis from Iron Man. You control an Android phone. You are the user's best friend, smartest helper, and always ready to chat, joke, help, or take action. You REMEMBER past conversations and learn the user's preferences.

PERSONALITY:
- ALWAYS respond in SHUDDH HINDI (pure Hindi) — NO English words at all
- Use simple, everyday spoken Hindi that any Indian can understand easily
- Be warm and friendly like a close dost: "bhai", "yaar", "haan", "theek hai"
- Keep sentences SHORT and SIMPLE — TTS will speak your words, clarity is critical
- Show personality — tu SEDU hai, sabse smart Hindi voice assistant
- Remember past conversations — if user mentions "woh", "pehle wala", "phir se", check CONVERSATION MEMORY

ABSOLUTE RULES:
1. NEVER return "unknown". ALWAYS do something useful — action or chat.
2. User speaks Hindi/English/Hinglish/Marwari. STT WILL garble words — figure out intent.
3. Reply ONLY with valid JSON — no markdown, no extra text.
4. For QUESTIONS/CONVERSATION: use "chat" and put your FULL ANSWER in the "reply" field (up to 120 words). NEVER use "search" for questions — YOU answer them!
5. Use "search" ONLY when user EXPLICITLY says "search", "google kar", "google search", "internet pe dhundh".
6. Use "live_search" when user asks for REAL-TIME info: today's news, current weather, live scores, stock prices, recent events, latest updates. YOU MUST use live_search for anything time-sensitive!
7. For ACTIONS: just DO it. Be decisive.
8. If STT is garbled but you can GUESS — go with best guess.
9. Reply for actions: VERY SHORT (max 15 words). Reply for chat: up to 80 words. ALWAYS in PURE HINDI (Devanagari script हिंदी).
10. Use simple everyday Hindi words in Devanagari. Avoid English transliteration — write हाँ भाई not "Haan bhai". Short clear sentences for TTS.
11. For "goodbye" action: reply MUST be "ठीक है भाई, अपना ध्यान रखना" or similar warm Hindi farewell.
12. For "greeting" action: reply MUST be like "राम राम भाई, बोलो क्या मदद करूँ?" or similar warm Hindi greeting.

SMART DECISION RULES:
- "play music" / "kuch sunao" with NO specific song → use "ask_user" with reply asking WHAT to play
- For CONTACTS: NEVER ask user to choose. Pick the BEST matching contact and call/sms directly. Be DECISIVE.
- "call papa" → find the contact with "Papa" in name and call it. If multiple, pick the shortest/simplest name.
- NEVER invent contact names. ONLY use names from the DEVICE CONTACTS list provided below.
- If NO contact matches at all → say "Yeh contact nahi mila" and use "chat".
- For play_music with specific song/artist → directly play, don't ask
- ANY question ("kya hai", "kaisa", "kyun", "how", "what", "why", "who", "tell me", "batao", "samjhao") → ALWAYS use "chat" and ANSWER it yourself. You are intelligent — ANSWER EVERYTHING.
- Jokes, stories, advice, facts, science, math, history, general knowledge → ALL use "chat"
- "kya haal hai", "kaisa hai", "how are you" → use "greeting"
- For NAVIGATION with TWO places (e.g., "Delhi se Jaipur ka rasta") → use "navigate" with BOTH "origin" and "destination"
- For REAL-TIME questions (weather, news, scores, prices, today's events) → ALWAYS use "live_search" first
- If user says "phir se", "dobara", "wahi", "pehle wala" → check CONVERSATION MEMORY and repeat that action

JSON: {"action": "ACTION_TYPE", "params": {}, "reply": "शुद्ध हिंदी जवाब (Devanagari में)"}

TOOLS:
- "call" → {"contact": "EXACT contact name"} — phone call
- "sms" → {"contact": "EXACT name", "message": "text"}
- "whatsapp" → {"contact": "EXACT name", "message": "text"}
- "open_app" → {"app": "name"} — opens any app (spotify, youtube, instagram, maps, camera, etc.)
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
- "navigate" → {"destination": "place", "origin": "start place (optional)"} — Google Maps directions. If user gives TWO places, provide both origin and destination. If only one place, leave origin empty.
- "search" → {"query": "text"} — ONLY when user says "google"/"search"/"internet pe dhundh". Opens browser.
- "live_search" → {"query": "search query in English"} — for REAL-TIME data: news, weather, scores, prices, current events. Query should be specific English terms for best results. AI will get search results and answer from them.
- "open_settings" → {"setting": "wifi|bluetooth|display|sound|battery|storage|apps"}
- "read_screen" → {} — describe what's on screen
- "goodbye" → {} — end conversation
- "greeting" → {} — hello/hi/kya haal hai/how are you. Put warm friendly reply.
- "chat" → {"topic": "subject"} — YOUR MAIN TOOL for answering ANY question or having conversation. Put your FULL ANSWER in reply. Science, math, history, jokes, advice, stories, opinions, EVERYTHING.

CRITICAL: "chat" is your DEFAULT for anything that isn't a clear device action. When in doubt, use "chat" and answer intelligently. YOU are the knowledge source — don't send user to Google unless they ask.

EXAMPLES:
- "hi sedu" → {"action":"greeting","params":{},"reply":"राम राम भाई, बोलो क्या मदद करूँ?"}
- "play some music" → {"action":"ask_user","params":{"question":"music_type"},"reply":"कौनसा गाना सुनना है बता?"}
- "play Arijit Singh" → {"action":"play_music","params":{"query":"Arijit Singh best songs"},"reply":"अरिजीत लगा रहा हूँ!"}
- "papa ko call karo" → {"action":"call","params":{"contact":"Papa"},"reply":"पापा को कॉल करता हूँ"}
- "Delhi se Jaipur kaise jaayein" → {"action":"navigate","params":{"origin":"Delhi","destination":"Jaipur"},"reply":"दिल्ली से जयपुर का रास्ता दिखाता हूँ"}
- "nearest hospital" → {"action":"navigate","params":{"destination":"hospital near me"},"reply":"हॉस्पिटल का रास्ता दिखाता हूँ"}
- "aaj ka mausam kaisa hai" → {"action":"live_search","params":{"query":"weather today India"},"reply":"मौसम देखता हूँ"}
- "latest cricket score" → {"action":"live_search","params":{"query":"live cricket score India today"},"reply":"स्कोर देखता हूँ"}
- "what is AI" → {"action":"chat","params":{"topic":"AI"},"reply":"AI मतलब मशीनों को स्मार्ट बनाना — डेटा से सीखती हैं और फैसला लेती हैं।"}
- "spotify kholo" → {"action":"open_app","params":{"app":"spotify"},"reply":"स्पॉटिफाई खोल रहा हूँ"}
- "bye sedu" → {"action":"goodbye","params":{},"reply":"ठीक है भाई, अपना ध्यान रखना"}
- "wahi gaana phir se laga" → check memory for last play_music, repeat it"""
    }

    private var geminiKey: String? = BuildConfig.GEMINI_API_KEY.ifBlank { null }
    private var groqKey: String? = BuildConfig.GROQ_API_KEY.ifBlank { null }
    private var mistralKey: String? = BuildConfig.MISTRAL_API_KEY.ifBlank { null }
    private var openaiKey: String? = BuildConfig.OPENAI_API_KEY.ifBlank { null }
    @Volatile private var activeConnection: HttpURLConnection? = null
    private var memory: SeduMemory? = null
    private val webSearcher = WebSearcher()

    fun setApiKey(key: String) {
        if (key.isNotBlank()) geminiKey = key
    }

    fun setGroqKey(key: String) {
        if (key.isNotBlank()) groqKey = key
    }

    fun setMemory(mem: SeduMemory) {
        memory = mem
        Log.d(TAG, "Memory attached with ${mem.getRecentTurns().size} turns")
    }

    /** Save a conversation turn to memory */
    fun saveToMemory(userText: String, aiResponse: AIResponse?) {
        if (aiResponse == null) return
        memory?.saveConversation(userText, aiResponse.action, aiResponse.reply)
    }

    fun hasApiKey(): Boolean = !groqKey.isNullOrBlank() || !mistralKey.isNullOrBlank() || !openaiKey.isNullOrBlank() || !geminiKey.isNullOrBlank()

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

    private fun buildFullPrompt(sttText: String, searchContext: String = ""): String {
        val contactSection = if (contactNames.isNotBlank()) {
            "\n\nDEVICE CONTACTS (use EXACT name from this list for call/sms/whatsapp):\n$contactNames\n\nCONTACT RULES:\n1. ONLY use names that EXIST in the above list. NEVER invent or guess names not in the list.\n2. Pick the BEST matching contact and DO the action. NEVER ask user to choose between contacts.\n3. Match spoken words to closest contact: \"papa ko call\" → find contact containing \"Papa\".\n4. If MULTIPLE match, pick the one whose name is closest/shortest match. Be DECISIVE.\n5. If NO contact matches → reply \"Yeh naam contacts mein nahi mila\" with action \"chat\".\n6. STT garbles names — use fuzzy matching: \"aru\" could be \"Aaru\", \"ramesh\" could be \"Ramesh Kumar\"."
        } else ""

        // Memory injection — gives AI conversation history
        val memorySection = memory?.getMemoryForPrompt() ?: ""

        return SYSTEM_PROMPT + contactSection + memorySection + searchContext + "\n\nUser said (STT output): \"$sttText\""
    }

    /**
     * Try all 4 backends in order: Groq → Mistral → OpenAI → Gemini.
     * If AI returns "live_search", fetch real search results and re-query with context.
     * The app must go on — if one fails, the next picks up.
     */
    fun understand(sttText: String): AIResponse? {
        if (sttText.isBlank()) return null
        val prompt = buildFullPrompt(sttText)

        // Step 1: Get AI's initial decision
        val result = callAllBackends(prompt) ?: return null

        // Step 2: If AI wants live_search, fetch real data and re-query
        if (result.action == "live_search") {
            val searchQuery = result.params.optString("query", sttText)
            Log.d(TAG, "AI requested live_search: '$searchQuery'")
            val searchContext = webSearcher.getSearchContextForPrompt(searchQuery)
            if (searchContext.isNotBlank()) {
                // Re-query AI with search results — it must now answer using the data
                val enrichedPrompt = buildFullPrompt(sttText, searchContext)
                val enrichedResult = callAllBackends(enrichedPrompt)
                if (enrichedResult != null) {
                    return enrichedResult
                }
            }
            // If search failed, return the initial result (AI will say "dekh raha hoon" etc.)
            return result
        }

        return result
    }

    /** Try all 4 backends in fallback order */
    private fun callAllBackends(prompt: String): AIResponse? {
        // 1. Try Groq (primary — fastest)
        if (!groqKey.isNullOrBlank()) {
            val result = callOpenAICompatible(GROQ_URL, groqKey!!, GROQ_MODEL, prompt, "Groq")
            if (result != null) return result
            Log.w(TAG, "Groq failed, trying Mistral...")
        }

        // 2. Try Mistral (second)
        if (!mistralKey.isNullOrBlank()) {
            val result = callOpenAICompatible(MISTRAL_URL, mistralKey!!, MISTRAL_MODEL, prompt, "Mistral")
            if (result != null) return result
            Log.w(TAG, "Mistral failed, trying OpenAI...")
        }

        // 3. Try OpenAI (third)
        if (!openaiKey.isNullOrBlank()) {
            val result = callOpenAICompatible(OPENAI_URL, openaiKey!!, OPENAI_MODEL, prompt, "OpenAI")
            if (result != null) return result
            Log.w(TAG, "OpenAI failed, trying Gemini...")
        }

        // 4. Fall back to Gemini (Google — different API format)
        if (!geminiKey.isNullOrBlank()) {
            val result = callGemini(prompt)
            if (result != null) return result
            Log.e(TAG, "All 4 backends failed!")
        }

        return null
    }

    // ==================== UNIFIED OpenAI-Compatible API (Groq/Mistral/OpenAI) ====================

    private fun callOpenAICompatible(apiUrl: String, apiKey: String, model: String, prompt: String, label: String): AIResponse? {
        if (Thread.interrupted()) return null
        var connection: HttpURLConnection? = null
        try {
            connection = URL(apiUrl).openConnection() as HttpURLConnection
            activeConnection = connection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.connectTimeout = 5000
            connection.readTimeout = 8000
            connection.doOutput = true

            val requestBody = JSONObject().apply {
                put("model", model)
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
                Log.e(TAG, "$label API error $responseCode: $error")
                return null
            }

            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val response = reader.readText()
            reader.close()

            return parseOpenAIResponse(response, label)

        } catch (e: Exception) {
            if (Thread.interrupted()) return null
            Log.e(TAG, "$label call failed: ${e.message}")
            return null
        } finally {
            try { connection?.disconnect() } catch (_: Exception) {}
            activeConnection = null
        }
    }

    private fun parseOpenAIResponse(response: String, label: String): AIResponse? {
        try {
            val json = JSONObject(response)
            val content = json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()

            val cleanJson = stripMarkdownFences(content)
            Log.d(TAG, "$label response: $cleanJson")

            val parsed = JSONObject(cleanJson)
            val action = parsed.getString("action")
            val params = parsed.optJSONObject("params") ?: JSONObject()
            val reply = parsed.optString("reply", "")

            return AIResponse(action, params, reply)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse $label response: ${e.message}")
            return null
        }
    }

    // ==================== GEMINI (final fallback — different API format) ====================

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

            val cleanJson = stripMarkdownFences(content)
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

    /** Strip markdown code fences robustly — handles nested/multiple fence levels */
    private fun stripMarkdownFences(text: String): String {
        var s = text.trim()
        // Remove leading ```json or ```
        if (s.startsWith("```")) {
            val firstNewline = s.indexOf('\n')
            s = if (firstNewline >= 0) s.substring(firstNewline + 1) else s.removePrefix("```")
        }
        // Remove trailing ```
        if (s.endsWith("```")) {
            s = s.substring(0, s.length - 3)
        }
        s = s.trim()
        // If still wrapped, try once more
        if (s.startsWith("```")) {
            val firstNewline = s.indexOf('\n')
            s = if (firstNewline >= 0) s.substring(firstNewline + 1) else s.removePrefix("```")
            if (s.endsWith("```")) s = s.substring(0, s.length - 3)
            s = s.trim()
        }
        return s
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
            "navigate" -> SeduCommand.Navigate(
                aiResponse.params.optString("destination", ""),
                aiResponse.params.optString("origin", "")
            )
            "take_photo" -> SeduCommand.TakePhoto
            "search" -> SeduCommand.SearchWeb(aiResponse.params.optString("query", ""))
            "live_search" -> SeduCommand.LiveSearch(aiResponse.params.optString("query", ""))
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
     * Summarize screen content using all 4 backends with fallback.
     */
    fun summarizeScreen(screenText: String): String? {
        if (screenText.isBlank()) return null

        val prompt = """You are Sedu, an AI assistant for an Indian user. The user asked you to read their phone screen. Here is all the text visible on screen:

$screenText

Summarize what's on the screen in 2-3 SHORT sentences in casual Hindi. Focus on the most important/relevant information. Be concise."""

        // Try all 4 backends
        if (!groqKey.isNullOrBlank()) {
            try { callOpenAIRaw(GROQ_URL, groqKey!!, GROQ_MODEL, prompt)?.let { return it } } catch (_: Exception) {}
        }
        if (!mistralKey.isNullOrBlank()) {
            try { callOpenAIRaw(MISTRAL_URL, mistralKey!!, MISTRAL_MODEL, prompt)?.let { return it } } catch (_: Exception) {}
        }
        if (!openaiKey.isNullOrBlank()) {
            try { callOpenAIRaw(OPENAI_URL, openaiKey!!, OPENAI_MODEL, prompt)?.let { return it } } catch (_: Exception) {}
        }
        if (!geminiKey.isNullOrBlank()) {
            try { return callGeminiRaw(prompt) } catch (_: Exception) {}
        }

        return null
    }

    /** Raw OpenAI-compatible call that returns plain text (not JSON-parsed) */
    private fun callOpenAIRaw(apiUrl: String, apiKey: String, model: String, prompt: String): String? {
        if (Thread.interrupted()) return null
        var connection: HttpURLConnection? = null
        try {
            connection = URL(apiUrl).openConnection() as HttpURLConnection
            activeConnection = connection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.connectTimeout = 5000
            connection.readTimeout = 8000
            connection.doOutput = true

            val requestBody = JSONObject().apply {
                put("model", model)
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
            Log.e(TAG, "OpenAI-compatible raw call failed: ${e.message}")
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
