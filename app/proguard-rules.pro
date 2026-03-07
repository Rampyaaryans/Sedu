# Sedu ProGuard Rules

# Vosk speech recognition
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**

# Gson serialization
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.sedu.assistant.model.** { *; }
-keep class com.sedu.assistant.ai.** { *; }

# Android speech
-keep class android.speech.** { *; }

# Keep services and activities
-keep class com.sedu.assistant.service.** { *; }
-keep class com.sedu.assistant.** extends android.app.Activity { *; }
-keep class com.sedu.assistant.** extends android.app.Service { *; }
