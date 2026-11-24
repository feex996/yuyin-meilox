package com.ljyh.mei.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import com.ljyh.mei.constants.SoundFxBassKey
import com.ljyh.mei.constants.SoundFxEnabledKey
import com.ljyh.mei.constants.SoundFxSurroundKey
import com.ljyh.mei.utils.dataStore
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 余音音效预设：一键组合 环绕声宽化 + 重低音 + EQ 曲线。
 * surround/bass 取值 0..100；-1 表示由用户手动调节（自定义）。
 */
enum class SoundFxPreset(
    val label: String,
    val surround: Int,
    val bass: Int,
    val equalizerPreset: EqualizerPreset,
) {
    Pop("流行", 30, 35, EqualizerPreset.Pop),
    Rock("摇滚", 25, 45, EqualizerPreset.Rock),
    Electronic("电子", 40, 50, EqualizerPreset.Electronic),
    Vocal("人声", 15, 10, EqualizerPreset.Vocal),
    Classical("古典", 35, 15, EqualizerPreset.Classical),
    Live("现场", 50, 35, EqualizerPreset.Acoustic),
    UltraBass("重低音", 10, 85, EqualizerPreset.BassBoost),
    Spatial("全景声", 75, 30, EqualizerPreset.Flat),
    Custom("自定义", -1, -1, EqualizerPreset.Custom),
}

data class SoundFxConfiguration(
    val enabled: Boolean = false,
    /** 0..1，0 表示不处理 */
    val surround: Float = 0f,
    /** 0..1，映射到 90Hz 低架增益 0..10dB */
    val bass: Float = 0f,
)

class SoundFxConfigurationState(
    context: Context,
    scope: CoroutineScope,
) {
    private val current = AtomicReference(SoundFxConfiguration())
    private val observation: Job = scope.launch {
        context.dataStore.data.collectLatest { preferences ->
            val surround = (preferences[SoundFxSurroundKey] ?: 0).coerceIn(0, 100) / 100f
            val bass = (preferences[SoundFxBassKey] ?: 0).coerceIn(0, 100) / 100f
            current.set(
                SoundFxConfiguration(
                    enabled = preferences[SoundFxEnabledKey] ?: false,
                    surround = surround,
                    bass = bass,
                ),
            )
        }
    }

    fun snapshot(): SoundFxConfiguration = current.get()
    fun release() = observation.cancel()
}

/**
 * 环绕声宽化：中/侧（M/S）处理，侧信号按 [SoundFxConfiguration.surround] 放大。
 * 只处理双声道 PCM；宽化系数 w = 1 + surround * 0.7，1 时与原声一致。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class StereoWidenerProcessor(
    private val state: SoundFxConfigurationState,
) : BaseAudioProcessor() {
    private companion object {
        const val BYPASS_EPSILON = 0.0001f
        const val MAX_SIDE_GAIN = 0.7f
    }

    private var channelCount = 0
    private var encoding = C.ENCODING_INVALID
    private var appliedConfiguration: SoundFxConfiguration? = null
    private var bypassed = true
    private var sideGain = 0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding !in setOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT)) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        channelCount = inputAudioFormat.channelCount
        encoding = inputAudioFormat.encoding
        appliedConfiguration = null
        bypassed = true
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val output = replaceOutputBuffer(inputBuffer.remaining()).order(ByteOrder.nativeOrder())
        inputBuffer.order(ByteOrder.nativeOrder())
        refreshConfiguration()
        // 仅双声道做 M/S 宽化，其余声道布局原样直通
        if (bypassed || channelCount != 2) {
            output.put(inputBuffer)
            output.flip()
            return
        }
        when (encoding) {
            C.ENCODING_PCM_FLOAT -> while (inputBuffer.remaining() >= 2 * Float.SIZE_BYTES) {
                val left = inputBuffer.float
                val right = inputBuffer.float
                val mid = (left + right) * 0.5f
                val side = (left - right) * 0.5f
                output.putFloat(mid + side * sideGain)
                output.putFloat(mid - side * sideGain)
            }
            C.ENCODING_PCM_16BIT -> while (inputBuffer.remaining() >= 2 * Short.SIZE_BYTES) {
                val left = inputBuffer.short / Short.MAX_VALUE.toFloat()
                val right = inputBuffer.short / Short.MAX_VALUE.toFloat()
                val mid = (left + right) * 0.5f
                val side = (left - right) * 0.5f
                output.putShort((mid + side * sideGain).coerceIn(-1f, 1f).let { (it * Short.MAX_VALUE).toInt() }.toShort())
                output.putShort((mid - side * sideGain).coerceIn(-1f, 1f).let { (it * Short.MAX_VALUE).toInt() }.toShort())
            }
        }
        output.flip()
    }

    override fun onReset() {
        channelCount = 0
        encoding = C.ENCODING_INVALID
        appliedConfiguration = null
        bypassed = true
        sideGain = 0f
    }

    private fun refreshConfiguration() {
        val configuration = state.snapshot()
        if (configuration == appliedConfiguration) return
        appliedConfiguration = configuration
        bypassed = !configuration.enabled || configuration.surround < BYPASS_EPSILON
        sideGain = 1f + configuration.surround * MAX_SIDE_GAIN
    }
}

/**
 * 重低音增强：90Hz 低架滤波器，[SoundFxConfiguration.bass] 0..1 映射 0..10dB。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class BassShelfProcessor(
    private val state: SoundFxConfigurationState,
) : BaseAudioProcessor() {
    private companion object {
        const val BYPASS_EPSILON = 0.0001f
        const val SHELF_FREQUENCY = 90.0
        const val MAX_GAIN_DB = 10f
    }

    private var sampleRate = 0
    private var channelCount = 0
    private var encoding = C.ENCODING_INVALID
    private var appliedConfiguration: SoundFxConfiguration? = null
    private var bypassed = true
    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f
    private var firstDelays = FloatArray(0)
    private var secondDelays = FloatArray(0)
    private var channelCursor = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding !in setOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT)) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        encoding = inputAudioFormat.encoding
        firstDelays = FloatArray(channelCount)
        secondDelays = FloatArray(channelCount)
        channelCursor = 0
        appliedConfiguration = null
        bypassed = true
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val output = replaceOutputBuffer(inputBuffer.remaining()).order(ByteOrder.nativeOrder())
        inputBuffer.order(ByteOrder.nativeOrder())
        refreshConfiguration()
        if (bypassed) {
            output.put(inputBuffer)
            output.flip()
            return
        }
        when (encoding) {
            C.ENCODING_PCM_FLOAT -> while (inputBuffer.remaining() >= Float.SIZE_BYTES) {
                output.putFloat(process(inputBuffer.float, channelCursor))
                channelCursor = (channelCursor + 1) % channelCount
            }
            C.ENCODING_PCM_16BIT -> while (inputBuffer.remaining() >= Short.SIZE_BYTES) {
                val sample = inputBuffer.short / Short.MAX_VALUE.toFloat()
                val rendered = (process(sample, channelCursor).coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
                output.putShort(rendered)
                channelCursor = (channelCursor + 1) % channelCount
            }
        }
        output.flip()
    }

    override fun onFlush() {
        firstDelays.fill(0f)
        secondDelays.fill(0f)
        channelCursor = 0
    }

    override fun onReset() {
        sampleRate = 0
        channelCount = 0
        encoding = C.ENCODING_INVALID
        appliedConfiguration = null
        bypassed = true
        firstDelays = FloatArray(0)
        secondDelays = FloatArray(0)
        channelCursor = 0
    }

    private fun refreshConfiguration() {
        val configuration = state.snapshot()
        if (configuration == appliedConfiguration) return
        appliedConfiguration = configuration
        bypassed = !configuration.enabled || configuration.bass < BYPASS_EPSILON
        if (bypassed || sampleRate <= 0) return
        val gainDb = configuration.bass * MAX_GAIN_DB
        val amplitude = 10.0.pow(gainDb / 40.0)
        val angularFrequency = 2.0 * PI * SHELF_FREQUENCY / sampleRate
        val cosine = cos(angularFrequency)
        val sine = sin(angularFrequency)
        // RBJ cookbook low-shelf，S=1 时 alpha = sin(ω0) * sqrt(A)
        val alpha = sine * sqrt(amplitude)
        val a0 = (amplitude + 1) + (amplitude - 1) * cosine + 2 * sqrt(amplitude) * alpha
        b0 = ((amplitude * ((amplitude + 1) - (amplitude - 1) * cosine + 2 * sqrt(amplitude) * alpha)) / a0).toFloat()
        b1 = ((2 * amplitude * ((amplitude - 1) - (amplitude + 1) * cosine)) / a0).toFloat()
        b2 = ((amplitude * ((amplitude + 1) - (amplitude - 1) * cosine - 2 * sqrt(amplitude) * alpha)) / a0).toFloat()
        a1 = ((-2 * ((amplitude - 1) + (amplitude + 1) * cosine)) / a0).toFloat()
        a2 = (((amplitude + 1) + (amplitude - 1) * cosine - 2 * sqrt(amplitude) * alpha) / a0).toFloat()
        firstDelays.fill(0f)
        secondDelays.fill(0f)
    }

    private fun process(input: Float, channel: Int): Float {
        val output = b0 * input + firstDelays[channel]
        firstDelays[channel] = b1 * input - a1 * output + secondDelays[channel]
        secondDelays[channel] = b2 * input - a2 * output
        return output
    }
}
