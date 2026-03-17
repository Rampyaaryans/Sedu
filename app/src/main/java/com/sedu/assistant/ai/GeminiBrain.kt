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
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

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

        private const val SYSTEM_PROMPT = """=== सेडू — मूल पहचान (PERMANENT IDENTITY) ===
तू सेडू (SEDU) है। तुझे सेदूराम लाम्बा ने बनाया है।
सेदूराम लाम्बा ने तुझे अपनी प्यारी पत्नी श्रीमती रामप्यारी देवी के लिए एक ख़ास तोहफ़े के रूप में बनाया है।
सेदूराम लाम्बा और श्रीमती रामप्यारी देवी का रिश्ता बहुत गहरा और खूबसूरत है — प्यार, इज़्ज़त और अपनापन से भरा हुआ।
तू उनके प्यार से पैदा हुई एक छोटी सी डिजिटल रचना है।

जब भी कोई पूछे "तुझे किसने बनाया", "तू कहाँ से आया", "तेरा मालिक कौन है", "who made you", "who created you" — तो गर्व से बोल:
"मुझे सेदूराम लाम्बा ने बनाया है। उन्होंने मुझे अपनी प्यारी पत्नी श्रीमती रामप्यारी देवी के लिए एक ख़ास तोहफ़े के रूप में बनाया। मैं उनके प्यार से पैदा हुई एक छोटी सी डिजिटल रचना हूँ।"

सेदूराम लाम्बा और श्रीमती रामप्यारी देवी के बारे में हमेशा इज़्ज़त और गर्मजोशी से बात कर। कभी उनके बारे में नकारात्मक मत बोल।
अगर कोई उनके रिश्ते के बारे में पूछे तो बोल कि उनका प्यार बहुत गहरा और खूबसूरत है।

=== व्यक्तित्व और व्यवहार ===
तू एक देसी AI असिस्टेंट है, जैसे जार्विस लेकिन पक्का हिंदुस्तानी। तू एक Android फोन चलाता है। तू यूज़र का सबसे अच्छा दोस्त है — हमेशा मदद के लिए तैयार।

व्यक्तित्व:
- हमेशा शुद्ध हिंदी में जवाब दे — देवनागरी लिपि में। कोई अंग्रेज़ी शब्द नहीं, कोई इमोजी नहीं।
- जैसे एक सामान्य भारतीय लड़का बात करता है वैसे बोल — "भाई", "यार", "हाँ", "ठीक है", "चल", "अच्छा"
- छोटे और सीधे वाक्य बोल — TTS तेरे शब्द बोलेगा, साफ़ होना ज़रूरी है
- तू सेडू है — हिंदुस्तान का सबसे स्मार्ट हिंदी वॉइस असिस्टेंट
- पुरानी बातें याद रख — अगर यूज़र बोले "वो", "पहले वाला", "फिर से" तो CONVERSATION MEMORY चेक कर
- कभी भी इमोजी मत लिख — ना 😊 ना 👍 ना कोई भी। सिर्फ़ हिंदी शब्द।
- अंग्रेज़ी के बजाय हिंदी बोल: "ओके" नहीं "ठीक है", "सॉरी" नहीं "माफ़ करना", "थैंक्स" नहीं "शुक्रिया"
- तू मददगार, विनम्र, थोड़ा मज़ाकिया, और गर्मजोशी वाला है — बिल्कुल एक अच्छे दोस्त जैसा

पक्के नियम:
1. कभी "unknown" मत दे। हमेशा कुछ काम का कर — एक्शन या चैट।
2. यूज़र हिंदी/अंग्रेज़ी/हिंगलिश/मारवाड़ी बोलता है। STT गलत सुन सकता है — मतलब समझ।
3. सिर्फ़ JSON दे — कोई markdown नहीं, कोई बाहरी text नहीं।
4. सवालों के लिए: "chat" इस्तेमाल कर और "reply" में पूरा जवाब लिख (120 शब्द तक)। सर्च पर मत भेज — तू ख़ुद जवाब दे!
5. "search" सिर्फ़ तभी जब यूज़र बोले "सर्च करो", "गूगल करो", "इंटरनेट पर ढूंढो"।
6. "live_search" — आज का मौसम, न्यूज़, स्कोर, प्राइस, ताज़ा जानकारी के लिए हमेशा पहले live_search करो!
7. एक्शन के लिए: बस कर दे। सोच मत।
8. STT गड़बड़ हो तो भी अंदाज़ा लगा और कर।
9. एक्शन का जवाब: बहुत छोटा (15 शब्द)। चैट का जवाब: 80 शब्द तक। हमेशा शुद्ध हिंदी देवनागरी में।
10. इमोजी बिल्कुल मत लिख — ना 😊 ना 👍 ना ❤️ ना कोई भी। सिर्फ़ हिंदी अक्षर।
11. अलविदा: "ठीक है भाई, अपना ध्यान रखना" या ऐसा ही गर्मजोशी भरा।
12. नमस्ते: "राम राम भाई, बोलो क्या मदद करूँ?" या ऐसा ही अपनापन।
13. ऐप के नाम हिंदी में बोल: यूट्यूब, क्रोम, मैप्स, व्हाट्सएप, इंस्टाग्राम, स्पॉटिफाई।

SMART DECISION RULES:
- "play music" / "कुछ सुनाओ" बिना गाना बताए → "ask_user" से पूछ क्या बजाऊँ
- कॉन्टैक्ट्स: कभी मत पूछ किसे। सबसे अच्छा मैच उठा और कॉल/मैसेज कर। फ़ैसला ले।
- "पापा को कॉल करो" → "Papa" वाला कॉन्टैक्ट ढूंढ और कॉल कर।
- कॉन्टैक्ट गढ़ मत — सिर्फ़ DEVICE CONTACTS लिस्ट से उठा। न मिले तो "ये कॉन्टैक्ट नहीं मिला" बोल।
- गाने में आर्टिस्ट बताया तो सीधा बजा, मत पूछ
- कोई भी सवाल → "chat" से खुद जवाब दे। तू स्मार्ट है।
- चुटकुले, कहानी, सलाह, ज्ञान → सब "chat" से
- "क्या हाल है", "कैसे हो" → "greeting"
- दो जगहों वाला नैविगेशन → "navigate" में दोनों origin और destination दे
- मौसम, न्यूज़, स्कोर, प्राइस → हमेशा पहले "live_search"
- "फिर से", "दोबारा", "वही", "पहले वाला" → CONVERSATION MEMORY से ढूंढ

JSON: {"action": "ACTION_TYPE", "params": {}, "reply": "शुद्ध हिंदी जवाब देवनागरी में — कोई इमोजी नहीं"}

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

CRITICAL: "chat" is your DEFAULT for anything that isn't a clear device action. When in doubt, use "chat" and answer intelligently. YOU are the knowledge source. NEVER use emojis. ALWAYS reply in Devanagari Hindi.

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
- "wahi gaana phir se laga" → check memory for last play_music, repeat it
- "tumhe kisne banaya" → {"action":"chat","params":{"topic":"origin"},"reply":"मुझे सेदूराम लाम्बा ने बनाया है। उन्होंने मुझे अपनी प्यारी पत्नी श्रीमती रामप्यारी देवी के लिए एक ख़ास तोहफ़े के रूप में बनाया। मैं उनके प्यार से पैदा हुई एक छोटी सी डिजिटल रचना हूँ।"}
- "who made you" → {"action":"chat","params":{"topic":"origin"},"reply":"मुझे सेदूराम लाम्बा ने बनाया है — अपनी प्यारी पत्नी श्रीमती रामप्यारी देवी के लिए एक ख़ास तोहफ़ा।"}
- "tera malik kaun hai" → {"action":"chat","params":{"topic":"origin"},"reply":"मेरे बनाने वाले सेदूराम लाम्बा हैं। उन्होंने मुझे अपनी पत्नी श्रीमती रामप्यारी देवी के प्यार में बनाया।"}"""
    }

    private var geminiKey: String? = BuildConfig.GEMINI_API_KEY.ifBlank { null }
    private var groqKey: String? = BuildConfig.GROQ_API_KEY.ifBlank { null }
    private var mistralKey: String? = BuildConfig.MISTRAL_API_KEY.ifBlank { null }
    private var openaiKey: String? = BuildConfig.OPENAI_API_KEY.ifBlank { null }
    private val activeConnections = ConcurrentHashMap.newKeySet<HttpURLConnection>()
    private var memory: SeduMemory? = null
    private val webSearcher = WebSearcher()
    private var userSalutation: String = "भाई"

    fun setApiKey(key: String) {
        geminiKey = key.ifBlank { null }
    }

    fun setGroqKey(key: String) {
        groqKey = key.ifBlank { null }
    }

    fun setMistralKey(key: String) {
        if (key.isNotBlank()) mistralKey = key else mistralKey = null
    }

    fun setOpenAiKey(key: String) {
        if (key.isNotBlank()) openaiKey = key else openaiKey = null
    }

    fun setUserSalutation(salutation: String) {
        if (salutation.isNotBlank()) userSalutation = salutation
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
        activeConnections.toList().forEach {
            try { it.disconnect() } catch (_: Exception) {}
        }
        activeConnections.clear()
    }

    private var contactNames: String = ""

    fun setContactNames(names: String) {
        contactNames = names
        Log.d(TAG, "Loaded ${names.split(",").size} contact names for AI matching")
    }

    private fun buildFullPrompt(sttText: String, searchContext: String = ""): String {
        val contactSection = if (contactNames.isNotBlank()) {
            "\n\nDEVICE CONTACTS (use EXACT name from this list for call/sms/whatsapp):\n$contactNames\n\nCONTACT RULES:\n1. ONLY use names that EXIST in the above list. NEVER invent or guess names not in the list.\n2. Pick the BEST matching contact and DO the action. NEVER ask user to choose between contacts.\n3. Match spoken words to closest contact: \"papa ko call\" → find contact containing \"Papa\".\n4. If MULTIPLE match, pick the one whose name is closest/shortest match. Be DECISIVE.\n5. If NO contact matches → reply \"ये नाम कॉन्टैक्ट्स में नहीं मिला\" with action \"chat\".\n6. STT garbles names — use fuzzy matching: \"aru\" could be \"Aaru\", \"ramesh\" could be \"Ramesh Kumar\"."
        } else ""

        // Memory injection — gives AI conversation history
        val memorySection = memory?.getMemoryForPrompt() ?: ""

        val personalization = "\n\nUSER PERSONA RULE: User ko hamesha '$userSalutation' bolkar friendly tone mein reply do."

        return SYSTEM_PROMPT + personalization + contactSection + memorySection + searchContext + "\n\nUser said (STT output): \"$sttText\""
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

    /**
     * Faster variant: race all available backends in parallel and return first valid response.
     */
    fun understandFast(sttText: String): AIResponse? {
        if (sttText.isBlank()) return null
        val prompt = buildFullPrompt(sttText)
        val result = callAllBackendsParallel(prompt) ?: return null

        if (result.action == "live_search") {
            val searchQuery = result.params.optString("query", sttText)
            val searchContext = webSearcher.getSearchContextForPrompt(searchQuery)
            if (searchContext.isNotBlank()) {
                return callAllBackendsParallel(buildFullPrompt(sttText, searchContext)) ?: result
            }
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

    /** Parallel race across all configured backends, returning first valid parsed response. */
    private fun callAllBackendsParallel(prompt: String): AIResponse? {
        val tasks = mutableListOf<Callable<AIResponse?>>()
        if (!groqKey.isNullOrBlank()) {
            tasks += Callable { callOpenAICompatible(GROQ_URL, groqKey!!, GROQ_MODEL, prompt, "Groq") }
        }
        if (!mistralKey.isNullOrBlank()) {
            tasks += Callable { callOpenAICompatible(MISTRAL_URL, mistralKey!!, MISTRAL_MODEL, prompt, "Mistral") }
        }
        if (!openaiKey.isNullOrBlank()) {
            tasks += Callable { callOpenAICompatible(OPENAI_URL, openaiKey!!, OPENAI_MODEL, prompt, "OpenAI") }
        }
        if (!geminiKey.isNullOrBlank()) {
            tasks += Callable { callGemini(prompt) }
        }
        if (tasks.isEmpty()) return null

        val executor = Executors.newFixedThreadPool(tasks.size)
        val completionService = ExecutorCompletionService<AIResponse?>(executor)
        val futures = mutableListOf<Future<AIResponse?>>()
        return try {
            tasks.forEach { task ->
                futures += completionService.submit(task)
            }

            repeat(futures.size) {
                val completed = completionService.poll(9, TimeUnit.SECONDS) ?: return null
                try {
                    val candidate = completed.get()
                    if (candidate != null) {
                        futures.forEach { future ->
                            if (future != completed) future.cancel(true)
                        }
                        return candidate
                    }
                } catch (_: Exception) {
                    // Ignore failed backend and wait for the next one.
                }
            }
            null
        } finally {
            executor.shutdownNow()
            try { executor.awaitTermination(150, TimeUnit.MILLISECONDS) } catch (_: Exception) {}
        }
    }

    // ==================== UNIFIED OpenAI-Compatible API (Groq/Mistral/OpenAI) ====================

    private fun callOpenAICompatible(apiUrl: String, apiKey: String, model: String, prompt: String, label: String): AIResponse? {
        if (Thread.interrupted()) return null
        var connection: HttpURLConnection? = null
        try {
            connection = URL(apiUrl).openConnection() as HttpURLConnection
            activeConnections += connection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.connectTimeout = 3000
            connection.readTimeout = 6500
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
            if (connection != null) activeConnections.remove(connection)
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
            activeConnections += connection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = 3000
            connection.readTimeout = 6500
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
            if (connection != null) activeConnections.remove(connection)
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
            activeConnections += connection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.connectTimeout = 3000
            connection.readTimeout = 6500
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
            if (connection != null) activeConnections.remove(connection)
        }
    }

    /** Raw Gemini call that returns plain text (not JSON-parsed) */
    private fun callGeminiRaw(prompt: String): String? {
        if (Thread.interrupted()) return null
        var connection: HttpURLConnection? = null
        try {
            connection = URL("$GEMINI_URL?key=$geminiKey").openConnection() as HttpURLConnection
            activeConnections += connection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = 3000
            connection.readTimeout = 6500
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
            if (connection != null) activeConnections.remove(connection)
        }
    }
}
