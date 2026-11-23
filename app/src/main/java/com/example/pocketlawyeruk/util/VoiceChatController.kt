package com.example.pocketlawyeruk.util

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

class VoiceChatController(private val context: Context) {

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    // Speech requested before the engine finished starting (or while it is being replaced).
    private var pendingSpeech: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private var initToken = 0
    private var candidateIndex = 0
    private val candidates: List<String?> = buildEngineCandidates()

    /** Invoked on the main thread when speech cannot be played, so the UI can explain why. */
    var onTtsError: ((String) -> Unit)? = null

    init {
        initNextEngine()
    }

    private fun buildEngineCandidates(): List<String?> {
        val google = "com.google.android.tts"
        val installed = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager
                .queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
                .mapNotNull { it.serviceInfo?.packageName }
                .distinct()
        }.getOrDefault(emptyList())

        val result = mutableListOf<String?>()
        if (installed.contains(google) || isPackageInstalled(google)) result.add(google)
        installed.filter { it != google }.forEach { result.add(it) }
        result.add(null) // system default, if a default engine is configured
        return result.distinct()
    }

    private fun isPackageInstalled(pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    private fun initNextEngine() {
        android.util.Log.d("VoiceChatController", "initNextEngine: index=$candidateIndex, candidates=${candidates.size}")
        if (candidateIndex >= candidates.size) {
            ttsReady = false
            pendingSpeech = null
            onTtsError?.invoke(
                "Text-to-speech isn't set up on this phone. Open Settings > Accessibility > Text-to-speech, install a voice, then tap SPEAK again."
            )
            return
        }

        val engine = candidates[candidateIndex++]
        val token = ++initToken
        android.util.Log.d("VoiceChatController", "Initializing engine: $engine with token $token")
        runCatching { tts?.shutdown() }
        tts = if (engine == null) {
            TextToSpeech(context.applicationContext) { status -> onTtsInit(status, token) }
        } else {
            TextToSpeech(context.applicationContext, { status -> onTtsInit(status, token) }, engine)
        }

        // Some devices never call back when no default engine is configured; don't hang forever.
        handler.postDelayed(
            { if (token == initToken && !ttsReady) initNextEngine() },
            6000
        )
    }

    private fun onTtsInit(status: Int, token: Int) {
        android.util.Log.d("VoiceChatController", "onTtsInit: status=$status, token=$token, initToken=$initToken")
        if (token != initToken) return
        if (status == TextToSpeech.SUCCESS) {
            val engineInstance = tts ?: return initNextEngine()
            val ukResult = engineInstance.setLanguage(Locale.UK)
            android.util.Log.d("VoiceChatController", "setLanguage(UK) result=$ukResult")
            if (ukResult == TextToSpeech.LANG_MISSING_DATA || ukResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                engineInstance.language = Locale.ENGLISH
            }
            engineInstance.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            ttsReady = true
            pendingSpeech?.let { text ->
                pendingSpeech = null
                speakNow(text)
            }
            return
        }
        initNextEngine()
    }

    fun startListening(
        onListeningState: (Boolean) -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("Speech recognition is not available on this device.")
            return
        }

        stopSpeaking()
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = onListeningState(true)
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() = onListeningState(false)
                override fun onError(error: Int) {
                    onListeningState(false)
                    onError("Could not hear speech clearly (code $error). Try again.")
                }
                override fun onResults(results: Bundle?) {
                    onListeningState(false)
                    val spoken = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (!spoken.isNullOrBlank()) onResult(spoken)
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GB")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        speechRecognizer?.startListening(intent)
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
    }

    fun speak(text: String) {
        android.util.Log.d("VoiceChatController", "speak: length=${text.length}, ttsReady=$ttsReady")
        if (text.isBlank()) return
        if (!ttsReady) {
            // Engine may still be starting; queue instead of silently dropping the request.
            pendingSpeech = text
            return
        }
        speakNow(text)
    }

    private fun speakNow(text: String) {
        // Strip markdown asterisks/bullets so it reads naturally
        val clean = text.replace(Regex("[*#`_]"), "").replace("•", "")
        android.util.Log.d("VoiceChatController", "speakNow: clean length=${clean.length}")
        
        // Chunk if text is too long for the TTS engine
        val maxLength = TextToSpeech.getMaxSpeechInputLength()
        if (clean.length > maxLength) {
            val chunks = clean.chunked(maxLength - 100)
            chunks.forEachIndexed { index, chunk ->
                val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                val result = tts?.speak(chunk, queueMode, null, "pocket_lawyer_tts_$index")
                android.util.Log.d("VoiceChatController", "speakNow: chunk $index result=$result")
            }
        } else {
            val result = tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "pocket_lawyer_tts")
            android.util.Log.d("VoiceChatController", "speakNow: result=$result")
            if (result == null || result == TextToSpeech.ERROR) {
                onTtsError?.invoke("Couldn't play speech. Check your text-to-speech engine and media volume.")
            }
        }
    }

    fun stopSpeaking() {
        tts?.stop()
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        speechRecognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
    }
}
