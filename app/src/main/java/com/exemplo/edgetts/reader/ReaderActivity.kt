package com.exemplo.edgetts.reader

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.exemplo.edgetts.databinding.ActivityReaderBinding
import java.util.concurrent.Executors

class ReaderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReaderBinding
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private lateinit var audioPlayer: DirectEdgeAudioPlayer

    private val pickTxtLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        binding.tvReaderStatus.text = "Status: a abrir TXT..."
        ioExecutor.execute {
            val res = ReaderContentLoader.loadTextFromUri(this, uri)
            runOnUiThread {
                res.onSuccess { text ->
                    binding.tvReaderContent.text = text
                    binding.tvReaderStatus.text = "Status: TXT carregado (${text.length} caracteres)"
                }.onFailure { err ->
                    binding.tvReaderStatus.text = "Status: ${err.message ?: "falha ao abrir TXT"}"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        audioPlayer = DirectEdgeAudioPlayer(this)

        binding.btnPickTxt.setOnClickListener {
            pickTxtLauncher.launch(arrayOf("text/plain", "text/*"))
        }

        binding.btnLoadUrl.setOnClickListener {
            val rawUrl = binding.etUrlInput.text?.toString().orEmpty()
            binding.tvReaderStatus.text = "Status: a importar link..."
            binding.btnLoadUrl.isEnabled = false

            ioExecutor.execute {
                val res = ReaderContentLoader.loadTextFromUrl(rawUrl)
                runOnUiThread {
                    binding.btnLoadUrl.isEnabled = true
                    res.onSuccess { text ->
                        binding.tvReaderContent.text = text
                        binding.tvReaderStatus.text = "Status: link carregado (${text.length} caracteres)"
                    }.onFailure { err ->
                        binding.tvReaderStatus.text = "Status: ${err.message ?: "falha ao importar link"}"
                    }
                }
            }
        }

        binding.btnPlayReader.setOnClickListener {
            val text = binding.tvReaderContent.text?.toString().orEmpty()
            audioPlayer.playText(
                fullText = text,
                onStatus = { msg -> runOnUiThread { binding.tvReaderStatus.text = "Status: $msg" } },
                onFinished = { runOnUiThread { binding.tvReaderStatus.text = "Status: concluído" } },
                onError = { err -> runOnUiThread { binding.tvReaderStatus.text = "Status: $err" } }
            )
        }

        binding.btnStopReader.setOnClickListener {
            audioPlayer.stop()
            binding.tvReaderStatus.text = "Status: parado"
        }
    }

    override fun onDestroy() {
        audioPlayer.stop()
        ioExecutor.shutdownNow()
        super.onDestroy()
    }
}
