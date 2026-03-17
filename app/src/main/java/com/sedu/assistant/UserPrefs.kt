package com.sedu.assistant

import android.content.Context

object UserPrefs {
    const val PREFS_NAME = "sedu_prefs"

    const val KEY_SETUP_DONE = "setup_done"
    const val KEY_USER_STOPPED = "user_stopped"
    const val KEY_USER_GENDER = "user_gender"

    const val KEY_TTS_VOICE_STYLE = "tts_voice_style"
    const val KEY_TTS_PITCH_MODE = "tts_pitch_mode"

    const val KEY_GROQ_API_KEY = "groq_api_key"
    const val KEY_MISTRAL_API_KEY = "mistral_api_key"
    const val KEY_OPENAI_API_KEY = "openai_api_key"
    const val KEY_GEMINI_API_KEY = "gemini_api_key"

    const val GENDER_MALE = "male"
    const val GENDER_FEMALE = "female"

    const val VOICE_STYLE_MALE = "male"
    const val VOICE_STYLE_FEMALE = "female"

    const val PITCH_LOW = "low"
    const val PITCH_MEDIUM = "medium"
    const val PITCH_HIGH = "high"

    fun salutationByGender(context: Context): String {
        return when (context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_USER_GENDER, GENDER_MALE)) {
            GENDER_FEMALE -> "बहन"
            else -> "भाई"
        }
    }
}
