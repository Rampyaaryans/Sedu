package com.sedu.assistant.voice

import android.content.Context
import android.util.Log
import org.json.JSONArray

/**
 * Speaker verification using MFCC voice fingerprinting.
 * Stores enrolled voice profile and compares incoming audio against it.
 */
class VoiceProfile(private val context: Context) {

    companion object {
        private const val TAG = "VoiceProfile"
        private const val PREFS_NAME = "sedu_voice_profile"
        private const val KEY_MEAN_MFCC = "mean_mfcc"
        private const val KEY_STD_MFCC = "std_mfcc"
        private const val KEY_ENROLLED = "enrolled"
        private const val KEY_THRESHOLD = "threshold"
        private const val DEFAULT_THRESHOLD = 0.72f
    }

    private val mfccExtractor = MFCCExtractor()
    private var meanMFCC: FloatArray? = null
    private var stdMFCC: FloatArray? = null
    private var threshold = DEFAULT_THRESHOLD

    init {
        load()
        threshold = DEFAULT_THRESHOLD
        Log.d(TAG, "Voice profile ready, enrolled=${meanMFCC != null}, threshold=$threshold")
    }

    fun isEnrolled(): Boolean = meanMFCC != null

    /**
     * Enroll from multiple audio samples.
     * Each sample is raw 16-bit PCM at 16kHz mono.
     */
    fun enroll(samples: List<ShortArray>) {
        Log.d(TAG, "Enrolling with ${samples.size} samples")

        val allMFCCs = mutableListOf<FloatArray>()
        for (sample in samples) {
            val mfccs = mfccExtractor.extract(sample)
            allMFCCs.addAll(mfccs)
        }

        if (allMFCCs.isEmpty()) {
            Log.e(TAG, "No MFCC frames extracted from enrollment samples!")
            return
        }

        Log.d(TAG, "Extracted ${allMFCCs.size} MFCC frames from ${samples.size} samples")

        val mfccArray = allMFCCs.toTypedArray()
        meanMFCC = mfccExtractor.meanMFCC(mfccArray)
        stdMFCC = mfccExtractor.stdMFCC(mfccArray, meanMFCC!!)
        threshold = DEFAULT_THRESHOLD

        save()
        Log.d(TAG, "Voice profile enrolled and saved (${allMFCCs.size} frames)")
    }

    /**
     * Verify if the given audio matches the enrolled voice.
     * Uses combined mean + std MFCC similarity for better speaker discrimination.
     * Returns similarity score (0-1). Higher = more similar.
     */
    fun verify(audioData: ShortArray): Float {
        if (meanMFCC == null || stdMFCC == null) return 1.0f  // Not enrolled → accept all

        // Check audio has actual speech (RMS energy)
        var sumSq = 0.0
        for (s in audioData) sumSq += s.toDouble() * s.toDouble()
        val rms = Math.sqrt(sumSq / audioData.size.coerceAtLeast(1))
        if (rms < 80.0) {
            Log.d(TAG, "Voice verification: audio too quiet (RMS=$rms), rejecting")
            return 0.0f
        }

        val mfccs = mfccExtractor.extract(audioData)
        if (mfccs.isEmpty()) return 0.0f

        val testMean = mfccExtractor.meanMFCC(mfccs)
        val testStd = mfccExtractor.stdMFCC(mfccs, testMean)
        
        val meanSim = mfccExtractor.cosineSimilarity(meanMFCC!!, testMean)
        val stdSim = mfccExtractor.cosineSimilarity(stdMFCC!!, testStd)
        
        // Weighted: mean captures vocal tract shape, std captures speaking style
        val combined = 0.65f * meanSim + 0.35f * stdSim

        Log.d(TAG, "Voice verification: meanSim=$meanSim, stdSim=$stdSim, combined=$combined, threshold=$threshold")
        return combined
    }

    /**
     * Check if the audio matches the enrolled speaker.
     * If not enrolled, accepts everyone.
     */
    fun isOwner(audioData: ShortArray): Boolean {
        if (!isEnrolled()) return true
        return verify(audioData) >= threshold
    }

    fun getThreshold(): Float = threshold

    fun clear() {
        meanMFCC = null
        stdMFCC = null
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().apply()
        Log.d(TAG, "Voice profile cleared")
    }

    private fun save() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()

        if (meanMFCC != null) {
            editor.putString(KEY_MEAN_MFCC, floatArrayToJson(meanMFCC!!))
            editor.putString(KEY_STD_MFCC, floatArrayToJson(stdMFCC!!))
            editor.putFloat(KEY_THRESHOLD, threshold)
            editor.putBoolean(KEY_ENROLLED, true)
        } else {
            editor.putBoolean(KEY_ENROLLED, false)
        }

        editor.apply()
    }

    private fun load() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ENROLLED, false)) {
            val meanJson = prefs.getString(KEY_MEAN_MFCC, null)
            val stdJson = prefs.getString(KEY_STD_MFCC, null)
            if (meanJson != null && stdJson != null) {
                meanMFCC = jsonToFloatArray(meanJson)
                stdMFCC = jsonToFloatArray(stdJson)
                threshold = prefs.getFloat(KEY_THRESHOLD, DEFAULT_THRESHOLD)
                Log.d(TAG, "Voice profile loaded, threshold=$threshold")
            }
        }
    }

    private fun floatArrayToJson(arr: FloatArray): String {
        val jsonArr = JSONArray()
        for (v in arr) jsonArr.put(v.toDouble())
        return jsonArr.toString()
    }

    private fun jsonToFloatArray(json: String): FloatArray {
        val jsonArr = JSONArray(json)
        return FloatArray(jsonArr.length()) { jsonArr.getDouble(it).toFloat() }
    }
}
