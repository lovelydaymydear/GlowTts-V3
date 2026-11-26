package com.exemplo.edgetts.reader

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.exemplo.edgetts.native.EdgeTtsNative
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class DirectEdgeAudioPlayer(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "edge_tts_prefs"
        private const val KEY_VOICE = "selected_voice"
        private const val KEY_RATE_PERCENT = "rate_percent"
        private const val KEY_PITCH_HZ = "pitch_hz"
        private const val DEFAULT_VOICE = "pt-BR-FranciscaNeural"
        private const val SAMPLE_RATE_HZ = 48000
        private const val MAX_CHUNK_CHARS = 280
    }

    private val playing = AtomicBoolean(false)
    private val stopFlag = AtomicBoolean(false)

    @Volatile
    private var currentRequestId: String? = null

    fun playText(
        fullText: String,
        onStatus: (String) -> Unit,
        onFinished: () -> Unit,
        onError: (String) -> Unit
    ) {
        stop()

        val chunks = splitIntoChunks(fullText.trim())
        if (chunks.isEmpty()) {
            onError("nenhum texto para reproduzir")
            return
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val voice = prefs.getString(KEY_VOICE, DEFAULT_VOICE)?.trim().orEmpty().ifEmpty { DEFAULT_VOICE }
        val ratePercent = prefs.getInt(KEY_RATE_PERCENT, 0).coerceIn(-50, 100)
        val pitchHz = prefs.getInt(KEY_PITCH_HZ, 0).coerceIn(-50, 50)

        val rateStr = if (ratePercent >= 0) "+${ratePercent}%" else "${ratePercent}%"
        val pitchStr = if (pitchHz >= 0) "+${pitchHz}Hz" else "${pitchHz}Hz"
        val volumeStr = "+0%"

        stopFlag.set(false)
        playing.set(true)

        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(8192)

        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE_HZ)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack.play()

        Thread {
            try {
                for ((index, chunk) in chunks.withIndex()) {
                    if (stopFlag.get()) break

                    onStatus("a ler trecho ${index + 1}/${chunks.size} ($voice)...")
                    val reqId = UUID.randomUUID().toString()
                    currentRequestId = reqId

                    val startErr = EdgeTtsNative.nativeBeginSynthesis(
                        requestId = reqId,
                        text = chunk,
                        voice = voice,
                        rate = rateStr,
                        volume = volumeStr,
                        pitch = pitchStr
                    )
                    if (!startErr.isNullOrBlank()) {
                        onError("erro ao iniciar síntese: $startErr")
                        break
                    }

                    var anyBytes = false
                    while (!stopFlag.get()) {
                        val pcm = EdgeTtsNative.nativeReadPcmChunk(reqId, minBuf) ?: break
                        if (pcm.isEmpty()) continue
                        anyBytes = true
                        audioTrack.write(pcm, 0, pcm.size)
                    }

                    EdgeTtsNative.nativeStop(reqId)
                    currentRequestId = null

                    val nativeErr = EdgeTtsNative.nativeGetLastError(reqId)
                    if (!nativeErr.isNullOrBlank() && !anyBytes && !stopFlag.get()) {
                        onError("erro na síntese: $nativeErr")
                        break
                    }
                }
            } catch (e: Exception) {
                if (!stopFlag.get()) {
                    onError(e.message ?: "erro durante a reprodução")
                }
            } finally {
                currentRequestId?.let { EdgeTtsNative.nativeStop(it) }
                currentRequestId = null
                try {
                    audioTrack.pause()
                    audioTrack.flush()
                    audioTrack.release()
                } catch (_: Exception) {
                }
                playing.set(false)
                onFinished()
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        stopFlag.set(true)
        currentRequestId?.let { EdgeTtsNative.nativeStop(it) }
        currentRequestId = null
        playing.set(false)
    }

    private fun splitIntoChunks(text: String): List<String> {
        if (text.length <= MAX_CHUNK_CHARS) return listOf(text)

        val result = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0

        while (i < text.length) {
            val ch = text[i]
            sb.append(ch)
            val endOfSentence = ch == '.' || ch == '!' || ch == '?' || ch == '\n'
            val nextSpaceOrEnd = (i + 1 >= text.length) || text[i + 1].isWhitespace()

            if ((endOfSentence && nextSpaceOrEnd && sb.length >= 120) || (sb.length >= MAX_CHUNK_CHARS && ch.isWhitespace())) {
                val part = sb.toString().trim()
                if (part.isNotEmpty()) result.add(part)
                sb.setLength(0)
            }
            i++
        }

        val tail = sb.toString().trim()
        if (tail.isNotEmpty()) result.add(tail)
        return if (result.isEmpty()) listOf(text) else result
    }
}
