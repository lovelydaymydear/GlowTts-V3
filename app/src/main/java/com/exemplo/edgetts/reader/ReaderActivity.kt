package com.exemplo.edgetts.reader

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class ReaderActivity : AppCompatActivity() {

    private lateinit var ivBookCover: ImageView
    private lateinit var tvCoverFallback: TextView
    private lateinit var tvBookTitle: TextView
    private lateinit var tvBookAuthor: TextView
    private lateinit var tvChapterInfo: TextView
    private lateinit var btnSelectTxt: Button
    private lateinit var btnSelectChapters: Button
    private lateinit var etWebUrl: EditText
    private lateinit var btnLoadUrl: Button
    private lateinit var btnPrevChapter: Button
    private lateinit var btnPlayReader: Button
    private lateinit var btnStopReader: Button
    private lateinit var btnNextChapter: Button
    private lateinit var tvReaderStatus: TextView
    private lateinit var tvReaderContent: TextView

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private lateinit var audioPlayer: DirectEdgeAudioPlayer

    private var currentBook: BookDocument? = null
    private var currentChapterIndex: Int = 0
    private var isPlaying: Boolean = false

    private val pickTxtLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            loadTxtBook(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)

        audioPlayer = DirectEdgeAudioPlayer(this)

        ivBookCover = findViewById(R.id.ivBookCover)
        tvCoverFallback = findViewById(R.id.tvCoverFallback)
        tvBookTitle = findViewById(R.id.tvBookTitle)
        tvBookAuthor = findViewById(R.id.tvBookAuthor)
        tvChapterInfo = findViewById(R.id.tvChapterInfo)
        btnSelectTxt = findViewById(R.id.btnSelectTxt)
        btnSelectChapters = findViewById(R.id.btnSelectChapters)
        etWebUrl = findViewById(R.id.etWebUrl)
        btnLoadUrl = findViewById(R.id.btnLoadUrl)
        btnPrevChapter = findViewById(R.id.btnPrevChapter)
        btnPlayReader = findViewById(R.id.btnPlayReader)
        btnStopReader = findViewById(R.id.btnStopReader)
        btnNextChapter = findViewById(R.id.btnNextChapter)
        tvReaderStatus = findViewById(R.id.tvReaderStatus)
        tvReaderContent = findViewById(R.id.tvReaderContent)

        btnSelectTxt.setOnClickListener {
            pickTxtLauncher.launch(arrayOf("text/plain", "text/*"))
        }

        btnLoadUrl.setOnClickListener {
            val url = etWebUrl.text?.toString().orEmpty()
            loadWebBook(url)
        }

        btnSelectChapters.setOnClickListener {
            showChapterSelectorDialog()
        }

        btnPrevChapter.setOnClickListener {
            if (currentChapterIndex > 0) {
                selectChapter(currentChapterIndex - 1, autoPlay = isPlaying)
            }
        }

        btnNextChapter.setOnClickListener {
            val total = currentBook?.chapters?.size ?: 0
            if (currentChapterIndex + 1 < total) {
                selectChapter(currentChapterIndex + 1, autoPlay = isPlaying)
            }
        }

        btnPlayReader.setOnClickListener {
            playCurrentChapter()
        }

        btnStopReader.setOnClickListener {
            audioPlayer.stop()
            isPlaying = false
            updateControlsState()
            tvReaderStatus.text = "Reprodução interrompida."
        }

        updateControlsState()
    }

    private fun loadTxtBook(uri: Uri) {
        audioPlayer.stop()
        isPlaying = false
        setLoadingState("A carregar ficheiro TXT...")

        ioExecutor.execute {
            val result = ReaderContentLoader.loadBookFromUri(this, uri)
            runOnUiThread {
                result.fold(
                    onSuccess = { book -> bindBook(book) },
                    onFailure = { err ->
                        tvReaderStatus.text = "Erro ao abrir TXT: ${err.message ?: "falha desconhecida"}"
                        updateControlsState()
                    }
                )
            }
        }
    }

    private fun loadWebBook(rawUrl: String) {
        audioPlayer.stop()
        isPlaying = false
        setLoadingState("A carregar página e limpar conteúdo...")

        ioExecutor.execute {
            val result = ReaderContentLoader.loadBookFromUrl(rawUrl)
            runOnUiThread {
                result.fold(
                    onSuccess = { book -> bindBook(book) },
                    onFailure = { err ->
                        tvReaderStatus.text = "Erro ao carregar link: ${err.message ?: "falha desconhecida"}"
                        updateControlsState()
                    }
                )
            }
        }
    }

    private fun setLoadingState(message: String) {
        btnSelectTxt.isEnabled = false
        btnLoadUrl.isEnabled = false
        btnSelectChapters.isEnabled = false
        btnPrevChapter.isEnabled = false
        btnPlayReader.isEnabled = false
        btnNextChapter.isEnabled = false
        tvReaderStatus.text = message
    }

    private fun bindBook(book: BookDocument) {
        currentBook = book
        currentChapterIndex = 0

        tvBookTitle.text = book.title
        tvBookAuthor.text = if (!book.author.isNullOrBlank()) {
            "Autor: ${book.author}"
        } else {
            "Autor não informado"
        }

        bindCover(book)
        renderCurrentChapter()
        updateControlsState()
        tvReaderStatus.text = "Carregado (${book.chapters.size} capítulo(s)/parte(s))."
    }

    private fun bindCover(book: BookDocument) {
        val typeLabel = when (book.sourceType) {
            BookSourceType.TXT -> "TXT"
            BookSourceType.WEB_LINK -> "WEB"
            BookSourceType.EPUB -> "EPUB"
            BookSourceType.PDF -> "PDF"
        }
        tvCoverFallback.text = "${typeLabel}\n\n${book.title.take(28)}"
        tvCoverFallback.visibility = android.view.View.VISIBLE
        ivBookCover.setImageDrawable(null)

        val coverUrl = book.coverUrl
        if (coverUrl.isNullOrBlank()) {
            return
        }

        ioExecutor.execute {
            val bitmap = downloadBitmap(coverUrl)
            runOnUiThread {
                val activeBook = currentBook
                if (activeBook === book && bitmap != null) {
                    ivBookCover.setImageBitmap(bitmap)
                    tvCoverFallback.visibility = android.view.View.GONE
                }
            }
        }
    }

    private fun downloadBitmap(urlStr: String): android.graphics.Bitmap? {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 10000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile)")
            }
            try {
                if (conn.responseCode !in 200..299) return null
                conn.inputStream.use { input ->
                    BitmapFactory.decodeStream(input)
                }
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun selectChapter(index: Int, autoPlay: Boolean) {
        val book = currentBook ?: return
        if (index !in book.chapters.indices) return

        audioPlayer.stop()
        currentChapterIndex = index
        renderCurrentChapter()
        updateControlsState()

        if (autoPlay) {
            playCurrentChapter()
        } else {
            tvReaderStatus.text = "Selecionado: ${book.chapters[index].title}"
        }
    }

    private fun renderCurrentChapter() {
        val book = currentBook
        if (book == null || book.chapters.isEmpty()) {
            tvChapterInfo.text = "Capítulo 0/0"
            tvReaderContent.text = ""
            return
        }

        val chapter = book.chapters[currentChapterIndex]
        tvChapterInfo.text = "Capítulo ${currentChapterIndex + 1}/${book.chapters.size}: ${chapter.title}"
        tvReaderContent.text = chapter.text
    }

    private fun showChapterSelectorDialog() {
        val book = currentBook ?: return
        if (book.chapters.isEmpty()) return

        val labels = book.chapters.mapIndexed { idx, ch ->
            "${idx + 1}. ${ch.title}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Selecionar capítulo")
            .setSingleChoiceItems(labels, currentChapterIndex) { dialog, which ->
                dialog.dismiss()
                selectChapter(which, autoPlay = isPlaying)
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun playCurrentChapter() {
        val book = currentBook ?: return
        val chapter = book.chapters.getOrNull(currentChapterIndex) ?: return
        val textToRead = chapter.text.trim()
        if (textToRead.isEmpty()) {
            tvReaderStatus.text = "Este capítulo não tem texto para ler."
            return
        }

        isPlaying = true
        updateControlsState()

        audioPlayer.playText(
            fullText = textToRead,
            onStatus = { status ->
                runOnUiThread {
                    tvReaderStatus.text = "[Cap. ${currentChapterIndex + 1}] $status"
                }
            },
            onFinished = {
                runOnUiThread {
                    if (isPlaying) {
                        isPlaying = false
                        updateControlsState()
                        tvReaderStatus.text = "Capítulo ${currentChapterIndex + 1} concluído."
                    }
                }
            },
            onError = { err ->
                runOnUiThread {
                    isPlaying = false
                    updateControlsState()
                    tvReaderStatus.text = "Erro na leitura: $err"
                }
            }
        )
    }

    private fun updateControlsState() {
        val book = currentBook
        val hasChapters = book != null && book.chapters.isNotEmpty()
        val total = book?.chapters?.size ?: 0

        btnSelectTxt.isEnabled = true
        btnLoadUrl.isEnabled = true
        btnSelectChapters.isEnabled = hasChapters
        btnPlayReader.isEnabled = hasChapters
        btnStopReader.isEnabled = isPlaying
        btnPrevChapter.isEnabled = hasChapters && currentChapterIndex > 0
        btnNextChapter.isEnabled = hasChapters && (currentChapterIndex + 1) < total
    }

    override fun onDestroy() {
        audioPlayer.stop()
        ioExecutor.shutdownNow()
        super.onDestroy()
    }
}
