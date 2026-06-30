package com.snapknow.app.voice

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

class WhisperAudioPreprocessor(
    private val config: WhisperTinyAssetConfig
) {
    fun toLogMelSpectrogram(samples: ShortArray): FloatArray {
        val targetSamples = config.sampleRateHz * config.maxAudioSeconds
        val pcm = FloatArray(targetSamples)
        val copyCount = min(samples.size, targetSamples)
        for (index in 0 until copyCount) {
            pcm[index] = samples[index] / 32768f
        }

        val spectrogram = stftMagnitude(pcm)
        val melSpectrogram = applyMelFilters(spectrogram)
        return normalizeLogMel(melSpectrogram)
    }

    private fun stftMagnitude(samples: FloatArray): Array<FloatArray> {
        val fftSize = 400
        val hopLength = 160
        val frameCount = 3000
        val freqBins = fftSize / 2 + 1
        val window = hannWindow(fftSize)
        val output = Array(frameCount) { FloatArray(freqBins) }

        for (frame in 0 until frameCount) {
            val frameOffset = frame * hopLength
            for (bin in 0 until freqBins) {
                var real = 0.0
                var imag = 0.0
                for (sampleIndex in 0 until fftSize) {
                    val index = frameOffset + sampleIndex
                    val sample = if (index < samples.size) {
                        samples[index] * window[sampleIndex]
                    } else {
                        0f
                    }
                    val angle = -2.0 * PI * bin * sampleIndex / fftSize
                    real += sample * cos(angle)
                    imag += sample * sin(angle)
                }
                output[frame][bin] = (real * real + imag * imag).toFloat()
            }
        }
        return output
    }

    private fun applyMelFilters(powerSpectrogram: Array<FloatArray>): FloatArray {
        val frameCount = powerSpectrogram.size
        val filters = melFilterBank()
        val mel = FloatArray(config.melBins * frameCount)

        for (frame in 0 until frameCount) {
            for (melIndex in 0 until config.melBins) {
                var value = 0f
                val weights = filters[melIndex]
                for (freqBin in weights.indices) {
                    value += weights[freqBin] * powerSpectrogram[frame][freqBin]
                }
                mel[melIndex * frameCount + frame] = max(value, 1e-10f)
            }
        }
        return mel
    }

    private fun normalizeLogMel(melSpectrogram: FloatArray): FloatArray {
        val logMel = FloatArray(melSpectrogram.size)
        var maxValue = Float.NEGATIVE_INFINITY
        for (index in melSpectrogram.indices) {
            val value = log10(melSpectrogram[index].toDouble()).toFloat()
            logMel[index] = value
            if (value > maxValue) {
                maxValue = value
            }
        }

        val floor = maxValue - 8f
        for (index in logMel.indices) {
            val clipped = max(logMel[index], floor)
            logMel[index] = (clipped + 4f) / 4f
        }
        return logMel
    }

    private fun hannWindow(length: Int): FloatArray {
        val window = FloatArray(length)
        for (index in 0 until length) {
            window[index] = (0.5 - 0.5 * cos(2.0 * PI * index / length)).toFloat()
        }
        return window
    }

    private fun melFilterBank(): Array<FloatArray> {
        val fftSize = 400
        val freqBins = fftSize / 2 + 1
        val filters = Array(config.melBins) { FloatArray(freqBins) }

        val melMin = hzToMel(0.0)
        val melMax = hzToMel(config.sampleRateHz / 2.0)
        val melPoints = DoubleArray(config.melBins + 2) { index ->
            melMin + (melMax - melMin) * index / (config.melBins + 1)
        }
        val hzPoints = melPoints.map(::melToHz)
        val binPoints = hzPoints.map { hz ->
            min(freqBins - 1, ((fftSize + 1) * hz / config.sampleRateHz).toInt())
        }

        for (melIndex in 0 until config.melBins) {
            val left = binPoints[melIndex]
            val center = binPoints[melIndex + 1]
            val right = binPoints[melIndex + 2]
            if (left == center || center == right) continue

            for (bin in left until center) {
                filters[melIndex][bin] = (bin - left).toFloat() / (center - left)
            }
            for (bin in center until right) {
                filters[melIndex][bin] = (right - bin).toFloat() / (right - center)
            }
        }

        return filters
    }

    private fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)

    private fun melToHz(mel: Double): Double = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)
}
