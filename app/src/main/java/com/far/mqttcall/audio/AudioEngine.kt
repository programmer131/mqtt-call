package com.far.mqttcall.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.LoudnessEnhancer
import android.os.Process
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val PLAYBACK_GAIN_MB = 3_000

internal fun applyInputGain(samples: ShortArray, gain: Float): ShortArray =
    ShortArray(samples.size) { index ->
        (samples[index] * gain).toInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
    }

class AudioBatcher(
    private val codec: OpusCodec,
    private val framesPerBatch: Int = DEFAULT_FRAMES_PER_BATCH,
) {
    private val frames = ArrayList<ByteArray>(framesPerBatch)

    init {
        require(framesPerBatch > 0) { "Audio batch must contain at least one frame" }
    }

    fun addFrame(frame: ShortArray): List<ByteArray>? {
        require(frame.size == FRAME_SAMPLES) { "Audio frame must contain 320 samples" }
        frames += codec.encode(frame)
        if (frames.size < framesPerBatch) return null
        return frames.toList().also { frames.clear() }
    }

    companion object {
        private const val DEFAULT_FRAMES_PER_BATCH = 10
        const val FRAME_SAMPLES = 320
        private const val FRAMES_PER_100MS = 5

        fun framesForInterval(audioPacketIntervalUnits: Int): Int =
            audioPacketIntervalUnits * FRAMES_PER_100MS
    }
}

interface AudioEngine {
    suspend fun startCapture(
        audioPacketIntervalUnits: Int,
        onBatch: suspend (List<ByteArray>) -> Unit,
    )

    suspend fun stopCapture()

    fun stopCaptureImmediately()

    suspend fun play(batch: AudioBatch)

    suspend fun stopPlayback()
}

class AndroidAudioEngine(
    private val context: Context,
    private val codecFactory: () -> OpusCodec = ::KopusCodec,
) : AudioEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var captureJob: Job? = null
    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null
    private var playbackCodec: OpusCodec? = null
    private var loudness: LoudnessEnhancer? = null

    override suspend fun startCapture(
        audioPacketIntervalUnits: Int,
        onBatch: suspend (List<ByteArray>) -> Unit,
    ) {
        stopCapture()
        captureJob = scope.launch(Dispatchers.IO) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val codec = codecFactory()
            val batcher = AudioBatcher(codec, AudioBatcher.framesForInterval(audioPacketIntervalUnits))
            val bufferSize = maxOf(
                AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                ),
                FRAME_SAMPLES * BYTES_PER_SAMPLE * 4,
            )
            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
            recorder = audioRecord
            var gainControl: AutomaticGainControl? = null
            try {
                check(audioRecord.state == AudioRecord.STATE_INITIALIZED) {
                    "AudioRecord failed to initialize"
                }
                // The voice-communication source captures quietly on some devices; level it up.
                if (AutomaticGainControl.isAvailable()) {
                    gainControl = runCatching {
                        AutomaticGainControl.create(audioRecord.audioSessionId)?.apply { enabled = true }
                    }.getOrNull()
                }
                audioRecord.startRecording()
                val pcm = ShortArray(FRAME_SAMPLES)
                while (currentCoroutineContext().isActive) {
                    val read = audioRecord.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                    if (read == FRAME_SAMPLES) {
                        val frame = pcm.copyOf()
                        val batch = batcher.addFrame(applyInputGain(frame, INPUT_GAIN))
                        if (batch != null) onBatch(batch)
                    }
                }
            } catch (_: CancellationException) {
                throw CancellationException()
            } finally {
                runCatching { audioRecord.stop() }
                gainControl?.release()
                audioRecord.release()
                recorder = null
                codec.close()
            }
        }
    }

    override suspend fun stopCapture() {
        captureJob?.cancel()
        runCatching { recorder?.stop() }
        captureJob?.cancelAndJoin()
        captureJob = null
        recorder = null
    }

    override fun stopCaptureImmediately() {
        captureJob?.cancel()
        runCatching { recorder?.stop() }
    }

    override suspend fun play(batch: AudioBatch) = withContext(Dispatchers.IO) {
        val audioTrack = ensureTrack()
        // Opus decoding is stateful across frames, so one decoder serves the whole stream.
        val codec = playbackCodec ?: codecFactory().also { playbackCodec = it }
        batch.frames.forEach { packet ->
            val pcm = codec.decode(packet)
            audioTrack.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
        }
    }

    override suspend fun stopPlayback() {
        withContext(Dispatchers.IO) {
            track?.let { audioTrack ->
                runCatching { audioTrack.pause() }
                runCatching { audioTrack.flush() }
                audioTrack.release()
            }
            track = null
            loudness?.release()
            loudness = null
            playbackCodec?.close()
            playbackCodec = null
        }
    }

    private fun ensureTrack(): AudioTrack {
        track?.let { return it }
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        // Voice-communication usage routes to the earpiece; PTT audio must always use the loudspeaker.
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        return AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuffer, FRAME_SAMPLES * BYTES_PER_SAMPLE * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also {
                check(it.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack failed to initialize" }
                // Pin to the built-in speaker even when a headset or Bluetooth device is attached.
                builtInSpeaker()?.let(it::setPreferredDevice)
                loudness = runCatching {
                    LoudnessEnhancer(it.audioSessionId).apply {
                        setTargetGain(PLAYBACK_GAIN_MB)
                        enabled = true
                    }
                }.getOrNull()
                it.play()
                track = it
            }
    }

    private fun builtInSpeaker(): AudioDeviceInfo? =
        context.getSystemService(AudioManager::class.java)
            ?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val INPUT_GAIN = 2.0f
        const val FRAME_SAMPLES = 320
        const val BYTES_PER_SAMPLE = 2
    }
}
