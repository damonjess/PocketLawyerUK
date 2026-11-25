package com.example.pocketlawyeruk.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pocketlawyeruk.model.*
import com.example.pocketlawyeruk.ui.theme.*

enum class EncounterType(val label: String, val statuteSubtitle: String) {
    PEDESTRIAN("Pedestrian", "Pedestrian Stop & Search (PACE s.1)"),
    VEHICLE("Vehicle", "Vehicle Traffic Stop (RTA s.163)"),
    FILMING("Filming", "Filming / Audit (Public Photography)")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    sharedUrl: String? = null,
    sharedVideoUri: Uri? = null,
    shareNonce: Int = 0,
    audits: List<AuditReport>,
    onAuditClick: (String) -> Unit,
    onRunAudit: (url: String, videoUri: Uri?, type: EncounterType, party: ConfrontingParty, depth: AuditDepth) -> Unit,
    onQueueBackgroundAudit: (url: String, type: EncounterType, party: ConfrontingParty, depth: AuditDepth) -> Unit,
    onOpenLiveCopilot: () -> Unit = {}
) {
    val clipboardManager = LocalClipboardManager.current
    var linkText by remember(sharedUrl, shareNonce) { mutableStateOf(sharedUrl.orEmpty()) }
    var selectedVideoUri by remember(sharedVideoUri, shareNonce) { mutableStateOf(sharedVideoUri) }
    var selectedType by remember { mutableStateOf(EncounterType.PEDESTRIAN) }
    var selectedParty by remember { mutableStateOf(ConfrontingParty.AUTO) }
    var selectedDepth by remember { mutableStateOf(AuditDepth.FAST) }

    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            selectedVideoUri = uri
            linkText = ""
        }
    }

    val canRunAudit = linkText.isNotBlank() || selectedVideoUri != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MidnightBg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // 1. Cyber Classified HUD Header
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "SYS // UK-LEGAL-INTEL v2.6",
                    fontFamily = CyberMono,
                    fontSize = 11.sp,
                    letterSpacing = 1.4.sp,
                    color = CreamAccent
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(EmeraldText))
                    Text("ONLINE", fontFamily = CyberMono, fontSize = 10.sp, color = EmeraldText, fontWeight = FontWeight.Bold)
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Shield, contentDescription = null, tint = CreamAccent, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "POCKET LAWYER UK",
                    fontFamily = CyberMono,
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    color = TextPrimary
                )
            }
            Text(
                text = "Intercept, transcribe & verify police/public legal claims across England, Wales, Scotland & NI.",
                fontSize = 12.sp,
                color = TextSecondary,
                lineHeight = 17.sp
            )
        }

        // Live Real-Time Encounter Co-Pilot Launcher
        Surface(
            shape = TacticalCardShape,
            color = EmeraldBg,
            border = BorderStroke(1.dp, EmeraldText.copy(alpha = 0.7f)),
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenLiveCopilot)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Outlined.Mic, contentDescription = null, tint = EmeraldText, modifier = Modifier.size(22.dp))
                    Column {
                        Text(
                            text = "LIVE ENCOUNTER CO-PILOT",
                            fontFamily = CyberMono,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = EmeraldText
                        )
                        Text(
                            text = "Real-time UK rights alerts while listening • 0 API calls offline",
                            fontSize = 11.sp,
                            color = TextPrimary
                        )
                    }
                }
                Text("LAUNCH ▸", fontFamily = CyberMono, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = EmeraldText)
            }
        }

        // 2. Cyber Target Ingestion Card
        Surface(
            shape = TacticalCardShape,
            color = CardSurface,
            border = BorderStroke(1.dp, CreamAccent.copy(alpha = 0.45f))
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "[ TARGET VIDEO INTERCEPT ]",
                    fontFamily = CyberMono,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    color = CreamAccent
                )

                // Link Input + Embedded Paste Trigger
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .background(MidnightBg, TacticalPillShape)
                        .border(1.dp, CardBorder, TacticalPillShape)
                        .padding(start = 12.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = linkText,
                        onValueChange = { newText ->
                            linkText = newText
                            if (newText.isNotBlank()) selectedVideoUri = null
                        },
                        placeholder = {
                            Text("TikTok / YouTube / Reels URL...", fontFamily = CyberMono, fontSize = 12.sp, color = TextSecondary, maxLines = 1)
                        },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    if (linkText.isNotBlank()) {
                        IconButton(
                            onClick = { linkText = "" },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Outlined.Close, contentDescription = "Clear link", tint = TextSecondary, modifier = Modifier.size(16.dp))
                        }
                    }
                    Button(
                        onClick = {
                            clipboardManager.getText()?.text?.let {
                                linkText = it
                                selectedVideoUri = null
                            }
                        },
                        shape = TacticalPillShape,
                        colors = ButtonDefaults.buttonColors(containerColor = CreamAccent, contentColor = CreamTextDark),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Outlined.ContentPaste, contentDescription = "Paste", modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("PASTE", fontFamily = CyberMono, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                }

                // Local Video Upload Button
                OutlinedButton(
                    onClick = {
                        videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                    },
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = TacticalPillShape,
                    border = BorderStroke(1.dp, if (selectedVideoUri != null) EmeraldText else CardBorder),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (selectedVideoUri != null) EmeraldText else TextPrimary
                    )
                ) {
                    Icon(Icons.Outlined.UploadFile, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (selectedVideoUri != null) "REPLACE LOCAL VIDEO FILE" else "LOAD LOCAL VIDEO FILE",
                        fontFamily = CyberMono,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (selectedVideoUri != null) {
                    Surface(shape = TacticalPillShape, color = EmeraldBg, border = BorderStroke(1.dp, EmeraldText)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("LOCAL CLIP LOCKED [✓]", fontFamily = CyberMono, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = EmeraldText)
                            Text(
                                text = "CLEAR",
                                fontFamily = CyberMono,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = RoseText,
                                modifier = Modifier.clickable { selectedVideoUri = null }
                            )
                        }
                    }
                }

                // Confronting Party Lock (Police vs Staff / Security)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "WHO IS IN THE CLIP?",
                        fontFamily = CyberMono,
                        fontSize = 11.sp,
                        color = TextSecondary,
                        letterSpacing = 1.sp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ConfrontingParty.entries.forEach { party ->
                            val isSelected = party == selectedParty
                            Surface(
                                shape = TacticalPillShape,
                                color = if (isSelected) CreamAccent else MidnightBg,
                                border = BorderStroke(1.dp, if (isSelected) CreamAccent else CardBorder),
                                modifier = Modifier.weight(1f).clickable { selectedParty = party }
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = party.label.uppercase(),
                                        fontFamily = CyberMono,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) CreamTextDark else TextPrimary,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        text = "▸ ${selectedParty.hint}",
                        fontFamily = CyberMono,
                        fontSize = 11.sp,
                        color = if (selectedParty == ConfrontingParty.AUTO) TextSecondary else EmeraldText
                    )
                }

                // Encounter Category Selector
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("CLASSIFICATION HINT", fontFamily = CyberMono, fontSize = 11.sp, color = TextSecondary, letterSpacing = 1.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        EncounterType.entries.forEach { type ->
                            val isSelected = type == selectedType
                            Surface(
                                shape = TacticalPillShape,
                                color = if (isSelected) CreamAccent else MidnightBg,
                                border = BorderStroke(1.dp, if (isSelected) CreamAccent else CardBorder),
                                modifier = Modifier.clickable { selectedType = type }
                            ) {
                                Text(
                                    text = type.label.uppercase(),
                                    fontFamily = CyberMono,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) CreamTextDark else TextPrimary,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = "▸ ${selectedType.statuteSubtitle} • Jurisdiction auto-detected",
                        fontFamily = CyberMono,
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }

                // 3-button row: RUN AUDIT + FAST/DEEP toggle + BG
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = { onRunAudit(linkText, selectedVideoUri, selectedType, selectedParty, selectedDepth) },
                        enabled = canRunAudit,
                        modifier = Modifier.weight(1f).height(50.dp),
                        shape = TacticalPillShape,
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CreamAccent,
                            contentColor = CreamTextDark,
                            disabledContainerColor = MutedButtonBg,
                            disabledContentColor = TextSecondary
                        )
                    ) {
                        Icon(Icons.Outlined.TrackChanges, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("RUN AUDIT", fontFamily = CyberMono, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                    }

                    // One-tap FAST / DEEP model switch
                    val isDeep = selectedDepth == AuditDepth.DEEP
                    OutlinedButton(
                        onClick = { selectedDepth = if (isDeep) AuditDepth.FAST else AuditDepth.DEEP },
                        modifier = Modifier.height(50.dp),
                        shape = TacticalPillShape,
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        border = BorderStroke(1.dp, if (isDeep) AmberText else EmeraldText.copy(alpha = 0.7f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (isDeep) AmberBg else MidnightBg,
                            contentColor = if (isDeep) AmberText else EmeraldText
                        )
                    ) {
                        Icon(
                            imageVector = if (isDeep) Icons.Outlined.Psychology else Icons.Outlined.Bolt,
                            contentDescription = "Toggle Fast or Deep Audit",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = selectedDepth.label.uppercase(),
                            fontFamily = CyberMono,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                    }

                    OutlinedButton(
                        onClick = { onQueueBackgroundAudit(linkText, selectedType, selectedParty, selectedDepth) },
                        enabled = linkText.isNotBlank(),
                        modifier = Modifier.height(50.dp),
                        shape = TacticalPillShape,
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        border = BorderStroke(1.dp, CardBorder),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                    ) {
                        Icon(Icons.Outlined.NotificationsActive, contentDescription = "Background Audit", modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("BG", fontFamily = CyberMono, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1)
                    }
                }

                Text(
                    text = "▸ ${selectedDepth.label.uppercase()}: ${selectedDepth.hint}",
                    fontFamily = CyberMono,
                    fontSize = 10.sp,
                    color = if (selectedDepth == AuditDepth.DEEP) AmberText else TextSecondary
                )
            }
        }

        // 3. Recent Dossiers
        Text(
            text = "RECENT DOSSIERS (${audits.size})",
            fontFamily = CyberMono,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            color = TextSecondary
        )

        audits.take(6).forEach { report ->
            CyberAuditDossierCard(report = report, onClick = { onAuditClick(report.id) })
        }

        // 4. Quick Offline Statute Reference
        Text(
            text = "OFFLINE RIGHTS BRIEFING",
            fontFamily = CyberMono,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            color = TextSecondary
        )
        RightsAccordionCard(
            icon = Icons.Outlined.Search,
            title = "GOWISELY: 8 Stop & Search Steps",
            subtitle = "PACE 1984 Code A (England & Wales)",
            details = "Before searching, an officer must state: Grounds, Object, Warrant card (if plain-clothed), Identity, Station, Entitlement to search record copy, Legal power used, and that You are detained."
        )
        RightsAccordionCard(
            icon = Icons.Outlined.Badge,
            title = "Identification Duty (UK Split)",
            subtitle = "England & Wales vs. Scotland",
            details = "England & Wales: No general duty to give name/address unless driving (RTA s.163/164), arrested (PACE s.24), or reasonably suspected of anti-social behaviour (Police Reform Act 2002 s.50). Scotland: Police can require name/address/DOB from suspects or witnesses under s.13 Criminal Procedure (Scotland) Act 1995."
        )
        RightsAccordionCard(
            icon = Icons.Outlined.PhotoCamera,
            title = "Filming Police in Public",
            subtitle = "NPCC Guidance • No Permit Needed",
            details = "The public may film officers in public places without a permit. Officers cannot seize devices or delete footage without a lawful power/arrest, provided filming does not physically obstruct their duty (Police Act 1996 s.89)."
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CyberAuditDossierCard(
    report: AuditReport,
    onClick: () -> Unit,
    showLiveBadge: Boolean = false
) {
    val (badgeBg, badgeFg) = when {
        report.complianceScore >= 80 -> EmeraldBg to EmeraldText
        report.complianceScore >= 50 -> AmberBg to AmberText
        else -> RoseBg to RoseText
    }

    val officerLabel = if (report.officerViolationCount == 1) "1 OFFICER FLAG" else "${report.officerViolationCount} OFFICER FLAGS"
    val staffLabel = if (report.staffViolationCount == 1) "1 STAFF FLAG" else "${report.staffViolationCount} STAFF FLAGS"
    val mythLabel = if (report.publicMythCount == 1) "1 MYTH" else "${report.publicMythCount} MYTHS"

    Surface(
        shape = TacticalCardShape,
        color = CardSurface,
        border = BorderStroke(1.dp, CardBorder),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = report.title,
                    fontFamily = CyberMono,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    lineHeight = 19.sp
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(report.date, fontFamily = CyberMono, fontSize = 11.sp, color = TextSecondary, softWrap = false)

                    if (report.staffViolationCount > 0 && report.officerViolationCount == 0) {
                        Text("• $staffLabel", fontFamily = CyberMono, fontSize = 11.sp, color = RoseText, softWrap = false)
                    } else {
                        Text(
                            text = "• $officerLabel",
                            fontFamily = CyberMono,
                            fontSize = 11.sp,
                            color = if (report.officerViolationCount == 0) EmeraldText else RoseText,
                            softWrap = false
                        )
                        if (report.staffViolationCount > 0) {
                            Text("• $staffLabel", fontFamily = CyberMono, fontSize = 11.sp, color = RoseText, softWrap = false)
                        }
                    }

                    if (report.publicMythCount > 0) {
                        Text("• $mythLabel", fontFamily = CyberMono, fontSize = 11.sp, color = AmberText, softWrap = false)
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Surface(shape = TacticalPillShape, color = MidnightBg, border = BorderStroke(1.dp, CardBorder)) {
                        Text(
                            text = report.jurisdiction.displayName.uppercase(),
                            fontFamily = CyberMono,
                            fontSize = 9.sp,
                            color = CreamAccent,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                    if (showLiveBadge) {
                        Surface(shape = TacticalPillShape, color = EmeraldBg, border = BorderStroke(1.dp, EmeraldText.copy(alpha = 0.6f))) {
                            Text(
                                text = "LIVE TRANSCRIPT",
                                fontFamily = CyberMono,
                                fontSize = 9.sp,
                                color = EmeraldText,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (report.isExample) {
                        Surface(shape = TacticalPillShape, color = MidnightBg, border = BorderStroke(1.dp, CardBorder)) {
                            Text(
                                text = "SIMULATION",
                                fontFamily = CyberMono,
                                fontSize = 9.sp,
                                color = TextSecondary,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            Surface(
                shape = TacticalPillShape,
                color = badgeBg,
                border = BorderStroke(1.dp, badgeFg.copy(alpha = 0.6f))
            ) {
                Text(
                    text = "${report.complianceScore}%",
                    fontFamily = CyberMono,
                    color = badgeFg,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun RightsAccordionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    details: String
) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        shape = TacticalCardShape,
        color = CardSurface,
        border = BorderStroke(1.dp, CardBorder),
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(36.dp).background(MidnightBg, TacticalPillShape).border(1.dp, CardBorder, TacticalPillShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = CreamAccent, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, fontFamily = CyberMono, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text(subtitle, fontSize = 12.sp, color = TextSecondary)
                }
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = CreamAccent
                )
            }
            AnimatedVisibility(visible = expanded) {
                Text(
                    text = details,
                    fontSize = 13.sp,
                    color = TextSecondary,
                    lineHeight = 19.sp,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }
    }
}
