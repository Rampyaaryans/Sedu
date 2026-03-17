package com.sedu.assistant

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.Uri
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sedu.assistant.service.SeduService
import com.sedu.assistant.tts.SeduTTS
import com.sedu.assistant.util.ModelManager
import com.sedu.assistant.voice.VoiceEnrollActivity
import com.sedu.assistant.voice.VoiceProfile

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var toggleButton: Button
    private lateinit var trainVoiceButton: Button
    private lateinit var userManualButton: TextView
    private lateinit var historyButton: Button
    private lateinit var voiceSettingsButton: Button
    private lateinit var apiKeysButton: Button
    private lateinit var letterS: TextView
    private lateinit var letterE: TextView
    private lateinit var letterD: TextView
    private lateinit var letterU: TextView
    private lateinit var animatedBg: View
    private lateinit var edgeGlow: View

    private var isServiceRunning = false
    private var bgAnimator: ValueAnimator? = null
    private var glowAnimator: ValueAnimator? = null
    private var shimmerAnimator: ValueAnimator? = null
    private var floatAnimators = mutableListOf<ObjectAnimator>()

    companion object {
        private const val OVERLAY_PERMISSION_REQUEST = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(UserPrefs.PREFS_NAME, MODE_PRIVATE)
        if (!prefs.getBoolean(UserPrefs.KEY_SETUP_DONE, false)) {
            startActivity(Intent(this, com.sedu.assistant.setup.SetupActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        initViews()
        promptGenderIfNeeded()

        // Force voice training if not enrolled
        val vp = VoiceProfile(this)
        if (!vp.isEnrolled()) {
            Toast.makeText(this, "Train your voice first so only YOU can activate Sedu!", Toast.LENGTH_LONG).show()
            Handler(Looper.getMainLooper()).postDelayed({
                startActivity(Intent(this, VoiceEnrollActivity::class.java))
            }, 1500)
        }

        // Auto-start only if user has not manually stopped Sedu.
        if (ModelManager.isModelReady(this) && !SeduService.isRunning && !prefs.getBoolean(UserPrefs.KEY_USER_STOPPED, false)) {
            startSeduService()
        }

        // Start animations
        startLetterAnimation()
        startBackgroundAnimation()
        startEdgeGlow()

        updateUI()
    }

    private fun initViews() {
        statusText = findViewById(R.id.statusText)
        subtitleText = findViewById(R.id.subtitleText)
        toggleButton = findViewById(R.id.toggleButton)
        trainVoiceButton = findViewById(R.id.trainVoiceButton)
        userManualButton = findViewById<TextView>(R.id.userManualButton)
        historyButton = findViewById(R.id.historyButton)
        voiceSettingsButton = findViewById(R.id.voiceSettingsButton)
        apiKeysButton = findViewById(R.id.apiKeysButton)
        letterS = findViewById(R.id.letterS)
        letterE = findViewById(R.id.letterE)
        letterD = findViewById(R.id.letterD)
        letterU = findViewById(R.id.letterU)
        animatedBg = findViewById(R.id.animatedBg)
        edgeGlow = findViewById(R.id.edgeGlow)

        // Apply unique tech-style typeface to SEDU letters
        val techFont = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        listOf(letterS, letterE, letterD, letterU).forEach { it.typeface = techFont }

        toggleButton.setOnClickListener {
            if (isServiceRunning) {
                stopSeduService()
            } else {
                startSeduService()
            }
        }

        trainVoiceButton.setOnClickListener {
            startActivity(Intent(this, VoiceEnrollActivity::class.java))
        }

        userManualButton.setOnClickListener {
            startActivity(Intent(this, UserManualActivity::class.java))
        }

        historyButton.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        voiceSettingsButton.setOnClickListener {
            showVoiceSettingsDialog()
        }

        apiKeysButton.setOnClickListener {
            showApiKeysDialog()
        }
    }

    private fun promptGenderIfNeeded() {
        val prefs = getSharedPreferences(UserPrefs.PREFS_NAME, MODE_PRIVATE)
        if (prefs.contains(UserPrefs.KEY_USER_GENDER)) return

        val choices = arrayOf("पुरुष", "महिला")
        var selected = 0
        AlertDialog.Builder(this)
            .setTitle("अपना जेंडर चुनो")
            .setMessage("ताकि सेडू तुम्हें अपने दोस्त की तरह सही तरीके से संबोधित करे")
            .setSingleChoiceItems(choices, 0) { _, which -> selected = which }
            .setCancelable(false)
            .setPositiveButton("सेव") { _, _ ->
                val gender = if (selected == 1) UserPrefs.GENDER_FEMALE else UserPrefs.GENDER_MALE
                prefs.edit().putString(UserPrefs.KEY_USER_GENDER, gender).apply()
            }
            .show()
    }

    private fun showVoiceSettingsDialog() {
        val prefs = getSharedPreferences(UserPrefs.PREFS_NAME, MODE_PRIVATE)
        val savedStyle = prefs.getString(UserPrefs.KEY_TTS_VOICE_STYLE, UserPrefs.VOICE_STYLE_MALE)
        val savedPitch = prefs.getString(UserPrefs.KEY_TTS_PITCH_MODE, UserPrefs.PITCH_MEDIUM)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 20, 32, 8)
        }

        val styleLabel = TextView(this).apply { text = "आवाज़ स्टाइल (हिंदी)" }
        val styleGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val maleStyle = RadioButton(this).apply { text = "पुरुष आवाज़"; id = View.generateViewId() }
        val femaleStyle = RadioButton(this).apply { text = "महिला आवाज़"; id = View.generateViewId() }
        styleGroup.addView(maleStyle)
        styleGroup.addView(femaleStyle)
        if (savedStyle == UserPrefs.VOICE_STYLE_FEMALE) styleGroup.check(femaleStyle.id) else styleGroup.check(maleStyle.id)

        val pitchLabel = TextView(this).apply {
            text = "पिच"
            setPadding(0, 20, 0, 0)
        }
        val pitchGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val lowPitch = RadioButton(this).apply { text = "लो पिच"; id = View.generateViewId() }
        val midPitch = RadioButton(this).apply { text = "मिड पिच"; id = View.generateViewId() }
        val highPitch = RadioButton(this).apply { text = "हाई पिच"; id = View.generateViewId() }
        pitchGroup.addView(lowPitch)
        pitchGroup.addView(midPitch)
        pitchGroup.addView(highPitch)
        when (savedPitch) {
            UserPrefs.PITCH_LOW -> pitchGroup.check(lowPitch.id)
            UserPrefs.PITCH_HIGH -> pitchGroup.check(highPitch.id)
            else -> pitchGroup.check(midPitch.id)
        }

        root.addView(styleLabel)
        root.addView(styleGroup)
        root.addView(pitchLabel)
        root.addView(pitchGroup)

        AlertDialog.Builder(this)
            .setTitle("वॉइस सेटिंग्स")
            .setView(root)
            .setNegativeButton("रद्द", null)
            .setNeutralButton("टेस्ट आवाज़") { _, _ ->
                val tempTts = SeduTTS(this)
                tempTts.speak("नमस्ते ${UserPrefs.salutationByGender(this)}, मैं सेडू हूँ") {
                    tempTts.shutdown()
                }
            }
            .setPositiveButton("सेव") { _, _ ->
                val style = if (styleGroup.checkedRadioButtonId == femaleStyle.id) {
                    UserPrefs.VOICE_STYLE_FEMALE
                } else {
                    UserPrefs.VOICE_STYLE_MALE
                }
                val pitch = when (pitchGroup.checkedRadioButtonId) {
                    lowPitch.id -> UserPrefs.PITCH_LOW
                    highPitch.id -> UserPrefs.PITCH_HIGH
                    else -> UserPrefs.PITCH_MEDIUM
                }
                prefs.edit()
                    .putString(UserPrefs.KEY_TTS_VOICE_STYLE, style)
                    .putString(UserPrefs.KEY_TTS_PITCH_MODE, pitch)
                    .apply()
                Toast.makeText(this, "वॉइस सेटिंग सेव हो गई", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showApiKeysDialog() {
        val prefs = getSharedPreferences(UserPrefs.PREFS_NAME, MODE_PRIVATE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 20, 32, 8)
        }

        fun keyField(hint: String, current: String): EditText {
            return EditText(this).apply {
                this.hint = hint
                setSingleLine(true)
                setText(current)
            }
        }

        val groq = keyField("Groq API Key", prefs.getString(UserPrefs.KEY_GROQ_API_KEY, "") ?: "")
        val mistral = keyField("Mistral API Key", prefs.getString(UserPrefs.KEY_MISTRAL_API_KEY, "") ?: "")
        val openai = keyField("OpenAI API Key", prefs.getString(UserPrefs.KEY_OPENAI_API_KEY, "") ?: "")
        val gemini = keyField("Gemini API Key", prefs.getString(UserPrefs.KEY_GEMINI_API_KEY, "") ?: "")

        root.addView(groq)
        root.addView(mistral)
        root.addView(openai)
        root.addView(gemini)

        AlertDialog.Builder(this)
            .setTitle("API Keys")
            .setMessage("अपने बैकअप API keys डालो ताकि Sedu कभी बंद न पड़े")
            .setView(root)
            .setNegativeButton("रद्द", null)
            .setPositiveButton("सेव") { _, _ ->
                prefs.edit()
                    .putString(UserPrefs.KEY_GROQ_API_KEY, groq.text.toString().trim())
                    .putString(UserPrefs.KEY_MISTRAL_API_KEY, mistral.text.toString().trim())
                    .putString(UserPrefs.KEY_OPENAI_API_KEY, openai.text.toString().trim())
                    .putString(UserPrefs.KEY_GEMINI_API_KEY, gemini.text.toString().trim())
                    .apply()

                val refreshIntent = Intent(this, SeduService::class.java).apply {
                    action = SeduService.ACTION_REFRESH_CONFIG
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(refreshIntent)
                } else {
                    startService(refreshIntent)
                }

                Toast.makeText(this, "API keys सेव हो गए", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun startLetterAnimation() {
        val letters = listOf(letterS, letterE, letterD, letterU)
        val animSet = AnimatorSet()
        val anims = mutableListOf<android.animation.Animator>()

        // Phase 1: Wave entrance — each letter drops in with elastic bounce + rotation
        letters.forEachIndexed { index, tv ->
            val delay = (index * 250).toLong()

            // Fade in
            anims.add(ObjectAnimator.ofFloat(tv, "alpha", 0f, 1f).apply {
                duration = 350
                startDelay = delay
            })
            // Scale from tiny with overshoot
            anims.add(ObjectAnimator.ofFloat(tv, "scaleX", 0f, 1.3f, 1f).apply {
                duration = 700
                startDelay = delay
                interpolator = OvershootInterpolator(3f)
            })
            anims.add(ObjectAnimator.ofFloat(tv, "scaleY", 0f, 1.3f, 1f).apply {
                duration = 700
                startDelay = delay
                interpolator = OvershootInterpolator(3f)
            })
            // Slide up from below with bounce
            anims.add(ObjectAnimator.ofFloat(tv, "translationY", 200f, -20f, 0f).apply {
                duration = 800
                startDelay = delay
                interpolator = DecelerateInterpolator(2f)
            })
            // Rotate in then settle
            val rotDir = if (index % 2 == 0) 15f else -15f
            anims.add(ObjectAnimator.ofFloat(tv, "rotation", rotDir, -rotDir * 0.3f, 0f).apply {
                duration = 800
                startDelay = delay
                interpolator = DecelerateInterpolator(1.5f)
            })
        }

        // Phase 2: Fade in subtitle, status, and bottom card elements with slide up
        val fadeDelay = 1200L
        val fadeViews = listOf<View>(
            subtitleText,
            statusText,
            toggleButton,
            trainVoiceButton,
            historyButton,
            voiceSettingsButton,
            apiKeysButton,
            userManualButton
        )
        fadeViews.forEachIndexed { i, v ->
            anims.add(ObjectAnimator.ofFloat(v, "alpha", 0f, 1f).apply {
                duration = 500
                startDelay = fadeDelay + (i * 100).toLong()
            })
            anims.add(ObjectAnimator.ofFloat(v, "translationY", 50f, 0f).apply {
                duration = 600
                startDelay = fadeDelay + (i * 100).toLong()
                interpolator = DecelerateInterpolator(1.5f)
            })
        }

        // Bottom card slide up
        val bottomCard = findViewById<View>(R.id.bottomCard)
        anims.add(ObjectAnimator.ofFloat(bottomCard, "translationY", 200f, 0f).apply {
            duration = 700
            startDelay = fadeDelay
            interpolator = DecelerateInterpolator(2f)
        })

        animSet.playTogether(anims)
        animSet.start()

        // Phase 3: After entrance, start continuous floating + shimmer
        Handler(Looper.getMainLooper()).postDelayed({
            startLetterFloat(letters)
            startGradientShimmer(letters)
        }, 2000)
    }

    /** Continuous gentle floating — each letter bobs up/down at different rates */
    private fun startLetterFloat(letters: List<TextView>) {
        floatAnimators.clear()
        letters.forEachIndexed { index, tv ->
            val floatAnim = ObjectAnimator.ofFloat(tv, "translationY", 0f, -12f, 0f, 8f, 0f).apply {
                duration = (2800 + index * 400).toLong()
                repeatMode = ValueAnimator.RESTART
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                startDelay = (index * 200).toLong()
            }
            floatAnim.start()
            floatAnimators.add(floatAnim)
        }
    }

    /** Shimmer gradient sweep across SEDU letters */
    private fun startGradientShimmer(letters: List<TextView>) {
        val darkGreen = Color.parseColor("#2F4F4F")
        val brightGreen = Color.parseColor("#00E676")
        val midGreen = Color.parseColor("#7DCEA0")

        shimmerAnimator = ValueAnimator.ofFloat(-1f, 2f).apply {
            duration = 3000
            repeatMode = ValueAnimator.RESTART
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val offset = anim.animatedValue as Float
                letters.forEachIndexed { index, tv ->
                    val letterPos = index.toFloat() / (letters.size - 1) // 0..1
                    val shimmerCenter = offset
                    val dist = kotlin.math.abs(letterPos - shimmerCenter)
                    if (dist < 0.4f) {
                        val intensity = 1f - (dist / 0.4f)
                        val r = lerp(darkGreen.red(), brightGreen.red(), intensity)
                        val g = lerp(darkGreen.green(), brightGreen.green(), intensity)
                        val b = lerp(darkGreen.blue(), brightGreen.blue(), intensity)
                        tv.setTextColor(Color.rgb(r, g, b))
                        tv.setShadowLayer(12f + intensity * 20f, 0f, 2f, Color.argb((intensity * 120).toInt(), 0, 230, 118))
                    } else {
                        val baseColor = if (index % 2 == 0) darkGreen else midGreen
                        tv.setTextColor(baseColor)
                        tv.setShadowLayer(12f, 0f, 4f, Color.parseColor("#4DA8E6CF"))
                    }
                }
            }
            start()
        }
    }

    private fun lerp(a: Int, b: Int, t: Float): Int {
        return (a + (b - a) * t).toInt().coerceIn(0, 255)
    }

    private fun Int.red() = Color.red(this)
    private fun Int.green() = Color.green(this)
    private fun Int.blue() = Color.blue(this)

    private fun startBackgroundAnimation() {
        val colors = intArrayOf(
            Color.parseColor("#E8F8F5"),
            Color.parseColor("#D5F5E3"),
            Color.parseColor("#ABEBC6"),
            Color.parseColor("#A8E6CF"),
            Color.parseColor("#D5F5E3"),
            Color.parseColor("#E8F8F5")
        )

        bgAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 8000
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val fraction = anim.animatedValue as Float
                val bgDrawable = object : Drawable() {
                    override fun draw(canvas: Canvas) {
                        val w = bounds.width().toFloat()
                        val h = bounds.height().toFloat()
                        val angle = fraction * 360
                        val rad = Math.toRadians(angle.toDouble())
                        val cx = w / 2 + (w / 3) * Math.cos(rad).toFloat()
                        val cy = h / 2 + (h / 3) * Math.sin(rad).toFloat()

                        val paint = Paint()
                        paint.shader = LinearGradient(
                            cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2,
                            colors, null, Shader.TileMode.CLAMP
                        )
                        canvas.drawRect(0f, 0f, w, h, paint)
                    }
                    override fun setAlpha(alpha: Int) {}
                    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
                    @Deprecated("Deprecated")
                    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
                }
                animatedBg.background = bgDrawable
            }
            start()
        }
    }

    private fun startEdgeGlow() {
        glowAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3000
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val intensity = anim.animatedValue as Float
                val glowDrawable = object : Drawable() {
                    override fun draw(canvas: Canvas) {
                        val w = bounds.width().toFloat()
                        val h = bounds.height().toFloat()
                        val alpha = (intensity * 100).toInt()
                        val borderW = 8f
                        val glowW = borderW * 6
                        val c1 = Color.argb(alpha, 168, 230, 207)
                        val c2 = Color.TRANSPARENT

                        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

                        // Top
                        paint.shader = LinearGradient(0f, 0f, 0f, glowW, c1, c2, Shader.TileMode.CLAMP)
                        canvas.drawRect(0f, 0f, w, glowW, paint)
                        // Bottom
                        paint.shader = LinearGradient(0f, h, 0f, h - glowW, c1, c2, Shader.TileMode.CLAMP)
                        canvas.drawRect(0f, h - glowW, w, h, paint)
                        // Left
                        paint.shader = LinearGradient(0f, 0f, glowW, 0f, c1, c2, Shader.TileMode.CLAMP)
                        canvas.drawRect(0f, 0f, glowW, h, paint)
                        // Right
                        paint.shader = LinearGradient(w, 0f, w - glowW, 0f, c1, c2, Shader.TileMode.CLAMP)
                        canvas.drawRect(w - glowW, 0f, w, h, paint)
                    }
                    override fun setAlpha(alpha: Int) {}
                    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
                    @Deprecated("Deprecated")
                    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
                }
                edgeGlow.background = glowDrawable
            }
            start()
        }
    }

    private fun startSeduService() {
        if (!ModelManager.isModelReady(this)) {
            statusText.text = "Model not downloaded yet"
            subtitleText.text = "Please complete setup first"
            return
        }
        val serviceIntent = Intent(this, SeduService::class.java)
        getSharedPreferences(UserPrefs.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(UserPrefs.KEY_USER_STOPPED, false)
            .apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        isServiceRunning = true
        updateUI()
    }

    private fun stopSeduService() {
        getSharedPreferences(UserPrefs.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(UserPrefs.KEY_USER_STOPPED, true)
            .apply()
        stopService(Intent(this, SeduService::class.java))
        isServiceRunning = false
        updateUI()
    }

    private fun updateUI() {
        val vp = VoiceProfile(this)
        val enrolled = vp.isEnrolled()

        if (isServiceRunning) {
            statusText.text = "Listening... say \"Sedu\""
            subtitleText.text = if (enrolled) "Only your voice activates Sedu" else "Train voice for owner-only mode"
            toggleButton.text = "Stop Sedu"
            toggleButton.backgroundTintList = ContextCompat.getColorStateList(this, R.color.red_stop)
        } else {
            statusText.text = "Tap Start to begin"
            subtitleText.text = if (enrolled) "Voice trained" else "Train your voice first"
            toggleButton.text = "Start Sedu"
            toggleButton.backgroundTintList = ContextCompat.getColorStateList(this, R.color.primary_dark)
        }

        trainVoiceButton.text = if (enrolled) "Retrain Voice" else "Train Voice"
    }

    override fun onResume() {
        super.onResume()
        isServiceRunning = SeduService.isRunning
        updateUI()

        // If voice was just enrolled, update button text
        val vp = VoiceProfile(this)
        trainVoiceButton.text = if (vp.isEnrolled()) "Retrain Voice" else "Train Voice"
    }

    override fun onDestroy() {
        super.onDestroy()
        bgAnimator?.cancel()
        glowAnimator?.cancel()
        shimmerAnimator?.cancel()
        floatAnimators.forEach { it.cancel() }
    }
}
