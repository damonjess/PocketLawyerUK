package com.example.pocketlawyeruk.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

data class ResolvedTikTokStream(
    val candidateUrls: List<String>,
    val cookieHeader: String,
    val userAgent: String,
    val referer: String
)

object TikTokWebViewResolver {

    /**
     * Loads a TikTok page (embed or full URL) in an offscreen Chromium WebView,
     * intercepts the live CDN video request or hydration playAddr, and returns
     * the stream URL(s) alongside the live session cookies (including tt_chain_token).
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun resolve(
        context: Context,
        targetUrl: String,
        timeoutMs: Long = 16_000L
    ): ResolvedTikTokStream? = withContext(Dispatchers.Main) {
        val mainHandler = Handler(Looper.getMainLooper())
        val webView = WebView(context.applicationContext)

        try {
            val settings = webView.settings
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            // Strip "; wv" so TikTok serves standard mobile web video streams
            val cleanUa = settings.userAgentString.replace("; wv)", ")")
            settings.userAgentString = cleanUa

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(webView, true)

            // Give the offscreen WebView real viewport dimensions so TikTok's player mounts
            webView.layout(0, 0, 1080, 1920)

            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<ResolvedTikTokStream?> { cont ->
                    val finished = AtomicBoolean(false)
                    val interceptedUrls = mutableListOf<String>()

                    fun finishWith(urls: List<String>) {
                        val unique = urls.distinct().filter { it.startsWith("http") }
                        if (unique.isEmpty()) return
                        if (finished.compareAndSet(false, true)) {
                            cookieManager.flush()
                            val mergedCookies = buildMergedCookies(cookieManager, unique.first(), targetUrl)
                            mainHandler.removeCallbacksAndMessages(null)
                            cont.resume(
                                ResolvedTikTokStream(
                                    candidateUrls = unique,
                                    cookieHeader = mergedCookies,
                                    userAgent = cleanUa,
                                    referer = "https://www.tiktok.com/"
                                )
                            )
                        }
                    }

                    fun runDomProbe(finishIfFound: Boolean) {
                        if (finished.get()) return
                        webView.evaluateJavascript(EXTRACT_VIDEO_JS) { raw ->
                            if (finished.get() || raw.isNullOrBlank() || raw == "null") return@evaluateJavascript
                            val extracted = parseJsUrlList(raw)
                            if (extracted.isNotEmpty() && finishIfFound) {
                                synchronized(interceptedUrls) {
                                    finishWith(interceptedUrls + extracted)
                                }
                            }
                        }
                    }

                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val reqUrl = request?.url?.toString() ?: return null
                            if (!finished.get() && looksLikeTikTokMediaStream(reqUrl)) {
                                synchronized(interceptedUrls) {
                                    if (!interceptedUrls.contains(reqUrl)) {
                                        interceptedUrls.add(reqUrl)
                                    }
                                    finishWith(interceptedUrls.toList())
                                }
                            }
                            return null
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            // Nudge playback first so shouldInterceptRequest captures the live CDN URL;
                            // if autoplay doesn't fire, fall back to DOM/JSON playAddr extraction.
                            mainHandler.postDelayed({ runDomProbe(finishIfFound = false) }, 600L)
                            mainHandler.postDelayed({ runDomProbe(finishIfFound = true) }, 2400L)
                            mainHandler.postDelayed({ runDomProbe(finishIfFound = true) }, 5000L)
                        }
                    }

                    cont.invokeOnCancellation {
                        mainHandler.removeCallbacksAndMessages(null)
                    }

                    webView.loadUrl(targetUrl)
                }
            }
        } finally {
            mainHandler.removeCallbacksAndMessages(null)
            runCatching {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.destroy()
            }
        }
    }

    private fun buildMergedCookies(
        cookieManager: CookieManager,
        streamUrl: String,
        pageUrl: String
    ): String {
        val pairs = linkedMapOf<String, String>()
        listOf("https://www.tiktok.com/", pageUrl, streamUrl).forEach { u ->
            cookieManager.getCookie(u)?.split(";")?.forEach { rawPair ->
                val trimmed = rawPair.trim()
                val eq = trimmed.indexOf('=')
                if (eq > 0) {
                    pairs[trimmed.substring(0, eq).trim()] = trimmed.substring(eq + 1).trim()
                }
            }
        }
        return pairs.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun looksLikeTikTokMediaStream(url: String): Boolean {
        val lower = url.lowercase()
        val blockedExt = listOf(".js", ".css", ".png", ".jpg", ".jpeg", ".webp", ".avif", ".gif", ".svg", ".woff", ".ttf", ".ico", ".json")
        val pathOnly = lower.substringBefore('?')
        if (blockedExt.any { pathOnly.endsWith(it) }) return false
        if (lower.contains("tiktokcdn") && (lower.contains("tplv-") || lower.contains("tos-maliva-i-") || lower.contains("tos-useast") && lower.contains("-i-"))) {
            return false // cover/avatar images
        }

        return lower.contains("/video/tos/") ||
            lower.contains("mime_type=video_mp4") ||
            lower.contains("mime_type=video_") ||
            lower.contains("mime_type=audio_") ||
            (lower.contains("tiktok") && pathOnly.endsWith(".mp4"))
    }

    private fun parseJsUrlList(rawResult: String): List<String> {
        return runCatching {
            val unwrapped = JSONObject("""{"v":$rawResult}""").optString("v", "")
            val arr = JSONArray(unwrapped)
            buildList {
                for (i in 0 until arr.length()) {
                    val u = arr.optString(i).trim()
                    if (u.startsWith("http")) add(u)
                }
            }
        }.getOrDefault(emptyList())
    }

    private const val EXTRACT_VIDEO_JS = """
        (function() {
          var urls = [];
          function push(u) {
            if (typeof u === 'string' && u.indexOf('http') === 0 && urls.indexOf(u) === -1) {
              urls.push(u);
            }
          }
          try {
            var vids = document.querySelectorAll('video');
            for (var i = 0; i < vids.length; i++) {
              push(vids[i].currentSrc);
              push(vids[i].src);
              try { vids[i].muted = true; vids[i].play(); } catch (e) {}
            }
            var srcs = document.querySelectorAll('video source');
            for (var j = 0; j < srcs.length; j++) {
              push(srcs[j].src);
            }
            var playBtn = document.querySelector('[class*="play"], [data-e2e*="play"], button');
            if (playBtn) { try { playBtn.click(); } catch (e) {} }
          } catch (e) {}

          try {
            var ids = ['__UNIVERSAL_DATA_FOR_REHYDRATION__', '__FRONTITY_CONNECT_STATE__', 'SIGI_STATE'];
            for (var k = 0; k < ids.length; k++) {
              var el = document.getElementById(ids[k]);
              if (!el || !el.textContent) continue;
              var txt = el.textContent;
              var re = /"(?:playAddr|downloadAddr|play_addr)"\s*:\s*"(https?:[^"]+)"/g;
              var m;
              while ((m = re.exec(txt)) !== null) {
                push(m[1].replace(/\\u002F/gi, '/').replace(/\\u0026/gi, '&').replace(/\\\//g, '/'));
              }
              var re2 = /"UrlList"\s*:\s*\[\s*"(https?:[^"]+)"/g;
              while ((m = re2.exec(txt)) !== null) {
                push(m[1].replace(/\\u002F/gi, '/').replace(/\\u0026/gi, '&').replace(/\\\//g, '/'));
              }
            }
          } catch (e) {}
          return JSON.stringify(urls);
        })();
    """
}