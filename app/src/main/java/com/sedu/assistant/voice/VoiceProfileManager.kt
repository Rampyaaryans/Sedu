package com.sedu.assistant.voice

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.sedu.assistant.model.VoiceProfile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class VoiceProfileManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "sedu_voice_profiles"
        private const val KEY_PROFILES = "profiles"
        private const val KEY_ACTIVE_PROFILE = "active_profile_id"
    }

    private val gson = Gson()
    private val profilesDir = File(context.filesDir, "voice_profiles")

    init {
        profilesDir.mkdirs()
    }

    fun createProfile(name: String): VoiceProfile {
        val id = UUID.randomUUID().toString().substring(0, 8)
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        val profile = VoiceProfile(id = id, name = name, createdDate = date)

        // Create profile directory
        File(profilesDir, id).mkdirs()

        // Save to preferences
        val profiles = getAllProfiles().toMutableList()
        profiles.add(profile)
        saveProfiles(profiles)

        return profile
    }

    fun saveSample(profileId: String, sampleIndex: Int, audioData: ShortArray) {
        val dir = File(profilesDir, profileId)
        dir.mkdirs()

        val file = File(dir, "sample_$sampleIndex.raw")
        val bytes = ByteArray(audioData.size * 2)
        for (i in audioData.indices) {
            bytes[i * 2] = (audioData[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (audioData[i].toInt() shr 8 and 0xFF).toByte()
        }
        file.writeBytes(bytes)
    }

    fun finalizeProfile(profile: VoiceProfile) {
        val profiles = getAllProfiles().toMutableList()
        val index = profiles.indexOfFirst { it.id == profile.id }
        if (index >= 0) {
            profiles[index] = profile.copy(isFinalized = true, samplesRecorded = 5)
            saveProfiles(profiles)
        }
    }

    fun getAllProfiles(): List<VoiceProfile> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_PROFILES, "[]") ?: "[]"
        val type = object : TypeToken<List<VoiceProfile>>() {}.type
        return gson.fromJson(json, type)
    }

    fun getActiveProfile(): VoiceProfile? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val activeId = prefs.getString(KEY_ACTIVE_PROFILE, null) ?: return null
        return getAllProfiles().find { it.id == activeId }
    }

    fun setActiveProfile(profileId: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_PROFILE, profileId)
            .apply()
    }

    fun deleteProfile(profileId: String) {
        // Delete audio samples
        val dir = File(profilesDir, profileId)
        dir.deleteRecursively()

        // Remove from saved profiles
        val profiles = getAllProfiles().toMutableList()
        profiles.removeAll { it.id == profileId }
        saveProfiles(profiles)

        // Clear active if it was the deleted one
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_ACTIVE_PROFILE, null) == profileId) {
            prefs.edit().remove(KEY_ACTIVE_PROFILE).apply()
        }
    }

    private fun saveProfiles(profiles: List<VoiceProfile>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROFILES, gson.toJson(profiles))
            .apply()
    }
}
