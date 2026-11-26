package com.exemplo.edgetts

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.appcompat.app.AppCompatActivity
import com.exemplo.edgetts.databinding.ActivityMainBinding
import com.exemplo.edgetts.reader.ReaderActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var watchDogRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvEngineStatus.text = "Status: a ligar ao GlowTTS ($packageName)..."

        tts = TextToSpeech(this, { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                attachProgressListener()
                val currentEngine = tts?.defaultEngine ?: packageName
                binding.tvEngineStatus.text = "Status: pronto (motor: $currentEngine)"
            } else {
                ttsReady = false
                binding.tvEngineStatus.text = "Status: falha ao iniciar motor (código $status)"
            }
        }, packageName)

        binding.btnSpeakSample.setOnClickListener {
            val text = binding.etSampleText.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) {
                binding.tvEngineStatus.text = "Status: escreva um texto antes de testar"
                return@setOnClickListener
            }
            if (!ttsReady || tts == null) {
                binding.tvEngineStatus.text = "Status: motor ainda não inicializado"
                return@setOnClickListener
            }

            val prefs = getSharedPreferences("edge_tts_prefs", MODE_PRIVATE)
            val voice = prefs.getString("selected_voice", "pt-BR-FranciscaNeural") ?: "pt-BR-FranciscaNeural"
            binding.tvEngineStatus.text = "Status: a sintetizar ($voice)..."

            cancelWatchDog()
            val pendingWatchDog = Runnable {
                val diag = readLastDiag()
                binding.tvEngineStatus.text = "Status: sem resposta — $diag"
            }
            watchDogRunnable = pendingWatchDog
            mainHandler.postDelayed(pendingWatchDog, 20_000L)

            val params = Bundle()
            val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "glowtts_test_utterance")
            if (result == TextToSpeech.ERROR) {
                cancelWatchDog()
                binding.tvEngineStatus.text = "Status: erro ao enviar texto (${readLastDiag()})"
            }
        }

        binding.btnVoiceSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnOpenReader.setOnClickListener {
            startActivity(Intent(this, ReaderActivity::class.java))
        }

        binding.btnOpenTtsSettings.setOnClickListener {
            val intent = Intent("com.android.settings.TTS_SETTINGS")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(intent)
            } catch (_: Exception) {
                binding.tvEngineStatus.text = "Status: abra Definições > Acessibilidade > Saída de texto para fala"
            }
        }
    }

    private fun readLastDiag(): String {
        val prefs = getSharedPreferences("edge_tts_prefs", MODE_PRIVATE)
        return prefs.getString("last_diag_message", "sem registo do serviço") ?: "sem registo do serviço"
    }

    private fun attachProgressListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                runOnUiThread {
                    cancelWatchDog()
                    binding.tvEngineStatus.text = "Status: a reproduzir áudio..."
                }
            }

            override fun onDone(utteranceId: String?) {
                runOnUiThread {
                    cancelWatchDog()
                    binding.tvEngineStatus.text = "Status: reprodução concluída!"
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                runOnUiThread {
                    cancelWatchDog()
                    binding.tvEngineStatus.text = "Status: erro (${readLastDiag()})"
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                runOnUiThread {
                    cancelWatchDog()
                    binding.tvEngineStatus.text = "Status: erro $errorCode (${readLastDiag()})"
                }
            }
        })
    }

    private fun cancelWatchDog() {
        watchDogRunnable?.let { mainHandler.removeCallbacks(it) }
        watchDogRunnable = null
    }

    override fun onDestroy() {
        cancelWatchDog()
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }
}
