package com.example.pocketlawyeruk.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.example.pocketlawyeruk.data.LegalAuditService
import com.example.pocketlawyeruk.data.VideoAudioExtractor
import com.example.pocketlawyeruk.data.local.PocketLawyerDatabase
import com.example.pocketlawyeruk.data.local.VaultMappers.toEntity
import com.example.pocketlawyeruk.data.local.VaultMappers.toModel
import com.example.pocketlawyeruk.model.*
import com.example.pocketlawyeruk.ui.screens.EncounterType
import com.example.pocketlawyeruk.worker.BackgroundAuditWorker
import com.example.pocketlawyeruk.data.LiveCoachAdvice
import com.example.pocketlawyeruk.data.LivePartyMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File

data class PocketLawyerUiState(
    val audits: List<AuditReport> = SampleAudits.examples,
    val scans: List<DocumentScanResult> = emptyList(),
    val bookmarks: List<SavedBookmark> = emptyList(),
    val chatMessages: List<ChatMessage> = emptyList(),
    val cases: List<CaseRecord> = emptyList(),
    val caseItems: List<CaseItem> = emptyList(),
    val activeCaseId: String? = null,
    val activeReport: AuditReport? = null,
    val activeScan: DocumentScanResult? = null,
    val vaultSearchQuery: String = "",
    val isAnalyzing: Boolean = false,
    val isScanningDocument: Boolean = false,
    val isChatLoading: Boolean = false,
    val statusMessage: String = "Auditing intercept against UK statutes...",
    val errorMessage: String? = null,
    val qaAnswer: String? = null,
    val isAskingQa: Boolean = false,
    val liveCoachAdvice: LiveCoachAdvice? = null,
    val isLiveCoachLoading: Boolean = false,
    val liveCoachError: String? = null,
    // Tap-to-Read Statute Sheet state
    val statuteSheetCitation: String? = null,
    val statuteLoading: Boolean = false,
    val statuteResult: StatuteLookupResult? = null,
    val statuteError: String? = null,
    val apiKey: String = ""
)

class PocketLawyerViewModel(application: Application) : AndroidViewModel(application) {

    private val db = PocketLawyerDatabase.getInstance(application)
    private val audioExtractor = VideoAudioExtractor()
    private val auditService = LegalAuditService(application)
    private var statuteJob: Job? = null
    private var auditJob: Job? = null

    fun cancelAudit() {
        auditJob?.cancel()
        auditJob = null
        _uiState.update { it.copy(isAnalyzing = false) }
    }

    private val _uiState = MutableStateFlow(
        PocketLawyerUiState(apiKey = LegalAuditService.getStoredApiKey(application))
    )
    val uiState: StateFlow<PocketLawyerUiState> = _uiState.asStateFlow()

    fun updateApiKey(newKey: String) {
        val cleaned = newKey.trim()
        LegalAuditService.saveApiKey(getApplication(), cleaned)
        _uiState.update { it.copy(apiKey = LegalAuditService.getStoredApiKey(getApplication())) }
    }

    init {
        // Observe Room Vault tables live
        viewModelScope.launch {
            db.auditDao().observeAll().collect { entities ->
                val saved = entities.map { it.toModel() }
                val combined = if (saved.isEmpty()) SampleAudits.examples else saved + SampleAudits.examples
                _uiState.update { it.copy(audits = combined) }
            }
        }
        viewModelScope.launch {
            db.documentScanDao().observeAll().collect { entities ->
                _uiState.update { it.copy(scans = entities.map { e -> e.toModel() }) }
            }
        }
        viewModelScope.launch {
            db.chatDao().observeAll().collect { entities ->
                _uiState.update { it.copy(chatMessages = entities.map { e -> e.toModel() }) }
            }
        }
        viewModelScope.launch {
            db.bookmarkDao().observeAll().collect { entities ->
                _uiState.update { it.copy(bookmarks = entities.map { e -> e.toModel() }) }
            }
        }
        viewModelScope.launch {
            db.caseDao().observeAll().collect { entities ->
                _uiState.update { it.copy(cases = entities.map { e -> e.toModel() }) }
            }
        }
        viewModelScope.launch {
            db.caseDao().observeAllItems().collect { entities ->
                _uiState.update { it.copy(caseItems = entities.map { e -> e.toModel() }) }
            }
        }
    }

    fun openAudit(auditId: String) {
        viewModelScope.launch {
            val found = _uiState.value.audits.find { it.id == auditId }
                ?: db.auditDao().getById(auditId)?.toModel()
            if (found != null) {
                _uiState.update { it.copy(activeReport = found, qaAnswer = null, errorMessage = null) }
            }
        }
    }

    fun closeReport() = _uiState.update { it.copy(activeReport = null, qaAnswer = null) }
    fun clearError() = _uiState.update { it.copy(errorMessage = null) }
    fun updateVaultSearch(query: String) = _uiState.update { it.copy(vaultSearchQuery = query) }
    fun openSavedScan(scan: DocumentScanResult) = _uiState.update { it.copy(activeScan = scan) }
    fun clearActiveScan() = _uiState.update { it.copy(activeScan = null) }

    fun createCase(title: String, onCreated: (String) -> Unit = {}) {
        val clean = title.trim()
        if (clean.isBlank()) return
        val now = System.currentTimeMillis()
        val record = CaseRecord(title = clean, createdAt = now, updatedAt = now)
        viewModelScope.launch {
            db.caseDao().upsertCase(record.toEntity())
            _uiState.update {
                it.copy(
                    // Avoid a frame where activeCaseId exists but the Room flow has not emitted yet.
                    cases = (listOf(record) + it.cases).distinctBy { case -> case.id },
                    activeCaseId = record.id
                )
            }
            onCreated(record.id)
        }
    }

    fun openCase(id: String) = _uiState.update { it.copy(activeCaseId = id) }
    fun closeCase() = _uiState.update { it.copy(activeCaseId = null) }

    fun renameCase(id: String, title: String) {
        val clean = title.trim()
        if (clean.isBlank()) return
        viewModelScope.launch {
            db.caseDao().rename(id, clean, System.currentTimeMillis())
        }
    }

    fun deleteCase(id: String) {
        viewModelScope.launch {
            db.withTransaction {
                db.caseDao().deleteLinksForCase(id)
                db.caseDao().deleteCase(id)
            }
            _uiState.update { it.copy(activeCaseId = it.activeCaseId?.takeUnless { active -> active == id }) }
        }
    }

    fun addItemsToCase(caseId: String, items: List<CaseItem>) {
        if (items.isEmpty()) return
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            db.withTransaction {
                items.forEach { item ->
                    db.caseDao().linkItem(item.copy(caseId = caseId, addedAt = now).toEntity())
                }
                db.caseDao().touch(caseId, now)
            }
        }
    }

    fun removeItemFromCase(caseId: String, type: CaseItemType, itemId: String) {
        viewModelScope.launch {
            db.withTransaction {
                db.caseDao().unlinkItem(caseId, type.name, itemId)
                db.caseDao().touch(caseId, System.currentTimeMillis())
            }
        }
    }

    fun deleteAudit(id: String) = viewModelScope.launch {
        db.withTransaction {
            db.auditDao().deleteById(id)
            db.caseDao().deleteLinksForItem(CaseItemType.AUDIT.name, id)
        }
    }

    fun deleteScan(id: String) = viewModelScope.launch {
        db.withTransaction {
            db.documentScanDao().deleteById(id)
            db.caseDao().deleteLinksForItem(CaseItemType.SCAN.name, id)
        }
    }

    fun deleteBookmark(id: String) = viewModelScope.launch {
        db.withTransaction {
            db.bookmarkDao().deleteById(id)
            db.caseDao().deleteLinksForItem(CaseItemType.BOOKMARK.name, id)
        }
    }

    private fun requireApiKey(): Boolean {
        if (_uiState.value.apiKey.isBlank()) {
            _uiState.update { it.copy(errorMessage = LegalAuditService.MISSING_KEY_MESSAGE) }
            return false
        }
        return true
    }

    fun queueBackgroundAudit(
        context: Context,
        url: String,
        type: EncounterType,
        confrontingParty: ConfrontingParty = ConfrontingParty.AUTO,
        auditDepth: AuditDepth = AuditDepth.FAST
    ) {
        if (!requireApiKey()) return
        BackgroundAuditWorker.enqueue(context, url, type, confrontingParty, auditDepth)
        _uiState.update {
            it.copy(errorMessage = "Queued in background (${auditDepth.label.uppercase()}). You can leave the app — a notification will fire when the legal dossier is ready.")
        }
    }

    fun runAudit(
        context: Context,
        url: String,
        videoUri: Uri?,
        encounterType: EncounterType,
        confrontingParty: ConfrontingParty = ConfrontingParty.AUTO,
        auditDepth: AuditDepth = AuditDepth.FAST
    ) {
        if (!requireApiKey()) return
        if (videoUri == null && url.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Paste a video link or load a local clip first.") }
            return
        }

        auditJob?.cancel()
        auditJob = viewModelScope.launch {
            _uiState.update { it.copy(isAnalyzing = true, statusMessage = "INITIALISING INTERCEPT...", errorMessage = null) }
            var downloadedAudio: File? = null
            try {
                val report = if (videoUri != null) {
                    auditService.analyzeVideoUri(context, videoUri, url.takeIf { it.isNotBlank() }, encounterType, confrontingParty, auditDepth) { status ->
                        _uiState.update { it.copy(statusMessage = status.uppercase()) }
                    }
                } else {
                    downloadedAudio = audioExtractor.downloadAudioFromUrl(context, url) { status ->
                        _uiState.update { it.copy(statusMessage = status.uppercase()) }
                    }
                    _uiState.update { it.copy(statusMessage = "TRANSCRIBING & FACT-CHECKING UK LAW...") }
                    auditService.analyzeAudioFile(downloadedAudio, url.trim(), encounterType, confrontingParty, auditDepth) { status ->
                        _uiState.update { it.copy(statusMessage = status.uppercase()) }
                    }
                }

                db.auditDao().upsert(report.toEntity())
                _uiState.update { it.copy(activeReport = report, isAnalyzing = false) }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(isAnalyzing = false) }
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isAnalyzing = false, errorMessage = e.localizedMessage ?: "Audit failed.") }
            } finally {
                downloadedAudio?.parentFile?.deleteRecursively()
            }
        }
    }

    fun analyzeDocument(context: Context, uris: List<Uri>, pastedText: String?, note: String) {
        if (!requireApiKey()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isScanningDocument = true, errorMessage = null) }
            try {
                val result = auditService.analyzeDocumentOrText(context, uris, pastedText, note)
                db.documentScanDao().upsert(result.toEntity())
                _uiState.update { it.copy(activeScan = result, isScanningDocument = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isScanningDocument = false, errorMessage = e.localizedMessage ?: "Document scan failed.") }
            }
        }
    }

    fun lookupStatute(citation: String, jurisdiction: UkJurisdiction) {
        if (_uiState.value.apiKey.isBlank()) {
            _uiState.update {
                it.copy(
                    statuteSheetCitation = citation,
                    statuteLoading = false,
                    statuteResult = null,
                    statuteError = LegalAuditService.MISSING_KEY_MESSAGE
                )
            }
            return
        }
        statuteJob?.cancel()
        statuteJob = viewModelScope.launch {
            _uiState.update {
                it.copy(statuteSheetCitation = citation, statuteLoading = true, statuteResult = null, statuteError = null)
            }
            try {
                val res = withTimeout(30_000L) {
                    auditService.lookupUkStatute(citation, jurisdiction)
                }
                _uiState.update { it.copy(statuteLoading = false, statuteResult = res) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        statuteLoading = false,
                        statuteError = e.localizedMessage ?: "Statute lookup timed out. Tap to retry."
                    )
                }
            }
        }
    }

    fun dismissStatuteSheet() {
        statuteJob?.cancel()
        _uiState.update { it.copy(statuteSheetCitation = null, statuteResult = null, statuteError = null, statuteLoading = false) }
    }

    fun saveBookmark(title: String, content: String, citation: String, folder: BookmarkFolder) {
        viewModelScope.launch {
            db.bookmarkDao().upsert(SavedBookmark(folder = folder, title = title, content = content, citation = citation).toEntity())
        }
    }

    fun askFollowUp(question: String) {
        if (!requireApiKey()) return
        val current = _uiState.value.activeReport ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isAskingQa = true) }
            try {
                val answer = auditService.askFollowUp(current, question)
                _uiState.update { it.copy(qaAnswer = answer, isAskingQa = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(qaAnswer = "Error: ${e.localizedMessage}", isAskingQa = false) }
            }
        }
    }

    fun sendChatMessage(text: String) {
        if (!requireApiKey()) return
        val userMsg = ChatMessage(isUser = true, text = text)
        val historyWithUser = _uiState.value.chatMessages + userMsg
        _uiState.update { it.copy(isChatLoading = true) }

        viewModelScope.launch {
            db.chatDao().insert(userMsg.toEntity())
            try {
                val reply = auditService.chatUkLaw(historyWithUser)
                db.chatDao().insert(reply.toEntity())
                _uiState.update { it.copy(isChatLoading = false) }
            } catch (e: Exception) {
                val err = ChatMessage(isUser = false, text = "Error verifying UK statutes: ${e.localizedMessage ?: "Network issue"}")
                db.chatDao().insert(err.toEntity())
                _uiState.update { it.copy(isChatLoading = false) }
            }
        }
    }

    fun clearChat() = viewModelScope.launch { db.chatDao().clearAll() }

    fun requestLiveCoach(transcript: String, partyMode: LivePartyMode, jurisdiction: UkJurisdiction) {
        if (!requireApiKey()) return
        if (transcript.isBlank()) {
            _uiState.update { it.copy(liveCoachError = "Wait for speech to appear in the transcript first.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLiveCoachLoading = true, liveCoachError = null) }
            try {
                val advice = auditService.getLiveCoachAdvice(transcript, partyMode, jurisdiction)
                _uiState.update { it.copy(liveCoachAdvice = advice, isLiveCoachLoading = false) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLiveCoachLoading = false, liveCoachError = e.localizedMessage ?: "Live AI check failed.")
                }
            }
        }
    }

    fun clearLiveCoach() {
        _uiState.update { it.copy(liveCoachAdvice = null, liveCoachError = null, isLiveCoachLoading = false) }
    }

    fun saveLiveTranscriptToVault(
        lines: List<TranscriptLine>,
        partyMode: LivePartyMode,
        jurisdiction: UkJurisdiction,
        onSavedAndOpened: () -> Unit
    ) {
        if (!requireApiKey()) return
        if (lines.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isAnalyzing = true, statusMessage = "ARCHIVING LIVE ENCOUNTER TO VAULT...", errorMessage = null) }
            try {
                val report = auditService.analyzeLiveTranscript(lines, partyMode, jurisdiction)
                db.auditDao().upsert(report.toEntity())
                _uiState.update { it.copy(activeReport = report, isAnalyzing = false, liveCoachAdvice = null) }
                onSavedAndOpened()
            } catch (e: Exception) {
                _uiState.update { it.copy(isAnalyzing = false, errorMessage = e.localizedMessage ?: "Could not save live audit.") }
            }
        }
    }
}
