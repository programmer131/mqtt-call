package com.far.mqttcall.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
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

class AudioBatcher(private val codec: OpusCodec) {
    private val frames = ArrayList<ByteArray>(FRAMES_PER_BATCH)

    fun addFrame(frame: ShortArray): List<ByteArray>? {
        require(frame.size == FRAME_SAMPLES) { "Audio frame must contain 320 samples" }
        frames += codec.encode(frame)
        if (frames.size < FRAMES_PER_BATCH) return null
        return frames.toList().also { frames.clear() }
    }

    private companion object {
        const val FRAME_SAMPLES = 320
        const val FRAMES_PER_BATCH = 10
    }
}

interface AudioEngine {
    suspend fun startCapture(onBatch: suspend (List<ByteArray>) -> Unit)

    suspend fun stopCapture()

    suspend fun play(batch: AudioBatch)

    suspend fun stopPlayback()
}

class AndroidAudioEngine(
    private val codecFactory: () -> OpusCodec = ::KopusCodec,
) : AudioEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var captureJob: Job? = null
    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null

    override suspend fun startCapture(onBatch: suspend (List<ByteArray>) -> Unit) {
        stopCapture()
        captureJob = scope.launch(Dispatchers.IO) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val codec = codecFactory()
            val batcher = AudioBatcher(codec)
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
            try {
                check(audioRecord.state == AudioRecord.STATE_INITIALIZED) {
                    "AudioRecord failed to initialize"
                }
                audioRecord.startRecording()
                val pcm = ShortArray(FRAME_SAMPLES)
                while (currentCoroutineContext().isActive) {
                    val read = audioRecord.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                    if (read == FRAME_SAMPLES) {
                        val batch = batcher.addFrame(pcm.copyOf())
                        if (batch != null) onBatch(batch)
                    }
                }
            } catch (_: CancellationException) {
                throw CancellationException()
            } finally {
                runCatching { audioRecord.stop() }
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

    override suspend fun play(batch: AudioBatch) = withContext(Dispatchers.IO) {
        val audioTrack = ensureTrack()
        val codec = codecFactory()
        try {
            batch.frames.forEach { packet ->
                val pcm = codec.decode(packet)
                audioTrack.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
            }
        } finally {
            codec.close()
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
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
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
                it.play()
                track = it
            }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 320
        const val BYTES_PER_SAMPLE = 2
    }
}
