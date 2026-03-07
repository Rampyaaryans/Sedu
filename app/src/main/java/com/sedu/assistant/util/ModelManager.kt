package com.sedu.assistant.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

class ModelManager {

    companion object {
        private const val TAG = "ModelManager"
        private const val EN_MODEL_DIR = "vosk-model-en"
        private const val HI_MODEL_DIR = "vosk-model-hi"
        private const val ENIN_MODEL_DIR = "vosk-model-en-in"
        private const val EN_MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        private const val HI_MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip"
        private const val ENIN_MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip"

        fun isModelReady(context: Context): Boolean {
            return isEnglishModelReady(context)
        }

        fun isEnglishModelReady(context: Context): Boolean {
            val dir = File(context.filesDir, EN_MODEL_DIR)
            return dir.exists() && (dir.listFiles()?.isNotEmpty() == true)
        }

        fun isHindiModelReady(context: Context): Boolean {
            val dir = File(context.filesDir, HI_MODEL_DIR)
            return dir.exists() && (dir.listFiles()?.isNotEmpty() == true)
        }

        fun areBothModelsReady(context: Context): Boolean {
            return isEnglishModelReady(context) && isHindiModelReady(context)
        }

        fun isIndianEnglishModelReady(context: Context): Boolean {
            val dir = File(context.filesDir, ENIN_MODEL_DIR)
            return dir.exists() && (dir.listFiles()?.isNotEmpty() == true)
        }

        fun getModelPath(context: Context): String? {
            return getEnglishModelPath(context)
        }

        fun getEnglishModelPath(context: Context): String? {
            val dir = File(context.filesDir, EN_MODEL_DIR)
            return if (dir.exists()) dir.absolutePath else null
        }

        fun getHindiModelPath(context: Context): String? {
            val dir = File(context.filesDir, HI_MODEL_DIR)
            return if (dir.exists()) dir.absolutePath else null
        }

        fun getIndianEnglishModelPath(context: Context): String? {
            val dir = File(context.filesDir, ENIN_MODEL_DIR)
            return if (dir.exists()) dir.absolutePath else null
        }

        fun downloadModel(context: Context, callback: DownloadCallback) {
            downloadBothModels(context, callback)
        }

        /** Synchronous download of Indian English model only (called from service) */
        fun downloadIndianEnglishModel(context: Context) {
            if (!isIndianEnglishModelReady(context)) {
                downloadSingleModel(context, ENIN_MODEL_URL, ENIN_MODEL_DIR, "Indian English") { }
            }
        }

        fun downloadBothModels(context: Context, callback: DownloadCallback) {
            Thread {
                try {
                    // Download English model for wake word (0-30%)
                    if (!isEnglishModelReady(context)) {
                        downloadSingleModel(context, EN_MODEL_URL, EN_MODEL_DIR, "English") { p ->
                            callback.onProgress((p * 0.30).toInt())
                        }
                    }
                    callback.onProgress(30)

                    // Download Indian English model for commands (30-65%)
                    // This model understands Hinglish/Indian-accented English much better
                    if (!isIndianEnglishModelReady(context)) {
                        downloadSingleModel(context, ENIN_MODEL_URL, ENIN_MODEL_DIR, "Indian English") { p ->
                            callback.onProgress(30 + (p * 0.35).toInt())
                        }
                    }
                    callback.onProgress(65)

                    // Download Hindi model (65-95%)
                    if (!isHindiModelReady(context)) {
                        downloadSingleModel(context, HI_MODEL_URL, HI_MODEL_DIR, "Hindi") { p ->
                            callback.onProgress(65 + (p * 0.30).toInt())
                        }
                    }
                    callback.onProgress(95)

                    // Preload models into memory for instant response
                    val enPath = getEnglishModelPath(context)
                    val enInPath = getIndianEnglishModelPath(context)
                    val hiPath = getHindiModelPath(context)
                    VoskModelHolder.preloadModels(enPath, hiPath, enInPath)

                    callback.onProgress(100)
                    callback.onComplete()
                } catch (e: Exception) {
                    Log.e(TAG, "Model download failed", e)
                    callback.onError(e.message ?: "Download failed")
                }
            }.start()
        }

        private fun downloadSingleModel(
            context: Context,
            modelUrl: String,
            dirName: String,
            label: String,
            onProgress: (Int) -> Unit
        ) {
            val modelDir = File(context.filesDir, dirName)
            val tempZip = File(context.cacheDir, "$dirName.zip")

            Log.d(TAG, "Downloading $label model from $modelUrl")
            onProgress(0)

            val url = URL(modelUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 30000
            connection.readTimeout = 60000
            connection.connect()

            val totalSize = connection.contentLength
            val input: InputStream = connection.inputStream
            val output = FileOutputStream(tempZip)

            val buffer = ByteArray(16384)
            var downloaded = 0L
            var read: Int

            while (input.read(buffer).also { read = it } != -1) {
                output.write(buffer, 0, read)
                downloaded += read
                if (totalSize > 0) {
                    onProgress((downloaded * 100 / totalSize).toInt())
                }
            }

            output.close()
            input.close()
            connection.disconnect()

            Log.d(TAG, "$label download complete, extracting...")
            onProgress(95)

            extractZip(tempZip, context.filesDir)

            // Find and rename extracted folder
            val extractedDirs = context.filesDir.listFiles { f ->
                f.isDirectory && f.name.startsWith("vosk-model") && f.name != EN_MODEL_DIR && f.name != HI_MODEL_DIR
            }
            val extracted = extractedDirs?.firstOrNull()
            if (extracted != null) {
                modelDir.deleteRecursively()
                extracted.renameTo(modelDir)
            }

            tempZip.delete()
            Log.d(TAG, "$label model ready at ${modelDir.absolutePath}")
        }

        private fun extractZip(zipFile: File, destDir: File) {
            ZipInputStream(zipFile.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val file = File(destDir, entry.name)

                    // Prevent zip slip attack
                    val canonicalDest = destDir.canonicalPath
                    val canonicalFile = file.canonicalPath
                    if (!canonicalFile.startsWith(canonicalDest)) {
                        throw SecurityException("Zip entry outside target directory: ${entry.name}")
                    }

                    if (entry.isDirectory) {
                        file.mkdirs()
                    } else {
                        file.parentFile?.mkdirs()
                        FileOutputStream(file).use { fos ->
                            val buffer = ByteArray(8192)
                            var len: Int
                            while (zis.read(buffer).also { len = it } > 0) {
                                fos.write(buffer, 0, len)
                            }
                        }
                    }
                    entry = zis.nextEntry
                }
            }
        }
    }

    interface DownloadCallback {
        fun onProgress(percent: Int)
        fun onComplete()
        fun onError(error: String)
    }
}
