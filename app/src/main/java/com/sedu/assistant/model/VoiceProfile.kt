package com.sedu.assistant.model

data class VoiceProfile(
    val id: String,
    val name: String,
    val createdDate: String,
    val samplesRecorded: Int = 0,
    val isFinalized: Boolean = false
)
