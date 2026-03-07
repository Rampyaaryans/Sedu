package com.sedu.assistant.util

import android.util.Log
import org.vosk.Model

object VoskModelHolder {

    private const val TAG = "VoskModelHolder"
    private var hindiModel: Model? = null
    private var englishModel: Model? = null
    private var indianEnglishModel: Model? = null
    private var hindiModelPath: String? = null
    private var englishModelPath: String? = null
    private var indianEnglishModelPath: String? = null

    @Synchronized
    fun getHindiModel(path: String): Model {
        if (hindiModel == null || hindiModelPath != path) {
            hindiModel?.close()
            Log.d(TAG, "Loading Hindi model from $path")
            hindiModel = Model(path)
            hindiModelPath = path
        }
        return hindiModel!!
    }

    @Synchronized
    fun getEnglishModel(path: String): Model {
        if (englishModel == null || englishModelPath != path) {
            englishModel?.close()
            Log.d(TAG, "Loading English model from $path")
            englishModel = Model(path)
            englishModelPath = path
        }
        return englishModel!!
    }

    @Synchronized
    fun getIndianEnglishModel(path: String): Model {
        if (indianEnglishModel == null || indianEnglishModelPath != path) {
            indianEnglishModel?.close()
            Log.d(TAG, "Loading Indian English model from $path")
            indianEnglishModel = Model(path)
            indianEnglishModelPath = path
        }
        return indianEnglishModel!!
    }

    @Synchronized
    fun preloadModels(englishPath: String?, hindiPath: String?, indianEnglishPath: String? = null) {
        if (englishPath != null && (englishModel == null || englishModelPath != englishPath)) {
            englishModel?.close()
            englishModel = Model(englishPath)
            englishModelPath = englishPath
            Log.d(TAG, "English model preloaded")
        }
        if (hindiPath != null && (hindiModel == null || hindiModelPath != hindiPath)) {
            hindiModel?.close()
            hindiModel = Model(hindiPath)
            hindiModelPath = hindiPath
            Log.d(TAG, "Hindi model preloaded")
        }
        if (indianEnglishPath != null && (indianEnglishModel == null || indianEnglishModelPath != indianEnglishPath)) {
            indianEnglishModel?.close()
            indianEnglishModel = Model(indianEnglishPath)
            indianEnglishModelPath = indianEnglishPath
            Log.d(TAG, "Indian English model preloaded")
        }
    }

    @Synchronized
    fun releaseAll() {
        hindiModel?.close()
        englishModel?.close()
        indianEnglishModel?.close()
        hindiModel = null
        englishModel = null
        indianEnglishModel = null
        hindiModelPath = null
        englishModelPath = null
        indianEnglishModelPath = null
        Log.d(TAG, "All models released")
    }
}
