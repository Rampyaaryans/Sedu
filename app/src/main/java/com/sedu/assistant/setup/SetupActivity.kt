package com.sedu.assistant.setup

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.sedu.assistant.MainActivity
import com.sedu.assistant.R
import com.sedu.assistant.util.ModelManager

class SetupActivity : AppCompatActivity() {

    private lateinit var titleText: TextView
    private lateinit var descriptionText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var nextButton: Button

    private var currentStep = 0

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private const val PREFS_NAME = "sedu_prefs"
        private const val KEY_SETUP_DONE = "setup_done"

        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_PHONE_STATE
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        initViews()
        showStep(0)
    }

    private fun initViews() {
        titleText = findViewById(R.id.setupTitle)
        descriptionText = findViewById(R.id.setupDescription)
        progressBar = findViewById(R.id.setupProgress)
        progressText = findViewById(R.id.setupProgressText)
        nextButton = findViewById(R.id.setupNextButton)

        nextButton.setOnClickListener { onNextClicked() }
    }

    private fun showStep(step: Int) {
        currentStep = step
        progressBar.visibility = View.GONE
        progressText.visibility = View.GONE

        when (step) {
            0 -> {
                titleText.text = "Welcome to Sedu"
                descriptionText.text = "Your personal AI voice assistant.\n\nSedu works completely offline and responds only to your voice.\n\nLet's get you set up!"
                nextButton.text = "Let's Go"
            }
            1 -> {
                titleText.text = "Permissions"
                descriptionText.text = "Sedu needs the following permissions to work:\n\n• Microphone - to hear your voice\n• Phone - to make calls\n• SMS - to send text messages\n• Contacts - to find your contacts\n• Notifications - to run in the background"
                nextButton.text = "Grant Permissions"
            }
            2 -> {
                titleText.text = "Downloading Voice Model"
                descriptionText.text = "Downloading the offline speech recognition model.\nThis is a one-time download (~50 MB)."
                progressBar.visibility = View.VISIBLE
                progressText.visibility = View.VISIBLE
                progressBar.isIndeterminate = false
                progressBar.progress = 0
                progressText.text = "Starting download..."
                nextButton.text = "Downloading..."
                nextButton.isEnabled = false
                startModelDownload()
            }
            3 -> {
                titleText.text = "All Set!"
                descriptionText.text = "Sedu is ready to go!\n\nSay \"Sedu\" followed by a command like:\n\n• \"Sedu, call Mom\"\n• \"Sedu, send SMS to Ali I'm on my way\"\n• \"Sedu, open YouTube\"\n• \"Sedu, what time is it?\""
                nextButton.text = "Start Using Sedu"
            }
        }
    }

    private fun onNextClicked() {
        when (currentStep) {
            0 -> showStep(1)
            1 -> requestPermissions()
            2 -> { /* handled by download */ }
            3 -> finishSetup()
        }
    }

    private fun requestPermissions() {
        val missingPermissions = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            showStep(2)
        } else {
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            // Check if at least microphone was granted
            val micGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (micGranted) {
                showStep(2)
            } else {
                descriptionText.text = "Microphone permission is required for Sedu to work.\nPlease grant it to continue."
            }
        }
    }

    private fun startModelDownload() {
        if (ModelManager.isModelReady(this)) {
            onModelReady()
            return
        }

        ModelManager.downloadModel(this, object : ModelManager.DownloadCallback {
            override fun onProgress(percent: Int) {
                runOnUiThread {
                    progressBar.progress = percent
                    progressText.text = "Downloading... $percent%"
                }
            }

            override fun onComplete() {
                runOnUiThread { onModelReady() }
            }

            override fun onError(error: String) {
                runOnUiThread {
                    progressText.text = "Download failed: $error"
                    nextButton.text = "Retry Download"
                    nextButton.isEnabled = true
                    nextButton.setOnClickListener {
                        nextButton.isEnabled = false
                        startModelDownload()
                    }
                }
            }
        })
    }

    private fun onModelReady() {
        progressBar.progress = 100
        progressText.text = "Model ready!"
        nextButton.isEnabled = true
        showStep(3)
    }

    private fun finishSetup() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SETUP_DONE, true)
            .apply()

        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
