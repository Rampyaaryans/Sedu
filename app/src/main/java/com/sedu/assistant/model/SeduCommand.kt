package com.sedu.assistant.model

sealed class SeduCommand {
    // Communication
    data class CallContact(val contact: String) : SeduCommand()
    data class SendSms(val contact: String, val message: String) : SeduCommand()
    data class SendWhatsApp(val contact: String, val message: String) : SeduCommand()

    // Apps
    data class OpenApp(val appName: String) : SeduCommand()
    data class OpenSettings(val setting: String) : SeduCommand()

    // Info
    object GetTime : SeduCommand()
    object GetDate : SeduCommand()
    object GetBattery : SeduCommand()

    // Volume & Sound
    object VolumeUp : SeduCommand()
    object VolumeDown : SeduCommand()
    object Mute : SeduCommand()

    // Hardware toggles
    object TorchOn : SeduCommand()
    object TorchOff : SeduCommand()
    object WifiOn : SeduCommand()
    object WifiOff : SeduCommand()
    object BluetoothOn : SeduCommand()
    object BluetoothOff : SeduCommand()
    object BrightnessUp : SeduCommand()
    object BrightnessDown : SeduCommand()

    // Media & Navigation
    data class PlayMusic(val query: String) : SeduCommand()
    data class Navigate(val destination: String) : SeduCommand()
    object TakePhoto : SeduCommand()
    data class SearchWeb(val query: String) : SeduCommand()

    // Timers & Alarms
    data class SetAlarm(val hour: Int, val minute: Int) : SeduCommand()
    data class SetTimer(val minutes: Int) : SeduCommand()

    // Clarification
    data class AskUser(val question: String) : SeduCommand()

    // Meta
    data class Unknown(val rawText: String) : SeduCommand()
    object TakeScreenshot : SeduCommand()
    object ReadNotifications : SeduCommand()
    object ReadScreen : SeduCommand()
    object Goodbye : SeduCommand()
}
