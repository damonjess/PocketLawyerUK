package com.example.pocketlawyeruk.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.pocketlawyeruk.model.DocumentScanResult
import com.example.pocketlawyeruk.model.UkJurisdiction
import com.example.pocketlawyeruk.ui.theme.*
import com.example.pocketlawyeruk.util.PdfLetterExporter
import java.io.File
import java.util.UUID

@Composable
fun ScanAndLettersScreen(
    activeScan: DocumentScanResult?,
    isScanning: Boolean,
    onAnalyzeDocument: (uri: Uri?, pastedText: String?, note: String) -> Unit,
    onCitationClick: (String, UkJurisdiction) -> Unit,
    onClearActiveScan: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var selectedDocUri by remember { mutableStateOf<Uri?>(null) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var pastedSmallPrint by remember { mutableStateOf("") }
    var contextNote by remember { mutableStateOf("") }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && pendingCameraUri != null) selectedDocUri = pendingCameraUri
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) selectedDocUri = uri
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MidnightBg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("MODULE // DOC-SCANNER & APPEAL ENGINE", fontFamily = CyberMono, fontSize = 11.sp, color = CreamAccent, letterSpacing = 1.2.sp)
            Text("SCAN FINES & SMALL PRINT", fontFamily = CyberMono, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text(
                text = "Check NIPs, parking PCNs, landlord notices, employment letters, or pasted T&Cs for UK statutory breaches and export an A4 PDF reply.",
                fontSize = 12.sp,
                color = TextSecondary,
                lineHeight = 17.sp
            )
        }

        // Input Panel
        Surface(shape = TacticalCardShape, color = CardSurface, border = BorderStroke(1.dp, CreamAccent.copy(alpha = 0.4f))) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val dir = File(context.cacheDir, "scans").apply { mkdirs() }
                            val file = File(dir, "scan_${UUID.randomUUID()}.jpg")
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                            pendingCameraUri = uri
                            cameraLauncher.launch(uri)
                        },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = TacticalPillShape,
                        border = BorderStroke(1.dp, CreamAccent),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CreamAccent)
                    ) {
                        Icon(Icons.Outlined.PhotoCamera, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("PHOTO", fontFamily = CyberMono, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = { filePicker.launch(arrayOf("image/*", "application/pdf")) },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = TacticalPillShape,
                        border = BorderStroke(1.dp, CardBorder),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                    ) {
                        Icon(Icons.Outlined.Description, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("PDF / IMG", fontFamily = CyberMono, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                if (selectedDocUri != null) {
                    Surface(shape = TacticalPillShape, color = EmeraldBg, border = BorderStroke(1.dp, EmeraldText)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("DOCUMENT ATTACHED [✓]", fontFamily = CyberMono, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = EmeraldText)
                            Text("CLEAR", fontFamily = CyberMono, fontSize = 11.sp, color = RoseText, modifier = Modifier.clickable { selectedDocUri = null })
                        }
                    }
                }

                OutlinedTextField(
                    value = pastedSmallPrint,
                    onValueChange = { pastedSmallPrint = it },
                    label = { Text("Or paste contract / T&Cs / letter text", fontSize = 12.sp, color = TextSecondary) },
                    minLines = 3,
                    maxLines = 6,
                    shape = TacticalPillShape,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CreamAccent,
                        unfocusedBorderColor = CardBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedContainerColor = MidnightBg,
                        unfocusedContainerColor = MidnightBg
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = contextNote,
                    onValueChange = { contextNote = it },
                    label = { Text("Extra details (e.g. received 19 days late, faulty after 20 days)", fontSize = 12.sp, color = TextSecondary) },
                    singleLine = true,
                    shape = TacticalPillShape,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CreamAccent,
                        unfocusedBorderColor = CardBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedContainerColor = MidnightBg,
                        unfocusedContainerColor = MidnightBg
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = { onAnalyzeDocument(selectedDocUri, pastedSmallPrint.takeIf { it.isNotBlank() }, contextNote) },
                    enabled = !isScanning && (selectedDocUri != null || pastedSmallPrint.isNotBlank()),
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = TacticalPillShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CreamAccent,
                        contentColor = CreamTextDark,
                        disabledContainerColor = MutedButtonBg,
                        disabledContentColor = TextSecondary
                    )
                ) {
                    if (isScanning) {
                        CircularProgressIndicator(color = CreamTextDark, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("SCANNING CLAUSES...", fontFamily = CyberMono, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    } else {
                        Icon(Icons.Outlined.DocumentScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("AUDIT DOCUMENT & DRAFT LETTER", fontFamily = CyberMono, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }

        // Active Scan Result + Editable Appeal Letter + PDF Exporter
        if (activeScan != null) {
            var editableLetter by remember(activeScan.id) { mutableStateOf(activeScan.appealOrReplyLetter) }

            Surface(shape = TacticalCardShape, color = CardSurface, border = BorderStroke(1.dp, EmeraldText.copy(alpha = 0.5f))) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(activeScan.documentType.uppercase(), fontFamily = CyberMono, fontSize = 10.sp, color = CreamAccent)
                            Text(activeScan.title, fontFamily = CyberMono, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        }
                        IconButton(onClick = onClearActiveScan) {
                            Icon(Icons.Outlined.Close, contentDescription = "Close", tint = TextSecondary)
                        }
                    }

                    Text(activeScan.summary, fontSize = 13.sp, color = TextPrimary, lineHeight = 19.sp)

                    if (activeScan.keyDeadlines.isNotEmpty()) {
                        Text("STATUTORY DEADLINES", fontFamily = CyberMono, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AmberText)
                        activeScan.keyDeadlines.forEach { d ->
                            Text("⏱ $d", fontSize = 13.sp, color = TextPrimary)
                        }
                    }

                    if (activeScan.redFlags.isNotEmpty()) {
                        Text("RED FLAGS & UNFAIR TERMS", fontFamily = CyberMono, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = RoseText)
                        activeScan.redFlags.forEach { flag ->
                            val flagColor = when (flag.severity.uppercase()) {
                                "HIGH" -> RoseText
                                "MEDIUM" -> AmberText
                                else -> CreamAccent
                            }
                            Surface(
                                shape = TacticalPillShape,
                                color = MidnightBg,
                                border = BorderStroke(1.dp, flagColor.copy(alpha = 0.45f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("[${flag.severity.uppercase()}] ${flag.clause}", fontFamily = CyberMono, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = flagColor)
                                    Text(flag.issue, fontSize = 12.sp, color = TextPrimary, lineHeight = 18.sp)
                                    if (flag.citation.isNotBlank()) {
                                        Text(
                                            text = "▸ TAP STATUTE: ${flag.citation}",
                                            fontFamily = CyberMono,
                                            fontSize = 11.sp,
                                            color = CreamAccent,
                                            modifier = Modifier.clickable { onCitationClick(flag.citation, activeScan.jurisdiction) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Draft Appeal / Reply Letter
                    Text("DRAFT UK REPLY / APPEAL LETTER", fontFamily = CyberMono, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = EmeraldText)
                    OutlinedTextField(
                        value = editableLetter,
                        onValueChange = { editableLetter = it },
                        minLines = 6,
                        maxLines = 14,
                        shape = TacticalPillShape,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = EmeraldText,
                            unfocusedBorderColor = CardBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedContainerColor = MidnightBg,
                            unfocusedContainerColor = MidnightBg
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                PdfLetterExporter.exportAndSharePdf(context, activeScan.title, editableLetter)
                            },
                            modifier = Modifier.weight(1f).height(46.dp),
                            shape = TacticalPillShape,
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldText, contentColor = CreamTextDark)
                        ) {
                            Icon(Icons.Outlined.PictureAsPdf, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("EXPORT A4 PDF", fontFamily = CyberMono, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(editableLetter))
                                Toast.makeText(context, "Letter copied to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.height(46.dp),
                            shape = TacticalPillShape,
                            border = BorderStroke(1.dp, CreamAccent),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = CreamAccent)
                        ) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("COPY", fontFamily = CyberMono, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}
