package com.exemplo.edgetts.reader

import android.content.Context
import android.net.Uri
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

object ReaderContentLoader {

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
            return raw.replace("\r\n", "\n").replace(Regex("[ \\t]+"), " ").trim()
        }

        val withoutScripts = raw
            .replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
            .replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
            .replace(Regex("(?is)<noscript[^>]*>.*?</noscript>"), " ")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</(p|div|h[1-6]|li|tr|article|section|blockquote)>"), "\n\n")
            .replace(Regex("<[^>]+>"), " ")

        return decodeEntities(withoutScripts)
            .replace("\r\n", "\n")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex(" ?\n ?"), "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun decodeEntities(input: String): String {
        return input
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
    }
}
