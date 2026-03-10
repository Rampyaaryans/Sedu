package com.sedu.assistant.memory

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * RAG-like conversation memory for Sedu.
 * Stores conversation turns in JSON file for persistent memory across sessions.
 * Provides recent context (last N turns) and all-time summary for AI prompts.
 */
class SeduMemory(private val context: Context) {

    companion object {
        private const val TAG = "SeduMemory"
        private const val MEMORY_FILE = "sedu_memory.json"
        private const val MAX_CONVERSATIONS = 100 // Keep last 100 turns
        private const val RECENT_COUNT = 8 // Inject last 8 turns into prompt
        private const val SUMMARY_COUNT = 30 // Summarize from last 30 turns
    }

    private val memoryFile: File = File(context.filesDir, MEMORY_FILE)
    private val conversations: MutableList<ConversationTurn> = mutableListOf()

    init {
        loadFromDisk()
    }

    data class ConversationTurn(
        val timestamp: Long,
        val userText: String,
        val aiAction: String,
        val aiReply: String
    )

    /** Save a conversation turn after AI responds */
    fun saveConversation(userText: String, aiAction: String, aiReply: String) {
        if (userText.isBlank()) return
        val turn = ConversationTurn(
            timestamp = System.currentTimeMillis(),
            userText = userText.take(500),
            aiAction = aiAction.take(50),
            aiReply = aiReply.take(500)
        )
        conversations.add(turn)

        // Trim to max size
        while (conversations.size > MAX_CONVERSATIONS) {
            conversations.removeAt(0)
        }

        saveToDisk()
        Log.d(TAG, "Saved conversation: user='${userText.take(40)}' action=$aiAction")
    }

    /** Get recent N conversation turns for immediate context */
    fun getRecentTurns(n: Int = RECENT_COUNT): List<ConversationTurn> {
        return conversations.takeLast(n)
    }

    /** Build memory context string for injection into AI prompt */
    fun getMemoryForPrompt(): String {
        if (conversations.isEmpty()) return ""

        val sb = StringBuilder()
        sb.appendLine("\n\nCONVERSATION MEMORY (previous interactions with this user):")

        // Recent turns — full detail
        val recent = conversations.takeLast(RECENT_COUNT)
        if (recent.isNotEmpty()) {
            sb.appendLine("RECENT CONVERSATIONS (latest ${recent.size}):")
            recent.forEach { turn ->
                val timeAgo = getTimeAgo(turn.timestamp)
                sb.appendLine("- [$timeAgo] User: \"${turn.userText}\" → You did: ${turn.aiAction}, said: \"${turn.aiReply}\"")
            }
        }

        // Older turns — summarized topics
        val older = if (conversations.size > RECENT_COUNT) {
            conversations.dropLast(RECENT_COUNT).takeLast(SUMMARY_COUNT - RECENT_COUNT)
        } else emptyList()

        if (older.isNotEmpty()) {
            sb.appendLine("OLDER TOPICS (summary): ")
            val topics = older.map { "${it.aiAction}(${it.userText.take(30)})" }
                .distinct().take(15)
            sb.appendLine(topics.joinToString(", "))
        }

        sb.appendLine("\nMEMORY RULES:")
        sb.appendLine("- Remember what user asked before. If they refer to 'woh', 'pehle wala', 'phir se' — check memory.")
        sb.appendLine("- User ke preferences yaad rakho (e.g., favourite songs, contacts they call often).")
        sb.appendLine("- If user says 'phir se karo' or 'wahi' — repeat the last relevant action.")

        return sb.toString()
    }

    /** Get frequently used actions/contacts for smarter defaults */
    fun getFrequentActions(): Map<String, Int> {
        return conversations.groupBy { it.aiAction }
            .mapValues { it.value.size }
            .toList()
            .sortedByDescending { it.second }
            .take(10)
            .toMap()
    }

    private fun getTimeAgo(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        val minutes = diff / 60_000
        val hours = minutes / 60
        val days = hours / 24
        return when {
            minutes < 2 -> "abhi"
            minutes < 60 -> "${minutes}m pehle"
            hours < 24 -> "${hours}h pehle"
            days < 7 -> "${days}d pehle"
            else -> "${days / 7}w pehle"
        }
    }

    private fun loadFromDisk() {
        try {
            if (!memoryFile.exists()) return
            val json = memoryFile.readText()
            if (json.isBlank()) return
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                conversations.add(ConversationTurn(
                    timestamp = obj.optLong("ts", 0),
                    userText = obj.optString("user", ""),
                    aiAction = obj.optString("action", ""),
                    aiReply = obj.optString("reply", "")
                ))
            }
            Log.d(TAG, "Loaded ${conversations.size} conversations from disk")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load memory: ${e.message}")
        }
    }

    private fun saveToDisk() {
        try {
            val array = JSONArray()
            for (turn in conversations) {
                array.put(JSONObject().apply {
                    put("ts", turn.timestamp)
                    put("user", turn.userText)
                    put("action", turn.aiAction)
                    put("reply", turn.aiReply)
                })
            }
            memoryFile.writeText(array.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save memory: ${e.message}")
        }
    }

    /** Get all conversation turns for history display */
    fun getAllConversations(): List<ConversationTurn> {
        return conversations.toList()
    }

    /** Clear all memory */
    fun clear() {
        conversations.clear()
        try { memoryFile.delete() } catch (_: Exception) {}
        Log.d(TAG, "Memory cleared")
    }
}
