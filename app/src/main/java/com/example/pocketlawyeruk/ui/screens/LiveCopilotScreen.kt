package com.example.pocketlawyeruk.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.pocketlawyeruk.data.LiveCoachAdvice
import com.example.pocketlawyeruk.data.LiveLegalRuleEngine
import com.example.pocketlawyeruk.data.LivePartyMode
import com.example.pocketlawyeruk.data.LiveRightsCard
import com.example.pocketlawyeruk.model.TranscriptLine
import com.example.pocketlawyeruk.model.UkJurisdiction
import com.example.pocketlawyeruk.ui.theme.*
import com.example.pocketlawyeruk.util.ContinuousSpeechController
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveCopilotScreen(
    coachAdvice: LiveCoachAdvice?,
    isCoachLoading: Boolean,
    coachError: String?,
    onRequestAiCheck: (recentTranscript: String, partyMode: LivePartyMode, jurisdiction: UkJurisdiction) -> Unit,
    onSaveFullAudit: (lines: List<TranscriptLine>, partyMode: LivePartyMode, jurisdiction: UkJurisdiction) -> Unit,
    onCitationClick: (citation: String, jurisdiction: UkJurisdiction) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current

    // Keep screen awake while Live Co-Pilot HUD is open
    DisposableEffect(view) {
        val previous = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }

    var isListening by remember { mutableStateOf(false) }
    var partyMode by remember { mutableStateOf(LivePartyMode.POLICE) }
    var jurisdiction by remember { mutableStateOf(UkJurisdiction.ENGLAND_WALES) }
    var sessionStartMs by remember { mutableLongStateOf(0L) }
    var partialText by remember { mutableStateOf("") }
    val transcriptLines = remember { mutableStateListOf<TranscriptLine>() }
    val triggeredCards = remember { mutableStateListOf<LiveRightsCard>() }
    val checkedGowisely = remember { mutableStateListOf<String>() }

    // 15-second cooldown on manual AI Check to protect free-tier quota
    var aiCooldownSec by remember { mutableIntStateOf(0) }
    LaunchedEffect(aiCooldownSec) {
        if (aiCooldownSec > 0) {
            delay(1000)
            aiCooldownSec--
        }
    }

    fun evaluateTextOffline(text: String) {
        val matches = LiveLegalRuleEngine.detectMatchingCards(text, partyMode, jurisdiction)
        matches.forEach { card ->
            val existingIdx = triggeredCards.indexOfFirst { it.id == card.id }
            if (existingIdx >= 0) {
                // Move most recently triggered card to top
                triggeredCards.removeAt(existingIdx)
            }
            triggeredCards.add(0, card)
        }
        if (partyMode == LivePartyMode.POLICE) {
            LiveLegalRuleEngine.detectGowiselyLetters(text).forEach { letter ->
                if (!checkedGowisely.contains(letter)) checkedGowisely.add(letter)
            }
        }
    }

    val speechController = remember { ContinuousSpeechController(context) }
    DisposableEffect(speechController) {
        onDispose { speechController.release() }
    }

    fun startMic() {
        if (sessionStartMs == 0L) sessionStartMs = System.currentTimeMillis()
        speechController.start(
            onPartial = { partial ->
                partialText = partial
                evaluateTextOffline(partial)
            },
            onFinal = { finalLine ->
                val sec = ((System.currentTimeMillis() - sessionStartMs) / 1000L).toInt().coerceAtLeast(0)
                transcriptLines.add(TranscriptLine(sec, "Live Audio", finalLine))
                evaluateTextOffline(finalLine)
            },
            onListeningChanged = { active -> isListening = active },
            onFatalError = { err -> Toast.makeText(context, err, Toast.LENGTH_LONG).show() }
        )
    }

    val micPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startMic()
        else Toast.makeText(context, "Microphone permission needed for Live Co-Pilot.", Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        containerColor = MidnightBg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CardSurface,
                    titleContentColor = TextPrimary,
                    navigationIconContentColor = CreamAccent
                ),
                navigationIcon = {
                    IconButton(onClick = {
                        speechController.stop()
                        onClose()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Exit Live Co-Pilot")
                    }
                },
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (isListening) RoseText else TextSecondary)
                            )
                            Text(
                                text = if (isListening) "LIVE CO-PILOT // LISTENING" else "LIVE CO-PILOT // STANDBY",
                                fontFamily = CyberMono,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isListening) RoseText else CreamAccent
                            )
                        }
                        Text(
                            text = "0 API CALLS OFFLINE • SILENT HUD",
                            fontFamily = CyberMono,
                            fontSize = 10.sp,
                            color = EmeraldText
                        )
                    }
                },
                actions = {
                    // Tap to cycle England & Wales -> Scotland -> Northern Ireland
                    Surface(
                        shape = TacticalPillShape,
                        color = MidnightBg,
                        border = BorderStroke(1.dp, CreamAccent.copy(alpha = 0.6f)),
                        modifier = Modifier
                            .padding(end = 10.dp)
                            .clickable {
                                val entries = UkJurisdiction.entries
                                jurisdiction = entries[(entries.indexOf(jurisdiction) + 1) % entries.size]
                            }
                    ) {
                        val shortJur = when (jurisdiction) {
                            UkJurisdiction.ENGLAND_WALES -> "ENG/WAL"
                            UkJurisdiction.SCOTLAND -> "SCOT"
                            UkJurisdiction.NORTHERN_IRELAND -> "N.I."
                        }
                        Text(
                            text = shortJur,
                            fontFamily = CyberMono,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = CreamAccent,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Party Toggle + Primary Mic Control
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LivePartyMode.entries.forEach { mode ->
                    val selected = partyMode == mode
                    Surface(
                        shape = TacticalPillShape,
                        color = if (selected) CreamAccent else CardSurface,
                        border = BorderStroke(1.dp, if (selected) CreamAccent else CardBorder),
                        modifier = Modifier.weight(1f).clickable { partyMode = mode }
                    ) {
                        Box(modifier = Modifier.padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
                            Text(
                                text = mode.label.uppercase(),
                                fontFamily = CyberMono,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selected) CreamTextDark else TextPrimary
                            )
                        }
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (isListening) {
                            speechController.stop()
                        } else {
                            val hasPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                            if (hasPerm) startMic() else micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = TacticalPillShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isListening) RoseText else EmeraldText,
                        contentColor = if (isListening) TextPrimary else CreamTextDark
                    )
                ) {
                    Icon(if (isListening) Icons.Outlined.Stop else Icons.Outlined.Mic, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (isListening) "PAUSE MIC" else "START LISTENING",
                        fontFamily = CyberMono,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Manual 1-call AI Check with 15s cooldown
                val canAiCheck = !isCoachLoading && aiCooldownSec == 0 && (transcriptLines.isNotEmpty() || partialText.isNotBlank())
                OutlinedButton(
                    onClick = {
                        val recent = (transcriptLines.takeLast(12).map { it.text } + partialText)
                            .filter { it.isNotBlank() }
                            .joinToString("\n")
                        aiCooldownSec = 15
                        onRequestAiCheck(recent, partyMode, jurisdiction)
                    },
                    enabled = canAiCheck,
                    modifier = Modifier.height(52.dp),
                    shape = TacticalPillShape,
                    border = BorderStroke(1.dp, if (canAiCheck) CreamAccent else CardBorder),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CreamAccent)
                ) {
                    if (isCoachLoading) {
                        CircularProgressIndicator(color = CreamAccent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    } else {
                        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = if (aiCooldownSec > 0) "${aiCooldownSec}s" else "AI CHECK",
                            fontFamily = CyberMono,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // 2. Live GOWISELY Strip (Police Mode Only — auto-checks + tap to toggle manually)
            if (partyMode == LivePartyMode.POLICE) {
                Surface(shape = TacticalCardShape, color = CardSurface, border = BorderStroke(1.dp, CardBorder)) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "LIVE GOWISELY TRACKER (AUTO + TAP)",
                            fontFamily = CyberMono,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextSecondary
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            LiveLegalRuleEngine.gowiselySteps.forEach { step ->
                                val done = checkedGowisely.contains(step.letter)
                                Surface(
                                    shape = TacticalPillShape,
                                    color = if (done) EmeraldBg else MidnightBg,
                                    border = BorderStroke(1.dp, if (done) EmeraldText else CardBorder),
                                    modifier = Modifier.weight(1f).clickable {
                                        if (done) checkedGowisely.remove(step.letter) else checkedGowisely.add(step.letter)
                                    }
                                ) {
                                    Box(modifier = Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                        Text(
                                            text = step.letter,
                                            fontFamily = CyberMono,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (done) EmeraldText else TextSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3. Optional Manual AI Tactical Coach Box
            if (coachError != null) {
                Text(coachError, color = RoseText, fontSize = 12.sp, fontFamily = CyberMono)
            }
            if (coachAdvice != null) {
                Surface(
                    shape = TacticalCardShape,
                    color = CardSurface,
                    border = BorderStroke(1.dp, CreamAccent)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "AI TACTICAL COACH",
                            fontFamily = CyberMono,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = CreamAccent
                        )
                        Text("✓ REFUSE: ${coachAdvice.canRefuse}", fontSize = 13.sp, color = EmeraldText, lineHeight = 18.sp)
                        Text("! COMPLY: ${coachAdvice.mustComply}", fontSize = 13.sp, color = AmberText, lineHeight = 18.sp)
                        Surface(shape = TacticalPillShape, color = MidnightBg, border = BorderStroke(1.dp, CreamAccent.copy(alpha = 0.5f))) {
                            Text(
                                text = "SAY: \"${coachAdvice.sayNow}\"",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextPrimary,
                                lineHeight = 19.sp,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                        Text(
                            text = "▸ TAP STATUTE: ${coachAdvice.statute}",
                            fontFamily = CyberMono,
                            fontSize = 10.sp,
                            color = CreamAccent,
                            modifier = Modifier.clickable { onCitationClick(coachAdvice.statute, jurisdiction) }
                        )
                    }
                }
            }

            // 4. Instant Offline UK Rights Cards (0 API Calls)
            Text(
                text = "INSTANT RIGHTS TRIGGERS (${triggeredCards.size})",
                fontFamily = CyberMono,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = EmeraldText,
                letterSpacing = 1.sp
            )

            if (triggeredCards.isEmpty()) {
                Surface(shape = TacticalCardShape, color = CardSurface, border = BorderStroke(1.dp, CardBorder), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Tap START LISTENING. The moment police or staff mention ID, Section 50, filming, GDPR, deleting footage, trespass, drones, or a search, your exact UK right and response script flash here instantly — offline, using 0 API calls.",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            } else {
                triggeredCards.forEach { card ->
                    val accent = if (card.isHighAlert) RoseText else EmeraldText
                    Surface(
                        shape = TacticalCardShape,
                        color = CardSurface,
                        border = BorderStroke(1.dp, accent.copy(alpha = 0.65f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(card.title, fontFamily = CyberMono, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = accent, modifier = Modifier.weight(1f))
                                Icon(
                                    imageVector = Icons.Outlined.Gavel,
                                    contentDescription = "Read Statute",
                                    tint = CreamAccent,
                                    modifier = Modifier.size(18.dp).clickable { onCitationClick(card.statute, jurisdiction) }
                                )
                            }
                            Text(card.yourRight, fontSize = 13.sp, color = TextPrimary, lineHeight = 19.sp)
                            Surface(
                                shape = TacticalPillShape,
                                color = MidnightBg,
                                border = BorderStroke(1.dp, EmeraldText.copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text("SAY THIS NOW:", fontFamily = CyberMono, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = EmeraldText)
                                    Text("\"${card.sayThis}\"", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextPrimary, lineHeight = 19.sp)
                                }
                            }
                            Text(
                                text = "▸ TAP STATUTE: ${card.statute}",
                                fontFamily = CyberMono,
                                fontSize = 10.sp,
                                color = CreamAccent,
                                modifier = Modifier.clickable { onCitationClick(card.statute, jurisdiction) }
                            )
                        }
                    }
                }
            }

            // 5. Live Transcript Feed + Save Full Audit to Vault
            Surface(shape = TacticalCardShape, color = CardSurface, border = BorderStroke(1.dp, CardBorder), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("LIVE AUDIO FEED (${transcriptLines.size})", fontFamily = CyberMono, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = CreamAccent)
                        if (transcriptLines.isNotEmpty()) {
                            Text(
                                text = "CLEAR",
                                fontFamily = CyberMono,
                                fontSize = 10.sp,
                                color = RoseText,
                                modifier = Modifier.clickable {
                                    transcriptLines.clear()
                                    triggeredCards.clear()
                                    checkedGowisely.clear()
                                    sessionStartMs = 0L
                                }
                            )
                        }
                    }

                    if (partialText.isNotBlank()) {
                        Text("▸ $partialText...", fontSize = 13.sp, color = CreamAccent, lineHeight = 18.sp)
                    }

                    if (transcriptLines.isEmpty() && partialText.isBlank()) {
                        Text("Waiting for speech...", fontFamily = CyberMono, fontSize = 12.sp, color = TextSecondary)
                    } else {
                        transcriptLines.takeLast(8).reversed().forEach { line ->
                            val ts = "%02d:%02d".format(line.timestampSeconds / 60, line.timestampSeconds % 60)
                            Text("[$ts] ${line.text}", fontSize = 12.sp, color = TextSecondary, lineHeight = 17.sp)
                        }
                    }
                }
            }

            if (transcriptLines.isNotEmpty()) {
                Button(
                    onClick = {
                        speechController.stop()
                        onSaveFullAudit(transcriptLines.toList(), partyMode, jurisdiction)
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = TacticalPillShape,
                    colors = ButtonDefaults.buttonColors(containerColor = CreamAccent, contentColor = CreamTextDark)
                ) {
                    Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "SAVE TO VAULT (1 REQ)",
                        fontFamily = CyberMono,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}