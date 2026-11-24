package com.example.pocketlawyeruk.util

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class ContinuousSpeechController(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var keepListening = false
    private var released = false

    private var onPartialCallback: ((String) -> Unit)? = null
    private var onFinalCallback: ((String) -> Unit)? = null
    private var onStateCallback: ((Boolean) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null

    fun start(
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onListeningChanged: (Boolean) -> Unit,
        onFatalError: (String) -> Unit
    ) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onFatalError("Speech recognition is not available on this phone.")
            return
        }
        onPartialCallback = onPartial
        onFinalCallback = onFinal
        onStateCallback = onListeningChanged
        onErrorCallback = onFatalError
        keepListening = true
        mainHandler.post {
            onStateCallback?.invoke(true)
            startSession()
        }
    }

    fun stop() {
        keepListening = false
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.post {
            runCatching { recognizer?.stopListening() }
            runCatching { recognizer?.destroy() }
            recognizer = null
            onPartialCallback?.invoke("")
            onStateCallback?.invoke(false)
        }
    }

    fun release() {
        released = true
        stop()
    }

    private fun startSession() {
        if (released || !keepListening) return

        runCatching { recognizer?.destroy() }
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    if (partial.isNotBlank()) {
                        mainHandler.post { onPartialCallback?.invoke(partial) }
                    }
                }

                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                        .trim()
                    mainHandler.post {
                        onPartialCallback?.invoke("")
                        if (text.isNotBlank()) onFinalCallback?.invoke(text)
                        scheduleRestart(300L)
                    }
                }

                override fun onError(error: Int) {
                    mainHandler.post {
                        onPartialCallback?.invoke("")
                        when (error) {
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                                keepListening = false
                                onStateCallback?.invoke(false)
                                onErrorCallback?.invoke("Microphone permission is required for Live Co-Pilot.")
                            }
                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> scheduleRestart(400L)
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                            SpeechRecognizer.ERROR_CLIENT -> scheduleRestart(900L)
                            else -> scheduleRestart(1200L)
                        }
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GB")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Longer silence window reduces restart gaps during normal conversation
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
        }

        runCatching { recognizer?.startListening(intent) }
            .onFailure { scheduleRestart(1000L) }
    }

    private fun scheduleRestart(delayMs: Long) {
        if (released || !keepListening) return
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({ startSession() }, delayMs)
    }
}