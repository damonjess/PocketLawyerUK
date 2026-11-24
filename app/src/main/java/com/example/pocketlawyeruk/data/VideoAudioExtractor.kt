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

    suspend fun updateExtractor(context: Context) = withContext(Dispatchers.IO) {
        ensureInitialized(context)
        YoutubeDL.getInstance().updateYoutubeDL(
            context.applicationContext,
            YoutubeDL.UpdateChannel.STABLE
        )
    }

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

        fun buildRequest(formatSpec: String): YoutubeDLRequest = YoutubeDLRequest(cleanUrl).apply {
            addOption("--no-playlist")
            addOption("--no-update")
            // Prefer AVC/H.264 streams to avoid TikTok's silent bytevc1 video-only formats
            addOption("-S", "vcodec:avc,res:720")
            addOption("-f", formatSpec)
            addOption("-x")
            addOption("--audio-format", "mp3")
            addOption("--postprocessor-args", "ffmpeg:-ac 1 -ar 16000 -b:a 48k")
            addOption("-o", outputTemplate)
        }

        val primarySpec = "bestaudio/best[vcodec*=avc]/best[vcodec*=h264]/best"
        val fallbackSpec = "best[vcodec*=avc]/best[vcodec*=h264]/worst[acodec!=none]/best"

        onStatus("Downloading audio from video link...")
        try {
            YoutubeDL.getInstance().execute(buildRequest(primarySpec)) { progress, _, _ ->
                if (progress > 0f) {
                    onStatus("Downloading audio (${progress.toInt()}%)...")
                }
            }
        } catch (_: Exception) {
            // Wipe any partial/silent file from attempt 1 before updating and retrying
            workDir.listFiles()?.forEach { it.delete() }
            onStatus("Updating video extractor & retrying...")
            runCatching { updateExtractor(context) }
            try {
                YoutubeDL.getInstance().execute(buildRequest(fallbackSpec))
            } catch (retryError: Exception) {
                val msg = retryError.localizedMessage.orEmpty()
                if (msg.contains("unable to obtain file audio codec with ffprobe", ignoreCase = true)) {
                    throw IllegalStateException(
                        "No usable audio track found in this link (it may be a muted clip, a TikTok photo post, or a blocked stream). Try saving the video to your phone and using LOAD LOCAL VIDEO FILE."
                    )
                }
                throw retryError
            }
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