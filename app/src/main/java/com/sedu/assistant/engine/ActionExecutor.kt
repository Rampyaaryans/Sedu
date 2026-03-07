package com.sedu.assistant.engine

import android.bluetooth.BluetoothAdapter
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.Log
import com.sedu.assistant.ai.GeminiBrain
import com.sedu.assistant.model.SeduCommand
import com.sedu.assistant.service.SeduAccessibilityService
import com.sedu.assistant.tts.SeduTTS
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ActionExecutor(private val context: Context, private val geminiBrain: GeminiBrain? = null) {

    companion object {
        private const val TAG = "ActionExecutor"
    }

    fun execute(command: SeduCommand, tts: SeduTTS, onComplete: () -> Unit) {
        execute(command, tts, null, onComplete)
    }

    fun execute(command: SeduCommand, tts: SeduTTS, aiReply: String?, onComplete: () -> Unit) {
        try {
            when (command) {
                is SeduCommand.CallContact -> makeCall(command.contact, tts, aiReply, onComplete)
                is SeduCommand.SendSms -> sendSms(command.contact, command.message, tts, aiReply, onComplete)
                is SeduCommand.SendWhatsApp -> sendWhatsApp(command.contact, command.message, tts, aiReply, onComplete)
                is SeduCommand.OpenApp -> openApp(command.appName, tts, aiReply, onComplete)
                is SeduCommand.OpenSettings -> openSettings(command.setting, tts, aiReply, onComplete)
                is SeduCommand.GetTime -> tellTime(tts, onComplete)
                is SeduCommand.GetDate -> tellDate(tts, onComplete)
                is SeduCommand.GetBattery -> tellBattery(tts, onComplete)
                is SeduCommand.VolumeUp -> volumeUp(tts, onComplete)
                is SeduCommand.VolumeDown -> volumeDown(tts, onComplete)
                is SeduCommand.Mute -> mutePhone(tts, onComplete)
                is SeduCommand.TorchOn -> torchOn(tts, onComplete)
                is SeduCommand.TorchOff -> torchOff(tts, onComplete)
                is SeduCommand.WifiOn -> wifiToggle(true, tts, onComplete)
                is SeduCommand.WifiOff -> wifiToggle(false, tts, onComplete)
                is SeduCommand.BluetoothOn -> bluetoothToggle(true, tts, onComplete)
                is SeduCommand.BluetoothOff -> bluetoothToggle(false, tts, onComplete)
                is SeduCommand.BrightnessUp -> adjustBrightness(true, tts, onComplete)
                is SeduCommand.BrightnessDown -> adjustBrightness(false, tts, onComplete)
                is SeduCommand.PlayMusic -> playMusic(command.query, tts, aiReply, onComplete)
                is SeduCommand.Navigate -> navigate(command.destination, tts, aiReply, onComplete)
                is SeduCommand.TakePhoto -> takePhoto(tts, onComplete)
                is SeduCommand.SearchWeb -> searchWeb(command.query, tts, aiReply, onComplete)
                is SeduCommand.SetAlarm -> setAlarm(command.hour, command.minute, tts, aiReply, onComplete)
                is SeduCommand.SetTimer -> setTimer(command.minutes, tts, aiReply, onComplete)
                is SeduCommand.AskUser -> {
                    // AskUser is handled by SeduService directly, shouldn't reach here
                    tts.speak(aiReply ?: "Kya chahiye?") { onComplete() }
                }
                is SeduCommand.TakeScreenshot -> {
                    if (SeduAccessibilityService.isRunning) {
                        tts.speak("Screenshot le raha hoon") {
                            SeduAccessibilityService.performGlobalScreenshot()
                            onComplete()
                        }
                    } else {
                        tts.speak("Screenshot ke liye accessibility service on karo settings mein.") { onComplete() }
                    }
                }
                is SeduCommand.ReadNotifications -> {
                    if (SeduAccessibilityService.isRunning) {
                        tts.speak("Notifications khol raha hoon") {
                            SeduAccessibilityService.performGlobalNotifications()
                            onComplete()
                        }
                    } else {
                        tts.speak("Notification ke liye accessibility on karo.") { onComplete() }
                    }
                }
                is SeduCommand.ReadScreen -> readScreen(tts, aiReply, onComplete)
                is SeduCommand.Goodbye -> {
                    tts.speak(aiReply ?: "Chal bye! Jab chahein bulana.") { onComplete() }
                }
                is SeduCommand.Unknown -> {
                    tts.speak(aiReply ?: "Boliye, main sun raha hoon") { onComplete() }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing command", e)
            tts.speak("Error aa gaya, sorry.") { onComplete() }
        }
    }

    // ==================== COMMUNICATION ====================

    private fun makeCall(contact: String, tts: SeduTTS, aiReply: String?, onComplete: () -> Unit) {
        if (contact.isBlank()) {
            tts.speak(aiReply ?: "Kisko call karun? Naam boliye.") { onComplete() }
            return
        }
        Log.d(TAG, "makeCall: resolving contact='$contact'")
        val phoneNumber = resolveContact(contact)
        Log.d(TAG, "makeCall: resolved number='$phoneNumber'")
        if (phoneNumber != null) {
            tts.speak(aiReply ?: "$contact ko call kar raha hoon") {
                try {
                    val uri = Uri.fromParts("tel", phoneNumber, null)
                    Log.d(TAG, "makeCall: placing call to $uri")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                        val extras = Bundle()
                        telecomManager.placeCall(uri, extras)
                        Log.d(TAG, "makeCall: TelecomManager.placeCall() done")
                    } else {
                        val intent = Intent(Intent.ACTION_CALL, uri).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                        Log.d(TAG, "makeCall: startActivity(ACTION_CALL) done")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "makeCall: FAILED", e)
                    // Fallback to ACTION_DIAL (opens dialer, user taps call)
                    try {
                        val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phoneNumber")).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(dialIntent)
                        Log.d(TAG, "makeCall: fallback ACTION_DIAL done")
                    } catch (e2: Exception) { Log.e(TAG, "makeCall: even DIAL failed", e2) }
                }
                onComplete()
            }
        } else {
            Log.w(TAG, "makeCall: contact '$contact' NOT FOUND in contacts")
            tts.speak("$contact contacts mein nahi mila, dialer khol raha hoon") {
                try {
                    val intent = Intent(Intent.ACTION_DIAL).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                    context.startActivity(intent)
                } catch (e: Exception) { Log.e(TAG, "Dial failed", e) }
                onComplete()
            }
        }
    }

    private fun sendSms(contact: String, message: String, tts: SeduTTS, aiReply: String?, onComplete: () -> Unit) {
        if (contact.isBlank()) {
            tts.speak(aiReply ?: "Kisko SMS bhejun?") { onComplete() }
            return
        }
        val phoneNumber = resolveContact(contact)
        if (phoneNumber != null) {
            tts.speak(aiReply ?: "$contact ke liye SMS khol raha hoon") {
                try {
                    val smsUri = Uri.parse("smsto:$phoneNumber")
                    val intent = Intent(Intent.ACTION_SENDTO, smsUri).apply {
                        if (message.isNotBlank()) putExtra("sms_body", message)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                } catch (e: Exception) { Log.e(TAG, "SMS app error", e) }
                onComplete()
            }
        } else {
            tts.speak("$contact contacts mein nahi mila.") { onComplete() }
        }
    }

    private fun sendWhatsApp(contact: String, message: String, tts: SeduTTS, aiReply: String?, onComplete: () -> Unit) {
        if (contact.isBlank()) {
            tts.speak(aiReply ?: "Kisko WhatsApp karun?") { onComplete() }
            return
        }
        val phoneNumber = resolveContact(contact)
        if (phoneNumber != null) {
            tts.speak(aiReply ?: "$contact ko WhatsApp bhej raha hoon") {
                try {
                    val number = phoneNumber.replace("+", "").replace(" ", "")
                    val encodedMsg = if (message.isNotBlank()) "?text=${Uri.encode(message)}" else ""
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse("https://wa.me/$number$encodedMsg")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                } catch (e: Exception) { Log.e(TAG, "WhatsApp error", e) }
                onComplete()
            }
        } else {
            tts.speak("$contact nahi mila, WhatsApp khol raha hoon") {
                openAppByPackage("com.whatsapp")
                onComplete()
            }
        }
    }

    // ==================== APPS & SETTINGS ====================

    private fun openApp(appName: String, tts: SeduTTS, aiReply: String?, onComplete: () -> Unit) {
        if (appName.isBlank()) {
            tts.speak("Kaunsa app kholun?") { onComplete() }
            return
        }

        val knownApps = mapOf(
            "youtube" to "com.google.android.youtube",
            "instagram" to "com.instagram.android",
            "facebook" to "com.facebook.katana",
            "whatsapp" to "com.whatsapp",
            "telegram" to "org.telegram.messenger",
            "twitter" to "com.twitter.android",
            "x" to "com.twitter.android",
            "snapchat" to "com.snapchat.android",
            "chrome" to "com.android.chrome",
            "gmail" to "com.google.android.gm",
            "maps" to "com.google.android.apps.maps",
            "google maps" to "com.google.android.apps.maps",
            "camera" to "com.android.camera",
            "gallery" to "com.google.android.apps.photos",
            "photos" to "com.google.android.apps.photos",
            "settings" to "com.android.settings",
            "calculator" to "com.google.android.calculator",
            "clock" to "com.google.android.deskclock",
            "calendar" to "com.google.android.calendar",
            "spotify" to "com.spotify.music",
            "netflix" to "com.netflix.mediaclient",
            "amazon" to "in.amazon.mShop.android.shopping",
            "flipkart" to "com.flipkart.android",
            "paytm" to "net.one97.paytm",
            "gpay" to "com.google.android.apps.nbu.paisa.user",
            "google pay" to "com.google.android.apps.nbu.paisa.user",
            "phonepe" to "com.phonepe.app",
            "phone pe" to "com.phonepe.app",
            "zomato" to "com.application.zomato",
            "swiggy" to "in.swiggy.android",
            "uber" to "com.ubercab",
            "ola" to "com.olacabs.customer",
        )

        val lowerName = appName.lowercase()
        val knownPkg = knownApps[lowerName]

        if (knownPkg != null) {
            val launched = openAppByPackage(knownPkg)
            if (launched) {
                tts.speak(aiReply ?: "$appName khol raha hoon") { onComplete() }
            } else {
                tts.speak("$appName installed nahi hai") { onComplete() }
            }
            return
        }

        // Search installed apps by label
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(0)
        val app = apps.find {
            pm.getApplicationLabel(it).toString().lowercase().contains(lowerName)
        }

        if (app != null) {
            val intent = pm.getLaunchIntentForPackage(app.packageName)
            if (intent != null) {
                tts.speak(aiReply ?: "$appName khol raha hoon") {
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                    onComplete()
                }
            } else {
                tts.speak("$appName open nahi ho raha.") { onComplete() }
            }
        } else {
            tts.speak("$appName nahi mila phone mein.") { onComplete() }
        }
    }

    private fun openAppByPackage(packageName: String): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                true
            } else false
        } catch (e: Exception) {
            Log.e(TAG, "Open app error: $packageName", e)
            false
        }
    }

    private fun openSettings(setting: String, tts: SeduTTS, aiReply: String?, onComplete: () -> Unit) {
        val action = when (setting.lowercase()) {
            "wifi", "network" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display", "brightness", "screen" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound", "volume", "audio" -> Settings.ACTION_SOUND_SETTINGS
            "battery", "power" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "storage", "memory" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            "apps", "application" -> Settings.ACTION_APPLICATION_SETTINGS
            "location", "gps" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "security" -> Settings.ACTION_SECURITY_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        tts.speak(aiReply ?: "Settings khol raha hoon") {
            try {
                val intent = Intent(action).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                context.startActivity(intent)
            } catch (e: Exception) { Log.e(TAG, "Settings error", e) }
            onComplete()
        }
    }

    // ==================== INFO ====================

    private fun tellTime(tts: SeduTTS, onComplete: () -> Unit) {
        val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
        tts.speak("Abhi $time baj rahe hain") { onComplete() }
    }

    private fun tellDate(tts: SeduTTS, onComplete: () -> Unit) {
        val date = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(Date())
        tts.speak("Aaj $date hai") { onComplete() }
    }

    private fun tellBattery(tts: SeduTTS, onComplete: () -> Unit) {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        tts.speak("Battery $level percent hai") { onComplete() }
    }

    // ==================== VOLUME & SOUND ====================

    private fun volumeUp(tts: SeduTTS, onComplete: () -> Unit) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
        am.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
        tts.speak("Volume badha diya") { onComplete() }
    }

    private fun volumeDown(tts: SeduTTS, onComplete: () -> Unit) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
        am.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
        tts.speak("Volume kam kiya") { onComplete() }
    }

    private fun mutePhone(tts: SeduTTS, onComplete: () -> Unit) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.ringerMode = AudioManager.RINGER_MODE_SILENT
        tts.speak("Phone silent kar diya") { onComplete() }
    }

    // ==================== HARDWARE TOGGLES ====================

    private fun torchOn(tts: SeduTTS, onComplete: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                cm.setTorchMode(cm.cameraIdList[0], true)
                tts.speak("Torch on") { onComplete() }
            } catch (e: Exception) {
                tts.speak("Torch on nahi ho raha") { onComplete() }
            }
        } else {
            tts.speak("Torch support nahi hai") { onComplete() }
        }
    }

    private fun torchOff(tts: SeduTTS, onComplete: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                cm.setTorchMode(cm.cameraIdList[0], false)
                tts.speak("Torch off") { onComplete() }
            } catch (e: Exception) {
                tts.speak("Torch off nahi ho raha") { onComplete() }
            }
        } else {
            tts.speak("Torch support nahi hai") { onComplete() }
        }
    }

    private fun wifiToggle(enable: Boolean, tts: SeduTTS, onComplete: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tts.speak("WiFi settings khol raha hoon") {
                try {
                    val intent = Intent(Settings.Panel.ACTION_WIFI).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                } catch (e: Exception) { Log.e(TAG, "WiFi panel error", e) }
                onComplete()
            }
        } else {
            @Suppress("DEPRECATION")
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wm.isWifiEnabled = enable
            tts.speak(if (enable) "WiFi on kar diya" else "WiFi off kar diya") { onComplete() }
        }
    }

    private fun bluetoothToggle(enable: Boolean, tts: SeduTTS, onComplete: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            tts.speak("Bluetooth settings khol raha hoon") {
                try {
                    val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                } catch (e: Exception) { Log.e(TAG, "BT settings error", e) }
                onComplete()
            }
        } else {
            try {
                @Suppress("DEPRECATION")
                val ba = BluetoothAdapter.getDefaultAdapter()
                if (ba != null) {
                    @Suppress("DEPRECATION", "MissingPermission")
                    if (enable) ba.enable() else ba.disable()
                    tts.speak(if (enable) "Bluetooth on" else "Bluetooth off") { onComplete() }
                } else {
                    tts.speak("Bluetooth support nahi hai") { onComplete() }
                }
            } catch (e: Exception) {
                tts.speak("Bluetooth settings khol raha hoon") {
                    try {
                        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                    } catch (ex: Exception) { Log.e(TAG, "BT error", ex) }
                    onComplete()
                }
            }
        }
    }

    private fun adjustBrightness(increase: Boolean, tts: SeduTTS, onComplete: () -> Unit) {
        try {
            val cr = context.contentResolver
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(context)) {
                tts.speak("Brightness permission chahiye, settings khol raha hoon") {
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                            data = Uri.parse("package:${context.packageName}")
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) { Log.e(TAG, "Write settings error", e) }
                    onComplete()
                }
                return
            }
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            val current = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
            val newVal = if (increase) (current + 50).coerceAtMost(255) else (current - 50).coerceAtLeast(10)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, newVal)
            tts.speak(if (increase) "Brightness badha di" else "Brightness kam ki") { onComplete() }
        } catch (e: Exception) {
            Log.e(TAG, "Brightness error", e)
            tts.speak("Brightness change nahi ho rahi") { onComplete() }
        }
    }

    // ==================== MEDIA & NAVIGATION ====================

    private fun playMusic(query: String, tts: SeduTTS, aiReply: String?, onComplete: () -> Unit) {
        tts.speak(aiReply ?: "$query chala raha hoon") {
            var played = false
            val bestApp = findBestMusicApp()

            // 1. Use best installed music app
            if (bestApp != null && !played) {
                played = openMusicApp(bestApp, query)
            }
            // 2. Try YouTube Music specifically
            if (!played) try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://music.youtube.com/search?q=${Uri.encode(query)}")
                    `package` = "com.google.android.apps.youtube.music"
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                played = true
            } catch (_: Exception) {}
            // 3. Try YouTube app
            if (!played) try {
                val intent = Intent(Intent.ACTION_SEARCH).apply {
                    `package` = "com.google.android.youtube"
                    putExtra("query", query)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                played = true
            } catch (_: Exception) {}
            // 4. Try Chrome with YouTube search
            if (!played) try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")
                    `package` = "com.android.chrome"
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                played = true
            } catch (_: Exception) {}
            // 5. Try local audio files matching query
            if (!played) {
                played = playLocalAudio(query)
            }
            // 6. Generic browser — YouTube web (any browser)
            if (!played) try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                played = true
            } catch (_: Exception) {}
            // 7. Google search as absolute last resort
            if (!played) try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://www.google.com/search?q=${Uri.encode("$query music play")}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                played = true
            } catch (e: Exception) { Log.e(TAG, "Play error", e) }

            // Auto-click first playable after app opens
            if (played && SeduAccessibilityService.isRunning) {
                Handler(Looper.getMainLooper()).postDelayed({
                    try { SeduAccessibilityService.clickFirstPlayable() } catch (_: Exception) {}
                }, 3500)
            }
            onComplete()
        }
    }

    /** Scan device for installed music apps, return best package name */
    private fun findBestMusicApp(): String? {
        val musicApps = listOf(
            "com.google.android.apps.youtube.music",
            "com.spotify.music",
            "com.jio.media.jiobeats",
            "com.gaana",
            "com.bsbportal.music",
            "com.amazon.mp3",
            "com.apple.android.music",
            "in.startv.hotstar",
            "com.hungama.myplay.activity"
        )
        for (pkg in musicApps) {
            try {
                context.packageManager.getPackageInfo(pkg, 0)
                Log.d(TAG, "Found music app: $pkg")
                return pkg
            } catch (_: Exception) {}
        }
        return null
    }

    /** Open a specific music app with a search query */
    private fun openMusicApp(packageName: String, query: String): Boolean {
        return try {
            when (packageName) {
                "com.google.android.apps.youtube.music" -> {
                    context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse("https://music.youtube.com/search?q=${Uri.encode(query)}")
                        `package` = packageName
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                    true
                }
                "com.spotify.music" -> {
                    context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse("spotify:search:${Uri.encode(query)}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                    true
                }
                "com.jio.media.jiobeats" -> {
                    context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse("jiosaavn://search/${Uri.encode(query)}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                    true
                }
                else -> {
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
                    if (launchIntent != null) {
                        launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        context.startActivity(launchIntent)
                        true
                    } else false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open music app $packageName", e)
            false
        }
    }

    /** Search local audio files matching query and play the first match */
    private fun playLocalAudio(query: String): Boolean {
        try {
            val queryLower = query.lowercase()
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.IS_MUSIC
            )
            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
            val cursor: Cursor? = context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, selection, null,
                "${MediaStore.Audio.Media.TITLE} ASC"
            )
            cursor?.use {
                val idCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                while (it.moveToNext()) {
                    val title = it.getString(titleCol)?.lowercase() ?: ""
                    val artist = it.getString(artistCol)?.lowercase() ?: ""
                    if (title.contains(queryLower) || artist.contains(queryLower)) {
                        val id = it.getLong(idCol)
                        val uri = Uri.withAppendedPath(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString()
                        )
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "audio/*")
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                        Log.d(TAG, "Playing local audio: $title by $artist")
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Local audio search error", e)
        }
        return false
    }

    private fun navigate(destination: String, tts: SeduTTS, aiReply: String?, onComplete: () -> Unit) {
        tts.speak(aiReply ?: "$destination ka direction deta hoon") {
            try {
                val gmmUri = Uri.parse("google.navigation:q=${Uri.encode(destination)}")
                val intent = Intent(Intent.ACTION_VIEW, gmmUri).apply {
                    `package` = "com.google.android.apps.maps"
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse("https://www.google.com/maps/search/${Uri.encode(destination)}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                } catch (e2: Exception) { Log.e(TAG, "Navigate error", e2) }
            }
            onComplete()
        }
    }

    private fun takePhoto(tts: SeduTTS, onComplete: () -> Unit) {
        tts.speak("Camera khol raha hoon") {
            try {
                val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                try {
                    val intent = Intent("android.media.action.STILL_IMAGE_CAMERA").apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                } catch (e2: Exception) { Log.e(TAG, "Camera error", e2) }
            }
            onComplete()
        }
    }

    private fun searchWeb(query: String, tts: SeduTTS, aiReply: String?, onComplete: () -> Unit) {
        val reply = aiReply ?: "$query search kar raha hoon"
        tts.speak(reply) {
            // Open Google search in Chrome (preferred) or any browser
            var opened = false
            if (!opened) try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")
                    `package` = "com.android.chrome"
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                opened = true
            } catch (_: Exception) {}
            if (!opened) try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                opened = true
            } catch (e: Exception) { Log.e(TAG, "Search error", e) }
            onComplete()
        }
    }

    // ==================== TIMERS & ALARMS ====================

    private fun setAlarm(hour: Int, minute: Int, tts: SeduTTS, aiReply: String?, onComplete: () -> Unit) {
        tts.speak(aiReply ?: "$hour baj ke $minute minute pe alarm lagaya") {
            try {
                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e: Exception) { Log.e(TAG, "Alarm error", e) }
            onComplete()
        }
    }

    private fun setTimer(minutes: Int, tts: SeduTTS, aiReply: String?, onComplete: () -> Unit) {
        tts.speak(aiReply ?: "$minutes minute ka timer start") {
            try {
                val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                    putExtra(AlarmClock.EXTRA_LENGTH, minutes * 60)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e: Exception) { Log.e(TAG, "Timer error", e) }
            onComplete()
        }
    }

    // ==================== SCREEN READING ====================

    private fun readScreen(tts: SeduTTS, aiReply: String?, onComplete: () -> Unit) {
        if (!SeduAccessibilityService.isRunning) {
            tts.speak("Screen padne ke liye accessibility service on karo settings mein.") { onComplete() }
            return
        }

        val screenText = SeduAccessibilityService.getScreenText()
        if (screenText.isBlank()) {
            tts.speak("Screen pe kuch nahi dikh raha.") { onComplete() }
            return
        }

        // Use Gemini to intelligently summarize screen content
        if (geminiBrain != null) {
            Thread {
                val summary = geminiBrain.summarizeScreen(screenText)
                Handler(Looper.getMainLooper()).post {
                    tts.speak(summary ?: screenText.take(300)) { onComplete() }
                }
            }.start()
        } else {
            tts.speak(screenText.take(300)) { onComplete() }
        }
    }

    // ==================== CONTACT RESOLUTION (FUZZY) ====================

    fun resolveContact(nameOrNumber: String): String? {
        if (nameOrNumber.isBlank()) return null
        Log.d(TAG, "resolveContact: searching for '$nameOrNumber'")

        // Direct phone number
        if (nameOrNumber.matches(Regex("[+]?[0-9\\s\\-()]{7,}"))) {
            Log.d(TAG, "resolveContact: direct phone number")
            return nameOrNumber.replace(Regex("[\\s\\-()]"), "")
        }

        val searchName = nameOrNumber.lowercase().trim()

        try {
            val resolver: ContentResolver = context.contentResolver
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )

            val cursor: Cursor? = resolver.query(uri, projection, null, null, null)
            cursor?.use {
                var bestMatch: String? = null
                var bestScore = Int.MAX_VALUE
                var bestMatchName = ""

                while (it.moveToNext()) {
                    val displayName = it.getString(0)?.lowercase()?.trim() ?: continue
                    val number = it.getString(1) ?: continue
                    val cleanNumber = number.replace(Regex("[\\s\\-()]"), "")

                    // EXACT match (highest priority)
                    if (displayName == searchName) {
                        Log.d(TAG, "resolveContact: EXACT match '$displayName' → $cleanNumber")
                        return cleanNumber
                    }

                    val nameWords = displayName.split(Regex("\\s+")).filter { w -> w.length >= 2 }
                    val searchWords = searchName.split(Regex("\\s+")).filter { w -> w.length >= 2 }

                    // Multi-word fuzzy match (high priority — both have multiple words)
                    if (searchWords.size > 1 && nameWords.size > 1) {
                        var matchedWords = 0
                        var totalDist = 0
                        for (sw in searchWords) {
                            var bestWordDist = Int.MAX_VALUE
                            for (nw in nameWords) {
                                val d = levenshtein(sw, nw)
                                if (d < bestWordDist) bestWordDist = d
                            }
                            if (bestWordDist <= 2) {
                                matchedWords++
                                totalDist += bestWordDist
                            }
                        }
                        // All search words must match for multi-word
                        if (matchedWords == searchWords.size && totalDist < bestScore) {
                            bestScore = totalDist
                            bestMatch = cleanNumber
                            bestMatchName = displayName
                            Log.d(TAG, "resolveContact: multi-word fuzzy '$displayName' score=$totalDist")
                        }
                    }

                    // Single-word fuzzy (only if search is single word AND name word is similar length)
                    if (searchWords.size == 1 && searchWords[0].length >= 2) {
                        for (nw in nameWords) {
                            // Don't match very different length words
                            val lenDiff = kotlin.math.abs(nw.length - searchWords[0].length)
                            if (lenDiff > 2) continue
                            val dist = levenshtein(nw, searchWords[0])
                            if (dist < bestScore && dist <= 2) {
                                bestScore = dist
                                bestMatch = cleanNumber
                                bestMatchName = displayName
                                Log.d(TAG, "resolveContact: single-word fuzzy '$displayName'/'$nw' dist=$dist")
                            }
                        }
                    }

                    // Substring match — only if contact name fully contains search or vice versa
                    // AND word counts are compatible (avoid "papa" matching "aru papa")
                    if (displayName.length >= 3 && searchName.length >= 3) {
                        if (displayName.contains(searchName)) {
                            Log.d(TAG, "resolveContact: substring match (search in display) '$displayName' → $cleanNumber")
                            return cleanNumber
                        }
                        // Only match "display in search" if contact has at least as many words
                        if (searchName.contains(displayName) && nameWords.size >= searchWords.size) {
                            Log.d(TAG, "resolveContact: substring match (display in search) '$displayName' → $cleanNumber")
                            return cleanNumber
                        }
                    }

                    // Multi-search against single-word name (e.g. search "aru papa" with nameWord having one of those)
                    if (searchWords.size > 1 && nameWords.size == 1) {
                        for (sw in searchWords) {
                            val lenDiff = kotlin.math.abs(nameWords[0].length - sw.length)
                            if (lenDiff > 2) continue
                            val d = levenshtein(nameWords[0], sw)
                            if (d <= 1 && d < bestScore) {
                                // Only match single-name contacts if very close (dist <= 1)
                                bestScore = d
                                bestMatch = cleanNumber
                                bestMatchName = displayName
                                Log.d(TAG, "resolveContact: multi-search vs single-name '$displayName' word='$sw' dist=$d")
                            }
                        }
                    }
                }

                if (bestMatch != null) {
                    Log.d(TAG, "resolveContact: BEST match='$bestMatchName' score=$bestScore → $bestMatch")
                    return bestMatch
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Contact lookup error", e)
        }
        Log.w(TAG, "resolveContact: NO MATCH found for '$nameOrNumber'")
        return null
    }

    private fun levenshtein(a: String, b: String): Int {
        val la = a.length; val lb = b.length
        val dp = Array(la + 1) { IntArray(lb + 1) }
        for (i in 0..la) dp[i][0] = i
        for (j in 0..lb) dp[0][j] = j
        for (i in 1..la) for (j in 1..lb) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
        }
        return dp[la][lb]
    }
}
