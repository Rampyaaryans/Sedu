package com.sedu.assistant.search

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Live web search for Sedu — fetches real-time data from the internet.
 * Uses DuckDuckGo Instant Answer API (free, no key needed) + Google search snippet extraction.
 * Results are fed back into AI for accurate, up-to-date answers.
 */
class WebSearcher {

    companion object {
        private const val TAG = "WebSearcher"
        private const val DDG_API = "https://api.duckduckgo.com/"
        private const val CONNECT_TIMEOUT = 4000
        private const val READ_TIMEOUT = 6000
    }

    data class SearchResult(
        val snippets: List<String>,
        val source: String
    )

    /**
     * Search the web and return relevant snippets for AI context.
     * Tries DuckDuckGo API first, then falls back to Google snippet extraction.
     */
    fun search(query: String): SearchResult? {
        if (query.isBlank()) return null

        // 1. Try DuckDuckGo Instant Answer API (structured, reliable)
        val ddgResult = searchDuckDuckGo(query)
        if (ddgResult != null && ddgResult.snippets.isNotEmpty()) {
            return ddgResult
        }

        // 2. Try Google search snippet extraction
        val googleResult = searchGoogleSnippets(query)
        if (googleResult != null && googleResult.snippets.isNotEmpty()) {
            return googleResult
        }

        return null
    }

    /**
     * Build a search context string for injection into AI prompt.
     * Formats search results so AI can use them to answer accurately.
     */
    fun getSearchContextForPrompt(query: String): String {
        val result = search(query) ?: return ""

        val sb = StringBuilder()
        sb.appendLine("\n\nLIVE INTERNET SEARCH RESULTS for \"$query\":")
        sb.appendLine("Source: ${result.source}")
        result.snippets.forEachIndexed { i, snippet ->
            sb.appendLine("${i + 1}. $snippet")
        }
        sb.appendLine("\nINSTRUCTION: Use ONLY the above search results to answer. Be accurate. Cite facts from the results. If results are insufficient, say so honestly.")
        return sb.toString()
    }

    private fun searchDuckDuckGo(query: String): SearchResult? {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = URL("$DDG_API?q=$encodedQuery&format=json&no_html=1&skip_disambig=1")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT
            connection.readTimeout = READ_TIMEOUT
            connection.setRequestProperty("User-Agent", "SeduAssistant/1.6")

            if (connection.responseCode != 200) {
                connection.disconnect()
                return null
            }

            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val response = reader.readText()
            reader.close()
            connection.disconnect()

            val json = JSONObject(response)
            val snippets = mutableListOf<String>()

            // Abstract (main answer)
            val abstract = json.optString("Abstract", "")
            if (abstract.isNotBlank()) {
                snippets.add(abstract)
            }

            // Answer (instant answer)
            val answer = json.optString("Answer", "")
            if (answer.isNotBlank()) {
                snippets.add(answer)
            }

            // Related topics
            val relatedTopics = json.optJSONArray("RelatedTopics")
            if (relatedTopics != null) {
                for (i in 0 until minOf(relatedTopics.length(), 5)) {
                    val topic = relatedTopics.optJSONObject(i) ?: continue
                    val text = topic.optString("Text", "")
                    if (text.isNotBlank()) {
                        snippets.add(text.take(300))
                    }
                }
            }

            // Infobox
            val infobox = json.optJSONObject("Infobox")
            if (infobox != null) {
                val content = infobox.optJSONArray("content")
                if (content != null) {
                    for (i in 0 until minOf(content.length(), 5)) {
                        val item = content.optJSONObject(i) ?: continue
                        val label = item.optString("label", "")
                        val value = item.optString("value", "")
                        if (label.isNotBlank() && value.isNotBlank()) {
                            snippets.add("$label: $value")
                        }
                    }
                }
            }

            if (snippets.isEmpty()) return null
            Log.d(TAG, "DuckDuckGo returned ${snippets.size} snippets for: $query")
            return SearchResult(snippets, "DuckDuckGo")

        } catch (e: Exception) {
            Log.e(TAG, "DuckDuckGo search failed: ${e.message}")
            return null
        }
    }

    private fun searchGoogleSnippets(query: String): SearchResult? {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = URL("https://www.google.com/search?q=$encodedQuery&hl=en&num=5")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT
            connection.readTimeout = READ_TIMEOUT
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")

            if (connection.responseCode != 200) {
                connection.disconnect()
                return null
            }

            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val html = reader.readText()
            reader.close()
            connection.disconnect()

            val snippets = extractGoogleSnippets(html)
            if (snippets.isEmpty()) return null

            Log.d(TAG, "Google returned ${snippets.size} snippets for: $query")
            return SearchResult(snippets, "Google Search")

        } catch (e: Exception) {
            Log.e(TAG, "Google search failed: ${e.message}")
            return null
        }
    }

    /** Extract text snippets from Google search HTML — simple regex-based approach */
    private fun extractGoogleSnippets(html: String): List<String> {
        val snippets = mutableListOf<String>()

        // Extract text from common snippet divs
        // Google uses various div classes for snippets
        val patterns = listOf(
            """<div class="BNeawe s3v9rd AP7Wnd"[^>]*>(.*?)</div>""",
            """<div class="BNeawe iBp4i AP7Wnd"[^>]*>(.*?)</div>""",
            """<span class="hgKElc">(.*?)</span>""",
            """<div data-sncf="1"[^>]*>(.*?)</div>"""
        )

        for (pattern in patterns) {
            val regex = Regex(pattern, RegexOption.DOT_MATCHES_ALL)
            val matches = regex.findAll(html)
            for (match in matches) {
                val text = match.groupValues[1]
                    .replace(Regex("<[^>]+>"), "") // Strip HTML tags
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                    .replace("&#39;", "'")
                    .replace("&nbsp;", " ")
                    .trim()
                if (text.length > 30 && !snippets.contains(text)) {
                    snippets.add(text.take(400))
                }
                if (snippets.size >= 6) break
            }
            if (snippets.size >= 6) break
        }

        return snippets.take(6)
    }
}
