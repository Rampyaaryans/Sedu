package com.sedu.assistant.profile

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.sedu.assistant.R
import com.sedu.assistant.voice.VoiceProfileManager
import com.sedu.assistant.model.VoiceProfile

class VoiceProfileActivity : AppCompatActivity() {

    private lateinit var profileManager: VoiceProfileManager
    private lateinit var profileList: RecyclerView
    private lateinit var addButton: Button
    private lateinit var statusText: TextView
    private lateinit var adapter: ProfileAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_profile)

        profileManager = VoiceProfileManager(this)

        profileList = findViewById(R.id.profileList)
        addButton = findViewById(R.id.addProfileButton)
        statusText = findViewById(R.id.profileStatusText)

        profileList.layoutManager = LinearLayoutManager(this)
        adapter = ProfileAdapter()
        profileList.adapter = adapter

        addButton.setOnClickListener { showAddProfileDialog() }

        refreshProfiles()
    }

    private fun refreshProfiles() {
        val profiles = profileManager.getAllProfiles()
        adapter.profiles = profiles
        adapter.notifyDataSetChanged()

        val active = profileManager.getActiveProfile()
        statusText.text = if (active != null) {
            "Active profile: ${active.name}"
        } else {
            "No active profile. Add one to get started."
        }
    }

    private fun showAddProfileDialog() {
        val input = EditText(this).apply {
            hint = "Enter profile name"
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("New Voice Profile")
            .setMessage("Enter a name for this voice profile:")
            .setView(input)
            .setPositiveButton("Start Training") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    startVoiceTraining(name)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startVoiceTraining(profileName: String) {
        statusText.text = "Training: Say 'Sedu' clearly..."

        val profile = profileManager.createProfile(profileName)
        recordVoiceSamples(profile, 0)
    }

    private fun recordVoiceSamples(profile: VoiceProfile, sampleIndex: Int) {
        val totalSamples = 5

        if (sampleIndex >= totalSamples) {
            profileManager.finalizeProfile(profile)
            profileManager.setActiveProfile(profile.id)
            statusText.text = "Training complete! '${profile.name}' is now active."
            refreshProfiles()
            return
        }

        statusText.text = "Say 'Sedu' clearly (${sampleIndex + 1}/$totalSamples)..."

        Thread {
            val sampleRate = 16000
            val bufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ) * 2

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            val audioData = ShortArray(sampleRate * 3) // 3 seconds
            recorder.startRecording()

            var offset = 0
            while (offset < audioData.size) {
                val read = recorder.read(audioData, offset, minOf(1024, audioData.size - offset))
                if (read > 0) offset += read
            }

            recorder.stop()
            recorder.release()

            profileManager.saveSample(profile.id, sampleIndex, audioData)

            runOnUiThread {
                Toast.makeText(this, "Sample ${sampleIndex + 1} recorded!", Toast.LENGTH_SHORT).show()
                recordVoiceSamples(profile, sampleIndex + 1)
            }
        }.start()
    }

    inner class ProfileAdapter : RecyclerView.Adapter<ProfileAdapter.ViewHolder>() {
        var profiles: List<VoiceProfile> = emptyList()

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val nameText: TextView = view.findViewById(android.R.id.text1)
            val statusText: TextView = view.findViewById(android.R.id.text2)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_2, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val profile = profiles[position]
            val isActive = profileManager.getActiveProfile()?.id == profile.id

            holder.nameText.text = if (isActive) "${profile.name} (Active)" else profile.name
            holder.statusText.text = "Created: ${profile.createdDate}"

            holder.itemView.setOnClickListener {
                AlertDialog.Builder(this@VoiceProfileActivity)
                    .setTitle(profile.name)
                    .setItems(arrayOf("Set as Active", "Retrain Voice", "Delete")) { _, which ->
                        when (which) {
                            0 -> {
                                profileManager.setActiveProfile(profile.id)
                                refreshProfiles()
                            }
                            1 -> startVoiceTraining(profile.name)
                            2 -> {
                                profileManager.deleteProfile(profile.id)
                                refreshProfiles()
                            }
                        }
                    }
                    .show()
            }
        }

        override fun getItemCount() = profiles.size
    }
}
