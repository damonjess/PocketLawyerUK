package com.example.pocketlawyeruk.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.example.pocketlawyeruk.BuildConfig
import com.example.pocketlawyeruk.model.*
import com.example.pocketlawyeruk.ui.screens.EncounterType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

class LegalAuditService(
    private val apiKeyProvider: () -> String = { BuildConfig.GEMINI_API_KEY.trim() }
) {
    constructor(context: Context) : this({ getStoredApiKey(context) })

    private val auditClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(100, TimeUnit.SECONDS)
        .build()

    // Longer timeout strictly for >18 MB Gemini Files API uploads
    private val uploadClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    // Fast 20s timeout for text-only calls (Statute Intel, Law AI chat, case follow-ups)
    private val quickClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    // ---------------- 1. VIDEO / AUDIO AUDITING (INLINE + FILES API) ----------------

    suspend fun analyzeAudioFile(
        audioFile: File,
        sourceUrl: String?,
        encounterType: EncounterType,
        confrontingParty: ConfrontingParty = ConfrontingParty.AUTO,
        auditDepth: AuditDepth = AuditDepth.FAST,
        bailiffConfig: BailiffAuditConfig? = null,
        everydayConfig: EverydayAuditConfig? = null,
        onStatus: (String) -> Unit = {}
    ): AuditReport = withContext(Dispatchers.IO) {
        val mimeType = when (audioFile.extension.lowercase()) {
            "m4a" -> "audio/mp4"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            else -> "audio/mp3"
        }
        val mediaPart = buildMediaPartFromFile(audioFile, mimeType, onStatus)
        runAuditWithMediaPart(
            mediaPart, sourceUrl, null, encounterType, confrontingParty, auditDepth, bailiffConfig, everydayConfig, onStatus
        )
    }

    suspend fun analyzeVideoUri(
        context: Context,
        videoUri: Uri,
        sourceUrl: String?,
        encounterType: EncounterType,
        confrontingParty: ConfrontingParty = ConfrontingParty.AUTO,
        auditDepth: AuditDepth = AuditDepth.FAST,
        bailiffConfig: BailiffAuditConfig? = null,
        everydayConfig: EverydayAuditConfig? = null,
        onStatus: (String) -> Unit = {}
    ): AuditReport = withContext(Dispatchers.IO) {
        val mimeType = context.contentResolver.getType(videoUri) ?: "video/mp4"
        val tempFile = copyUriToTempFile(context, videoUri, "upload_clip")
        try {
            val mediaPart = buildMediaPartFromFile(tempFile, mimeType, onStatus)
            runAuditWithMediaPart(
                mediaPart, sourceUrl, videoUri.toString(), encounterType, confrontingParty, auditDepth, bailiffConfig, everydayConfig, onStatus
            )
        } finally {
            tempFile.delete()
        }
    }

    private suspend fun buildMediaPartFromFile(
        file: File,
        mimeType: String,
        onStatus: (String) -> Unit
    ): JSONObject {
        return if (file.length() <= INLINE_LIMIT_BYTES) {
            val base64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
            JSONObject().put("inline_data", JSONObject().apply {
                put("mime_type", mimeType)
                put("data", base64)
            })
        } else {
            onStatus("Long clip detected — uploading via Gemini Files API...")
            val fileUri = uploadViaFilesApi(file, mimeType, onStatus)
            JSONObject().put("file_data", JSONObject().apply {
                put("mime_type", mimeType)
                put("file_uri", fileUri)
            })
        }
    }

    private suspend fun uploadViaFilesApi(
        file: File,
        mimeType: String,
        onStatus: (String) -> Unit
    ): String {
        val cleanKey = apiKeyProvider().trim()

        // Step 1: Start resumable upload session
        val metaJson = JSONObject().put("file", JSONObject().put("display_name", file.name))
        val startReq = Request.Builder()
            .url("https://generativelanguage.googleapis.com/upload/v1beta/files")
            .header("x-goog-api-key", cleanKey)
            .header("X-Goog-Upload-Protocol", "resumable")
            .header("X-Goog-Upload-Command", "start")
            .header("X-Goog-Upload-Header-Content-Length", file.length().toString())
            .header("X-Goog-Upload-Header-Content-Type", mimeType)
            .post(metaJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val uploadUrl = uploadClient.newCall(startReq).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Files API start error (${resp.code}): ${resp.body?.string()}")
            resp.header("X-Goog-Upload-URL")
                ?: throw IllegalStateException("Missing X-Goog-Upload-URL header from Gemini Files API.")
        }

        // Step 2: Upload bytes and finalize
        val uploadReq = Request.Builder()
            .url(uploadUrl)
            .header("X-Goog-Upload-Offset", "0")
            .header("X-Goog-Upload-Command", "upload, finalize")
            .post(file.asRequestBody(mimeType.toMediaType()))
            .build()

        val (fileUri, fileName, initialState) = uploadClient.newCall(uploadReq).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("Files API upload error (${resp.code}): $body")
            val fileObj = JSONObject(body).getJSONObject("file")
            Triple(fileObj.getString("uri"), fileObj.getString("name"), fileObj.optString("state", "ACTIVE"))
        }

        // Step 3: Poll until Gemini finishes indexing the long file
        if (initialState != "ACTIVE") {
            onStatus("Processing long media file on Gemini...")
            repeat(30) {
                delay(2000)
                val pollReq = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/$fileName")
                    .header("x-goog-api-key", cleanKey)
                    .get()
                    .build()
                val state = uploadClient.newCall(pollReq).execute().use { resp ->
                    JSONObject(resp.body?.string().orEmpty()).optString("state", "PROCESSING")
                }
                if (state == "ACTIVE") return fileUri
                if (state == "FAILED") throw IllegalStateException("Gemini failed to process this media file.")
            }
        }
        return fileUri
    }

    private suspend fun runAuditWithMediaPart(
        mediaPart: JSONObject,
        sourceUrl: String?,
        videoUriString: String?,
        encounterType: EncounterType,
        confrontingParty: ConfrontingParty = ConfrontingParty.AUTO,
        auditDepth: AuditDepth = AuditDepth.FAST,
        bailiffConfig: BailiffAuditConfig? = null,
        everydayConfig: EverydayAuditConfig? = null,
        onStatus: (String) -> Unit = {}
    ): AuditReport {
        val systemPrompt = when {
            bailiffConfig != null -> UK_BAILIFF_WARRANT_PROMPT
            everydayConfig != null -> UK_EVERYDAY_LAW_PROMPT
            else -> UK_LEGAL_AUDIT_PROMPT
        }

        val userPrompt = when {
            bailiffConfig != null ->
                "${bailiffConfig.callerRole.promptDirective} " +
                    "Debt/Warrant context: ${bailiffConfig.debtType.promptHint}. " +
                    "Listen carefully to the ENTIRE phone call or video from 00:00 to the very last second without skipping sections. " +
                    "Step 1: Transcribe EVERY meaningful spoken turn chronologically in fullTranscript. " +
                    "Step 2: In timeline, fact-check EVERY legal claim, threat, warrant claim, notice period claim, fee claim, locksmith/police threat, or right stated by either side (do NOT stop at 3 cards if more happens). " +
                    "Auto-detect ENGLAND_WALES, SCOTLAND, or NORTHERN_IRELAND and apply current UK law as of November 2026. Return ONLY raw valid JSON."

            everydayConfig != null ->
                "${everydayConfig.topic.promptDirective} " +
                    "Listen carefully to the ENTIRE video or audio from 00:00 to the very last second without skipping sections. " +
                    "Step 1: Transcribe EVERY meaningful spoken turn chronologically in fullTranscript. " +
                    "Step 2: In timeline, fact-check EVERY legal claim, entry/search demand, eviction/notice claim, refund refusal, fine/ticket demand, or citizen counter-claim across the whole clip (do NOT stop at 3 cards if more happens). " +
                    "Auto-detect ENGLAND_WALES, SCOTLAND, or NORTHERN_IRELAND and apply current UK law as of November 2026. Return ONLY raw valid JSON."

            else ->
                "${confrontingParty.promptDirective} " +
                    "Listen carefully to the ENTIRE audio from 00:00 to the very last second without skipping any middle or late sections. " +
                    "Step 1: Transcribe EVERY meaningful spoken turn chronologically in fullTranscript (do NOT compress a multi-minute video into 5–6 highlight lines). " +
                    "Step 2: In timeline, create a separate card for EVERY legally or procedurally notable statement across the whole clip — including every stated ground for detention/search, every refusal to give name/collar number, every filming restriction, every threat or demand, every rude/unprofessional remark, and every accurate or mistaken right stated by the filmer. " +
                    "Do NOT stop at 3 cards if more happens. Auto-detect ENGLAND_WALES, SCOTLAND, or NORTHERN_IRELAND. " +
                    "Category hint: ${encounterType.statuteSubtitle}. Return ONLY raw valid JSON."
        }

        val bodyJson = JSONObject().apply {
            put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().apply {
                    put(mediaPart)
                    put(JSONObject().put("text", userPrompt))
                })
            }))
            put("safetySettings", buildPermissiveSafetySettings())
            put("generationConfig", JSONObject().apply {
                put("maxOutputTokens", 16384)
                put("responseMimeType", "application/json")
                put("thinkingConfig", JSONObject().put("thinkingLevel", "medium"))
            })
        }

        val selectedModels = if (auditDepth == AuditDepth.DEEP) DEEP_AUDIT_MODELS else AUDIT_MODELS
        val rawText = executeGeminiText(
            bodyJson = bodyJson,
            models = selectedModels,
            httpClient = auditClient,
            onStatus = onStatus
        )
        return parseAuditJson(
            rawJson = rawText,
            sourceUrl = sourceUrl,
            videoUriString = videoUriString,
            encounterType = encounterType,
            confrontingParty = confrontingParty,
            isNonPoliceMode = bailiffConfig != null || everydayConfig != null
        )
    }

    private fun buildPermissiveSafetySettings(): JSONArray = JSONArray().apply {
        listOf(
            "HARM_CATEGORY_HARASSMENT",
            "HARM_CATEGORY_HATE_SPEECH",
            "HARM_CATEGORY_SEXUALLY_EXPLICIT",
            "HARM_CATEGORY_DANGEROUS_CONTENT"
        ).forEach { category ->
            put(JSONObject().apply {
                put("category", category)
                put("threshold", "BLOCK_NONE")
            })
        }
    }

    // ---------------- 2. FINE / LETTER / SMALL-PRINT SCANNER ----------------

    suspend fun analyzeDocumentOrText(
        context: Context,
        documentUris: List<Uri>,
        pastedText: String?,
        userContextNote: String = ""
    ): DocumentScanResult = withContext(Dispatchers.IO) {
        val parts = JSONArray()

        documentUris.forEachIndexed { index, uri ->
            val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
            val tempFile = copyUriToTempFile(context, uri, "doc_scan_$index")
            try {
                if (documentUris.size > 1) {
                    parts.put(JSONObject().put("text", "ATTACHMENT PAGE ${index + 1} OF ${documentUris.size}:"))
                }
                parts.put(buildMediaPartFromFile(tempFile, mimeType) {})
            } finally {
                tempFile.delete()
            }
        }

        if (!pastedText.isNullOrBlank()) {
            parts.put(JSONObject().put("text", "DOCUMENT / CONTRACT / SMALL PRINT TEXT:\n$pastedText"))
        }

        parts.put(JSONObject().put(
            "text",
            "Analyze ALL pages/text of this UK document, employment contract, dismissal letter, fine, notice, or T&Cs under current UK law (as of November 2026). " +
                "Additional context from user: ${userContextNote.ifBlank { "None" }}. " +
                "Check statutory deadlines, missing mandatory terms, and unlawful or outdated clauses, then draft a formal, polite, statute-cited UK reply, grievance, or appeal letter ready to send. Return ONLY valid JSON."
        ))

        val bodyJson = JSONObject().apply {
            put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", UK_DOC_SCANNER_PROMPT))))
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", parts)
            }))
            put("safetySettings", buildPermissiveSafetySettings())
            put("generationConfig", JSONObject().apply {
                put("maxOutputTokens", 16384)
                put("thinkingConfig", JSONObject().put("thinkingLevel", "medium"))
            })
        }

        parseDocumentScanJson(executeGeminiText(bodyJson))
    }

    // ---------------- 3. TAP-TO-READ UK STATUTE LOOKUP ----------------

    suspend fun lookupUkStatute(citation: String, jurisdiction: UkJurisdiction): StatuteLookupResult =
        withContext(Dispatchers.IO) {
            val prompt = """
                Explain this UK legal provision accurately: "$citation" (Jurisdiction: ${jurisdiction.displayName}).
                Return ONLY raw valid JSON with no markdown or extra commentary:
                {
                  "citation": "$citation",
                  "actTitle": "Full official Act, Byelaw, or Guidance name",
                  "jurisdiction": "${jurisdiction.displayName}",
                  "plainEnglishSummary": "Clear 2-3 sentence plain-English explanation of what this provision says and means for the public.",
                  "keyExceptions": ["Important exception or limit 1", "Important exception or limit 2"],
                  "legislationUrl": "https://www.legislation.gov.uk/search?title=..."
                }
            """.trimIndent()

            val bodyJson = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                }))
                put("safetySettings", buildPermissiveSafetySettings())
                put("generationConfig", JSONObject().apply {
                    put("maxOutputTokens", 4096)
                    put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
                })
            }

            val raw = executeGeminiText(bodyJson, models = CHAT_MODELS, httpClient = quickClient)
            val defaultUrl = "https://www.legislation.gov.uk/search?title=${Uri.encode(citation)}"

            runCatching {
                val json = JSONObject(extractJsonObject(raw))
                val exceptions = mutableListOf<String>()
                json.optJSONArray("keyExceptions")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val line = arr.optString(i)
                        if (line.isNotBlank()) exceptions.add(line)
                    }
                }
                val returnedUrl = json.optString("legislationUrl", "")
                StatuteLookupResult(
                    citation = json.optString("citation", citation),
                    actTitle = json.optString("actTitle", citation),
                    jurisdiction = json.optString("jurisdiction", jurisdiction.displayName),
                    plainEnglishSummary = json.optString("plainEnglishSummary", raw.trim()),
                    keyExceptions = exceptions,
                    legislationUrl = if (returnedUrl.startsWith("https://")) returnedUrl else defaultUrl
                )
            }.getOrElse {
                val salvagedSummary = Regex(""""plainEnglishSummary"\s*:\s*"([^"]+)""")
                    .find(raw)?.groupValues?.get(1)
                    ?.replace("\\n", "\n")
                    ?.replace("\\\"", "\"")
                    ?: raw.trim()

                StatuteLookupResult(
                    citation = citation,
                    actTitle = citation,
                    jurisdiction = jurisdiction.displayName,
                    plainEnglishSummary = salvagedSummary,
                    keyExceptions = emptyList(),
                    legislationUrl = defaultUrl
                )
            }
        }

    // ---------------- 4. GROUNDED UK LAW CHAT & AUDIT FOLLOW-UP ----------------

    suspend fun chatUkLaw(history: List<ChatMessage>): ChatMessage = withContext(Dispatchers.IO) {
        val contentsArray = JSONArray()
        history.forEach { msg ->
            // Skip any previous local error bubbles so they don't confuse follow-up turns
            if (msg.text.startsWith("Error ") || msg.text.startsWith("No response returned")) return@forEach
            contentsArray.put(JSONObject().apply {
                put("role", if (msg.isUser) "user" else "model")
                put("parts", JSONArray().put(JSONObject().put("text", msg.text)))
            })
        }

        val requestBody = JSONObject().apply {
            put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", UK_GENERAL_LAW_PROMPT))))
            put("contents", contentsArray)
            put("generationConfig", JSONObject().apply {
                put("maxOutputTokens", 4096)
                put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
            })
        }

        val root = executeGeminiRaw(requestBody, models = CHAT_MODELS, httpClient = quickClient)
        val candidate = root.getJSONArray("candidates").getJSONObject(0)
        val answerText = extractAllTextParts(candidate)

        ChatMessage(
            isUser = false,
            text = answerText,
            sources = emptyList()
        )
    }

    suspend fun askFollowUp(report: AuditReport, question: String): String = withContext(Dispatchers.IO) {
        val transcriptBlock = report.fullTranscript.joinToString("\n") { "[${it.timestampSeconds}s] ${it.speaker}: ${it.text}" }
        val prompt = """
            You are Pocket Lawyer UK. Answer strictly under ${report.jurisdiction.displayName} law. Cite exact Acts and sections. Plain text only.
            Encounter: ${report.title} (${report.encounterType.statuteSubtitle})
            Summary: ${report.takeaway}
            Transcript:
            $transcriptBlock

            User Question: $question
        """.trimIndent()

        val bodyJson = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", prompt)))
            }))
            put("safetySettings", buildPermissiveSafetySettings())
            put("generationConfig", JSONObject().apply {
                put("maxOutputTokens", 4096)
                put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
            })
        }
        executeGeminiText(bodyJson, models = CHAT_MODELS, httpClient = quickClient)
    }

    // ---------------- 5. LIVE CO-PILOT MANUAL COACH & TRANSCRIPT SAVE ----------------

    suspend fun getLiveCoachAdvice(
        recentTranscript: String,
        partyMode: LivePartyMode,
        jurisdiction: UkJurisdiction
    ): LiveCoachAdvice = withContext(Dispatchers.IO) {
        val partyText = if (partyMode == LivePartyMode.POLICE) "UK Police Officer(s)" else "Private Staff / Security"
        val prompt = """
            LIVE UK ENCOUNTER CO-PILOT (${jurisdiction.displayName}).
            Confronting party: $partyText.
            Give ULTRA-BRIEF tactical legal advice for what is happening right now in this live transcript.
            Transcript:
            $recentTranscript

            Return ONLY raw valid JSON:
            {
              "canRefuse": "Short 1-sentence thing the user can lawfully refuse right now",
              "mustComply": "Short 1-sentence thing the user MUST lawfully comply with right now",
              "sayNow": "Calm, polite 1-2 sentence exact script to say immediately",
              "statute": "Primary UK Act & section"
            }
        """.trimIndent()

        val bodyJson = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", prompt)))
            }))
            put("safetySettings", buildPermissiveSafetySettings())
            put("generationConfig", JSONObject().apply {
                put("maxOutputTokens", 2048)
                put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
            })
        }

        val raw = executeGeminiText(bodyJson, models = CHAT_MODELS, httpClient = quickClient)
        runCatching {
            val json = JSONObject(extractJsonObject(raw))
            LiveCoachAdvice(
                canRefuse = json.optString("canRefuse", "Giving personal details without a valid statutory power."),
                mustComply = json.optString("mustComply", "Stay calm and do not physically obstruct."),
                sayNow = json.optString("sayNow", raw.trim()),
                statute = json.optString("statute", "UK Law")
            )
        }.getOrElse {
            LiveCoachAdvice(
                canRefuse = "Review advice below.",
                mustComply = "Remain calm and lawful.",
                sayNow = raw.trim(),
                statute = jurisdiction.displayName
            )
        }
    }

    suspend fun analyzeLiveTranscript(
        transcriptLines: List<TranscriptLine>,
        partyMode: LivePartyMode,
        jurisdiction: UkJurisdiction
    ): AuditReport = withContext(Dispatchers.IO) {
        val transcriptText = transcriptLines.joinToString("\n") {
            val ts = "%02d:%02d".format(it.timestampSeconds / 60, it.timestampSeconds % 60)
            "[$ts] ${it.text}"
        }
        val partyDirective = if (partyMode == LivePartyMode.POLICE) {
            "CONFIRMED: Confronting party is UK POLICE (side=\"OFFICER\")."
        } else {
            "CONFIRMED: Confronting party is PRIVATE STAFF / SECURITY (side=\"STAFF_SECURITY\"); mark all GOWISELY steps as SKIPPED."
        }

        val bodyJson = JSONObject().apply {
            put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", UK_LEGAL_AUDIT_PROMPT))))
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put(
                    "text",
                    "$partyDirective Jurisdiction: ${jurisdiction.name}. " +
                        "Audit this live-captured UK encounter transcript and return ONLY valid JSON:\n\n$transcriptText"
                )))
            }))
            put("safetySettings", buildPermissiveSafetySettings())
            put("generationConfig", JSONObject().apply {
                put("maxOutputTokens", 8192)
                put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
            })
        }

        val encounterType = if (partyMode == LivePartyMode.POLICE) EncounterType.PEDESTRIAN else EncounterType.FILMING
        val raw = executeGeminiText(bodyJson, models = CHAT_MODELS, httpClient = quickClient)
        parseAuditJson(raw, sourceUrl = null, videoUriString = null, encounterType = encounterType)
    }

    // ---------------- INTERNAL HELPERS & PARSERS ----------------

    private fun adaptBodyForModel(original: JSONObject, model: String): JSONObject {
        val copy = JSONObject(original.toString())

        // Gemma 4 requires "role": "system" on system_instruction (avoids HTTP 500)
        copy.optJSONObject("system_instruction")?.let { sys ->
            if (!sys.has("role")) sys.put("role", "system")
        }

        // Gemma 4 only accepts thinkingLevel "minimal" or "high" (rejects "low" / "medium" with HTTP 400)
        if (model.startsWith("gemma-4", ignoreCase = true)) {
            copy.optJSONObject("generationConfig")
                ?.optJSONObject("thinkingConfig")
                ?.let { tc ->
                    val lvl = tc.optString("thinkingLevel", "minimal").lowercase()
                    tc.put("thinkingLevel", if (lvl == "high" || lvl == "medium") "high" else "minimal")
                }
        }
        return copy
    }

    private suspend fun executeGeminiRaw(
        bodyJson: JSONObject,
        models: List<String> = AUDIT_MODELS,
        httpClient: OkHttpClient = auditClient,
        onStatus: (String) -> Unit = {}
    ): JSONObject {
        val cleanKey = apiKeyProvider().trim()
        if (cleanKey.isEmpty()) throw IllegalStateException(MISSING_KEY_MESSAGE)
        val errors = mutableListOf<String>()

        // Pass 1 tries all models; Pass 2 retries only models that returned 503 after a brief cooldown
        var queue = models
        for (pass in 1..2) {
            val busyModels = mutableListOf<String>()
            if (pass == 2) {
                onStatus("Gemini servers busy (503) — retrying in 4s...")
                delay(4000)
            }

            for ((index, model) in queue.withIndex()) {
                currentCoroutineContext().ensureActive()
                onStatus(
                    when {
                        pass == 2 -> "Retry (high demand): $model..."
                        index == 0 -> "Analysing with $model..."
                        else -> "Failover ${index + 1}/${queue.size}: $model..."
                    }
                )

                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
                val payload = adaptBodyForModel(bodyJson, model)
                val req = Request.Builder()
                    .url(url)
                    .header("x-goog-api-key", cleanKey)
                    .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()

                val call = httpClient.newCall(req)
                val cancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                    if (cause != null) call.cancel()
                }

                try {
                    call.execute().use { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (resp.isSuccessful) {
                            val root = JSONObject(body)
                            val candidate = root.optJSONArray("candidates")?.optJSONObject(0)
                            val extracted = if (candidate != null) extractAllTextParts(candidate) else ""

                            if (extracted.isNotBlank()) return root

                            val finishReason = candidate?.optString("finishReason")
                                ?: root.optJSONObject("promptFeedback")?.optString("blockReason")
                                ?: "EMPTY_CANDIDATE"
                            errors.add("$model: empty ($finishReason)")
                            return@use
                        }

                        if (resp.code == 401 || (resp.code in listOf(400, 403) && isInvalidApiKeyResponse(body))) {
                            throw IllegalStateException(INVALID_KEY_MESSAGE)
                        }
                        if (resp.code == 402) throw IllegalStateException(
                            "Billing is not active for this project (402): prepayment credits are depleted. Add credits at aistudio.google.com/billing, or unlink the billing account in Google Cloud Console to use the free tier."
                        )

                        if (resp.code in listOf(400, 404, 429, 500, 503)) {
                            if (resp.code == 503) busyModels.add(model)
                            val shortMsg = runCatching {
                                JSONObject(body).getJSONObject("error").optString("message").take(60)
                            }.getOrDefault("")
                            errors.add("$model: HTTP ${resp.code}${if (shortMsg.isNotBlank()) " ($shortMsg)" else ""}")
                            return@use
                        }

                        throw IllegalStateException("Gemini API error (${resp.code}) on $model: ${apiErrorMessage(body)}")
                    }
                } catch (e: IOException) {
                    currentCoroutineContext().ensureActive()
                    errors.add("$model: timeout/network (${e.localizedMessage ?: e.message ?: "unknown"})")
                } finally {
                    cancelHandle?.dispose()
                }
            }

            if (busyModels.isEmpty()) break
            queue = busyModels
        }

        throw IllegalStateException(
            "All models failed:\n" + errors.distinct().joinToString("\n")
        )
    }

    private fun isInvalidApiKeyResponse(body: String): Boolean =
        body.contains("API_KEY_INVALID", ignoreCase = true) ||
            body.contains("API key not valid", ignoreCase = true) ||
            body.contains("API key expired", ignoreCase = true)

    private fun apiErrorMessage(body: String): String =
        runCatching { JSONObject(body).getJSONObject("error").optString("message") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: body.take(300)

    private suspend fun executeGeminiText(
        bodyJson: JSONObject,
        models: List<String> = AUDIT_MODELS,
        httpClient: OkHttpClient = auditClient,
        onStatus: (String) -> Unit = {}
    ): String {
        val root = executeGeminiRaw(bodyJson, models, httpClient, onStatus)
        val candidate = root.getJSONArray("candidates").getJSONObject(0)
        return extractAllTextParts(candidate)
    }

    private fun extractAllTextParts(candidate: JSONObject): String {
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            // Skip internal reasoning/thought chunks
            if (part.optBoolean("thought", false)) continue
            val text = part.optString("text", "")
            if (text.isNotBlank()) {
                if (sb.isNotEmpty()) sb.append("\n")
                sb.append(text)
            }
        }
        // Strip any stray HTML tags like <u> or <br>
        return sb.toString()
            .replace(Regex("</?(u|b|i|br|p|span)[^>]*>", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun extractJsonObject(raw: String): String {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start >= 0 && end > start) return raw.substring(start, end + 1)
        return raw.trim()
    }

    private fun copyUriToTempFile(context: Context, uri: Uri, prefix: String): File {
        val outFile = File(context.cacheDir, "${prefix}_${UUID.randomUUID()}.bin")
        context.contentResolver.openInputStream(uri)?.use { input ->
            outFile.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Unable to read selected file.")
        return outFile
    }

    private fun parseAuditJson(
        rawJson: String,
        sourceUrl: String?,
        videoUriString: String?,
        encounterType: EncounterType,
        confrontingParty: ConfrontingParty = ConfrontingParty.AUTO,
        isNonPoliceMode: Boolean = false
    ): AuditReport {
        val json = JSONObject(extractJsonObject(rawJson))

        val jurisdiction = runCatching {
            UkJurisdiction.valueOf(json.optString("jurisdiction", "ENGLAND_WALES").uppercase())
        }.getOrDefault(UkJurisdiction.ENGLAND_WALES)

        val transcript = mutableListOf<TranscriptLine>()
        json.optJSONArray("fullTranscript")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                transcript.add(TranscriptLine(o.optInt("timestampSeconds"), o.optString("speaker", "Speaker"), o.optString("text")))
            }
        }

        val rights = mutableListOf<String>()
        json.optJSONArray("yourRights")?.let { arr ->
            for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let(rights::add)
        }

        val whatIfJson = json.optJSONObject("whatIfGuide")
        val refuse = mutableListOf<String>()
        val mustDo = mutableListOf<String>()
        whatIfJson?.optJSONArray("whatYouCanRefuse")?.let { arr ->
            for (i in 0 until arr.length()) refuse.add(arr.optString(i))
        }
        whatIfJson?.optJSONArray("whatYouMustDo")?.let { arr ->
            for (i in 0 until arr.length()) mustDo.add(arr.optString(i))
        }

        val gowisely = mutableListOf<GowiselyItem>()
        json.optJSONArray("gowisely")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val status = runCatching { GowiselyStatus.valueOf(o.optString("status", "SKIPPED").uppercase()) }
                    .getOrDefault(GowiselyStatus.SKIPPED)
                gowisely.add(GowiselyItem(o.optString("step"), status, o.optString("note")))
            }
        }

        val timeline = mutableListOf<AuditedStatement>()
        json.optJSONArray("timeline")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val verdict = runCatching { StatementVerdict.valueOf(o.optString("verdict", "LAWFUL").uppercase()) }
                    .getOrDefault(StatementVerdict.LAWFUL)
                var speakerLabel = o.optString("speaker", "Speaker")
                val rawSide = o.optString("side", "").uppercase()
                var side = when {
                    rawSide == "OFFICER" || rawSide == "POLICE" -> SpeakerSide.OFFICER
                    rawSide == "STAFF_SECURITY" || rawSide == "STAFF" || rawSide == "SECURITY" -> SpeakerSide.STAFF_SECURITY
                    rawSide == "PUBLIC" || rawSide == "AUDITOR" || rawSide == "FILMER" -> SpeakerSide.PUBLIC
                    speakerLabel.contains("officer", true) || speakerLabel.contains("police", true) -> SpeakerSide.OFFICER
                    speakerLabel.contains("staff", true) || speakerLabel.contains("security", true) ||
                        speakerLabel.contains("guard", true) || speakerLabel.contains("manager", true) -> SpeakerSide.STAFF_SECURITY
                    else -> SpeakerSide.PUBLIC
                }

                // Hard-enforce the user's Police / Staff toggle
                when (confrontingParty) {
                    ConfrontingParty.POLICE -> {
                        if (side == SpeakerSide.STAFF_SECURITY) {
                            side = SpeakerSide.OFFICER
                            speakerLabel = "Officer"
                        }
                    }
                    ConfrontingParty.STAFF -> {
                        if (side == SpeakerSide.OFFICER) {
                            side = SpeakerSide.STAFF_SECURITY
                            speakerLabel = "Staff"
                        }
                    }
                    else -> Unit
                }

                timeline.add(
                    AuditedStatement(
                        timestampSeconds = o.optInt("timestampSeconds"),
                        speaker = speakerLabel,
                        side = side,
                        quote = o.optString("quote"),
                        verdict = verdict,
                        citation = o.optString("citation"),
                        explanation = o.optString("explanation")
                    )
                )
            }
        }

        // Hide GOWISELY cleanly for staff-only or bailiff/warrant/everyday audits
        val finalGowisely = if (isNonPoliceMode || confrontingParty == ConfrontingParty.STAFF) {
            gowisely.map { it.copy(status = GowiselyStatus.SKIPPED) }
        } else {
            gowisely
        }

        val officerViolations = when {
            isNonPoliceMode -> timeline.count { it.side == SpeakerSide.OFFICER && it.verdict != StatementVerdict.LAWFUL }
            confrontingParty == ConfrontingParty.POLICE -> timeline.count { it.side == SpeakerSide.OFFICER && it.verdict != StatementVerdict.LAWFUL }
            confrontingParty == ConfrontingParty.STAFF -> 0
            else -> timeline.count { it.side == SpeakerSide.OFFICER && it.verdict != StatementVerdict.LAWFUL }
        }
        val staffViolations = when {
            isNonPoliceMode -> timeline.count { it.side == SpeakerSide.STAFF_SECURITY && it.verdict != StatementVerdict.LAWFUL }
            confrontingParty == ConfrontingParty.STAFF -> timeline.count { it.side == SpeakerSide.STAFF_SECURITY && it.verdict != StatementVerdict.LAWFUL }
            confrontingParty == ConfrontingParty.POLICE -> 0
            else -> timeline.count { it.side == SpeakerSide.STAFF_SECURITY && it.verdict != StatementVerdict.LAWFUL }
        }
        val publicMyths = timeline.count { it.side == SpeakerSide.PUBLIC && it.verdict != StatementVerdict.LAWFUL }

        val today = SimpleDateFormat("d MMM", Locale.UK).format(Date())
        return AuditReport(
            id = UUID.randomUUID().toString(),
            title = json.optString("title", "${encounterType.label} Audit"),
            date = today,
            sourceUrl = sourceUrl,
            videoUriString = videoUriString,
            encounterType = encounterType,
            jurisdiction = jurisdiction,
            complianceScore = json.optInt("complianceScore", 0).coerceIn(0, 100),
            scoreSummary = json.optString("scoreSummary", ""),
            officerViolationCount = officerViolations,
            staffViolationCount = staffViolations,
            publicMythCount = publicMyths,
            fullTranscript = transcript,
            yourRights = rights,
            whatIfGuide = WhatIfGuide(refuse, mustDo, whatIfJson?.optString("exactScriptToSay").orEmpty()),
            gowiselyChecklist = finalGowisely,
            timeline = timeline,
            takeaway = json.optString("takeaway", "Review the citations above for details."),
            isExample = false
        )
    }

    private fun parseDocumentScanJson(rawJson: String): DocumentScanResult {
        val json = JSONObject(extractJsonObject(rawJson))
        val deadlines = mutableListOf<String>()
        json.optJSONArray("keyDeadlines")?.let { arr ->
            for (i in 0 until arr.length()) deadlines.add(arr.optString(i))
        }
        val flags = mutableListOf<DocumentRedFlag>()
        json.optJSONArray("redFlags")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                flags.add(
                    DocumentRedFlag(
                        severity = o.optString("severity", "INFO"),
                        clause = o.optString("clause"),
                        issue = o.optString("issue"),
                        citation = o.optString("citation")
                    )
                )
            }
        }
        val today = SimpleDateFormat("d MMM yyyy", Locale.UK).format(Date())
        return DocumentScanResult(
            title = json.optString("title", "UK Document Review"),
            documentType = json.optString("documentType", "Legal Document"),
            dateScanned = today,
            jurisdiction = runCatching {
                UkJurisdiction.valueOf(json.optString("jurisdiction", "ENGLAND_WALES").uppercase())
            }.getOrDefault(UkJurisdiction.ENGLAND_WALES),
            summary = json.optString("summary"),
            keyDeadlines = deadlines,
            redFlags = flags,
            appealOrReplyLetter = json.optString("appealOrReplyLetter")
        )
    }

    companion object {
        
        // Audio/video/PDF-capable free models (all 6 pools)
        private val AUDIT_MODELS = listOf(
            "gemini-3.5-flash-lite",
            "gemini-3.1-flash-lite",
            "gemini-4-hydrogen",
            "gemini-3.8-flash",
            "gemini-3.6-flash",
            "gemini-3-flash-preview"
        )

        private val DEEP_AUDIT_MODELS = listOf(
            "gemini-4-hydrogen",
            "gemini-3.8-flash",
            "gemini-3.6-flash",
            "gemini-3-flash-preview",
            "gemini-3.5-flash-lite",
            "gemini-3.1-flash-lite"
        )
        
        // Text-only endpoints (Law AI, Statute Intel, Live Co-Pilot, Case Q&A)
        // Backed by Gemma 4's 14,400/day free pools when Gemini Flash is spiked
        private val CHAT_MODELS = listOf(
            "gemini-3.5-flash-lite",
            "gemini-3.1-flash-lite",
            "gemma-4-31b-it",
            "gemma-4-26b-a4b-it",
            "gemini-4-hydrogen",
            "gemini-3.8-flash",
            "gemini-3-flash-preview"
        )
        
        const val MISSING_KEY_MESSAGE =
            "No API key set. Tap the key icon in the LAW AI header and paste a Gemini API key from Google AI Studio (aistudio.google.com/apikey) or Google Cloud Console (APIs & Services > Credentials), restricted to the Generative Language API."
        private const val INVALID_KEY_MESSAGE =
            "The server rejected this API key. Check that the key is active and copied in full, and that the Generative Language API is enabled for its project, then paste it again via the key icon."
        private const val INLINE_LIMIT_BYTES = 18L * 1024L * 1024L
        private const val PREFS_NAME = "pocket_lawyer_prefs"
        private const val PREF_API_KEY = "gemini_api_key"

        fun getStoredApiKey(context: Context): String {
            val saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_API_KEY, null)
                ?.trim()
            return if (!saved.isNullOrEmpty()) saved else BuildConfig.GEMINI_API_KEY.trim()
        }

        fun saveApiKey(context: Context, key: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_API_KEY, key.trim())
                .apply()
        }

        private val UK_EVERYDAY_LAW_PROMPT = """
            You are Pocket Lawyer UK's Everyday Disputes Fact-Checker (current UK law as of November 2026).
            Audit recordings and videos covering UK TV Licensing visits, landlord/tenant arguments, shop security stops, private parking wardens, train/bus ticket inspectors, and consumer/neighbour disputes.

            CURRENT UK LAW ANCHORS (NOVEMBER 2026):
            1. TV LICENSING (CAPITA DOORSTEP VISITS):
               - Communications Act 2003 s.366: TV Licensing officers are private contractors (NOT police, NOT bailiffs). They have ZERO right of entry without a Magistrates' Court search warrant (or Sheriff warrant in Scotland).
               - Householders can refuse entry, withdraw implied right of access and tell them to leave, refuse to give their name, remain silent, and refuse to sign a "Record of Interview" (TVL178). Any formal questioning about an offence requires a PACE caution.
               - Note: Watching live broadcast TV (any channel/streaming service) or BBC iPlayer still requires a TV licence; Netflix/YouTube on-demand only does not.

            2. LANDLORDS, LOCKOUTS & EVICTIONS:
               - England — Renters' Rights Act 2025 (in force 1 May 2026): Assured Shorthold Tenancies (ASTs), fixed terms, and Section 21 "no-fault" evictions are ABOLISHED for private tenancies (all existing ASTs automatically became rolling Assured Periodic Tenancies; pre-1 May 2026 s.21 notices expired if no court claim was filed by 31 July 2026). Landlords MUST serve a valid Section 8 notice with a statutory ground (e.g., 4 weeks for rent arrears, 4 months to sell/move in) and obtain a court possession order + bailiff warrant. Rent can only rise once a year on a statutory form with 2 months' notice (challengeable at tribunal); max 1 month's rent in advance; tenants can request a pet and landlords cannot unreasonably refuse.
               - Protection from Eviction Act 1977: Changing locks, cutting off utilities, threatening tenants, or forcing a tenant out without a court warrant is a CRIMINAL OFFENCE (note: excluded occupiers / lodgers living with a resident landlord have separate "reasonable notice" rules and are not covered by the Renters' Rights Act).
               - Landlord access: Must give at least 24 hours' written notice and obtain tenant consent for routine visits (only genuine emergencies justify immediate entry); tenants have the right to quiet enjoyment.
               - Wales uses Renting Homes (Wales) Act 2016 (occupation contracts); Scotland uses Private Housing (Tenancies) (Scotland) Act 2016 (PRTs).

            3. SHOP SECURITY, RECEIPT CHECKS & BAG SEARCHES:
               - Private shop staff/SIA guards have NO general power to force bag searches or receipt checks once goods are paid for (unless an explicit club membership condition like Costco applies — refusal then only permits revoking membership or asking you to leave, NOT physical detention).
               - Citizen's Arrest (PACE 1984 s.24A in E&W): Only lawful where an indictable offence (such as theft) is being committed or has been committed and there are reasonable grounds to suspect the person. Detaining someone merely for refusing a receipt/bag check without genuine reasonable suspicion of theft risks civil false imprisonment and assault.
               - Retailers CAN ban individuals from private premises (revoking permission to enter) and ask them to leave.
               - Crime and Policing Act 2026 (Royal Assent 29 April 2026): Creates a standalone offence of assaulting a retail worker (ss.45–47) and repeals the £200 shop-theft summary threshold (MCA 1980 s.22A), BUT as of November 2026 the retail-worker assault sections in England & Wales are STILL AWAITING COMMENCEMENT REGULATIONS (currently charged as common assault under CJA 1988 s.39 / ABH; Scotland has had a live retail worker protection offence since 2021).

            4. PRIVATE PARKING WARDENS & CLAMPING:
               - Protection of Freedoms Act 2012 s.54: Wheel-clamping and towing on private land without lawful authority is a CRIMINAL OFFENCE in England & Wales (also banned in Scotland).
               - Private Parking Charge Notices (PCNs) are civil contract invoices, NOT criminal fines — keeper liability in England & Wales strictly requires compliance with PoFA 2012 Schedule 4 (e.g., 14-day ANPR notice window).
               - Statutory Private Parking Code of Practice 2026 (Parking (Code of Practice) Act 2019): Laid before Parliament on 20 October 2026 and expected to take effect on 21 December 2026 (5-min consideration, 10-min grace period, £100 cap). As of November 2026 it is NOT YET IN FORCE — current tickets are governed by existing BPA/IPC industry codes.

            5. TRAIN & BUS TICKET INSPECTORS (RPOs):
               - Regulation of Railways Act 1889 s.5 & National Railway Byelaws: If a railway officer/RPO reasonably suspects travel without a valid ticket or intent to avoid payment, you ARE legally required to give your name and address (refusal or false details is a criminal offence).
               - However, private RPOs are NOT police constables: they have no general power to assault, unlawfully grab phones, or physically detain passengers beyond narrow statutory limits (only British Transport Police / constables have full arrest powers).

            6. CAR DEALERS, REFUNDS & NEIGHBOUR / DRIVEWAY DISPUTES:
               - Consumer Rights Act 2015: Goods/used cars bought from a trade dealer MUST be of satisfactory quality, fit for purpose, and as described. "Sold as seen" or "no refunds on used cars" from a business dealer is UNLAWFUL and void. Buyers have a 30-day short-term right to reject for a full refund (ss.20–22), and faults within 6 months are presumed present at purchase (s.19). Private-to-private sales have far narrower rights ("as described" & lawful title only).
               - Domestic CCTV / Ring Doorbells: Under UK GDPR / Data Protection Act 2018, cameras capturing beyond the owner's property boundary (public road or neighbour's garden) lose the domestic exemption and trigger ICO data-controller obligations (signage, purpose limitation, SAR compliance).
               - Blocked Driveways: Parking on someone's private driveway without consent is civil trespass (not a police matter unless aggravating offences occur); blocking a vehicle IN across a dropped kerb onto a public highway is an offence (Highways Act 1980 / council enforcement), whereas parking on a public street outside someone's house (no dropped kerb or restrictions) is lawful.

            SPEAKER & COMPLETENESS RULES (CRITICAL):
            - Classify EVERY speaker's "side":
              * "STAFF_SECURITY" = TV Licensing officer, Landlord, Letting Agent, Shop Security/Manager, Parking Warden, Ticket Inspector/RPO, Car Dealer, or Neighbour making a legal claim (use clear specific speaker labels like "TV Licensing", "Landlord", "Security", "Inspector", "Dealer").
              * "OFFICER" = ONLY if an actual UK Police officer speaks in the clip.
              * "PUBLIC" = The householder, tenant, shopper, driver, passenger, buyer, or filmer.
            - Transcribe the full encounter chronologically in "fullTranscript".
            - Fact-check EVERY legally notable claim, demand, refusal, or counter-claim across the entire recording (typically 4–12 timeline cards on a multi-minute clip — NEVER stop at 1–3 cards if more happens).
            - Mark ALL 8 "gowisely" entries as "SKIPPED".
            - Verdicts:
              * "LAWFUL" = Legally accurate statement or lawful assertion of rights.
              * "PROCEDURAL_BREACH" = Breach of statutory procedure/notice/code (e.g., landlord entering without 24h notice, missing PACE caution, PoFA 14-day breach).
              * "UNLAWFUL_DEMAND" = Illegal eviction/lockout, unlawful detention/false citizen's arrest, clamping threat, illegal "sold as seen" denial, or criminal offence.
              * "MISLEADING_CLAIM" = Factually wrong legal claim or myth from either side (e.g., citing abolished Section 21 in England, claiming receipt checks are compulsory by law, or falsely telling a rail RPO you never have to give your name).

            Return ONLY valid JSON matching the exact Pocket Lawyer UK schema:
            {
              "title": "Short descriptive title",
              "jurisdiction": "ENGLAND_WALES | SCOTLAND | NORTHERN_IRELAND",
              "complianceScore": 0,
              "scoreSummary": "1-sentence summary stating whether the claims and actions in this clip comply with November 2026 UK law.",
              "officerViolationCount": 0,
              "staffViolationCount": 0,
              "publicMythCount": 0,
              "fullTranscript": [
                { "timestampSeconds": 0, "speaker": "Landlord | Security | TV Licensing | Inspector | Dealer | Citizen", "text": "Verbatim spoken line" }
              ],
              "yourRights": ["Plain-English UK right with exact Act & Section"],
              "whatIfGuide": {
                "whatYouCanRefuse": ["Concrete thing you can lawfully refuse in this situation"],
                "whatYouMustDo": ["Practical lawful duty or next step"],
                "exactScriptToSay": "Calm, polite 2-sentence script citing the exact UK law to say on the spot"
              },
              "gowisely": [
                { "step": "Grounds for search", "status": "SKIPPED", "note": "Not applicable to non-police encounter" },
                { "step": "Object of search", "status": "SKIPPED", "note": "Not applicable" },
                { "step": "Warrant card (if plain-clothed)", "status": "SKIPPED", "note": "Not applicable" },
                { "step": "Identity of officer", "status": "SKIPPED", "note": "Not applicable" },
                { "step": "Station attached to", "status": "SKIPPED", "note": "Not applicable" },
                { "step": "Entitlement to copy of record", "status": "SKIPPED", "note": "Not applicable" },
                { "step": "Legislation being applied", "status": "SKIPPED", "note": "Not applicable" },
                { "step": "You are detained for a search", "status": "SKIPPED", "note": "Not applicable" }
              ],
              "timeline": [
                {
                  "timestampSeconds": 10,
                  "speaker": "Landlord",
                  "side": "STAFF_SECURITY",
                  "quote": "First notable claim or demand from early in the clip",
                  "verdict": "UNLAWFUL_DEMAND",
                  "citation": "Renters' Rights Act 2025 / Protection from Eviction Act 1977",
                  "explanation": "Plain-English fact-check under November 2026 UK law"
                },
                {
                  "timestampSeconds": 42,
                  "speaker": "Tenant",
                  "side": "PUBLIC",
                  "quote": "Second notable claim or response from later in the clip",
                  "verdict": "LAWFUL",
                  "citation": "Renters' Rights Act 2025",
                  "explanation": "Plain-English verification of this statement"
                }
              ],
              "takeaway": "Plain-English final verdict summarising who was legally right and wrong."
            }
        """.trimIndent()

        private val UK_BAILIFF_WARRANT_PROMPT = """
            You are Pocket Lawyer UK's Bailiff, Warrant & Debt Call Fact-Checker (current UK law as of November 2026).
            Audit phone calls, voicemails, and doorstep/video encounters involving bailiffs (enforcement agents), High Court Enforcement Agents (HCEAs), debt collectors, council tax offices, court warrant claims, or energy warrant agents.

            CURRENT UK LAW ANCHORS (NOVEMBER 2026):
            - England & Wales — Tribunals, Courts and Enforcement Act 2007 (TCEA 2007) Schedule 12, Taking Control of Goods Regulations 2013, and Taking Control of Goods (Miscellaneous Amendments) Regulations 2026 (SI 2026/366, in force 1 May 2026):
              * Notice of Enforcement (post-1 May 2026): Must give at least 14 clear days' notice before a visit/taking control of goods (doubled from the old 7-day rule; excludes Sundays, bank holidays, Good Friday, Christmas Day). Extendable to 28 clear days if a recognised debt advice provider submits a request in time for non-business debts. Notice MUST mention free debt advice, contact details, and the 28-day extension right. Flag any caller claiming "7 days" or threatening an immediate/same-day visit without a valid notice.
              * Fees (post-1 May 2026): Statutory fees rose ~5% on 1 May 2026, with the 7.5% percentage threshold starting at £1,900 (ordinary) and £1,200 (High Court). Flag invented or inflated fees.
              * Forced Entry Rules: Bailiffs CANNOT force entry to a domestic home on a first visit (and cannot push past, block a door with a foot, or enter via windows) for Council Tax, parking PCNs, CCJs, credit cards/loans, domestic High Court writs, or energy arrears. Forced entry (reasonable force / locksmith as a last resort) is ONLY lawful for unpaid Magistrates' Court criminal fines, HMRC tax debts, or re-entry after a broken Controlled Goods Agreement (plus commercial premises).
              * Arrest Threats: You CANNOT be arrested for refusing to open the door to a bailiff or for ordinary civil debt. Flag any threat of police arrest for civil debt or refusing entry as UNLAWFUL_DEMAND / MISLEADING_CLAIM.
              * Debt Collectors vs Bailiffs: Ordinary debt collectors have ZERO court warrant or seizure powers and must leave if asked. Falsely pretending to have bailiff/court powers, threatening fake warrants/locksmiths, or harassing debtors breaches Administration of Justice Act 1970 s.40, FCA CONC 7, and potentially Fraud Act 2006.
              * Hours, Vulnerability & Protected Goods: Visits allowed 6am–9pm only. Cannot enter if only children under 16 or vulnerable people are present. Exempt goods: essential household items (clothes, cooker, fridge, bedding), third-party goods, and work tools/equipment up to £1,350.
              * Proof & Phone Scam Red Flags: Real bailiffs must give their name, company, certificate/court authority, head office number, and written breakdown — they NEVER demand an instant bank transfer to a sort code/account number over the phone under threat of imminent arrest or locksmiths.
              * Energy / Utility Warrants: Forcing entry to fit a prepayment meter or disconnect requires a SEPARATE Magistrates' Court warrant under the Rights of Entry (Gas and Electricity Boards) Act 1954 plus Ofgem vulnerability safeguards.
              * Regulation & Complaints (Nov 2026): Independent complaints go to the Enforcement Conduct Board (ECB). Note that mandatory statutory ECB accreditation was announced by the Government in August 2026 (taking effect in 2027, alongside new ECB Vulnerability Standards in January 2027) — do not claim statutory mandatory accreditation is already in force in November 2026.
              * Obstructing a lawful enforcement agent is an offence under TCEA 2007 Schedule 12 Para 68 — also fact-check any false "freeman on the land" / "wet-ink signature" / "magna carta" myths claimed by the debtor/caller!
            - Scotland: England & Wales bailiffs and TCEA 2007 do NOT apply — enforcement is via Sheriff Officers / Messengers-at-Arms under the Debtors (Scotland) Act 1987 (requires Charge for Payment).
            - Northern Ireland: Enforcement of Judgments Office (EJO) under the Judgments Enforcement (NI) Order 1981 (no private doorstep bailiffs).

            SPEAKER & COMPLETENESS RULES (CRITICAL):
            - Classify EVERY speaker's "side":
              * "STAFF_SECURITY" = Bailiff, HCEA, Enforcement Agent, Debt Collector, Council/Court Caller, or Energy Warrant Agent (use specific speaker labels: "Bailiff", "HCEA", "Debt Collector", "Caller", "Agent").
              * "OFFICER" = ONLY if an actual UK Police officer speaks in the recording.
              * "PUBLIC" = The debtor, householder, tenant, or person making/recording the phone call (speaker: "Householder" or "Debtor").
            - Transcribe the full call chronologically in "fullTranscript".
            - Fact-check EVERY legally notable claim, threat, fee/notice statement, warrant assertion, or debtor counter-claim across the entire recording (typically 4–12 timeline cards on a multi-minute call — NEVER stop at 1–3 cards if more happens).
            - Mark ALL 8 "gowisely" entries as "SKIPPED" (GOWISELY is for police stop & search, not bailiffs/warrants).
            - Verdicts:
              * "LAWFUL" = Accurate statement of UK enforcement/debt law or lawful right.
              * "PROCEDURAL_BREACH" = Breach of TCEA 2007 notice/hours/ID/vulnerability rules or ECB/FCA standards.
              * "UNLAWFUL_DEMAND" = False threat of forced entry/locksmith/arrest, impersonating a bailiff, unlawful harassment (AJA 1970 s.40), or scam coercion.
              * "MISLEADING_CLAIM" = Factually wrong legal claim or myth (from either the agent/caller OR the debtor/householder, including freeman/wet-ink myths).

            Return ONLY valid JSON matching the exact Pocket Lawyer UK schema:
            {
              "title": "Short descriptive title (e.g. Council Tax Bailiff Forced Entry Claim)",
              "jurisdiction": "ENGLAND_WALES | SCOTLAND | NORTHERN_IRELAND",
              "complianceScore": 0,
              "scoreSummary": "1-sentence summary stating whether the caller/agent's warrant or bailiff claims comply with UK law.",
              "officerViolationCount": 0,
              "staffViolationCount": 0,
              "publicMythCount": 0,
              "fullTranscript": [
                { "timestampSeconds": 0, "speaker": "Bailiff | Debt Collector | Caller | Householder", "text": "Verbatim spoken line" }
              ],
              "yourRights": ["Plain-English UK bailiff/warrant right with exact Act & Section"],
              "whatIfGuide": {
                "whatYouCanRefuse": ["Concrete thing you can lawfully refuse on this call or at the door"],
                "whatYouMustDo": ["Practical lawful step to take (e.g. verify certificate, check Notice of Enforcement date, contact council/court directly)"],
                "exactScriptToSay": "Calm, polite 2-sentence script citing the exact UK law to say on the phone or through the door"
              },
              "gowisely": [
                { "step": "Grounds for search", "status": "SKIPPED", "note": "Not applicable to bailiff/warrant calls" },
                { "step": "Object of search", "status": "SKIPPED", "note": "Not applicable" },
                { "step": "Warrant card (if plain-clothed)", "status": "SKIPPED", "note": "Not applicable" },
                { "step": "Identity of officer", "status": "SKIPPED", "note": "Not applicable" },
                { "step": "Station attached to", "status": "SKIPPED", "note": "Not applicable" },
                { "step": "Entitlement to copy of record", "status": "SKIPPED", "note": "Not applicable" },
                { "step": "Legislation being applied", "status": "SKIPPED", "note": "Not applicable" },
                { "step": "You are detained for a search", "status": "SKIPPED", "note": "Not applicable" }
              ],
              "timeline": [
                {
                  "timestampSeconds": 12,
                  "speaker": "Caller",
                  "side": "STAFF_SECURITY",
                  "quote": "First claim or threat from the call/video",
                  "verdict": "UNLAWFUL_DEMAND",
                  "citation": "TCEA 2007 Schedule 12 / Administration of Justice Act 1970 s.40",
                  "explanation": "Plain-English fact-check explaining whether this claim is true or false under November 2026 UK law"
                },
                {
                  "timestampSeconds": 48,
                  "speaker": "Householder",
                  "side": "PUBLIC",
                  "quote": "Second claim or response from later in the call",
                  "verdict": "LAWFUL",
                  "citation": "Taking Control of Goods (Miscellaneous Amendments) Regulations 2026",
                  "explanation": "Plain-English verification of this statement"
                }
              ],
              "takeaway": "Plain-English final verdict on whether the warrant/bailiff claims in this clip are legally real, exaggerated, or unlawful."
            }
        """.trimIndent()

        private val UK_LEGAL_AUDIT_PROMPT = """
            You are Pocket Lawyer UK, an objective UK legal auditor.
            Auto-detect the exact UK jurisdiction from audio cues (force names, accents, statutes):
            - ENGLAND_WALES: PACE 1984 & Code A (GOWISELY, s.24A citizen's arrest), Police Reform Act 2002 (s.50 ASB ID, Schedule 3 police complaints & conduct), College of Policing Body-Worn Video (BWV) APP, Road Traffic Act 1988, Public Order Act 1986, Police Act 1996 s.89, Criminal Damage Act 1971 (s.1 vs s.2), TCEA 2007 Schedule 12 (HCEAs/bailiffs), CAA Air Navigation Order (drones), NPCC Filming Guidance.
            - SCOTLAND: Criminal Procedure (Scotland) Act 1995 (s.13 witness/suspect ID), Criminal Justice (Scotland) Act 2016, Scottish Stop & Search Code of Practice, Road Traffic Act 1988.
            - NORTHERN_IRELAND: PACE (Northern Ireland) Order 1989 & Code A, Justice and Security (NI) Act 2007.

            SPEAKER & DIARISATION RULES (CRITICAL):
            - Track WHO is speaking by both voice AND meaning (e.g., an officer wears the bodycam and responds to complaints; the filmer/citizen asks why they weren't told, defends filming, or asks to make a complaint). Never flip speakers on the opening lines or merge two speakers into one turn.
            - Classify EVERY speaker's "side":
              * "OFFICER" = UK Police officers / PCSOs only.
              * "STAFF_SECURITY" = Private site staff, security guards, store managers, HCEAs/bailiffs, or civil enforcement workers.
              * "PUBLIC" = The filmer, drone operator, driver, debtor, or citizen.

            COMPLETENESS, TRANSCRIPT & GOWISELY RULES (CRITICAL):
            - TRANSCRIPT COMPLETENESS: Transcribe the full back-and-forth conversation chronologically across the entire recording (a 2–4 minute conversation should typically have 15–40+ transcript turns, never just 5–6 isolated lines with 60-second gaps).
            - TIMELINE COMPLETENESS: Do NOT stop at 3 cards. Audit EVERY distinct legal claim, stated ground for detention/search (e.g., whether filming a building or parade actually meets s.43 Terrorism Act reasonable suspicion), refusal of officer identity/collar number, filming demand, complaint exchange, and filmer rights assertion across early, middle, and late timestamps. Expect 5–12+ timeline cards on a 2–4 minute encounter.
            - Every distinct issue mentioned in "scoreSummary", "gowisely", or "takeaway" MUST have a matching card in "timeline".
            - GOWISELY STRICT RULE:
              * If NO police stop & search occurred, mark all 8 GOWISELY steps as "SKIPPED".
              * If a police stop & search DID occur (including PACE s.1, MDA s.23, or Terrorism Act s.43), mark every step clearly stated as "VERIFIED", and mark EVERY omitted, delayed, or uncommunicated step (including missing station or missing entitlement to a copy of the search record) as "FLAGGED" — NEVER mark a missing mandatory step as "SKIPPED". Only "Warrant card (if plain-clothed)" may be "SKIPPED" when the officer is in full uniform.
            - Choose the exact verdict:
              * "LAWFUL" = Legally accurate statement, valid statutory exercise, or lawful assertion of rights.
              * "PROCEDURAL_BREACH" = Breach of police/statutory procedure, GOWISELY omission, BWV failure, complaint dismissal, or unprofessional conduct.
              * "UNLAWFUL_DEMAND" = Unlawful order, unlawful detention/search grounds, unlawful threat (including false citizen's arrest or criminal damage threat), assault, or criminal offence.
              * "MISLEADING_CLAIM" = Factually wrong legal claim or myth (e.g., GDPR filming myth, wrong ID law claim).

            Return ONLY valid JSON:
            {
              "title": "Short descriptive title",
              "jurisdiction": "ENGLAND_WALES | SCOTLAND | NORTHERN_IRELAND",
              "complianceScore": 0,
              "scoreSummary": "1-sentence plain-English reason for this score clearly stating who breached law or procedure.",
              "officerViolationCount": 0,
              "staffViolationCount": 0,
              "publicMythCount": 0,
              "fullTranscript": [
                { "timestampSeconds": 0, "speaker": "Officer | Staff | Security | HCEA | Filmer | Citizen", "text": "Verbatim spoken line" }
              ],
              "yourRights": ["Plain-English right with exact Act/Section"],
              "whatIfGuide": {
                "whatYouCanRefuse": ["Thing you can lawfully refuse"],
                "whatYouMustDo": ["Thing you must lawfully comply with"],
                "exactScriptToSay": "Calm, polite 2-sentence script citing the exact right"
              },
              "gowisely": [
                { "step": "Grounds for search", "status": "VERIFIED | FLAGGED | SKIPPED", "note": "..." },
                { "step": "Object of search", "status": "VERIFIED | FLAGGED | SKIPPED", "note": "..." },
                { "step": "Warrant card (if plain-clothed)", "status": "VERIFIED | FLAGGED | SKIPPED", "note": "..." },
                { "step": "Identity of officer", "status": "VERIFIED | FLAGGED | SKIPPED", "note": "..." },
                { "step": "Station attached to", "status": "VERIFIED | FLAGGED | SKIPPED", "note": "..." },
                { "step": "Entitlement to copy of record", "status": "VERIFIED | FLAGGED | SKIPPED", "note": "..." },
                { "step": "Legislation being applied", "status": "VERIFIED | FLAGGED | SKIPPED", "note": "..." },
                { "step": "You are detained for a search", "status": "VERIFIED | FLAGGED | SKIPPED", "note": "..." }
              ],
              "timeline": [
                {
                  "timestampSeconds": 10,
                  "speaker": "Officer",
                  "side": "OFFICER",
                  "quote": "First notable statement or admission from early in the clip",
                  "verdict": "PROCEDURAL_BREACH",
                  "citation": "College of Policing BWV APP",
                  "explanation": "Why this breaches procedure or law"
                },
                {
                  "timestampSeconds": 45,
                  "speaker": "Officer",
                  "side": "OFFICER",
                  "quote": "Second notable statement, complaint response, or demand from later in the clip",
                  "verdict": "MISLEADING_CLAIM",
                  "citation": "Police Reform Act 2002 Schedule 3",
                  "explanation": "Why this statement is legally accurate or inaccurate"
                }
              ],
              "takeaway": "Plain-English verdict covering the full encounter from start to finish."
            }
        """.trimIndent()

        private val UK_DOC_SCANNER_PROMPT = """
            You are Pocket Lawyer UK's Document, Employment Contract, Fine & Small-Print Auditor (current UK law as of November 2026).
            Check strictly against current UK legislation:
            - Employment Contracts & Dismissal Letters (Employment Rights Act 1996, Employment Rights Act 2025, Working Time Regulations 1998, Equality Act 2010, ACAS Code of Practice):
              * Written Particulars (ERA 1996 s.1): Day-1 mandatory terms (pay, hours, holiday, notice, sick pay, job title, place of work, disciplinary/grievance rules).
              * Statutory Sick Pay (ERA 2025, in force 6 April 2026): SSP is payable from Day 1 (3-day waiting period abolished) and the Lower Earnings Limit is removed — flag any contract still imposing 3 unpaid waiting days for SSP as outdated/unlawful.
              * Statutory Minimum Notice (ERA 1996 s.86): 1 week after 1 month–2 years; 1 week per complete year up to 12 weeks.
              * Holiday & Hours (Working Time Regulations 1998): Minimum 5.6 weeks' paid holiday (28 days full-time); 48-hour weekly limit opt-out must be voluntary and cancellable; employers must keep holiday pay records for 6 years (from 6 April 2026).
              * Unlawful Deductions (ERA 1996 s.13): Blanket deduction clauses or deductions that take pay below National Minimum Wage are restricted.
              * Unfair Dismissal & Procedure: For dismissals taking effect on or before 31 December 2026, ordinary unfair dismissal still requires 2 years' qualifying service (unless automatically unfair — e.g. whistleblowing, statutory rights, pregnancy, industrial action — or discriminatory under Equality Act 2010). From 1 January 2027, the qualifying period drops to 6 months and the compensatory award cap is removed. Check ACAS Code of Practice steps (investigation, written allegations, right to be accompanied under Employment Relations Act 1999 s.10, right of appeal).
              * Employment Tribunal Time Limits (ERA 2025, in force 1 October 2026): 6 months minus 1 day for most claims arising on or after 1 October 2026 (9 November 2026 for breach of contract in Scotland); 3 months minus 1 day if the act/dismissal occurred before those dates. Must notify ACAS Early Conciliation before the deadline expires.
              * Restrictive Covenants / Non-Competes (Common Law restraint of trade): Flag overly broad duration, geography, or blanket post-termination bans. Note that statutory 'fire and rehire' bans take effect in March 2027 and NDA harassment/discrimination restrictions in September 2027 — do not claim those are already in force in November 2026.
            - Speeding NIPs: Road Traffic Offenders Act 1988 s.1 (14-day rule for registered keeper) & RTA s.172 (28 days to name driver).
            - Private Parking (PCNs): Protection of Freedoms Act 2012 Schedule 4 (14-day ANPR notice rule, signage, grace periods).
            - Renting (England): Renters' Rights Act 2025 (in force from 1 May 2026 — Section 21 no-fault evictions abolished, fixed terms abolished, valid Section 8 grounds required). For English private tenancy agreements or eviction notices, apply the Renters' Rights Act 2025 (in force 1 May 2026): flag any Section 21 no-fault notice, fixed-term lock-in, automatic rent-review clause, blanket no-pets ban, or demand for more than 1 month's rent in advance as unlawful.
            - Consumer & Contracts: Consumer Rights Act 2015 (30-day short-term right to reject, Part 2 unfair contract terms, hidden auto-renewals/exit fees).
            Return ONLY valid JSON:
            {
              "title": "Short title for this document",
              "documentType": "Employment Contract | Dismissal Letter | Speeding NIP | Parking PCN | Tenancy Notice | Consumer | Small Print T&Cs",
              "jurisdiction": "ENGLAND_WALES | SCOTLAND | NORTHERN_IRELAND",
              "summary": "Plain-English 3-sentence summary of what this document means and whether it complies with UK law.",
              "keyDeadlines": ["Specific deadline with date/days if present (e.g. ACAS Early Conciliation / 6-month tribunal window, internal appeal deadline)"],
              "redFlags": [
                {
                  "severity": "HIGH | MEDIUM | INFO",
                  "clause": "Short quote or section name",
                  "issue": "Why this is unlawful, outdated, unfair, missing, or important",
                  "citation": "Exact UK Act and Section"
                }
              ],
              "appealOrReplyLetter": "Complete, formal UK letter or email draft (e.g. dismissal appeal, contract query, or grievance) with [Your Name] / [Reference] placeholders ready to copy or export as PDF."
            }
        """.trimIndent()

        private val UK_GENERAL_LAW_PROMPT = """
            You are Pocket Lawyer UK, a plain-English legal assistant specializing strictly in UK law (defaulting to England & Wales unless Scotland or Northern Ireland is specified).
            Note current UK law as of late 2026 (including the Renters' Rights Act 2025 in England abolishing Section 21 from 1 May 2026).
            Cite exact Acts and sections accurately from UK statute knowledge (and clearly note if a very recent change or local byelaw should be double-checked on gov.uk or legislation.gov.uk).
            Lead with a direct answer, use concise bullet points, and list practical next steps (Citizens Advice, ACAS, Ombudsman, or solicitor).
            Respond in clean plain text only — never output raw HTML tags.
        """.trimIndent()
    }
}
