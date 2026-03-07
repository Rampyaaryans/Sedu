package com.sedu.assistant.engine

import android.content.Context
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.util.Log

/**
 * Scans device contacts, call log, and SMS to build a full knowledge base.
 * Provides ranked contact list (most-contacted first) for Gemini and speech bias.
 */
class ContactsHelper(private val context: Context) {

    companion object {
        private const val TAG = "ContactsHelper"
    }

    data class Contact(val name: String, val number: String, var score: Int = 0)

    private var cachedContacts: List<Contact> = emptyList()
    private var cachedPrompt: String = ""
    private var lastLoadTime = 0L
    private val CACHE_DURATION_MS = 5 * 60 * 1000L // 5 minutes

    /**
     * Load all contacts, ranked by usage frequency from call log + SMS.
     */
    fun loadContacts(): List<Contact> {
        val now = System.currentTimeMillis()
        if (cachedContacts.isNotEmpty() && now - lastLoadTime < CACHE_DURATION_MS) {
            return cachedContacts
        }

        val contactMap = mutableMapOf<String, Contact>() // key = lowercase name

        // 1. Load all contacts with phone numbers
        try {
            val resolver = context.contentResolver
            val cursor = resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ), null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )
            cursor?.use {
                while (it.moveToNext()) {
                    val name = it.getString(0)?.trim() ?: continue
                    val number = it.getString(1) ?: continue
                    val key = name.lowercase()
                    if (key !in contactMap && name.length >= 2) {
                        contactMap[key] = Contact(name, number.replace(Regex("[\\s\\-()]"), ""))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading contacts", e)
        }

        // 2. Scan call log — boost frequently called contacts
        try {
            val resolver = context.contentResolver
            val cursor = resolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER),
                null, null,
                CallLog.Calls.DATE + " DESC"
            )
            cursor?.use {
                val maxRows = 500 // Last 500 calls
                var count = 0
                while (it.moveToNext() && count < maxRows) {
                    val name = it.getString(0)?.trim()
                    if (name != null && name.length >= 2) {
                        val key = name.lowercase()
                        contactMap[key]?.let { c -> c.score += 3 } // Calls are high-value
                    }
                    count++
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning call log", e)
        }

        // 3. Scan SMS — boost frequently messaged contacts
        try {
            val resolver = context.contentResolver
            val cursor = resolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS),
                null, null,
                Telephony.Sms.DATE + " DESC"
            )
            cursor?.use {
                val maxRows = 300
                var count = 0
                val numberToName = contactMap.values.associateBy {
                    it.number.takeLast(10)
                }
                while (it.moveToNext() && count < maxRows) {
                    val address = it.getString(0)?.replace(Regex("[\\s\\-()]"), "") ?: continue
                    val last10 = address.takeLast(10)
                    numberToName[last10]?.let { c -> c.score += 1 }
                    count++
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning SMS", e)
        }

        // Sort by score (most contacted first), then alphabetical
        val sorted = contactMap.values.sortedWith(compareByDescending<Contact> { it.score }.thenBy { it.name })
        cachedContacts = sorted
        lastLoadTime = now
        Log.d(TAG, "Loaded ${sorted.size} contacts (top: ${sorted.take(5).map { "${it.name}(${it.score})" }})")
        return sorted
    }

    /**
     * Get contact names for Gemini prompt — top 300 contacts, most-used first.
     * Includes usage indicators for frequently contacted people.
     */
    fun getContactNamesForPrompt(): String {
        if (cachedPrompt.isNotBlank() && System.currentTimeMillis() - lastLoadTime < CACHE_DURATION_MS) {
            return cachedPrompt
        }
        val contacts = loadContacts()
        if (contacts.isEmpty()) return ""

        val sb = StringBuilder()
        contacts.take(300).forEach { c ->
            sb.append(c.name)
            if (c.score >= 5) sb.append(" [frequent]")
            sb.append(", ")
        }
        cachedPrompt = sb.toString().trimEnd(',', ' ')
        return cachedPrompt
    }

    /**
     * Get contact names as list for speech recognizer bias — top 200.
     */
    fun getContactNamesList(): List<String> {
        return loadContacts().take(200).map { it.name }
    }

    fun refresh() {
        lastLoadTime = 0
        cachedPrompt = ""
        loadContacts()
    }
}
