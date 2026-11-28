package com.example.pocketlawyeruk.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.pocketlawyeruk.model.*
import com.example.pocketlawyeruk.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditReportScreen(
    report: AuditReport,
    qaAnswer: String?,
    isAskingQa: Boolean,
    onAskQuestion: (String) -> Unit,
    onCitationClick: (citation: String, jurisdiction: UkJurisdiction) -> Unit,
    onBookmarkStatement: (AuditedStatement) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var questionText by remember { mutableStateOf("") }
    // null = ALL CLAIMS, otherwise filter by specific SpeakerSide
    var selectedSide by remember(report.id) { mutableStateOf<SpeakerSide?>(null) }

    val scrollState = rememberScrollState()
    var isFollowUpFocused by remember { mutableStateOf(false) }
    val imeBottomPx = WindowInsets.ime.getBottom(LocalDensity.current)

    // Automatically scroll the input card above the keyboard whenever it opens
    LaunchedEffect(imeBottomPx, isFollowUpFocused) {
        if (isFollowUpFocused && imeBottomPx > 0) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    val exoPlayer = remember(report.videoUriString) {
        if (report.videoUriString != null) {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.parse(report.videoUriString)))
                prepare()
            }
        } else null
    }
    DisposableEffect(exoPlayer) { onDispose { exoPlayer?.release() } }

    val hasApplicableGowisely = remember(report.gowiselyChecklist) {
        report.gowiselyChecklist.any { it.status != GowiselyStatus.SKIPPED }
    }
    val hasPoliceInClip = remember(report, hasApplicableGowisely) {
        hasApplicableGowisely ||
            report.officerViolationCount > 0 ||
            report.timeline.any { it.side == SpeakerSide.OFFICER }
    }
    val hasStaffInClip = remember(report) {
        report.staffViolationCount > 0 ||
            report.timeline.any { it.side == SpeakerSide.STAFF_SECURITY }
    }
    val staffPillLabel = when {
        report.timeline.any {
            it.speaker.contains("Bailiff", true) || it.speaker.contains("HCEA", true) ||
                it.speaker.contains("Collector", true) || it.speaker.contains("Caller", true)
        } -> "${report.staffViolationCount} CALLER FLAGS"
        report.timeline.any {
            it.speaker.contains("Landlord", true) || it.speaker.contains("TV Licensing", true) ||
                it.speaker.contains("Dealer", true) || it.speaker.contains("Inspector", true) ||
                it.speaker.contains("Warden", true) || it.speaker.contains("Neighbour", true)
        } -> "${report.staffViolationCount} PARTY FLAGS"
        else -> "${report.staffViolationCount} STAFF FLAGS"
    }

    val scoreColor = when {
        report.complianceScore >= 80 -> EmeraldText
        report.complianceScore >= 50 -> AmberText
        else -> RoseText
    }

    val scoreReason = remember(report, hasPoliceInClip, hasStaffInClip) {
        report.scoreSummary.ifBlank {
            when {
                hasStaffInClip && !hasPoliceInClip ->
                    "${report.staffViolationCount} unlawful or false claim(s) made by staff/security."
                hasPoliceInClip ->
                    "${report.officerViolationCount} officer breach(es) and ${report.publicMythCount} public myth(s) flagged."
                report.publicMythCount > 0 ->
                    "${report.publicMythCount} unlawful or misleading claim(s) flagged in this clip."
                else -> "Overall legal conduct score for this encounter."
            }
        }
    }

    val availableSides = remember(report.timeline) {
        SpeakerSide.entries.filter { side -> report.timeline.any { it.side == side } }
    }

    val filteredTimeline = remember(report.timeline, selectedSide) {
        val list = if (selectedSide == null) report.timeline
        else report.timeline.filter { it.side == selectedSide }
        list.sortedBy { it.timestampSeconds }
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
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column {
                        Text(report.title, fontFamily = CyberMono, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = "${report.jurisdiction.displayName.uppercase()} • ${report.date}",
                            fontFamily = CyberMono,
                            fontSize = 11.sp,
                            color = CreamAccent
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
                .consumeWindowInsets(innerPadding)
                .imePadding()
                .verticalScroll(scrollState)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Video Player or Source URL
            if (exoPlayer != null) {
                Surface(
                    shape = TacticalCardShape,
                    color = Color.Black,
                    border = BorderStroke(1.dp, CreamAccent.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth().height(210.dp)
                ) {
                    AndroidView(
                        factory = { ctx -> PlayerView(ctx).apply { player = exoPlayer } },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else if (!report.sourceUrl.isNullOrBlank()) {
                Surface(
                    shape = TacticalPillShape,
                    color = CardSurface,
                    border = BorderStroke(1.dp, CardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Link, contentDescription = null, tint = CreamAccent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(report.sourceUrl, fontFamily = CyberMono, fontSize = 11.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            // 2. Smart Compliance Card with 1-Line Explanation
            Surface(
                shape = TacticalCardShape,
                color = CardSurface,
                border = BorderStroke(1.dp, scoreColor.copy(alpha = 0.55f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = if (hasPoliceInClip) "PROCEDURAL COMPLIANCE" else "LEGAL COMPLIANCE",
                                fontFamily = CyberMono,
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                            Text(
                                text = "${report.complianceScore}%",
                                fontFamily = CyberMono,
                                fontSize = 34.sp,
                                fontWeight = FontWeight.Bold,
                                color = scoreColor
                            )
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (hasPoliceInClip) {
                                StatusCountPill(
                                    label = "${report.officerViolationCount} OFFICER FLAGS",
                                    isClear = report.officerViolationCount == 0,
                                    alertBg = RoseBg,
                                    alertFg = RoseText
                                )
                            }
                            if (hasStaffInClip) {
                                StatusCountPill(
                                    label = staffPillLabel,
                                    isClear = report.staffViolationCount == 0,
                                    alertBg = RoseBg,
                                    alertFg = RoseText
                                )
                            }
                            StatusCountPill(
                                label = if (hasStaffInClip || hasPoliceInClip) "${report.publicMythCount} FILMER MYTHS" else "${report.publicMythCount} PUBLIC MYTHS",
                                isClear = report.publicMythCount == 0,
                                alertBg = AmberBg,
                                alertFg = AmberText,
                                clearBg = MidnightBg,
                                clearFg = TextSecondary
                            )
                        }
                    }

                    LinearProgressIndicator(
                        progress = { report.complianceScore / 100f },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = scoreColor,
                        trackColor = MidnightBg
                    )

                    Text(
                        text = scoreReason,
                        fontSize = 12.sp,
                        color = TextSecondary,
                        lineHeight = 17.sp
                    )
                }
            }

            // 3. Dynamic Fact-Check Filter Tabs (All + only sides present in clip)
            Text("LEGAL FACT-CHECK BREAKDOWN", fontFamily = CyberMono, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CreamAccent, letterSpacing = 1.sp)
            if (availableSides.size > 1) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterTabChip(
                        label = "ALL (${report.timeline.size})",
                        selected = selectedSide == null,
                        modifier = Modifier.weight(1f),
                        onClick = { selectedSide = null }
                    )
                    availableSides.forEach { side ->
                        FilterTabChip(
                            label = side.label.uppercase(),
                            selected = selectedSide == side,
                            modifier = Modifier.weight(1f),
                            onClick = { selectedSide = side }
                        )
                    }
                }
            }

            if (filteredTimeline.isEmpty()) {
                Text("No statements logged under this filter.", fontFamily = CyberMono, fontSize = 12.sp, color = TextSecondary)
            } else {
                filteredTimeline.forEach { statement ->
                    CyberTimelineCard(
                        statement = statement,
                        onJump = { sec ->
                            exoPlayer?.seekTo(sec * 1000L)
                            exoPlayer?.playWhenReady = true
                        },
                        onCitationClick = { onCitationClick(statement.citation, report.jurisdiction) },
                        onBookmark = {
                            onBookmarkStatement(statement)
                            Toast.makeText(context, "Saved to Mythbuster Vault", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }

            // 4. "What If This Happened to Me?" Tactical Playbook
            val guide = report.whatIfGuide
            if (guide.whatYouCanRefuse.isNotEmpty() || guide.whatYouMustDo.isNotEmpty() || guide.exactScriptToSay.isNotBlank()) {
                Text("WHAT IF THIS HAPPENED TO ME?", fontFamily = CyberMono, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = EmeraldText, letterSpacing = 1.sp)
                Surface(
                    shape = TacticalCardShape,
                    color = CardSurface,
                    border = BorderStroke(1.dp, EmeraldText.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (guide.whatYouCanRefuse.isNotEmpty()) {
                            Text("YOU CAN LAWFULLY REFUSE:", fontFamily = CyberMono, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = EmeraldText)
                            guide.whatYouCanRefuse.forEach { Text("✓ $it", fontSize = 13.sp, color = TextPrimary, lineHeight = 19.sp) }
                        }
                        if (guide.whatYouMustDo.isNotEmpty()) {
                            Text("YOU MUST COMPLY WITH:", fontFamily = CyberMono, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AmberText)
                            guide.whatYouMustDo.forEach { Text("! $it", fontSize = 13.sp, color = TextPrimary, lineHeight = 19.sp) }
                        }
                        if (guide.exactScriptToSay.isNotBlank()) {
                            Surface(
                                shape = TacticalPillShape,
                                color = MidnightBg,
                                border = BorderStroke(1.dp, CreamAccent.copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("RECOMMENDED SCRIPT", fontFamily = CyberMono, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = CreamAccent)
                                        Icon(
                                            imageVector = Icons.Outlined.ContentCopy,
                                            contentDescription = "Copy script",
                                            tint = CreamAccent,
                                            modifier = Modifier.size(16.dp).clickable {
                                                clipboard.setText(AnnotatedString(guide.exactScriptToSay))
                                                Toast.makeText(context, "Script copied", Toast.LENGTH_SHORT).show()
                                            }
                                        )
                                    }
                                    Text("\"${guide.exactScriptToSay}\"", fontSize = 13.sp, color = TextPrimary, lineHeight = 19.sp)
                                }
                            }
                        }
                    }
                }
            }

            // 5. GOWISELY Audit Matrix (Auto-hidden when no police stop & search occurred)
            if (hasApplicableGowisely) {
                Text("GOWISELY PROCEDURAL MATRIX", fontFamily = CyberMono, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextSecondary, letterSpacing = 1.sp)
                Surface(shape = TacticalCardShape, color = CardSurface, border = BorderStroke(1.dp, CardBorder)) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        report.gowiselyChecklist.forEachIndexed { idx, item ->
                            val (bg, fg, lbl) = when (item.status) {
                                GowiselyStatus.VERIFIED -> Triple(EmeraldBg, EmeraldText, "VERIFIED")
                                GowiselyStatus.FLAGGED -> Triple(RoseBg, RoseText, "BREACH")
                                GowiselyStatus.SKIPPED -> Triple(MidnightBg, TextSecondary, "N/A")
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(item.step, fontFamily = CyberMono, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary, modifier = Modifier.weight(1f))
                                    Surface(shape = TacticalPillShape, color = bg) {
                                        Text(lbl, fontFamily = CyberMono, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = fg, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                                    }
                                }
                                if (item.note.isNotBlank()) Text(item.note, fontSize = 12.sp, color = TextSecondary)
                            }
                            if (idx < report.gowiselyChecklist.lastIndex) HorizontalDivider(color = CardBorder)
                        }
                    }
                }
            }

            // 6. Your Rights Summary
            if (report.yourRights.isNotEmpty()) {
                Text("APPLICABLE STATUTORY RIGHTS", fontFamily = CyberMono, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextSecondary, letterSpacing = 1.sp)
                Surface(shape = TacticalCardShape, color = CardSurface, border = BorderStroke(1.dp, CardBorder)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        report.yourRights.forEach { right ->
                            Text("▸ $right", fontSize = 13.sp, color = TextPrimary, lineHeight = 19.sp)
                        }
                    }
                }
            }

            // 7. Expandable Full Transcript
            if (report.fullTranscript.isNotEmpty()) {
                var showTranscript by remember { mutableStateOf(false) }
                Surface(
                    shape = TacticalCardShape,
                    color = CardSurface,
                    border = BorderStroke(1.dp, CardBorder),
                    modifier = Modifier.fillMaxWidth().clickable { showTranscript = !showTranscript }
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("INTERCEPT TRANSCRIPT (${report.fullTranscript.size} LINES)", fontFamily = CyberMono, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CreamAccent)
                            Icon(if (showTranscript) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null, tint = CreamAccent)
                        }
                        if (showTranscript) {
                            HorizontalDivider(color = CardBorder)
                            report.fullTranscript.forEach { line ->
                                val ts = "%02d:%02d".format(line.timestampSeconds / 60, line.timestampSeconds % 60)
                                Column {
                                    Text("[$ts] ${line.speaker.uppercase()}", fontFamily = CyberMono, fontSize = 11.sp, color = CreamAccent)
                                    Text(line.text, fontSize = 13.sp, color = TextPrimary, lineHeight = 19.sp)
                                }
                            }
                        }
                    }
                }
            }

            // 8. Final Legal Briefing + Follow-up Chat
            Surface(shape = TacticalCardShape, color = CardSurface, border = BorderStroke(1.dp, CreamAccent.copy(alpha = 0.5f))) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("FINAL LEGAL BRIEFING", fontFamily = CyberMono, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CreamAccent)
                    Text(report.takeaway, fontSize = 14.sp, color = TextPrimary, lineHeight = 20.sp)
                }
            }

            Surface(shape = TacticalCardShape, color = CardSurface, border = BorderStroke(1.dp, CardBorder)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("INTERROGATE THIS CASE", fontFamily = CyberMono, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CreamAccent)
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = questionText,
                            onValueChange = { questionText = it },
                            placeholder = { Text("Ask a follow-up about this clip...", fontSize = 13.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            singleLine = true,
                            shape = TacticalPillShape,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CreamAccent,
                                unfocusedBorderColor = CardBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .onFocusChanged { isFollowUpFocused = it.isFocused }
                        )
                        Spacer(Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                if (questionText.isNotBlank()) {
                                    onAskQuestion(questionText.trim())
                                    questionText = ""
                                }
                            },
                            enabled = !isAskingQa && questionText.isNotBlank(),
                            modifier = Modifier.size(48.dp).background(CreamAccent, TacticalPillShape)
                        ) {
                            if (isAskingQa) CircularProgressIndicator(color = CreamTextDark, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                            else Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = CreamTextDark)
                        }
                    }
                    if (!qaAnswer.isNullOrBlank()) {
                        Surface(shape = TacticalPillShape, color = MidnightBg, border = BorderStroke(1.dp, CardBorder)) {
                            Text(qaAnswer, fontSize = 13.sp, color = TextPrimary, lineHeight = 19.sp, modifier = Modifier.padding(12.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StatusCountPill(
    label: String,
    isClear: Boolean,
    alertBg: Color,
    alertFg: Color,
    clearBg: Color = EmeraldBg,
    clearFg: Color = EmeraldText
) {
    val bg = if (isClear) clearBg else alertBg
    val fg = if (isClear) clearFg else alertFg
    Surface(
        shape = TacticalPillShape,
        color = bg,
        border = BorderStroke(1.dp, fg.copy(alpha = 0.5f))
    ) {
        Text(
            text = label,
            fontFamily = CyberMono,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = fg,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun FilterTabChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        shape = TacticalPillShape,
        color = if (selected) CreamAccent else CardSurface,
        border = BorderStroke(1.dp, if (selected) CreamAccent else CardBorder),
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Box(modifier = Modifier.padding(vertical = 9.dp, horizontal = 4.dp), contentAlignment = Alignment.Center) {
            Text(
                text = label,
                fontFamily = CyberMono,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = if (selected) CreamTextDark else TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun CyberTimelineCard(
    statement: AuditedStatement,
    onJump: (Int) -> Unit,
    onCitationClick: () -> Unit,
    onBookmark: () -> Unit
) {
    val (badgeBg, badgeFg) = when (statement.verdict) {
        StatementVerdict.LAWFUL -> EmeraldBg to EmeraldText
        StatementVerdict.PROCEDURAL_BREACH -> AmberBg to AmberText
        StatementVerdict.MISLEADING_CLAIM -> AmberBg to AmberText
        StatementVerdict.UNLAWFUL_DEMAND -> RoseBg to RoseText
    }
    val ts = "%02d:%02d".format(statement.timestampSeconds / 60, statement.timestampSeconds % 60)

    Surface(
        shape = TacticalCardShape,
        color = CardSurface,
        border = BorderStroke(1.dp, badgeFg.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth().clickable { onJump(statement.timestampSeconds) }
    ) {
        Column(modifier = Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            // Clean single-line header: Timestamp + Speaker on left, Crisp Verdict Pill on right
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(shape = TacticalPillShape, color = MidnightBg, border = BorderStroke(1.dp, CardBorder)) {
                    Text(
                        text = ts,
                        fontFamily = CyberMono,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = CreamAccent,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }

                val headerSpeaker = if (statement.side.label.contains(statement.speaker, ignoreCase = true)) {
                    statement.side.label.uppercase()
                } else {
                    "${statement.speaker.uppercase()} • ${statement.side.label.uppercase()}"
                }

                Text(
                    text = headerSpeaker,
                    fontFamily = CyberMono,
                    fontSize = 10.sp,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Surface(shape = TacticalPillShape, color = badgeBg) {
                    Text(
                        text = statement.verdict.label.uppercase(),
                        fontFamily = CyberMono,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = badgeFg,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Text("\"${statement.quote}\"", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary, lineHeight = 20.sp)
            Text(statement.explanation, fontSize = 13.sp, color = TextSecondary, lineHeight = 19.sp)

            // Bottom action bar: Tap-to-Read Statute on left + Dedicated Save button on right
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (statement.citation.isNotBlank()) {
                    Surface(
                        shape = TacticalPillShape,
                        color = MidnightBg,
                        border = BorderStroke(1.dp, CreamAccent.copy(alpha = 0.5f)),
                        modifier = Modifier.weight(1f).clickable(onClick = onCitationClick)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Outlined.Gavel, contentDescription = null, tint = CreamAccent, modifier = Modifier.size(13.dp))
                            Text(
                                text = "STATUTE: ${statement.citation}",
                                fontFamily = CyberMono,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = CreamAccent,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }

                Surface(
                    shape = TacticalPillShape,
                    color = MidnightBg,
                    border = BorderStroke(1.dp, EmeraldText.copy(alpha = 0.5f)),
                    modifier = Modifier.clickable(onClick = onBookmark)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Outlined.BookmarkAdd, contentDescription = "Save", tint = EmeraldText, modifier = Modifier.size(14.dp))
                        Text("SAVE", fontFamily = CyberMono, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = EmeraldText)
                    }
                }
            }
        }
    }
}