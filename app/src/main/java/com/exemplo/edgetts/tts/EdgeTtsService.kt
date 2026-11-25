package com.exemplo.edgetts.tts

import android.content.Context
import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import com.exemplo.edgetts.native.EdgeTtsNative
import com.exemplo.edgetts.protocol.EdgeTtsClient
import com.exemplo.edgetts.protocol.EdgeVoice
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

class EdgeTtsService : TextToSpeechService() {

    companion object {
        private const val PREFS_NAME = "edge_tts_prefs"
        private const val KEY_VOICE = "selected_voice"
        private const val KEY_VOICE_LIST = "selected_voices_list"
        private const val KEY_SINGLE_VOICE_MODE = "single_voice_mode"
        private const val KEY_DIALOGUE_VOICE_ENABLED = "dialogue_voice_enabled"
        private const val KEY_DIALOGUE_VOICE = "dialogue_voice"
        private const val KEY_RATE_PERCENT = "rate_percent"
        private const val KEY_PITCH_HZ = "pitch_hz"
        private const val KEY_LAST_DIAG = "last_diag_message"
        private const val KEY_CACHED_VOICES_JSON = "cached_voices_json"
        private const val DEFAULT_VOICE = "pt-BR-FranciscaNeural"
        private const val DEFAULT_DIALOGUE_VOICE = "pt-BR-AntonioNeural"
        private const val SAMPLE_RATE_HZ = 48000

        private const val MIN_CHUNK_CHARS_BEFORE_SPLIT = 140
        private const val TARGET_MAX_CHUNK_CHARS = 280

        // Cache leve em memória para trechos repetidos (máx ~5 MB e até 32 entradas)
        private const val MAX_CACHE_ENTRIES = 32
        private const val MAX_CACHE_TOTAL_BYTES = 5 * 1024 * 1024
        private const val MAX_CACHEABLE_TEXT_LEN = 320
    }

    private data class SpeechSegment(
        val text: String,
        val isDialogue: Boolean
    )

    private data class CacheKey(
        val voice: String,
        val rate: String,
        val pitch: String,
        val text: String
    )

    private val stopRequested = AtomicBoolean(false)
    private val prefetchExecutor = Executors.newSingleThreadExecutor()

    private val cacheLock = Any()
    private var cachedBytesTotal = 0
    private val pcmCache = object : LinkedHashMap<CacheKey, ByteArray>(MAX_CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, ByteArray>?): Boolean {
            val shouldEvict = size > MAX_CACHE_ENTRIES || (eldest != null && cachedBytesTotal > MAX_CACHE_TOTAL_BYTES)
            if (shouldEvict && eldest != null) {
                cachedBytesTotal = (cachedBytesTotal - eldest.value.size).coerceAtLeast(0)
            }
            return shouldEvict
        }
    }

    @Volatile
    private var lastDiagInMemory: String = ""

    @Volatile
    private var activeRequestId: String? = null

    @Volatile
    private var prefetchedRequestId: String? = null

    @Volatile
    private var prefetchFuture: Future<*>? = null

    private var currentLanguage = arrayOf("por", "BRA", "")

    override fun onGetLanguage(): Array<String> = currentLanguage

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val normalizedLang = (lang ?: "").lowercase(Locale.US)
        return when {
            normalizedLang.startsWith("por") || normalizedLang.startsWith("pt") -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            normalizedLang.startsWith("eng") || normalizedLang.startsWith("en") -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            normalizedLang.startsWith("spa") || normalizedLang.startsWith("es") -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            normalizedLang.startsWith("fra") || normalizedLang.startsWith("fr") -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            normalizedLang.startsWith("deu") || normalizedLang.startsWith("de") -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            normalizedLang.startsWith("ita") || normalizedLang.startsWith("it") -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            normalizedLang.startsWith("jpn") || normalizedLang.startsWith("ja") -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            normalizedLang.startsWith("zho") || normalizedLang.startsWith("zh") -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            normalizedLang.isNotBlank() -> TextToSpeech.LANG_AVAILABLE
            else -> TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val availability = onIsLanguageAvailable(lang, country, variant)
        if (availability != TextToSpeech.LANG_NOT_SUPPORTED) {
            currentLanguage = arrayOf(lang ?: "por", country ?: "BRA", variant ?: "")
        }
        return availability
    }

    override fun onGetVoices(): List<Voice> {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cachedJson = prefs.getString(KEY_CACHED_VOICES_JSON, null)
        val sourceList = parseCachedVoices(cachedJson).ifEmpty {
            EdgeTtsClient.DEFAULT_FALLBACK_VOICES
        }

        return sourceList.map { v ->
            val parts = v.locale.split("-")
            val locale = if (parts.size >= 2) Locale(parts[0], parts[1]) else Locale("pt", "BR")
            Voice(v.shortName, locale, Voice.QUALITY_HIGH, Voice.LATENCY_NORMAL, true, emptySet())
        }
    }

    private fun parseCachedVoices(rawJson: String?): List<EdgeVoice> {
        if (rawJson.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(rawJson)
            val list = ArrayList<EdgeVoice>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val shortName = obj.optString("shortName").trim()
                if (shortName.isEmpty()) continue
                val friendlyName = obj.optString("friendlyName").ifBlank { shortName }.trim()
                val locale = obj.optString("locale").trim()
                val gender = obj.optString("gender").trim()
                list.add(EdgeVoice(shortName, friendlyName, locale, gender))
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun onStop() {
        stopRequested.set(true)
        cancelPrefetch()
        activeRequestId?.let { EdgeTtsNative.nativeStop(it) }
    }

    override fun onDestroy() {
        stopRequested.set(true)
        cancelPrefetch()
        activeRequestId?.let { EdgeTtsNative.nativeStop(it) }
        prefetchExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun getCachedPcm(key: CacheKey): ByteArray? {
        if (key.text.length > MAX_CACHEABLE_TEXT_LEN) return null
        synchronized(cacheLock) {
            return pcmCache[key]
        }
    }

    private fun putCachedPcm(key: CacheKey, data: ByteArray) {
        if (data.isEmpty() || key.text.length > MAX_CACHEABLE_TEXT_LEN || data.size > MAX_CACHE_TOTAL_BYTES / 2) {
            return
        }
        synchronized(cacheLock) {
            val existing = pcmCache.remove(key)
            if (existing != null) {
                cachedBytesTotal = (cachedBytesTotal - existing.size).coerceAtLeast(0)
            }
            pcmCache[key] = data
            cachedBytesTotal += data.size
            while (cachedBytesTotal > MAX_CACHE_TOTAL_BYTES && pcmCache.size > 1) {
                val iterator = pcmCache.entries.iterator()
                if (iterator.hasNext()) {
                    val eldest = iterator.next()
                    cachedBytesTotal = (cachedBytesTotal - eldest.value.size).coerceAtLeast(0)
                    iterator.remove()
                } else {
                    break
                }
            }
        }
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return

        stopRequested.set(false)
        cancelPrefetch()

        val text = request.charSequenceText?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            saveDiag("Texto vazio", persistNow = true)
            callback.done()
            return
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val configuredVoice = prefs.getString(KEY_VOICE, DEFAULT_VOICE)?.trim().orEmpty()
            .ifEmpty { DEFAULT_VOICE }
        val primaryVoice = resolveVoiceForRequest(request, configuredVoice)

        val dialogueVoiceEnabled = prefs.getBoolean(KEY_DIALOGUE_VOICE_ENABLED, false)
        val dialogueVoice = prefs.getString(KEY_DIALOGUE_VOICE, DEFAULT_DIALOGUE_VOICE)?.trim().orEmpty()
            .ifEmpty { DEFAULT_DIALOGUE_VOICE }

        val segments = if (dialogueVoiceEnabled && dialogueVoice.isNotBlank()) {
            buildDialogueAwareSegments(text)
        } else {
            buildBalancedChunks(text).map { SpeechSegment(it, isDialogue = false) }
        }

        if (segments.isEmpty()) {
            saveDiag("Texto vazio", persistNow = true)
            callback.done()
            return
        }

        val savedList = prefs.getString(KEY_VOICE_LIST, primaryVoice).orEmpty()
            .split("|")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        val singleVoiceMode = prefs.getBoolean(KEY_SINGLE_VOICE_MODE, savedList.size <= 1)

        val voiceRotation = if (singleVoiceMode || savedList.size <= 1) {
            listOf(primaryVoice)
        } else {
            savedList
        }

        val ratePercent = prefs.getInt(KEY_RATE_PERCENT, (request.speechRate - 100).coerceIn(-50, 100))
        val pitchHz = prefs.getInt(KEY_PITCH_HZ, (request.pitch - 100).coerceIn(-50, 50))

        val rateStr = if (ratePercent >= 0) "+${ratePercent}%" else "${ratePercent}%"
        val pitchStr = if (pitchHz >= 0) "+${pitchHz}Hz" else "${pitchHz}Hz"
        val volumeStr = "+0%"

        var callbackStarted = false
        var totalBytesAll = 0
        var narrationIndex = 0

        fun resolveSegmentVoice(seg: SpeechSegment): String {
            if (seg.isDialogue && dialogueVoiceEnabled && dialogueVoice.isNotBlank()) {
                return dialogueVoice
            }
            val voice = if (singleVoiceMode || voiceRotation.size <= 1) {
                primaryVoice
            } else {
                voiceRotation[narrationIndex % voiceRotation.size]
            }
            narrationIndex++
            return voice
        }

        val firstVoice = resolveSegmentVoice(segments[0])
        val firstCacheKey = CacheKey(firstVoice, rateStr, pitchStr, segments[0].text)
        val firstCached = getCachedPcm(firstCacheKey)

        var currentVoice = firstVoice
        var currentReqId = UUID.randomUUID().toString()
        var currentCachedPcm: ByteArray? = firstCached

        if (currentCachedPcm == null) {
            activeRequestId = currentReqId
            val startErr = EdgeTtsNative.nativeBeginSynthesis(
                requestId = currentReqId,
                text = segments[0].text,
                voice = currentVoice,
                rate = rateStr,
                volume = volumeStr,
                pitch = pitchStr
            )
            if (!startErr.isNullOrBlank()) {
                saveDiag("Erro início: $startErr", persistNow = true)
                callback.error()
                return
            }
        }

        try {
            for (index in segments.indices) {
                if (stopRequested.get()) break

                val segText = segments[index].text
                val reqId = currentReqId
                val activeVoiceForChunk = currentVoice
                val cachedForThisChunk = currentCachedPcm

                if (cachedForThisChunk == null) {
                    activeRequestId = reqId
                }

                saveDiag(
                    "A sintetizar (${index + 1}/${segments.size}, $activeVoiceForChunk)...",
                    persistNow = (index == 0)
                )

                if (index + 1 < segments.size) {
                    val nextSeg = segments[index + 1]
                    val nextVoice = resolveSegmentVoice(nextSeg)
                    val nextKey = CacheKey(nextVoice, rateStr, pitchStr, nextSeg.text)
                    val nextCached = getCachedPcm(nextKey)

                    if (nextCached != null) {
                        prefetchedRequestId = null
                        prefetchFuture = null
                        currentCachedPcm = nextCached
                    } else {
                        currentCachedPcm = null
                        val nextReqId = UUID.randomUUID().toString()
                        prefetchedRequestId = nextReqId
                        prefetchFuture = prefetchExecutor.submit {
                            if (!stopRequested.get()) {
                                EdgeTtsNative.nativeBeginSynthesis(
                                    requestId = nextReqId,
                                    text = nextSeg.text,
                                    voice = nextVoice,
                                    rate = rateStr,
                                    volume = volumeStr,
                                    pitch = pitchStr
                                )
                            }
                        }
                        currentReqId = nextReqId
                    }
                    currentVoice = nextVoice
                } else {
                    prefetchedRequestId = null
                    prefetchFuture = null
                    currentCachedPcm = null
                }

                if (!callbackStarted) {
                    callback.start(SAMPLE_RATE_HZ, AudioFormat.ENCODING_PCM_16BIT, 1)
                    callbackStarted = true
                }

                val chunkMax = callback.maxBufferSize.coerceAtLeast(8192)
                var segmentBytes = 0

                if (cachedForThisChunk != null) {
                    var offset = 0
                    while (offset < cachedForThisChunk.size && !stopRequested.get()) {
                        val toWrite = (cachedForThisChunk.size - offset).coerceAtMost(chunkMax)
                        segmentBytes += toWrite
                        totalBytesAll += toWrite
                        val status = callback.audioAvailable(cachedForThisChunk, offset, toWrite)
                        if (status != TextToSpeech.SUCCESS) {
                            stopRequested.set(true)
                            break
                        }
                        offset += toWrite
                    }
                } else {
                    val shouldCache = segText.length <= MAX_CACHEABLE_TEXT_LEN
                    val collector = if (shouldCache) ByteArrayOutputStream() else null

                    while (!stopRequested.get()) {
                        val pcmChunk = EdgeTtsNative.nativeReadPcmChunk(reqId, chunkMax) ?: break
                        if (pcmChunk.isEmpty()) continue
                        segmentBytes += pcmChunk.size
                        totalBytesAll += pcmChunk.size
                        collector?.write(pcmChunk, 0, pcmChunk.size)
                        val status = callback.audioAvailable(pcmChunk, 0, pcmChunk.size)
                        if (status != TextToSpeech.SUCCESS) {
                            stopRequested.set(true)
                            break
                        }
                    }

                    EdgeTtsNative.nativeStop(reqId)

                    val nativeErr = EdgeTtsNative.nativeGetLastError(reqId)
                    if (!nativeErr.isNullOrBlank() && segmentBytes == 0 && totalBytesAll == 0) {
                        cancelPrefetch()
                        saveDiag("Erro: $nativeErr", persistNow = true)
                        callback.error()
                        return
                    }

                    if (nativeErr.isNullOrBlank() && collector != null && collector.size() > 0 && !stopRequested.get()) {
                        putCachedPcm(
                            CacheKey(activeVoiceForChunk, rateStr, pitchStr, segText),
                            collector.toByteArray()
                        )
                    }
                }
            }

            if (!callbackStarted) {
                callback.start(SAMPLE_RATE_HZ, AudioFormat.ENCODING_PCM_16BIT, 1)
            }
            saveDiag("OK ($totalBytesAll bytes PCM)", persistNow = true)
            callback.done()
        } catch (e: Exception) {
            cancelPrefetch()
            saveDiag("Erro: ${e.message ?: e.javaClass.simpleName}", persistNow = true)
            try {
                callback.error()
            } catch (_: Exception) {
            }
        } finally {
            cancelPrefetch()
            activeRequestId?.let { EdgeTtsNative.nativeStop(it) }
            activeRequestId = null
        }
    }

    /**
     * Separa falas (travessão ou aspas) de trechos narrativos dentro de cada parágrafo,
     * agrupando blocos consecutivos da mesma categoria para manter a síntese rápida.
     */
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

        // Agrupa segmentos consecutivos com o mesmo tipo (diálogo + diálogo ou narração + narração)
        // até o limite TARGET_MAX_CHUNK_CHARS para evitar micro-requisições lentas.
        val merged = ArrayList<SpeechSegment>()
        for (seg in rawSegments) {
            val cleanText = seg.text.trim()
            if (cleanText.isEmpty()) continue

            val last = merged.lastOrNull()
            if (last != null &&
                last.isDialogue == seg.isDialogue &&
                (last.text.length + 1 + cleanText.length) <= TARGET_MAX_CHUNK_CHARS
            ) {
                merged[merged.lastIndex] = SpeechSegment(
                    text = "${last.text} $cleanText",
                    isDialogue = last.isDialogue
                )
            } else if (cleanText.length > TARGET_MAX_CHUNK_CHARS) {
                for (subChunk in buildBalancedChunks(cleanText)) {
                    merged.add(SpeechSegment(subChunk, isDialogue = seg.isDialogue))
                }
            } else {
                merged.add(SpeechSegment(cleanText, isDialogue = seg.isDialogue))
            }
        }

        return merged
    }

    private fun extractSegmentsFromLine(line: String, out: MutableList<SpeechSegment>) {
        // 1. Caso clássico com travessão no início da linha (ex.: "— Olá! — disse ele. — Tudo bem?")
        if (startsWithDash(line)) {
            val parts = splitByDashMarkers(line)
            if (parts.isNotEmpty()) {
                for (i in parts.indices) {
                    val piece = parts[i].trim()
                    if (piece.isEmpty()) continue
                    // Em diálogos com travessão: índice par (0, 2, 4...) é fala; índice ímpar (1, 3...) é incisa narrativa
                    val isSpeech = (i % 2 == 0)
                    out.add(SpeechSegment(piece, isDialogue = isSpeech))
                }
                return
            }
        }

        // 2. Caso de aspas ou fala após narração na mesma linha (ex.: Ela disse: "Oi" e saiu.)
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

        // 3. Linha puramente narrativa
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

    private fun cancelPrefetch() {
        prefetchFuture?.cancel(true)
        prefetchFuture = null
        prefetchedRequestId?.let { EdgeTtsNative.nativeStop(it) }
        prefetchedRequestId = null
    }

    private fun buildBalancedChunks(text: String): List<String> {
        val normalized = text.replace("\r\n", "\n").trim()
        if (normalized.length <= TARGET_MAX_CHUNK_CHARS) {
            return listOf(normalized)
        }

        val result = ArrayList<String>()
        val current = StringBuilder()
        var i = 0

        while (i < normalized.length) {
            val ch = normalized[i]
            current.append(ch)

            val isSentenceEnd = ch == '.' || ch == '!' || ch == '?' || ch == '\n'
            val nextIsSpaceOrEnd = (i + 1 >= normalized.length) || normalized[i + 1].isWhitespace()

            if (isSentenceEnd && nextIsSpaceOrEnd) {
                val len = current.length
                if (len >= MIN_CHUNK_CHARS_BEFORE_SPLIT || len >= TARGET_MAX_CHUNK_CHARS) {
                    val piece = current.toString().trim()
                    if (piece.isNotEmpty()) {
                        result.add(piece)
                    }
                    current.setLength(0)
                }
            } else if (current.length >= TARGET_MAX_CHUNK_CHARS && ch.isWhitespace()) {
                val piece = current.toString().trim()
                if (piece.isNotEmpty()) {
                    result.add(piece)
                }
                current.setLength(0)
            }

            i++
        }

        val remainder = current.toString().trim()
        if (remainder.isNotEmpty()) {
            if (remainder.length < 60 && result.isNotEmpty()) {
                val lastIdx = result.lastIndex
                result[lastIdx] = "${result[lastIdx]} $remainder"
            } else {
                result.add(remainder)
            }
        }

        return if (result.isEmpty()) listOf(normalized) else result
    }

    private fun saveDiag(message: String, persistNow: Boolean = false) {
        lastDiagInMemory = message
        if (persistNow) {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_DIAG, message)
                .apply()
        }
    }

    private fun resolveVoiceForRequest(request: SynthesisRequest, configuredVoice: String): String {
        if (configuredVoice.isNotBlank()) {
            return configuredVoice
        }

        val lang = (request.language ?: "").lowercase(Locale.US)
        val country = (request.country ?: "").uppercase(Locale.US)
        return when {
            lang.startsWith("por") || lang.startsWith("pt") ->
                if (country == "PRT" || country == "PT") "pt-PT-RaquelNeural" else "pt-BR-FranciscaNeural"
            lang.startsWith("eng") || lang.startsWith("en") ->
                if (country == "GBR" || country == "GB") "en-GB-SoniaNeural" else "en-US-JennyNeural"
            lang.startsWith("spa") || lang.startsWith("es") -> "es-ES-ElviraNeural"
            lang.startsWith("fra") || lang.startsWith("fr") -> "fr-FR-DeniseNeural"
            lang.startsWith("deu") || lang.startsWith("de") -> "de-DE-KatjaNeural"
            lang.startsWith("ita") || lang.startsWith("it") -> "it-IT-ElsaNeural"
            lang.startsWith("jpn") || lang.startsWith("ja") -> "ja-JP-NanamiNeural"
            lang.startsWith("zho") || lang.startsWith("zh") -> "zh-CN-XiaoxiaoNeural"
            else -> DEFAULT_VOICE
        }
    }
}
