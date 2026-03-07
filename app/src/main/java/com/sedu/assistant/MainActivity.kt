package com.sedu.assistant

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sedu.assistant.service.SeduService
import com.sedu.assistant.util.ModelManager
import com.sedu.assistant.voice.VoiceEnrollActivity
import com.sedu.assistant.voice.VoiceProfile

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var toggleButton: Button
    private lateinit var trainVoiceButton: Button
    private lateinit var userManualButton: Button
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
        private const val PREFS_NAME = "sedu_prefs"
        private const val KEY_SETUP_DONE = "setup_done"
        private const val OVERLAY_PERMISSION_REQUEST = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_SETUP_DONE, false)) {
            startActivity(Intent(this, com.sedu.assistant.setup.SetupActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        initViews()

        // Force voice training if not enrolled
        val vp = VoiceProfile(this)
        if (!vp.isEnrolled()) {
            Toast.makeText(this, "Train your voice first so only YOU can activate Sedu!", Toast.LENGTH_LONG).show()
            Handler(Looper.getMainLooper()).postDelayed({
                startActivity(Intent(this, VoiceEnrollActivity::class.java))
            }, 1500)
        }

        // Auto-start service immediately
        if (ModelManager.isModelReady(this) && !SeduService.isRunning) {
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
        userManualButton = findViewById(R.id.userManualButton)
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

        // Phase 2: Fade in subtitle, status, buttons with slide up
        val fadeDelay = 1200L
        val fadeViews = listOf(subtitleText, statusText, toggleButton, trainVoiceButton, userManualButton)
        fadeViews.forEachIndexed { i, v ->
            anims.add(ObjectAnimator.ofFloat(v, "alpha", 0f, 1f).apply {
                duration = 500
                startDelay = fadeDelay + (i * 120).toLong()
            })
            anims.add(ObjectAnimator.ofFloat(v, "translationY", 40f, 0f).apply {
                duration = 500
                startDelay = fadeDelay + (i * 120).toLong()
                interpolator = DecelerateInterpolator()
            })
        }

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
        // If service is passive, send GO_ACTIVE action
        if (SeduService.isPassive) {
            serviceIntent.action = SeduService.ACTION_GO_ACTIVE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        isServiceRunning = true
        updateUI()
    }

    private fun stopSeduService() {
        // Don't stop service — go passive (keep listening for owner's wake word)
        val intent = Intent(this, SeduService::class.java).apply {
            action = SeduService.ACTION_GO_PASSIVE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        isServiceRunning = false
        updateUI()
    }

    private fun updateUI() {
        val vp = VoiceProfile(this)
        val enrolled = vp.isEnrolled()

        if (isServiceRunning) {
            statusText.text = "Listening... say \"Sedu\""
            subtitleText.text = if (enrolled) "Only YOUR voice activates Sedu" else "⚠ Train voice for owner-only activation"
            toggleButton.text = "Stop"
            toggleButton.backgroundTintList = ContextCompat.getColorStateList(this, R.color.red_stop)
        } else if (SeduService.isPassive) {
            statusText.text = "Sleeping — say \"Sedu\" to wake"
            subtitleText.text = if (enrolled) "Owner voice will wake Sedu" else "⚠ Train voice first"
            toggleButton.text = "Start"
            toggleButton.backgroundTintList = ContextCompat.getColorStateList(this, R.color.primary)
        } else {
            statusText.text = "Tap to start"
            subtitleText.text = if (enrolled) "Voice trained ✓" else "⚠ Train your voice first!"
            toggleButton.text = "Start"
            toggleButton.backgroundTintList = ContextCompat.getColorStateList(this, R.color.primary)
        }

        trainVoiceButton.text = if (enrolled) "Retrain Voice" else "★ Train My Voice ★"
    }

    override fun onResume() {
        super.onResume()
        isServiceRunning = SeduService.isRunning && !SeduService.isPassive
        updateUI()

        // If voice was just enrolled, update button text
        val vp = VoiceProfile(this)
        trainVoiceButton.text = if (vp.isEnrolled()) "Retrain Voice" else "★ Train My Voice ★"
    }

    override fun onDestroy() {
        super.onDestroy()
        bgAnimator?.cancel()
        glowAnimator?.cancel()
        shimmerAnimator?.cancel()
        floatAnimators.forEach { it.cancel() }
    }
}
