package com.example.pocketlawyeruk.data

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class VideoAudioExtractor {

    private val initMutex = Mutex()
    private var isInitialized = false

    suspend fun ensureInitialized(context: Context) = initMutex.withLock {
        if (!isInitialized) {
            withContext(Dispatchers.IO) {
                YoutubeDL.getInstance().init(context.applicationContext)
                FFmpeg.getInstance().init(context.applicationContext)
            }
            isInitialized = true
        }
    }

    /**
     * Call occasionally (or on extraction error) to pull the latest yt-dlp
     * extractors if TikTok/YouTube changes their page format.
     */
    suspend fun updateExtractor(context: Context) = withContext(Dispatchers.IO) {
        ensureInitialized(context)
        YoutubeDL.getInstance().updateYoutubeDL(
            context.applicationContext,
            YoutubeDL.UpdateChannel.NIGHTLY
        )
    }

    /**
     * Extracts the first URL from messy TikTok/social share text and downloads
     * a compact mono MP3 (~360 KB/min) into cacheDir for AI transcription.
     */
    suspend fun downloadAudioFromUrl(
        context: Context,
        rawInput: String,
        onStatus: (String) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        val cleanUrl = extractFirstUrl(rawInput)
            ?: throw IllegalArgumentException("No valid link found. Paste a TikTok, YouTube, or social video URL.")

        onStatus("Initialising audio extractor...")
        ensureInitialized(context)

        val workDir = File(context.cacheDir, "audit_audio_${UUID.randomUUID()}").apply { mkdirs() }
        val outputTemplate = File(workDir, "clip.%(ext)s").absolutePath

        val request = YoutubeDLRequest(cleanUrl).apply {
            addOption("--no-playlist")
            addOption("-x")
            addOption("--audio-format", "mp3")
            // Mono, 16kHz, 48kbps is crystal clear for speech and keeps long audits tiny
            addOption("--postprocessor-args", "ffmpeg:-ac 1 -ar 16000 -b:a 48k")
            addOption("-o", outputTemplate)
        }

        onStatus("Downloading audio from video link...")
        try {
            YoutubeDL.getInstance().execute(request) { progress, _, _ ->
                if (progress > 0f) {
                    onStatus("Downloading audio (${progress.toInt()}%)...")
                }
            }
        } catch (firstError: Exception) {
            // Auto-update yt-dlp once if a platform broke the bundled extractor, then retry
            onStatus("Updating video extractor & retrying...")
            runCatching { updateExtractor(context) }
            YoutubeDL.getInstance().execute(request)
        }

        val audioFile = workDir.listFiles()?.firstOrNull { it.extension.equals("mp3", ignoreCase = true) }
            ?: throw IllegalStateException("Audio extraction finished, but no MP3 file was produced.")

        audioFile
    }

    private fun extractFirstUrl(text: String): String? {
        val regex = Regex("""https?://[^\s]+""")
        return regex.find(text.trim())?.value
    }
}
