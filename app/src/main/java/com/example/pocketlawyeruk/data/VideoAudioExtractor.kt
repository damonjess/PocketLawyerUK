package com.example.pocketlawyeruk.data

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.TimeUnit

class VideoAudioExtractor {

    private val initMutex = Mutex()
    private var isInitialized = false

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

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
            YoutubeDL.UpdateChannel.NIGHTLY
        )
    }

    suspend fun downloadAudioFromUrl(
        context: Context,
        rawInput: String,
        onStatus: (String) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        val cleanUrl = extractFirstUrl(rawInput)
            ?: throw IllegalArgumentException("No valid link found. Paste a TikTok, YouTube, or social video URL.")

        val workDir = File(context.cacheDir, "audit_audio_${UUID.randomUUID()}").apply { mkdirs() }
        val diagnostics = mutableListOf<String>()

        if (isTikTokUrl(cleanUrl)) {
            onStatus("Expanding TikTok link...")
            val canonicalUrl = expandShortUrl(cleanUrl)
            val videoId = extractTikTokVideoId(canonicalUrl)

            // Tier 1: On-device Chromium WebView (no third-party server, real Chrome TLS + cookies)
            val webViewTargets = buildList {
                if (videoId != null) add("https://www.tiktok.com/embed/v2/$videoId")
                add(canonicalUrl)
            }.distinct()

            for ((idx, pageUrl) in webViewTargets.withIndex()) {
                val label = if (pageUrl.contains("/embed/")) "TikTok embed player" else "TikTok web player"
                onStatus("Intercepting stream via $label (${idx + 1}/${webViewTargets.size})...")
                val resolved = runCatching {
                    TikTokWebViewResolver.resolve(context, pageUrl)
                }.onFailure {
                    diagnostics.add("WebView($label): ${it.localizedMessage}")
                }.getOrNull()

                if (resolved != null) {
                    val fileResult = runCatching {
                        downloadFirstValidStream(resolved, workDir, onStatus)
                    }
                    if (fileResult.isSuccess) {
                        return@withContext fileResult.getOrThrow()
                    } else {
                        diagnostics.add("StreamDL($label): ${fileResult.exceptionOrNull()?.localizedMessage}")
                        workDir.listFiles()?.forEach { it.delete() }
                    }
                } else {
                    diagnostics.add("WebView($label): no stream found")
                }
            }

            // Tier 2: tikwm backup with expanded canonical URL
            onStatus("Trying backup TikTok resolver...")
            val tikwmResult = runCatching {
                downloadTikTokViaTikWm(canonicalUrl, workDir, onStatus)
            }
            if (tikwmResult.isSuccess) {
                return@withContext tikwmResult.getOrThrow()
            } else {
                diagnostics.add("TikWM: ${tikwmResult.exceptionOrNull()?.localizedMessage}")
                workDir.listFiles()?.forEach { it.delete() }
            }

            throw IllegalStateException(
                "Could not extract TikTok audio automatically:\n" +
                    diagnostics.joinToString("\n") +
                    "\n\nTip: If this clip is private or age-restricted, save it in TikTok and use LOAD LOCAL VIDEO FILE."
            )
        }

        // Non-TikTok URLs (YouTube, Reels, X, etc.) -> yt-dlp
        onStatus("Initialising audio extractor...")
        ensureInitialized(context)

        val outputTemplate = File(workDir, "clip.%(ext)s").absolutePath

        fun buildRequest(formatSpec: String?): YoutubeDLRequest = YoutubeDLRequest(cleanUrl).apply {
            addOption("--no-playlist")
            addOption("--no-update")
            if (formatSpec != null) addOption("-f", formatSpec)
            addOption("-x")
            addOption("--audio-format", "mp3")
            addOption("--postprocessor-args", "ffmpeg:-ac 1 -ar 16000 -b:a 48k")
            addOption("-o", outputTemplate)
        }

        onStatus("Downloading audio from video link...")
        try {
            YoutubeDL.getInstance().execute(buildRequest("bestaudio/best")) { progress, _, _ ->
                if (progress > 0f) {
                    onStatus("Downloading audio (${progress.toInt()}%)...")
                }
            }
        } catch (_: Exception) {
            workDir.listFiles()?.forEach { it.delete() }
            onStatus("Updating video extractor & retrying...")
            runCatching { updateExtractor(context) }
            YoutubeDL.getInstance().execute(buildRequest(null))
        }

        workDir.listFiles()?.firstOrNull { it.extension.equals("mp3", ignoreCase = true) }
            ?: throw IllegalStateException("Audio extraction finished, but no MP3 file was produced.")
    }

    private fun expandShortUrl(url: String): String = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", MOBILE_UA)
            .get()
            .build()
        httpClient.newCall(req).execute().use { resp ->
            resp.request.url.toString().substringBefore('?')
        }
    }.getOrDefault(url)

    private fun extractTikTokVideoId(url: String): String? {
        val match = Regex("""/(?:video|photo|v|embed/v2)/(\d{15,22})""").find(url)
        return match?.groupValues?.get(1)
    }

    private fun downloadFirstValidStream(
        resolved: ResolvedTikTokStream,
        workDir: File,
        onStatus: (String) -> Unit
    ): File {
        var lastErr = "No playable stream URL"
        for (streamUrl in resolved.candidateUrls) {
            val mp4File = File(workDir, "clip.mp4")
            try {
                onStatus("Downloading TikTok audio stream...")
                downloadBinary(
                    url = streamUrl,
                    outFile = mp4File,
                    userAgent = resolved.userAgent,
                    cookie = resolved.cookieHeader,
                    referer = resolved.referer
                )
                onStatus("Extracting compact audio track...")
                val m4aFile = File(workDir, "clip.m4a")
                if (stripAudioTrackFromMp4(mp4File, m4aFile)) {
                    mp4File.delete()
                    return m4aFile
                }
                m4aFile.delete()
                return mp4File
            } catch (e: Exception) {
                mp4File.delete()
                lastErr = e.localizedMessage ?: "Download error"
            }
        }
        throw IllegalStateException(lastErr)
    }

    private fun downloadTikTokViaTikWm(
        canonicalUrl: String,
        workDir: File,
        onStatus: (String) -> Unit
    ): File {
        val encoded = URLEncoder.encode(canonicalUrl, "UTF-8")
        val getReq = Request.Builder()
            .url("https://www.tikwm.com/api/?url=$encoded&hd=0")
            .header("User-Agent", MOBILE_UA)
            .header("Accept", "application/json")
            .get()
            .build()

        val jsonText = httpClient.newCall(getReq).execute().use { resp ->
            if (resp.isSuccessful) {
                resp.body?.string().orEmpty()
            } else {
                // Retry once via POST if GET failed
                val postReq = Request.Builder()
                    .url("https://www.tikwm.com/api/")
                    .header("User-Agent", MOBILE_UA)
                    .header("Accept", "application/json")
                    .post(FormBody.Builder().add("url", canonicalUrl).add("hd", "0").build())
                    .build()
                httpClient.newCall(postReq).execute().use { postResp ->
                    if (!postResp.isSuccessful) throw IllegalStateException("HTTP ${postResp.code}")
                    postResp.body?.string().orEmpty()
                }
            }
        }

        val root = JSONObject(jsonText)
        if (root.optInt("code", -1) != 0) {
            throw IllegalStateException(root.optString("msg", "Resolver error"))
        }
        val data = root.optJSONObject("data") ?: throw IllegalStateException("Empty payload")
        val playUrl = normalizeTikWmUrl(data.optString("play").ifBlank { data.optString("wmplay") })
        val musicUrl = normalizeTikWmUrl(data.optString("music"))

        if (playUrl != null) {
            val mp4File = File(workDir, "clip.mp4")
            downloadBinary(playUrl, mp4File, MOBILE_UA, cookie = "", referer = "https://www.tikwm.com/")
            onStatus("Extracting compact audio track...")
            val m4aFile = File(workDir, "clip.m4a")
            if (stripAudioTrackFromMp4(mp4File, m4aFile)) {
                mp4File.delete()
                return m4aFile
            }
            m4aFile.delete()
            return mp4File
        }

        if (musicUrl != null) {
            val mp3File = File(workDir, "clip.mp3")
            downloadBinary(musicUrl, mp3File, MOBILE_UA, cookie = "", referer = "https://www.tikwm.com/")
            return mp3File
        }

        throw IllegalStateException("No media URL in TikWM response")
    }

    private fun downloadBinary(
        url: String,
        outFile: File,
        userAgent: String,
        cookie: String,
        referer: String
    ) {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "*/*")
            .header("Accept-Encoding", "identity")
            .get()
        if (referer.isNotBlank()) builder.header("Referer", referer)
        if (cookie.isNotBlank()) builder.header("Cookie", cookie)

        httpClient.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val contentType = resp.header("Content-Type").orEmpty().lowercase()
            if (contentType.contains("text/html") || contentType.contains("application/json")) {
                throw IllegalStateException("Got $contentType instead of video")
            }
            val body = resp.body ?: throw IllegalStateException("Empty body")
            body.byteStream().use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        if (outFile.length() < 8 * 1024) {
            throw IllegalStateException("File too small (${outFile.length()} bytes)")
        }
    }

    private fun stripAudioTrackFromMp4(mp4File: File, outM4a: File): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        return runCatching {
            extractor.setDataSource(mp4File.absolutePath)
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = fmt
                    break
                }
            }
            if (audioTrackIndex < 0 || audioFormat == null) return false

            extractor.selectTrack(audioTrackIndex)
            muxer = MediaMuxer(outM4a.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxTrack = muxer!!.addTrack(audioFormat)
            muxer!!.start()

            val maxSize = if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(256 * 1024)
            } else {
                256 * 1024
            }
            val buffer = ByteBuffer.allocate(maxSize)
            val info = MediaCodec.BufferInfo()
            var sampleCount = 0

            while (true) {
                info.offset = 0
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.size = size
                info.presentationTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                info.flags = if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else {
                    0
                }
                muxer!!.writeSampleData(muxTrack, buffer, info)
                sampleCount++
                extractor.advance()
            }

            muxer!!.stop()
            sampleCount > 0 && outM4a.length() > 1024
        }.getOrDefault(false).also {
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun normalizeTikWmUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return if (trimmed.startsWith("/")) "https://www.tikwm.com$trimmed" else trimmed
    }

    private fun isTikTokUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("tiktok.com/") || lower.contains("tiktokv.com/")
    }

    private fun extractFirstUrl(text: String): String? {
        val regex = Regex("""https?://[^\s]+""")
        return regex.find(text.trim())?.value
    }

    companion object {
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}