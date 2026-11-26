package com.exemplo.edgetts.reader

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.util.Locale

object ReaderContentLoader {

    private const val TARGET_CHAPTER_CHARS = 3500

    private val noiseBlockTags = listOf(
        "script", "style", "noscript", "svg", "canvas", "iframe",
        "header", "footer", "nav", "aside", "form"
    )

    private val cookieAndChromePhrases = listOf(
        "cookie", "cookies", "política de privacidade", "politica de privacidade",
        "termos de uso", "termos e condições", "termos e condicoes",
        "gerenciar consentimento", "preferências de cookies", "preferencias de cookies",
        "aceitar todos", "rejeitar todos", "configurações de cookies", "configuracoes de cookies",
        "todos os direitos reservados", "all rights reserved",
        "assinar newsletter", "newsletter", "inscreva-se", "cadastre-se",
        "compartilhar no", "seguir no", "pular para o conteúdo", "pular para o conteudo"
    )

    private val standaloneUiLabels = setOf(
        "menu", "fechar", "entrar", "login", "sair", "buscar", "pesquisar",
        "compartilhar", "comentar", "comentários", "comentarios",
        "próximo", "proximo", "anterior", "voltar", "topo", "início", "inicio",
        "publicidade", "anúncio", "anuncio", "patrocinado", "ler mais"
    )

    private val chapterHeadingRegex = Regex(
        """(?i)^((cap[ií]tulo|cap\.?|parte|se[cç][aã]o|ato|cena|epis[oó]dio)\s+([0-9]+|[ivxlcdm]+|um|dois|tr[eê]s|quatro|cinco|seis|sete|oito|nove|dez)(\s*[:.\-–—].*)?|pr[oó]logo|ep[ií]logo|introdu[cç][aã]o|conclus[aã]o)(\s*[:.\-–—].*)?$"""
    )

    // Mantém compatibilidade caso algum ponto chame texto direto
    fun loadTextFromUri(context: Context, uri: Uri): Result<String> {
        return loadBookFromUri(context, uri).map { it.combinedText() }
    }

    fun loadTextFromUrl(rawUrl: String): Result<String> {
        return loadBookFromUrl(rawUrl).map { it.combinedText() }
    }

    fun loadBookFromUri(context: Context, uri: Uri): Result<BookDocument> {
        return try {
            val stream = context.contentResolver.openInputStream(uri)
                ?: return Result.failure(IllegalStateException("Não foi possível abrir o ficheiro."))

            val rawText = stream.use { input ->
                BufferedReader(InputStreamReader(input, Charset.forName("UTF-8")))
                    .readText()
                    .replace("\r\n", "\n")
                    .replace("\r", "\n")
                    .trim()
            }

            if (rawText.isEmpty()) {
                return Result.failure(IllegalStateException("O ficheiro TXT está vazio."))
            }

            val fileName = resolveDisplayName(context, uri) ?: "Ficheiro TXT"
            val baseTitle = fileName
                .substringBeforeLast(".")
                .replace("_", " ")
                .replace("-", " ")
                .trim()
                .ifEmpty { "Livro TXT" }

            val extractedAuthor = extractAuthorFromHeaderLines(rawText)
            val chapters = splitPlainTextIntoChapters(rawText, fallbackPrefix = "Parte")

            Result.success(
                BookDocument(
                    title = baseTitle,
                    author = extractedAuthor,
                    coverUrl = null,
                    sourceType = BookSourceType.TXT,
                    sourceLabel = fileName,
                    chapters = chapters
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun loadBookFromUrl(rawUrl: String): Result<BookDocument> {
        return try {
            val cleanedUrl = rawUrl.trim()
            if (cleanedUrl.isEmpty()) {
                return Result.failure(IllegalArgumentException("Insira um link válido."))
            }

            val finalUrl = if (
                cleanedUrl.startsWith("http://", ignoreCase = true) ||
                cleanedUrl.startsWith("https://", ignoreCase = true)
            ) {
                cleanedUrl
            } else {
                "https://$cleanedUrl"
            }

            val conn = (URL(finalUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12000
                readTimeout = 15000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile)")
            }

            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    return Result.failure(IllegalStateException("Erro ao carregar link (HTTP $code)."))
                }

                val effectiveUrl = conn.url?.toString() ?: finalUrl
                val body = conn.inputStream.use { input ->
                    BufferedReader(InputStreamReader(input, Charset.forName("UTF-8"))).readText()
                }

                val doc = parseHtmlIntoBook(
                    rawHtml = body,
                    pageUrl = effectiveUrl,
                    sourceLabel = cleanedUrl
                )

                if (doc.chapters.isEmpty() || doc.combinedText().isBlank()) {
                    Result.failure(IllegalStateException("Não foi encontrado texto legível no link."))
                } else {
                    Result.success(doc)
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseHtmlIntoBook(
        rawHtml: String,
        pageUrl: String,
        sourceLabel: String
    ): BookDocument {
        if (!rawHtml.contains("<")) {
            val cleaned = cleanPlainLines(rawHtml)
            val title = cleaned.lineSequence().firstOrNull()?.take(80)?.ifBlank { sourceLabel } ?: sourceLabel
            return BookDocument(
                title = title,
                author = extractAuthorFromHeaderLines(cleaned),
                coverUrl = null,
                sourceType = BookSourceType.WEB_LINK,
                sourceLabel = sourceLabel,
                chapters = splitPlainTextIntoChapters(cleaned, fallbackPrefix = "Secção")
            )
        }

        val focusHtml = extractMainFocusHtml(rawHtml)
        val coverUrl = extractCoverUrl(rawHtml, focusHtml, pageUrl)
        val title = extractPageTitle(rawHtml, focusHtml, pageUrl)
        val author = extractWebAuthor(rawHtml, focusHtml)

        val chaptersFromHtml = extractChaptersFromHtml(focusHtml, fallbackTitle = title)
        val finalChapters = if (chaptersFromHtml.size > 1) {
            chaptersFromHtml
        } else {
            val plain = htmlToCleanPlainText(focusHtml)
            splitPlainTextIntoChapters(plain, fallbackPrefix = "Capítulo")
        }

        return BookDocument(
            title = title,
            author = author,
            coverUrl = coverUrl,
            sourceType = BookSourceType.WEB_LINK,
            sourceLabel = sourceLabel,
            chapters = finalChapters
        )
    }

    private fun extractPageTitle(rawHtml: String, focusHtml: String, pageUrl: String): String {
        val h1Match = Regex("(?is)<h1\\b[^>]*>(.*?)</h1\\s*>").find(focusHtml)
            ?: Regex("(?is)<h1\\b[^>]*>(.*?)</h1\\s*>").find(rawHtml)
        if (h1Match != null) {
            val h1Text = decodeEntities(h1Match.groupValues[1].replace(Regex("<[^>]+>"), " "))
                .replace(Regex("\\s+"), " ")
                .trim()
            if (h1Text.length in 2..140 && !h1Text.contains("cookie", ignoreCase = true)) {
                return h1Text
            }
        }

        val titleMatch = Regex("(?is)<title\\b[^>]*>(.*?)</title\\s*>").find(rawHtml)
        if (titleMatch != null) {
            val rawTitle = decodeEntities(titleMatch.groupValues[1].replace(Regex("<[^>]+>"), " "))
                .replace(Regex("\\s+"), " ")
                .trim()
            val splitTitle = rawTitle.split(Regex("\\s+[|–—-]\\s+")).firstOrNull()?.trim()
            val candidate = if (!splitTitle.isNullOrBlank() && splitTitle.length >= 3) splitTitle else rawTitle
            if (candidate.isNotBlank()) {
                return candidate.take(140)
            }
        }

        return try {
            URL(pageUrl).host.removePrefix("www.").ifBlank { "Leitura da Web" }
        } catch (_: Exception) {
            "Leitura da Web"
        }
    }

    private fun extractWebAuthor(rawHtml: String, focusHtml: String): String? {
        val metaPatterns = listOf(
            Regex("(?is)<meta\\b[^>]*name\\s*=\\s*[\"']author[\"'][^>]*content\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>"),
            Regex("(?is)<meta\\b[^>]*content\\s*=\\s*[\"']([^\"']+)[\"'][^>]*name\\s*=\\s*[\"']author[\"'][^>]*>"),
            Regex("(?is)<meta\\b[^>]*property\\s*=\\s*[\"']article:author[\"'][^>]*content\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>")
        )

        for (regex in metaPatterns) {
            val match = regex.find(rawHtml)
            if (match != null) {
                val value = decodeEntities(match.groupValues[1]).replace(Regex("\\s+"), " ").trim()
                if (isValidAuthorCandidate(value)) {
                    return value
                }
            }
        }

        val plainFocus = htmlToCleanPlainText(focusHtml)
        return extractAuthorFromHeaderLines(plainFocus)
    }

    private fun extractAuthorFromHeaderLines(text: String): String? {
        val headerLines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(8)
            .toList()

        val prefixes = listOf("por ", "autor:", "autora:", "escritor:", "escritora:", "de ")
        for (line in headerLines) {
            if (line.length > 70) continue
            val lower = line.lowercase(Locale.ROOT)
            for (prefix in prefixes) {
                if (lower.startsWith(prefix)) {
                    val candidate = line.substring(prefix.length).trim().trimEnd(',', '.', ';')
                    if (isValidAuthorCandidate(candidate)) {
                        return candidate
                    }
                }
            }
        }
        return null
    }

    private fun isValidAuthorCandidate(candidate: String): Boolean {
        if (candidate.isBlank() || candidate.length !in 2..60) return false
        val lower = candidate.lowercase(Locale.ROOT)
        if (lower.contains("http") || lower.contains("cookie") || lower.contains("privacidade")) {
            return false
        }
        val words = candidate.split(Regex("\\s+")).filter { it.isNotBlank() }
        return words.size in 1..6 && words.none { it.any { ch -> ch.isDigit() } }
    }

    private fun extractCoverUrl(rawHtml: String, focusHtml: String, pageUrl: String): String? {
        val ogPatterns = listOf(
            Regex("(?is)<meta\\b[^>]*property\\s*=\\s*[\"']og:image[\"'][^>]*content\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>"),
            Regex("(?is)<meta\\b[^>]*content\\s*=\\s*[\"']([^\"']+)[\"'][^>]*property\\s*=\\s*[\"']og:image[\"'][^>]*>"),
            Regex("(?is)<meta\\b[^>]*name\\s*=\\s*[\"']twitter:image[\"'][^>]*content\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>")
        )

        for (regex in ogPatterns) {
            val match = regex.find(rawHtml)
            if (match != null) {
                val resolved = resolveUrl(pageUrl, decodeEntities(match.groupValues[1]).trim())
                if (resolved != null && !looksLikeIconOrBadge(resolved)) {
                    return resolved
                }
            }
        }

        val imgMatches = Regex("(?is)<img\\b[^>]*src\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>").findAll(focusHtml)
        for (img in imgMatches) {
            val tag = img.value
            val src = decodeEntities(img.groupValues[1]).trim()
            val resolved = resolveUrl(pageUrl, src) ?: continue
            if (looksLikeIconOrBadge(resolved) || looksLikeIconOrBadge(tag)) continue
            return resolved
        }

        return null
    }

    private fun looksLikeIconOrBadge(value: String): Boolean {
        val lower = value.lowercase(Locale.ROOT)
        return lower.contains("favicon") ||
            lower.contains("sprite") ||
            lower.contains("logo") ||
            lower.contains("avatar") ||
            lower.contains("icon") ||
            lower.contains("tracking") ||
            lower.contains("pixel") ||
            lower.startsWith("data:image")
    }

    private fun resolveUrl(baseUrl: String, candidate: String): String? {
        if (candidate.isBlank()) return null
        return try {
            URL(URL(baseUrl), candidate).toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun extractChaptersFromHtml(focusHtml: String, fallbackTitle: String): List<BookChapter> {
        val strippedNoise = stripNoiseTags(focusHtml)
        val headingRegex = Regex("(?is)<h([2-4])\\b[^>]*>(.*?)</h\\1\\s*>")
        val matches = headingRegex.findAll(strippedNoise).toList()

        if (matches.size < 2) {
            return emptyList()
        }

        val chapters = ArrayList<BookChapter>()
        var lastEnd = 0

        val introHtml = strippedNoise.substring(0, matches.first().range.first)
        val introText = htmlToCleanPlainText(introHtml)
        if (introText.length >= 250) {
            chapters.add(
                BookChapter(
                    index = chapters.size,
                    title = fallbackTitle.take(80),
                    text = introText
                )
            )
        }

        for (i in matches.indices) {
            val currentMatch = matches[i]
            val headingTitle = decodeEntities(currentMatch.groupValues[2].replace(Regex("<[^>]+>"), " "))
                .replace(Regex("\\s+"), " ")
                .trim()
                .ifEmpty { "Capítulo ${chapters.size + 1}" }

            val bodyStart = currentMatch.range.last + 1
            val bodyEnd = if (i + 1 < matches.size) matches[i + 1].range.first else strippedNoise.length
            val sectionText = htmlToCleanPlainText(strippedNoise.substring(bodyStart, bodyEnd))

            if (sectionText.length >= 60) {
                chapters.add(
                    BookChapter(
                        index = chapters.size,
                        title = headingTitle.take(100),
                        text = sectionText
                    )
                )
            }
            lastEnd = bodyEnd
        }

        if (lastEnd < strippedNoise.length) {
            val tailText = htmlToCleanPlainText(strippedNoise.substring(lastEnd))
            if (tailText.length >= 120 && chapters.isNotEmpty()) {
                val last = chapters.last()
                chapters[chapters.lastIndex] = last.copy(text = "${last.text}\n\n$tailText")
            }
        }

        return chapters
    }

    private fun htmlToCleanPlainText(html: String): String {
        val cleanedHtml = stripNoiseTags(html)
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</(p|div|h[1-6]|li|tr|article|section|blockquote)>"), "\n\n")
            .replace(Regex("<[^>]+>"), " ")

        return cleanPlainLines(decodeEntities(cleanedHtml))
    }

    private fun stripNoiseTags(html: String): String {
        var processed = html
        for (tag in noiseBlockTags) {
            processed = processed.replace(
                Regex("(?is)<$tag\\b[^>]*>.*?</$tag\\s*>"),
                " "
            )
        }
        processed = processed.replace(
            Regex("(?is)<(div|section|aside|footer|header)\\b[^>]*(id|class)\\s*=\\s*[\"'][^\"']*(cookie|consent|gdpr|banner|modal|popup|sidebar|nav|menu|footer|header|promo|ad-)[^\"']*[\"'][^>]*>.*?</\\1\\s*>"),
            " "
        )
        return processed
    }

    private fun extractMainFocusHtml(html: String): String {
        val articleMatch = Regex("(?is)<article\\b[^>]*>(.*?)</article\\s*>").find(html)
        if (articleMatch != null) {
            val candidate = articleMatch.groupValues[1]
            if (candidate.replace(Regex("<[^>]+>"), " ").trim().length >= 300) {
                return candidate
            }
        }

        val mainMatch = Regex("(?is)<main\\b[^>]*>(.*?)</main\\s*>").find(html)
        if (mainMatch != null) {
            val candidate = mainMatch.groupValues[1]
            if (candidate.replace(Regex("<[^>]+>"), " ").trim().length >= 300) {
                return candidate
            }
        }

        return html
    }

    private fun splitPlainTextIntoChapters(text: String, fallbackPrefix: String): List<BookChapter> {
        val clean = cleanPlainLines(text)
        if (clean.isBlank()) return emptyList()

        val lines = clean.split("\n")
        val detectedSections = ArrayList<Pair<String, StringBuilder>>()
        var currentTitle: String? = null
        var currentBody = StringBuilder()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.length in 3..90 && chapterHeadingRegex.matches(trimmed)) {
                if (currentBody.toString().trim().isNotEmpty()) {
                    detectedSections.add(
                        (currentTitle ?: "$fallbackPrefix ${detectedSections.size + 1}") to currentBody
                    )
                }
                currentTitle = trimmed
                currentBody = StringBuilder()
            } else {
                if (currentBody.isNotEmpty()) currentBody.append("\n")
                currentBody.append(line)
            }
        }

        if (currentBody.toString().trim().isNotEmpty()) {
            detectedSections.add(
                (currentTitle ?: "$fallbackPrefix ${detectedSections.size + 1}") to currentBody
            )
        }

        val hasExplicitChapters = detectedSections.size > 1 || currentTitle != null
        if (hasExplicitChapters) {
            return detectedSections.mapIndexedNotNull { idx, (title, sb) ->
                val body = sb.toString().trim()
                if (body.isEmpty()) null else BookChapter(index = idx, title = title, text = body)
            }
        }

        if (clean.length <= TARGET_CHAPTER_CHARS + 600) {
            return listOf(
                BookChapter(
                    index = 0,
                    title = "$fallbackPrefix 1",
                    text = clean
                )
            )
        }

        val paragraphs = clean.split("\n\n").filter { it.isNotBlank() }
        val chunks = ArrayList<BookChapter>()
        val buf = StringBuilder()

        for (para in paragraphs) {
            val extraLen = if (buf.isEmpty()) para.length else para.length + 2
            if (buf.isNotEmpty() && buf.length + extraLen > TARGET_CHAPTER_CHARS) {
                chunks.add(
                    BookChapter(
                        index = chunks.size,
                        title = "$fallbackPrefix ${chunks.size + 1}",
                        text = buf.toString().trim()
                    )
                )
                buf.setLength(0)
            }
            if (buf.isNotEmpty()) buf.append("\n\n")
            buf.append(para)
        }

        if (buf.isNotBlank()) {
            chunks.add(
                BookChapter(
                    index = chunks.size,
                    title = "$fallbackPrefix ${chunks.size + 1}",
                    text = buf.toString().trim()
                )
            )
        }

        return chunks
    }

    private fun cleanPlainLines(raw: String): String {
        val normalized = raw
            .replace("\r\n", "\n")
            .replace("\r", "\n")

        val lines = normalized.split("\n")
        val keptLines = ArrayList<String>(lines.size)

        for (rawLine in lines) {
            val line = rawLine.replace(Regex("[ \\t]+"), " ").trim()
            if (line.isEmpty()) {
                if (keptLines.isNotEmpty() && keptLines.last().isNotEmpty()) {
                    keptLines.add("")
                }
                continue
            }

            if (isNoiseLine(line)) {
                continue
            }

            keptLines.add(line)
        }

        return keptLines
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun isNoiseLine(line: String): Boolean {
        val lower = line.lowercase(Locale.ROOT).trim()
        val normalizedPunct = lower.replace(Regex("[.:!?•|»«—–-]+$"), "").trim()

        if (normalizedPunct in standaloneUiLabels) {
            return true
        }

        if (line.length <= 160 && cookieAndChromePhrases.any { lower.contains(it) }) {
            return true
        }

        if (line.length <= 24 && !line.endsWith(".") && !line.endsWith("!") && !line.endsWith("?")) {
            val wordCount = line.split(Regex("\\s+")).size
            if (wordCount <= 2 && lower.none { it.isDigit() }) {
                return true
            }
        }

        return false
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx)?.trim() else null
                } else {
                    null
                }
            }?.ifBlank { uri.lastPathSegment }
        } catch (_: Exception) {
            uri.lastPathSegment
        }
    }

    private fun decodeEntities(input: String): String {
        return input
            .replace("&nbsp;", " ")
            .replace("&#160;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&laquo;", "«")
            .replace("&raquo;", "»")
            .replace("&hellip;", "…")
    }
}
