package com.sedu.assistant.voice

import kotlin.math.*

/**
 * Pure Kotlin MFCC (Mel-Frequency Cepstral Coefficients) feature extraction.
 * Used for speaker verification — captures vocal tract characteristics (voice fingerprint).
 * Input: 16-bit PCM audio at 16kHz mono.
 */
class MFCCExtractor {

    companion object {
        const val SAMPLE_RATE = 16000
        const val FRAME_SIZE_MS = 25
        const val FRAME_STEP_MS = 10
        const val NUM_MEL_FILTERS = 26
        const val NUM_MFCC = 13
        const val PRE_EMPHASIS_COEFF = 0.97f
        const val MIN_FREQ = 0.0
        val MAX_FREQ = SAMPLE_RATE / 2.0
    }

    /**
     * Extract MFCC features from raw 16-bit PCM audio at 16kHz.
     * Returns array of MFCC vectors (one per frame), each with NUM_MFCC coefficients.
     */
    fun extract(audioData: ShortArray): Array<FloatArray> {
        if (audioData.size < SAMPLE_RATE * FRAME_SIZE_MS / 1000) {
            return emptyArray()
        }

        // Convert to float samples normalized to [-1, 1]
        val samples = FloatArray(audioData.size) { audioData[it].toFloat() / 32768.0f }

        // Pre-emphasis filter (boosts high frequencies, captures formants better)
        for (i in samples.size - 1 downTo 1) {
            samples[i] = samples[i] - PRE_EMPHASIS_COEFF * samples[i - 1]
        }

        // Frame parameters
        val frameLength = SAMPLE_RATE * FRAME_SIZE_MS / 1000  // 400 samples
        val frameStep = SAMPLE_RATE * FRAME_STEP_MS / 1000    // 160 samples
        val fftSize = nextPowerOf2(frameLength)                // 512
        val numFrames = max(1, (samples.size - frameLength) / frameStep + 1)

        // Precompute Hamming window
        val window = FloatArray(frameLength) { i ->
            (0.54f - 0.46f * cos(2.0 * PI * i / (frameLength - 1))).toFloat()
        }

        // Precompute mel filterbank
        val filterbank = createMelFilterbank(fftSize)

        val mfccs = Array(numFrames) { FloatArray(NUM_MFCC) }

        for (f in 0 until numFrames) {
            val start = f * frameStep

            // Apply window and zero-pad to FFT size
            val real = FloatArray(fftSize)
            val imag = FloatArray(fftSize)
            for (j in 0 until frameLength) {
                if (start + j < samples.size) {
                    real[j] = samples[start + j] * window[j]
                }
            }

            // FFT
            fft(real, imag)

            // Power spectrum (only first half + DC)
            val powerSpecSize = fftSize / 2 + 1
            val powerSpec = FloatArray(powerSpecSize) { k ->
                (real[k] * real[k] + imag[k] * imag[k]) / fftSize
            }

            // Apply mel filterbank → log energies
            val melEnergies = FloatArray(NUM_MEL_FILTERS) { m ->
                var energy = 0.0f
                for (k in 0 until powerSpecSize) {
                    energy += filterbank[m][k] * powerSpec[k]
                }
                ln(max(energy, 1e-10f))
            }

            // DCT to get MFCCs
            mfccs[f] = dct(melEnergies)
        }

        return mfccs
    }

    /** Compute mean MFCC vector across all frames (supports any dimension). */
    fun meanMFCC(mfccs: Array<FloatArray>): FloatArray {
        if (mfccs.isEmpty()) return FloatArray(0)
        val dim = mfccs[0].size
        val mean = FloatArray(dim)
        for (frame in mfccs) {
            for (i in 0 until min(frame.size, dim)) mean[i] += frame[i]
        }
        val count = mfccs.size.toFloat()
        for (i in mean.indices) mean[i] /= count
        return mean
    }

    /** Compute standard deviation of MFCC across all frames (supports any dimension). */
    fun stdMFCC(mfccs: Array<FloatArray>, mean: FloatArray): FloatArray {
        val dim = mean.size
        if (mfccs.size < 2) return FloatArray(dim) { 1.0f }
        val std = FloatArray(dim)
        for (frame in mfccs) {
            for (i in 0 until min(frame.size, dim)) {
                val diff = frame[i] - mean[i]
                std[i] += diff * diff
            }
        }
        for (i in std.indices) {
            std[i] = sqrt(std[i] / (mfccs.size - 1).toFloat()).coerceAtLeast(0.001f)
        }
        return std
    }

    /**
     * Extract MFCC + delta MFCC features (26-dimensional vectors).
     * Delta MFCCs capture temporal dynamics of the voice — how it changes across frames.
     * This significantly improves speaker discrimination over static MFCCs alone.
     * Audio is amplitude-normalized before extraction so near-field and far-field produce
     * consistent features for the same speaker.
     */
    fun extractWithDeltas(audioData: ShortArray): Array<FloatArray> {
        val normalized = normalizeAudio(audioData)
        val staticMFCCs = extract(normalized)
        if (staticMFCCs.size < 3) return emptyArray()

        val deltas = computeDeltas(staticMFCCs)

        // Concatenate static + delta → 26-dimensional vectors
        return Array(staticMFCCs.size) { i ->
            FloatArray(NUM_MFCC * 2) { j ->
                if (j < NUM_MFCC) staticMFCCs[i][j] else deltas[i][j - NUM_MFCC]
            }
        }
    }

    /**
     * Normalize audio amplitude so peak is at ~80% of max.
     * This ensures far-field (quiet) and near-field (loud) audio produce
     * consistent MFCC features for the same speaker.
     */
    fun normalizeAudio(audio: ShortArray): ShortArray {
        if (audio.isEmpty()) return audio
        var maxAmp = 0
        for (s in audio) {
            val abs = abs(s.toInt())
            if (abs > maxAmp) maxAmp = abs
        }
        if (maxAmp < 50) return audio // too quiet to normalize (pure silence)
        val targetAmp = 26000 // ~80% of 32767
        val scale = targetAmp.toFloat() / maxAmp
        if (scale < 1.1f && scale > 0.9f) return audio // already at good level
        return ShortArray(audio.size) { (audio[it] * scale).toInt().coerceIn(-32768, 32767).toShort() }
    }

    /**
     * Compute delta (first derivative) of MFCC frame sequence.
     * delta[t] = (mfcc[t+1] - mfcc[t-1]) / 2
     */
    private fun computeDeltas(mfccs: Array<FloatArray>): Array<FloatArray> {
        val n = mfccs.size
        return Array(n) { t ->
            val prev = if (t > 0) mfccs[t - 1] else mfccs[0]
            val next = if (t < n - 1) mfccs[t + 1] else mfccs[n - 1]
            FloatArray(NUM_MFCC) { i -> (next[i] - prev[i]) / 2.0f }
        }
    }

    /** Cosine similarity between two vectors. Returns value in [-1, 1]. */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0f
        var normA = 0.0f
        var normB = 0.0f
        val minLen = min(a.size, b.size)
        for (i in 0 until minLen) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom > 0) dot / denom else 0.0f
    }

    // =============== FFT (In-place Radix-2 Cooley-Tukey) ===============

    private fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size

        // Bit-reversal permutation
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                var t = real[i]; real[i] = real[j]; real[j] = t
                t = imag[i]; imag[i] = imag[j]; imag[j] = t
            }
            var m = n / 2
            while (m >= 1 && j >= m) {
                j -= m
                m /= 2
            }
            j += m
        }

        // Butterfly operations
        var step = 2
        while (step <= n) {
            val halfStep = step / 2
            val angle = -2.0 * PI / step
            val wR = cos(angle).toFloat()
            val wI = sin(angle).toFloat()

            var i = 0
            while (i < n) {
                var curR = 1.0f
                var curI = 0.0f
                for (k in 0 until halfStep) {
                    val idx1 = i + k
                    val idx2 = i + k + halfStep
                    val tR = curR * real[idx2] - curI * imag[idx2]
                    val tI = curR * imag[idx2] + curI * real[idx2]
                    real[idx2] = real[idx1] - tR
                    imag[idx2] = imag[idx1] - tI
                    real[idx1] = real[idx1] + tR
                    imag[idx1] = imag[idx1] + tI
                    val newCurR = curR * wR - curI * wI
                    curI = curR * wI + curI * wR
                    curR = newCurR
                }
                i += step
            }
            step *= 2
        }
    }

    // =============== Mel Filterbank ===============

    private fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)
    private fun melToHz(mel: Double): Double = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)

    private fun createMelFilterbank(fftSize: Int): Array<FloatArray> {
        val numBins = fftSize / 2 + 1
        val lowMel = hzToMel(MIN_FREQ)
        val highMel = hzToMel(MAX_FREQ)

        // Uniformly spaced mel points
        val melPoints = DoubleArray(NUM_MEL_FILTERS + 2) { i ->
            lowMel + i * (highMel - lowMel) / (NUM_MEL_FILTERS + 1)
        }

        // Convert to frequency bin indices
        val binPoints = IntArray(melPoints.size) { i ->
            ((melToHz(melPoints[i]) * fftSize / SAMPLE_RATE).toInt()).coerceIn(0, numBins - 1)
        }

        return Array(NUM_MEL_FILTERS) { m ->
            val filter = FloatArray(numBins)
            val left = binPoints[m]
            val center = binPoints[m + 1]
            val right = binPoints[m + 2]

            for (k in left until center) {
                if (center != left) {
                    filter[k] = (k - left).toFloat() / (center - left)
                }
            }
            for (k in center until right) {
                if (right != center) {
                    filter[k] = (right - k).toFloat() / (right - center)
                }
            }
            filter
        }
    }

    // =============== DCT (Type II) ===============

    private fun dct(input: FloatArray): FloatArray {
        val n = input.size
        val output = FloatArray(NUM_MFCC)
        for (k in 0 until NUM_MFCC) {
            var sum = 0.0f
            for (i in 0 until n) {
                sum += input[i] * cos(PI * k * (2 * i + 1) / (2.0 * n)).toFloat()
            }
            output[k] = sum
        }
        return output
    }

    private fun nextPowerOf2(n: Int): Int {
        var v = n - 1
        v = v or (v shr 1)
        v = v or (v shr 2)
        v = v or (v shr 4)
        v = v or (v shr 8)
        v = v or (v shr 16)
        return v + 1
    }
}
