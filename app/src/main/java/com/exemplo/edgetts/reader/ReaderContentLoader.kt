package com.exemplo.edgetts.reader

import android.content.Context
import android.net.Uri
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.util.Locale

object ReaderContentLoader {

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

    fun loadTextFromUri(context: Context, uri: Uri): Result<String> {
        return try {
            val stream = context.contentResolver.openInputStream(uri)
                ?: return Result.failure(IllegalStateException("Não foi possível abrir o ficheiro."))

            stream.use { input ->
                val text = BufferedReader(InputStreamReader(input, Charset.forName("UTF-8")))
                    .readText()
                    .replace("\r\n", "\n")
                    .replace("\r", "\n")
                    .trim()

                if (text.isEmpty()) {
                    Result.failure(IllegalStateException("O ficheiro TXT está vazio."))
                } else {
                    Result.success(text)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun loadTextFromUrl(rawUrl: String): Result<String> {
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

                val body = conn.inputStream.use { input ->
                    BufferedReader(InputStreamReader(input, Charset.forName("UTF-8"))).readText()
                }

                val readable = htmlToText(body)
                if (readable.isBlank()) {
                    Result.failure(IllegalStateException("Não foi encontrado texto legível no link."))
                } else {
                    Result.success(readable)
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun htmlToText(raw: String): String {
        if (!raw.contains("<")) {
            return cleanPlainLines(raw)
        }

        val focusHtml = extractMainFocusHtml(raw)
        var processed = focusHtml

        for (tag in noiseBlockTags) {
            processed = processed.replace(
                Regex("(?is)<$tag\\b[^>]*>.*?</$tag\\s*>"),
                " "
            )
        }

        // Remove elementos com id/class típicos de banner de cookies ou rodapé/menu
        processed = processed.replace(
            Regex("(?is)<(div|section|aside|footer|header)\\b[^>]*(id|class)\\s*=\\s*[\"'][^\"']*(cookie|consent|gdpr|banner|modal|popup|sidebar|nav|menu|footer|header|promo|ad-)[^\"']*[\"'][^>]*>.*?</\\1\\s*>"),
            " "
        )

        processed = processed
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</(p|div|h[1-6]|li|tr|article|section|blockquote)>"), "\n\n")
            .replace(Regex("<[^>]+>"), " ")

        val decoded = decodeEntities(processed)
        return cleanPlainLines(decoded)
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

        // Linhas curtas ou médias que contêm termos de cookies / privacidade / navegação
        if (line.length <= 160 && cookieAndChromePhrases.any { lower.contains(it) }) {
            return true
        }

        // Linhas muito curtas sem pontuação final que parecem itens soltos de menu
        if (line.length <= 24 && !line.endsWith(".") && !line.endsWith("!") && !line.endsWith("?")) {
            val wordCount = line.split(Regex("\\s+")).size
            if (wordCount <= 2 && lower.none { it.isDigit() }) {
                return true
            }
        }

        return false
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
