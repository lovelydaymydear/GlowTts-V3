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
        private const val KEY_DIALOGUE_VOICE_ENABLED = "dialogue_voice_enabled"
        private const val KEY_DIALOGUE_VOICE = "dialogue_voice"
        private const val KEY_RATE_PERCENT = "rate_percent"
        private const val KEY_PITCH_HZ = "pitch_hz"
        private const val DEFAULT_VOICE = "pt-BR-FranciscaNeural"
        private const val DEFAULT_DIALOGUE_VOICE = "pt-BR-AntonioNeural"
        private const val SAMPLE_RATE_HZ = 48000
        private const val MIN_CHUNK_CHARS_BEFORE_SPLIT = 120
        private const val MAX_CHUNK_CHARS = 280
    }

    private data class SpeechSegment(
        val text: String,
        val isDialogue: Boolean
    )

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

        val cleanFullText = fullText.trim()
        if (cleanFullText.isEmpty()) {
            onError("nenhum texto para reproduzir")
            return
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val primaryVoice = prefs.getString(KEY_VOICE, DEFAULT_VOICE)?.trim().orEmpty()
            .ifEmpty { DEFAULT_VOICE }
        val dialogueVoiceEnabled = prefs.getBoolean(KEY_DIALOGUE_VOICE_ENABLED, false)
        val dialogueVoice = prefs.getString(KEY_DIALOGUE_VOICE, DEFAULT_DIALOGUE_VOICE)?.trim().orEmpty()
            .ifEmpty { DEFAULT_DIALOGUE_VOICE }

        val ratePercent = prefs.getInt(KEY_RATE_PERCENT, 0).coerceIn(-50, 100)
        val pitchHz = prefs.getInt(KEY_PITCH_HZ, 0).coerceIn(-50, 50)

        val rateStr = if (ratePercent >= 0) "+${ratePercent}%" else "${ratePercent}%"
        val pitchStr = if (pitchHz >= 0) "+${pitchHz}Hz" else "${pitchHz}Hz"
        val volumeStr = "+0%"

        val segments = if (dialogueVoiceEnabled && dialogueVoice.isNotBlank()) {
            buildDialogueAwareSegments(cleanFullText)
        } else {
            splitIntoChunks(cleanFullText).map { SpeechSegment(it, isDialogue = false) }
        }

        if (segments.isEmpty()) {
            onError("nenhum texto para reproduzir")
            return
        }

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
                for ((index, segment) in segments.withIndex()) {
                    if (stopFlag.get()) break

                    val voiceForSegment = if (segment.isDialogue && dialogueVoiceEnabled && dialogueVoice.isNotBlank()) {
                        dialogueVoice
                    } else {
                        primaryVoice
                    }

                    val modeLabel = if (segment.isDialogue && dialogueVoiceEnabled) "diálogo" else "narração"
                    onStatus("a ler trecho ${index + 1}/${segments.size} ($modeLabel • $voiceForSegment)...")

                    val reqId = UUID.randomUUID().toString()
                    currentRequestId = reqId

                    val startErr = EdgeTtsNative.nativeBeginSynthesis(
                        requestId = reqId,
                        text = segment.text,
                        voice = voiceForSegment,
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

    private fun buildDialogueAwareSegments(text: String): List<SpeechSegment> {
        val normalized = text.replace("\r\n", "\n").trim()
        if (normalized.isEmpty()) return emptyList()

        val rawSegments = ArrayList<SpeechSegment>()
        val paragraphs = normalized.split("\n")

        for (rawParagraph in paragraphs) {
            val line = rawParagraph.trim()
            if (line.isEmpty()) continue
            extractSegmentsFromLine(line, rawSegments)
        }

        if (rawSegments.isEmpty()) {
            return listOf(SpeechSegment(normalized, isDialogue = false))
        }

        val merged = ArrayList<SpeechSegment>()
        for (seg in rawSegments) {
            val cleanText = seg.text.trim()
            if (cleanText.isEmpty()) continue

            val last = merged.lastOrNull()
            if (last != null &&
                last.isDialogue == seg.isDialogue &&
                (last.text.length + 1 + cleanText.length) <= MAX_CHUNK_CHARS
            ) {
                merged[merged.lastIndex] = SpeechSegment(
                    text = "${last.text} $cleanText",
                    isDialogue = last.isDialogue
                )
            } else if (cleanText.length > MAX_CHUNK_CHARS) {
                for (subChunk in splitIntoChunks(cleanText)) {
                    merged.add(SpeechSegment(subChunk, isDialogue = seg.isDialogue))
                }
            } else {
                merged.add(SpeechSegment(cleanText, isDialogue = seg.isDialogue))
            }
        }

        return merged
    }

    private fun extractSegmentsFromLine(line: String, out: MutableList<SpeechSegment>) {
        if (startsWithDash(line)) {
            val parts = splitByDashMarkers(line)
            if (parts.isNotEmpty()) {
                for (i in parts.indices) {
                    val piece = parts[i].trim()
                    if (piece.isEmpty()) continue
                    val isSpeech = (i % 2 == 0)
                    out.add(SpeechSegment(piece, isDialogue = isSpeech))
                }
                return
            }
        }

        val quoteRegex = Regex("""(["“«][^"”»]+["”»])""")
        var cursor = 0
        var matchedAny = false

        for (match in quoteRegex.findAll(line)) {
            matchedAny = true
            val before = line.substring(cursor, match.range.first).trim()
            if (before.isNotEmpty()) {
                out.add(SpeechSegment(before, isDialogue = false))
            }
            val speech = match.value.trim()
            if (speech.isNotEmpty()) {
                out.add(SpeechSegment(speech, isDialogue = true))
            }
            cursor = match.range.last + 1
        }

        if (matchedAny) {
            val after = line.substring(cursor).trim()
            if (after.isNotEmpty()) {
                out.add(SpeechSegment(after, isDialogue = false))
            }
            return
        }

        out.add(SpeechSegment(line, isDialogue = false))
    }

    private fun startsWithDash(text: String): Boolean {
        val t = text.trimStart()
        return t.startsWith("—") || t.startsWith("–") || t.startsWith("- ")
    }

    private fun splitByDashMarkers(line: String): List<String> {
        val result = ArrayList<String>()
        val current = StringBuilder()
        var i = 0

        while (i < line.length) {
            val ch = line[i]
            val isLongDash = (ch == '—' || ch == '–')
            val isHyphenMarker = (ch == '-' && i + 1 < line.length && line[i + 1] == ' ' &&
                (i == 0 || line[i - 1].isWhitespace()))

            if ((isLongDash || isHyphenMarker) && current.isNotBlank()) {
                result.add(current.toString().trim())
                current.setLength(0)
            }

            current.append(ch)
            i++
        }

        if (current.isNotBlank()) {
            result.add(current.toString().trim())
        }
        return result
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

            if ((endOfSentence && nextSpaceOrEnd && sb.length >= MIN_CHUNK_CHARS_BEFORE_SPLIT) ||
                (sb.length >= MAX_CHUNK_CHARS && ch.isWhitespace())
            ) {
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
