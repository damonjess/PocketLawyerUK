package com.example.pocketlawyeruk

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.pocketlawyeruk.model.BookmarkFolder
import com.example.pocketlawyeruk.ui.PocketLawyerViewModel
import com.example.pocketlawyeruk.ui.components.StatuteLookupSheet
import com.example.pocketlawyeruk.ui.screens.*
import com.example.pocketlawyeruk.ui.theme.*
import com.example.pocketlawyeruk.worker.BackgroundAuditWorker

private enum class AppTab { AUDIT, SCAN, CHAT, VAULT }

private const val STATE_SHARED_URL = "state_shared_url"
private const val STATE_SHARED_VIDEO = "state_shared_video"

class MainActivity : ComponentActivity() {

    private var incomingUrlState by mutableStateOf<String?>(null)
    private var incomingVideoState by mutableStateOf<Uri?>(null)
    private var openAuditIdState by mutableStateOf<String?>(null)
    private var shareNonce by mutableIntStateOf(0)

    private val notifPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()

        // Only consume the launch intent once. Re-processing ACTION_SEND on rotation
        // would replay the share and close any report the user has open.
        if (savedInstanceState == null) {
            handleIncomingIntent(intent)
        } else {
            incomingUrlState = savedInstanceState.getString(STATE_SHARED_URL)
            incomingVideoState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                savedInstanceState.getParcelable(STATE_SHARED_VIDEO, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                savedInstanceState.getParcelable(STATE_SHARED_VIDEO)
            }
        }

        setContent {
            PocketLawyerTheme {
                PocketLawyerRoot(
                    sharedUrl = incomingUrlState,
                    sharedVideoUri = incomingVideoState,
                    shareNonce = shareNonce,
                    openAuditId = openAuditIdState,
                    onConsumedOpenAudit = { openAuditIdState = null }
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SHARED_URL, incomingUrlState)
        outState.putParcelable(STATE_SHARED_VIDEO, incomingVideoState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return

        intent.getStringExtra(BackgroundAuditWorker.EXTRA_OPEN_AUDIT_ID)?.let {
            openAuditIdState = it
        }

        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            incomingUrlState = intent.getStringExtra(Intent.EXTRA_TEXT)
            incomingVideoState = null
            shareNonce++
        } else if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("video/") == true) {
            incomingVideoState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            incomingUrlState = null
            shareNonce++
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!granted) notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
fun PocketLawyerRoot(
    sharedUrl: String?,
    sharedVideoUri: Uri?,
    shareNonce: Int,
    openAuditId: String?,
    onConsumedOpenAudit: () -> Unit,
    vm: PocketLawyerViewModel = viewModel()
) {
    val state by vm.uiState.collectAsState()
    val context = LocalContext.current
    var currentTab by remember { mutableStateOf(AppTab.AUDIT) }

    // Open audit automatically when user taps a background completion notification
    LaunchedEffect(openAuditId) {
        if (openAuditId != null) {
            vm.openAudit(openAuditId)
            onConsumedOpenAudit()
        }
    }

    // Hardware/gesture back closes an open report or scan instead of exiting the app
    BackHandler(enabled = state.activeReport != null) {
        vm.closeReport()
    }
    BackHandler(enabled = state.activeReport == null && currentTab == AppTab.SCAN && state.activeScan != null) {
        vm.clearActiveScan()
    }

    // Close any open dossier and jump to Audit whenever a new link or video is shared in
    LaunchedEffect(shareNonce) {
        if (shareNonce > 0 && (sharedUrl != null || sharedVideoUri != null)) {
            vm.closeReport()
            currentTab = AppTab.AUDIT
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MidnightBg)) {
        val activeReport = state.activeReport
        if (activeReport != null) {
            AuditReportScreen(
                report = activeReport,
                qaAnswer = state.qaAnswer,
                isAskingQa = state.isAskingQa,
                onAskQuestion = { vm.askFollowUp(it) },
                onCitationClick = { citation, jurisdiction -> vm.lookupStatute(citation, jurisdiction) },
                onBookmarkStatement = { st ->
                    val folder = if (st.side == com.example.pocketlawyeruk.model.SpeakerSide.PUBLIC) BookmarkFolder.MYTHBUSTER else BookmarkFolder.POLICE_AND_PUBLIC
                    vm.saveBookmark(
                        title = "${st.speaker}: \"${st.quote.take(45)}...\"",
                        content = "${st.verdict.label} — ${st.explanation}",
                        citation = st.citation,
                        folder = folder
                    )
                },
                onBack = { vm.closeReport() }
            )
        } else {
            Scaffold(
                modifier = Modifier.imePadding(),
                containerColor = MidnightBg,
                bottomBar = {
                    Column {
                        HorizontalDivider(color = CreamAccent.copy(alpha = 0.35f))
                        NavigationBar(containerColor = CardSurface) {
                            val navColors = NavigationBarItemDefaults.colors(
                                selectedIconColor = CreamTextDark,
                                selectedTextColor = CreamAccent,
                                indicatorColor = CreamAccent,
                                unselectedIconColor = TextSecondary,
                                unselectedTextColor = TextSecondary
                            )
                            NavigationBarItem(
                                selected = currentTab == AppTab.AUDIT,
                                onClick = { currentTab = AppTab.AUDIT },
                                icon = { Icon(Icons.Outlined.Radar, contentDescription = "Audit") },
                                label = { Text("AUDIT", fontFamily = CyberMono, fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                                colors = navColors
                            )
                            NavigationBarItem(
                                selected = currentTab == AppTab.SCAN,
                                onClick = { currentTab = AppTab.SCAN },
                                icon = { Icon(Icons.Outlined.DocumentScanner, contentDescription = "Scan") },
                                label = { Text("SCAN", fontFamily = CyberMono, fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                                colors = navColors
                            )
                            NavigationBarItem(
                                selected = currentTab == AppTab.CHAT,
                                onClick = { currentTab = AppTab.CHAT },
                                icon = { Icon(Icons.Outlined.Terminal, contentDescription = "Law AI") },
                                label = { Text("LAW AI", fontFamily = CyberMono, fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                                colors = navColors
                            )
                            NavigationBarItem(
                                selected = currentTab == AppTab.VAULT,
                                onClick = { currentTab = AppTab.VAULT },
                                icon = { Icon(Icons.Outlined.FolderSpecial, contentDescription = "Vault") },
                                label = { Text("VAULT", fontFamily = CyberMono, fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                                colors = navColors
                            )
                        }
                    }
                }
            ) { innerPadding ->
                Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                    when (currentTab) {
                        AppTab.AUDIT -> HomeScreen(
                            sharedUrl = sharedUrl,
                            sharedVideoUri = sharedVideoUri,
                            shareNonce = shareNonce,
                            audits = state.audits,
                            onAuditClick = { vm.openAudit(it) },
                            onRunAudit = { url, uri, type -> vm.runAudit(context, url, uri, type) },
                            onQueueBackgroundAudit = { url, type -> vm.queueBackgroundAudit(context, url, type) }
                        )
                        AppTab.SCAN -> ScanAndLettersScreen(
                            activeScan = state.activeScan,
                            isScanning = state.isScanningDocument,
                            onAnalyzeDocument = { uris, text, note -> vm.analyzeDocument(context, uris, text, note) },
                            onCitationClick = { citation, jur -> vm.lookupStatute(citation, jur) },
                            onClearActiveScan = { vm.clearActiveScan() }
                        )
                        AppTab.CHAT -> LegalChatScreen(
                            messages = state.chatMessages,
                            isLoading = state.isChatLoading,
                            currentApiKey = state.apiKey,
                            onUpdateApiKey = { vm.updateApiKey(it) },
                            onSendMessage = { vm.sendChatMessage(it) },
                            onBookmarkAnswer = { title, content, folder -> vm.saveBookmark(title, content, "", folder) },
                            onClearChat = { vm.clearChat() }
                        )
                        AppTab.VAULT -> VaultLibraryScreen(
                            searchQuery = state.vaultSearchQuery,
                            audits = state.audits,
                            scans = state.scans,
                            bookmarks = state.bookmarks,
                            onSearchChange = { vm.updateVaultSearch(it) },
                            onOpenAudit = { vm.openAudit(it) },
                            onDeleteAudit = { vm.deleteAudit(it) },
                            onOpenScan = { scan ->
                                vm.openSavedScan(scan)
                                currentTab = AppTab.SCAN
                            },
                            onDeleteScan = { vm.deleteScan(it) },
                            onDeleteBookmark = { vm.deleteBookmark(it) }
                        )
                    }
                }
            }
        }

        // Tap-to-Read UK Statute Bottom Sheet
        state.statuteSheetCitation?.let { citation ->
            StatuteLookupSheet(
                citationQuery = citation,
                isLoading = state.statuteLoading,
                result = state.statuteResult,
                errorMessage = state.statuteError,
                onBookmark = { title, content, cit, folder -> vm.saveBookmark(title, content, cit, folder) },
                onDismiss = { vm.dismissStatuteSheet() }
            )
        }

        // Cyber HUD Analyzing Overlay
        if (state.isAnalyzing) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.84f)),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = TacticalCardShape,
                    color = CardSurface,
                    border = BorderStroke(1.dp, CreamAccent),
                    modifier = Modifier.padding(28.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        CircularProgressIndicator(color = CreamAccent, strokeWidth = 3.dp)
                        Text(
                            text = state.statusMessage,
                            fontFamily = CyberMono,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = CreamAccent
                        )
                    }
                }
            }
        }

        // Cyber Alert Dialog
        state.errorMessage?.let { msg ->
            AlertDialog(
                onDismissRequest = { vm.clearError() },
                containerColor = CardSurface,
                titleContentColor = CreamAccent,
                textContentColor = TextPrimary,
                shape = TacticalCardShape,
                title = { Text("SYSTEM NOTICE", fontFamily = CyberMono, fontWeight = FontWeight.Bold, fontSize = 15.sp) },
                text = { Text(msg, fontSize = 13.sp, lineHeight = 19.sp) },
                confirmButton = {
                    TextButton(onClick = { vm.clearError() }) {
                        Text("ACKNOWLEDGE", fontFamily = CyberMono, color = CreamAccent, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    }
}
