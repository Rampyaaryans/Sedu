package com.sedu.assistant.voice

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.sedu.assistant.R

class VoiceEnrollActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "VoiceEnroll"
        private const val SAMPLE_RATE = 16000
        private const val TOTAL_SAMPLES = 6
        private const val RECORD_DURATION_MS = 2500L
    }

    private lateinit var instructionText: TextView
    private lateinit var sampleCountText: TextView
    private lateinit var statusText: TextView
    private lateinit var recordButton: Button
    private lateinit var doneButton: Button
    private lateinit var resetButton: Button
    private lateinit var progressBar: ProgressBar

    private val handler = Handler(Looper.getMainLooper())
    private val samples = mutableListOf<ShortArray>()
    private var isRecording = false
    private var audioRecord: AudioRecord? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_enroll)

        instructionText = findViewById(R.id.instructionText)
        sampleCountText = findViewById(R.id.sampleCountText)
        statusText = findViewById(R.id.statusText)
        recordButton = findViewById(R.id.recordButton)
        doneButton = findViewById(R.id.doneButton)
        resetButton = findViewById(R.id.resetButton)
        progressBar = findViewById(R.id.enrollProgress)

        doneButton.visibility = View.GONE
        progressBar.max = TOTAL_SAMPLES
        progressBar.progress = 0

        // Show reset button if already enrolled
        val voiceProfile = VoiceProfile(this)
        if (voiceProfile.isEnrolled()) {
            resetButton.visibility = View.VISIBLE
            instructionText.text = "Voice already enrolled.\nRe-record to update, or reset."
        } else {
            resetButton.visibility = View.GONE
            instructionText.text = "Train SEDU on your voice.\nSay SEDU at different distances.\nFirst 3 close, last 3 from far."
        }

        updateUI()

        recordButton.setOnClickListener {
            if (!isRecording) {
                recordSample()
            }
        }

        doneButton.setOnClickListener { finish() }

        resetButton.setOnClickListener {
            voiceProfile.clear()
            samples.clear()
            resetButton.visibility = View.GONE
            statusText.text = "Voice profile cleared."
            instructionText.text = "Train SEDU on your voice.\nSay SEDU at different distances.\nFirst 3 close, last 3 from far."
            updateUI()
        }
    }

    private fun updateUI() {
        val count = samples.size
        sampleCountText.text = "Sample $count / $TOTAL_SAMPLES"
        progressBar.progress = count

        if (count >= TOTAL_SAMPLES) {
            recordButton.visibility = View.GONE
            instructionText.text = "Processing your voice..."
            processEnrollment()
        } else {
            recordButton.isEnabled = true
            recordButton.visibility = View.VISIBLE
            if (count == 0 && statusText.text.isBlank()) {
                instructionText.text = "Say SEDU clearly when you press Record.\nFirst 3 samples: hold phone close.\nLast 3 samples: step 2-3 meters away."
            } else if (count in 1..2) {
                instructionText.text = "Good! Say SEDU again (close to phone)."
            } else if (count == 3) {
                instructionText.text = "Now step 2-3 meters away.\nSay SEDU from there."
            } else if (count > 3) {
                instructionText.text = "Good! Say SEDU again (from distance)."
            }
        }
    }

    private fun recordSample() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
            return
        }

        isRecording = true
        recordButton.isEnabled = false
        statusText.text = "Recording... say SEDU now!"
        instructionText.text = "Listening..."

        Thread {
            try {
                val bufferSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(4096)

                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    handler.post {
                        statusText.text = "Mic error. Try again."
                        isRecording = false
                        recordButton.isEnabled = true
                    }
                    return@Thread
                }

                audioRecord?.startRecording()

                val totalSamples = (SAMPLE_RATE * RECORD_DURATION_MS / 1000).toInt()
                val audioData = ShortArray(totalSamples)
                var offset = 0
                val readBuffer = ShortArray(1024)

                val startTime = System.currentTimeMillis()
                while (System.currentTimeMillis() - startTime < RECORD_DURATION_MS && offset < totalSamples) {
                    val read = audioRecord?.read(readBuffer, 0,
                        minOf(readBuffer.size, totalSamples - offset)) ?: 0
                    if (read > 0) {
                        System.arraycopy(readBuffer, 0, audioData, offset, read)
                        offset += read
                    }
                }

                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null

                val trimmed = if (offset < totalSamples) audioData.copyOf(offset) else audioData

                // Check audio has enough energy (not too quiet)
                val rms = computeRMS(trimmed)
                Log.d(TAG, "Sample ${samples.size + 1}: ${trimmed.size} samples, RMS=$rms")

                if (rms < 20) {
                    handler.post {
                        statusText.text = "Too quiet! Speak a bit louder."
                        isRecording = false
                        recordButton.isEnabled = true
                        instructionText.text = "Say SEDU a bit louder."
                    }
                    return@Thread
                }

                samples.add(trimmed)

                handler.post {
                    isRecording = false
                    statusText.text = "Sample ${samples.size} recorded!"
                    updateUI()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Recording error", e)
                handler.post {
                    statusText.text = "Error: ${e.message}"
                    isRecording = false
                    recordButton.isEnabled = true
                }
            }
        }.start()
    }

    private fun processEnrollment() {
        Thread {
            try {
                val voiceProfile = VoiceProfile(this)
                voiceProfile.enroll(samples)

                handler.post {
                    instructionText.text = "Voice enrolled!"
                    statusText.text = "Sedu will now only respond to YOUR voice.\nNo one else can activate it."
                    doneButton.visibility = View.VISIBLE
                    doneButton.text = "Done"
                    resetButton.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                Log.e(TAG, "Enrollment error", e)
                handler.post {
                    instructionText.text = "Enrollment failed"
                    statusText.text = "Error: ${e.message}\nTry again."
                    doneButton.visibility = View.VISIBLE
                    doneButton.text = "Close"
                }
            }
        }.start()
    }

    private fun computeRMS(data: ShortArray): Double {
        var sum = 0.0
        for (s in data) sum += s.toDouble() * s.toDouble()
        return kotlin.math.sqrt(sum / data.size)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) { }
    }
}
