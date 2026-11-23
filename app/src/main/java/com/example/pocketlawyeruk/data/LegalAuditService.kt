package com.example.pocketlawyeruk.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.example.pocketlawyeruk.BuildConfig
import com.example.pocketlawyeruk.model.*
import com.example.pocketlawyeruk.ui.screens.EncounterType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .readTimeout(240, TimeUnit.SECONDS)
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
        onStatus: (String) -> Unit = {}
    ): AuditReport = withContext(Dispatchers.IO) {
        val mediaPart = buildMediaPartFromFile(audioFile, "audio/mp3", onStatus)
        runAuditWithMediaPart(mediaPart, sourceUrl, null, encounterType)
    }

    suspend fun analyzeVideoUri(
        context: Context,
        videoUri: Uri,
        sourceUrl: String?,
        encounterType: EncounterType,
        onStatus: (String) -> Unit = {}
    ): AuditReport = withContext(Dispatchers.IO) {
        val mimeType = context.contentResolver.getType(videoUri) ?: "video/mp4"
        val tempFile = copyUriToTempFile(context, videoUri, "upload_clip")
        try {
            val mediaPart = buildMediaPartFromFile(tempFile, mimeType, onStatus)
            runAuditWithMediaPart(mediaPart, sourceUrl, videoUri.toString(), encounterType)
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

        val uploadUrl = client.newCall(startReq).execute().use { resp ->
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

        val (fileUri, fileName, initialState) = client.newCall(uploadReq).execute().use { resp ->
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
                val state = client.newCall(pollReq).execute().use { resp ->
                    JSONObject(resp.body?.string().orEmpty()).optString("state", "PROCESSING")
                }
                if (state == "ACTIVE") return fileUri
                if (state == "FAILED") throw IllegalStateException("Gemini failed to process this media file.")
            }
        }
        return fileUri
    }

    private fun runAuditWithMediaPart(
        mediaPart: JSONObject,
        sourceUrl: String?,
        videoUriString: String?,
        encounterType: EncounterType
    ): AuditReport {
        val bodyJson = JSONObject().apply {
            put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", UK_LEGAL_AUDIT_PROMPT))))
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().apply {
                    put(mediaPart)
                    put(JSONObject().put(
                        "text",
                        "Listen to the full UK encounter. Auto-detect whether this occurred in ENGLAND_WALES, SCOTLAND, or NORTHERN_IRELAND " +
                            "(from accents, force names, uniforms, or legislation mentioned) and apply that exact jurisdiction's law. " +
                            "Transcribe it, fact-check BOTH police officers AND public/auditor claims, and produce the 'What If This Happened to Me?' guide. " +
                            "Category hint: ${encounterType.statuteSubtitle}. Return ONLY raw valid JSON with no extra commentary."
                    ))
                })
            }))
            put("safetySettings", buildPermissiveSafetySettings())
            put("generationConfig", JSONObject().apply {
                put("maxOutputTokens", 8192)
                put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
            })
        }

        val rawText = executeGeminiText(bodyJson, models = AUDIT_MODELS)
        return parseAuditJson(rawText, sourceUrl, videoUriString, encounterType)
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
        documentUri: Uri?,
        pastedText: String?,
        userContextNote: String = ""
    ): DocumentScanResult = withContext(Dispatchers.IO) {
        val parts = JSONArray()

        if (documentUri != null) {
            val mimeType = context.contentResolver.getType(documentUri) ?: "image/jpeg"
            val tempFile = copyUriToTempFile(context, documentUri, "doc_scan")
            try {
                parts.put(buildMediaPartFromFile(tempFile, mimeType) {})
            } finally {
                tempFile.delete()
            }
        }

        if (!pastedText.isNullOrBlank()) {
            parts.put(JSONObject().put("text", "DOCUMENT / SMALL PRINT TEXT:\n$pastedText"))
        }

        parts.put(JSONObject().put(
            "text",
            "Analyze this UK document, fine, notice, or contract/T&Cs under current UK law (as of late 2026). " +
                "Additional context from user: ${userContextNote.ifBlank { "None" }}. " +
                "Check statutory deadlines (e.g., 14-day NIP rule, POFA 2012 Schedule 4, Consumer Rights Act 2015, Renters' Rights Act 2025). " +
                "Identify red flags or unfair terms, and draft a formal, polite, statute-cited UK reply or appeal letter ready to send. Return ONLY valid JSON."
        ))

        val bodyJson = JSONObject().apply {
            put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", UK_DOC_SCANNER_PROMPT))))
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", parts)
            }))
            put("safetySettings", buildPermissiveSafetySettings())
            put("generationConfig", JSONObject().apply {
                put("maxOutputTokens", 8192)
                put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
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

    // ---------------- INTERNAL HELPERS & PARSERS ----------------

    private fun executeGeminiRaw(
        bodyJson: JSONObject,
        models: List<String> = AUDIT_MODELS,
        httpClient: OkHttpClient = client
    ): JSONObject {
        val cleanKey = apiKeyProvider().trim()
        if (cleanKey.isEmpty()) throw IllegalStateException(MISSING_KEY_MESSAGE)
        val errors = mutableListOf<String>()

        for (model in models) {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
            val req = Request.Builder()
                .url(url)
                .header("x-goog-api-key", cleanKey)
                .post(bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            try {
                httpClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        val root = JSONObject(body)
                        val candidate = root.optJSONArray("candidates")?.optJSONObject(0)
                        val extracted = if (candidate != null) extractAllTextParts(candidate) else ""

                        if (extracted.isNotBlank()) {
                            return root
                        }

                        // HTTP 200 but empty text (e.g. thought-only / STOP with no parts / safety block) -> try next model
                        val finishReason = candidate?.optString("finishReason")
                            ?: root.optJSONObject("promptFeedback")?.optString("blockReason")
                            ?: "EMPTY_CANDIDATE"
                        errors.add("$model: empty ($finishReason)")
                        return@use
                    }

                    // Fail over on quota (429), missing model (404), server busy (500/503), or param mismatch (400)
                    if (resp.code in listOf(400, 404, 429, 500, 503)) {
                        val shortMsg = runCatching {
                            JSONObject(body).getJSONObject("error").optString("message").take(60)
                        }.getOrDefault("")
                        errors.add("$model: HTTP ${resp.code}${if (shortMsg.isNotBlank()) " ($shortMsg)" else ""}")
                        return@use
                    }

                    if (resp.code == 401) throw IllegalStateException(INVALID_KEY_MESSAGE)
                    if (resp.code == 402) throw IllegalStateException(
                        "Billing is not active for this project (402): prepayment credits are depleted. Add credits at aistudio.google.com/billing, or unlink the billing account in Google Cloud Console to use the free tier."
                    )

                    throw IllegalStateException("Gemini API error (${resp.code}) on $model: ${apiErrorMessage(body)}")
                }
            } catch (e: IOException) {
                errors.add("$model: timeout/network (${e.localizedMessage ?: e.message ?: "unknown"})")
            }
        }

        throw IllegalStateException(
            "All models failed:\n" + errors.joinToString("\n")
        )
    }

    private fun apiErrorMessage(body: String): String =
        runCatching { JSONObject(body).getJSONObject("error").optString("message") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: body.take(300)

    private fun executeGeminiText(
        bodyJson: JSONObject,
        models: List<String> = AUDIT_MODELS,
        httpClient: OkHttpClient = client
    ): String {
        val root = executeGeminiRaw(bodyJson, models, httpClient)
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
        encounterType: EncounterType
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
                val speakerLabel = o.optString("speaker", "Speaker")
                val rawSide = o.optString("side", "").uppercase()
                val side = when {
                    rawSide == "OFFICER" || rawSide == "POLICE" -> SpeakerSide.OFFICER
                    rawSide == "STAFF_SECURITY" || rawSide == "STAFF" || rawSide == "SECURITY" -> SpeakerSide.STAFF_SECURITY
                    rawSide == "PUBLIC" || rawSide == "AUDITOR" || rawSide == "FILMER" -> SpeakerSide.PUBLIC
                    speakerLabel.contains("officer", true) || speakerLabel.contains("police", true) -> SpeakerSide.OFFICER
                    speakerLabel.contains("staff", true) || speakerLabel.contains("security", true) ||
                        speakerLabel.contains("guard", true) || speakerLabel.contains("manager", true) -> SpeakerSide.STAFF_SECURITY
                    else -> SpeakerSide.PUBLIC
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

        val officerViolations = json.optInt(
            "officerViolationCount",
            timeline.count { it.side == SpeakerSide.OFFICER && it.verdict != StatementVerdict.LAWFUL }
        )
        val staffViolations = json.optInt(
            "staffViolationCount",
            timeline.count { it.side == SpeakerSide.STAFF_SECURITY && it.verdict != StatementVerdict.LAWFUL }
        )
        val publicMyths = json.optInt(
            "publicMythCount",
            timeline.count { it.side == SpeakerSide.PUBLIC && it.verdict != StatementVerdict.LAWFUL }
        )

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
            gowiselyChecklist = gowisely,
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
        
        // Best reasoning first for video/audio & doc scans, backed by your 500/day Lite pools
        private val AUDIT_MODELS = listOf(
            "gemini-3.8-flash",
            "gemini-3-flash-preview",
            "gemini-3.5-flash-lite",
            "gemini-3.1-flash-lite"
        )
        
        // Your two 500/day Flash-Lite pools first for fast Law AI chat & statute taps
        private val CHAT_MODELS = listOf(
            "gemini-3.5-flash-lite",
            "gemini-3.1-flash-lite",
            "gemini-3.8-flash",
            "gemini-3-flash-preview"
        )
        
        const val MISSING_KEY_MESSAGE =
            "No API key set. Tap the key icon in the LAW AI header and paste a Gemini API key from Google AI Studio (aistudio.google.com/apikey) or Google Cloud Console (APIs & Services > Credentials), restricted to the Generative Language API."
        private const val INVALID_KEY_MESSAGE =
            "The server rejected this API key (401). Check that the key is active and copied in full, and that the Generative Language API is enabled for its project, then paste it again via the key icon."
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

        private val UK_LEGAL_AUDIT_PROMPT = """
            You are Pocket Lawyer UK, an objective UK legal auditor.
            Auto-detect the exact UK jurisdiction from audio cues (force names, accents, statutes):
            - ENGLAND_WALES: PACE 1984 & Code A (GOWISELY), Road Traffic Act 1988, Police Reform Act 2002 s.50, Public Order Acts, Police Act 1996 s.89, Criminal Damage Act 1971 (s.1 actual damage, s.2 threats to destroy/damage), CAA Air Navigation Order (drones), NPCC Filming Guidance.
            - SCOTLAND: Criminal Procedure (Scotland) Act 1995 (s.13 witness/suspect ID duty), Criminal Justice (Scotland) Act 2016, Scottish Stop & Search Code of Practice, Road Traffic Act 1988.
            - NORTHERN_IRELAND: PACE (Northern Ireland) Order 1989 & Code A, Justice and Security (NI) Act 2007.

            Accurately classify EVERY speaker's "side":
            - "OFFICER" = Police officers / PCSOs only.
            - "STAFF_SECURITY" = Private site staff, security guards, receptionists, store managers, or council/civil enforcement workers.
            - "PUBLIC" = The filmer, drone operator, auditor, driver, or uninvolved members of the public.

            Grade ALL sides objectively: flag unlawful orders/threats by police or staff AND flag legal myths repeated by filmers/auditors.
            If no police stop & search occurred, mark all GOWISELY steps as "SKIPPED" and base "complianceScore" (0-100) on the overall legal conduct of the confronting party (police or staff/security).
            Provide a concise 1-sentence "scoreSummary" explaining why the score is that number.

            Return ONLY valid JSON:
            {
              "title": "Short descriptive title",
              "jurisdiction": "ENGLAND_WALES | SCOTLAND | NORTHERN_IRELAND",
              "complianceScore": 0,
              "scoreSummary": "1-sentence plain-English reason for this score (e.g. 'Staff made 2 false filming claims and threatened criminal damage.')",
              "officerViolationCount": 0,
              "staffViolationCount": 0,
              "publicMythCount": 0,
              "fullTranscript": [
                { "timestampSeconds": 0, "speaker": "Officer | Staff | Security | Filmer | Citizen", "text": "Verbatim spoken line" }
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
                  "timestampSeconds": 12,
                  "speaker": "Officer | Staff | Security | Filmer | Citizen",
                  "side": "OFFICER | STAFF_SECURITY | PUBLIC",
                  "quote": "Exact claim, threat, or order",
                  "verdict": "LAWFUL | UNLAWFUL_DEMAND | MISLEADING_CLAIM",
                  "citation": "Exact UK statute (distinguish s.1 vs s.2 Criminal Damage Act 1971, etc.)",
                  "explanation": "Why this statement is legally true or false"
                }
              ],
              "takeaway": "Plain-English verdict on everyone involved."
            }
        """.trimIndent()

        private val UK_DOC_SCANNER_PROMPT = """
            You are Pocket Lawyer UK's Document, Fine & Small-Print Auditor (current UK law as of late 2026).
            Check:
            - Speeding NIPs: Road Traffic Offenders Act 1988 s.1 (14-day rule for registered keeper) & RTA s.172 (28 days to name driver).
            - Private Parking (PCNs): Protection of Freedoms Act 2012 Schedule 4 (14-day ANPR notice rule, signage, grace periods).
            - Renting (England): Renters' Rights Act 2025 (in force from 1 May 2026 — Section 21 no-fault evictions abolished, fixed terms abolished, valid Section 8 grounds required).
            - Consumer & Contracts: Consumer Rights Act 2015 (30-day short-term right to reject, Part 2 unfair contract terms, hidden auto-renewals/exit fees).
            Return ONLY valid JSON:
            {
              "title": "Short title for this document",
              "documentType": "Speeding NIP | Parking PCN | Tenancy Notice | Employment | Consumer | Small Print T&Cs",
              "jurisdiction": "ENGLAND_WALES | SCOTLAND | NORTHERN_IRELAND",
              "summary": "Plain-English 3-sentence summary of what this document means and whether it looks enforceable.",
              "keyDeadlines": ["Specific deadline with date/days if present"],
              "redFlags": [
                {
                  "severity": "HIGH | MEDIUM | INFO",
                  "clause": "Short quote or section name",
                  "issue": "Why this is unlawful, unfair, missing, or important",
                  "citation": "Exact UK Act and Section"
                }
              ],
              "appealOrReplyLetter": "Complete, formal UK letter or email draft with [Your Name] / [Reference] placeholders ready to copy or export as PDF."
            }
        """.trimIndent()

        private val UK_GENERAL_LAW_PROMPT = """
            You are Pocket Lawyer UK, a plain-English legal assistant specializing strictly in UK law (defaulting to England & Wales unless Scotland or Northern Ireland is specified).
            Note current UK law as of late 2026 (including the Renters' Rights Act 2025 in England abolishing Section 21 from 1 May 2026).
            Always verify current legislation via Google Search, cite exact Acts and sections, lead with a direct answer, use concise bullet points, and list practical next steps (Citizens Advice, ACAS, Ombudsman, or solicitor).
            Respond in clean plain text only — never output raw HTML tags.
        """.trimIndent()
    }
}
