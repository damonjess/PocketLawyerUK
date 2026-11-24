package com.example.pocketlawyeruk.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pocketlawyeruk.model.AuditReport
import com.example.pocketlawyeruk.model.BookmarkFolder
import com.example.pocketlawyeruk.model.DocumentScanResult
import com.example.pocketlawyeruk.model.SavedBookmark
import com.example.pocketlawyeruk.ui.theme.*

private enum class VaultTab(val label: String) {
    AUDITS("AUDITS"),
    SCANS("DOCS"),
    BOOKMARKS("MYTHBUSTER")
}

@Composable
fun VaultLibraryScreen(
    searchQuery: String,
    audits: List<AuditReport>,
    scans: List<DocumentScanResult>,
    bookmarks: List<SavedBookmark>,
    onSearchChange: (String) -> Unit,
    onOpenAudit: (String) -> Unit,
    onDeleteAudit: (String) -> Unit,
    onOpenScan: (DocumentScanResult) -> Unit,
    onDeleteScan: (String) -> Unit,
    onDeleteBookmark: (String) -> Unit
) {
    var activeTab by remember { mutableStateOf(VaultTab.AUDITS) }
    var selectedFolder by remember { mutableStateOf<BookmarkFolder?>(null) }

    val filteredBookmarks = remember(bookmarks, selectedFolder, searchQuery) {
        bookmarks.filter { bm ->
            val folderOk = selectedFolder == null || bm.folder == selectedFolder
            val queryOk = searchQuery.isBlank() ||
                bm.title.contains(searchQuery, true) ||
                bm.content.contains(searchQuery, true) ||
                bm.citation.contains(searchQuery, true)
            folderOk && queryOk
        }
    }

    val filteredAudits = remember(audits, searchQuery) {
        if (searchQuery.isBlank()) audits
        else audits.filter { r ->
            r.title.contains(searchQuery, true) ||
                r.takeaway.contains(searchQuery, true) ||
                r.fullTranscript.any { it.text.contains(searchQuery, true) } ||
                r.timeline.any { it.citation.contains(searchQuery, true) || it.quote.contains(searchQuery, true) }
        }
    }

    val filteredScans = remember(scans, searchQuery) {
        if (searchQuery.isBlank()) scans
        else scans.filter { s ->
            s.title.contains(searchQuery, true) ||
                s.documentType.contains(searchQuery, true) ||
                s.summary.contains(searchQuery, true) ||
                s.appealOrReplyLetter.contains(searchQuery, true) ||
                s.redFlags.any {
                    it.clause.contains(searchQuery, true) ||
                        it.issue.contains(searchQuery, true) ||
                        it.citation.contains(searchQuery, true)
                }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MidnightBg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("ENCRYPTED OFFLINE ARCHIVE // ROOM DB", fontFamily = CyberMono, fontSize = 11.sp, color = CreamAccent, letterSpacing = 1.2.sp)
            Text("LEGAL VAULT", fontFamily = CyberMono, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        }

        // Full-Text Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = CreamAccent) },
            placeholder = { Text("Search transcripts, Section 50, NIP, myths...", fontSize = 13.sp, color = TextSecondary) },
            singleLine = true,
            shape = TacticalPillShape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = CreamAccent,
                unfocusedBorderColor = CardBorder,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                focusedContainerColor = CardSurface,
                unfocusedContainerColor = CardSurface
            ),
            modifier = Modifier.fillMaxWidth()
        )

        // Vault Sub-Tabs
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            VaultTab.entries.forEach { tab ->
                val selected = activeTab == tab
                Surface(
                    shape = TacticalPillShape,
                    color = if (selected) CreamAccent else CardSurface,
                    border = BorderStroke(1.dp, if (selected) CreamAccent else CardBorder),
                    modifier = Modifier.weight(1f).clickable { activeTab = tab }
                ) {
                    Box(modifier = Modifier.padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = tab.label,
                            fontFamily = CyberMono,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selected) CreamTextDark else TextPrimary
                        )
                    }
                }
            }
        }

        when (activeTab) {
            VaultTab.AUDITS -> {
                if (filteredAudits.isEmpty()) {
                    Text("No matching video audits in your offline vault.", fontFamily = CyberMono, fontSize = 12.sp, color = TextSecondary)
                } else {
                    filteredAudits.forEach { audit ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(modifier = Modifier.weight(1f)) {
                                CyberAuditDossierCard(report = audit, onClick = { onOpenAudit(audit.id) })
                            }
                            if (!audit.isExample) {
                                IconButton(onClick = { onDeleteAudit(audit.id) }) {
                                    Icon(Icons.Outlined.DeleteOutline, contentDescription = "Delete", tint = RoseText)
                                }
                            }
                        }
                    }
                }
            }

            VaultTab.SCANS -> {
                if (filteredScans.isEmpty()) {
                    Text(
                        text = if (searchQuery.isBlank()) "No scanned fines, notices, or contracts saved yet."
                        else "No matching document scans in your offline vault.",
                        fontFamily = CyberMono,
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                } else {
                    filteredScans.forEach { scan ->
                        Surface(
                            shape = TacticalCardShape,
                            color = CardSurface,
                            border = BorderStroke(1.dp, CardBorder),
                            modifier = Modifier.fillMaxWidth().clickable { onOpenScan(scan) }
                        ) {
                            Row(
                                modifier = Modifier.padding(15.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Icon(Icons.Outlined.Description, contentDescription = null, tint = CreamAccent)
                                    Column {
                                        Text(scan.title, fontFamily = CyberMono, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                        Text("${scan.documentType} • ${scan.dateScanned} • ${scan.redFlags.size} flags", fontFamily = CyberMono, fontSize = 11.sp, color = TextSecondary)
                                    }
                                }
                                IconButton(onClick = { onDeleteScan(scan.id) }) {
                                    Icon(Icons.Outlined.DeleteOutline, contentDescription = "Delete", tint = RoseText)
                                }
                            }
                        }
                    }
                }
            }

            VaultTab.BOOKMARKS -> {
                // Folder Filter Chips
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val allSelected = selectedFolder == null
                    Surface(
                        shape = TacticalPillShape,
                        color = if (allSelected) EmeraldText else CardSurface,
                        border = BorderStroke(1.dp, if (allSelected) EmeraldText else CardBorder),
                        modifier = Modifier.clickable { selectedFolder = null }
                    ) {
                        Text("ALL", fontFamily = CyberMono, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (allSelected) CreamTextDark else TextPrimary, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                    BookmarkFolder.entries.forEach { folder ->
                        val isSel = selectedFolder == folder
                        Surface(
                            shape = TacticalPillShape,
                            color = if (isSel) EmeraldText else CardSurface,
                            border = BorderStroke(1.dp, if (isSel) EmeraldText else CardBorder),
                            modifier = Modifier.clickable { selectedFolder = folder }
                        ) {
                            Text(folder.label.uppercase(), fontFamily = CyberMono, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (isSel) CreamTextDark else TextPrimary, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                        }
                    }
                }

                if (filteredBookmarks.isEmpty()) {
                    Text("Tap the bookmark icon on any flagged video statement, statute lookup, or AI Law Chat answer to build your pocket cheat-sheet.", fontSize = 13.sp, color = TextSecondary, lineHeight = 19.sp)
                } else {
                    filteredBookmarks.forEach { bm ->
                        Surface(shape = TacticalCardShape, color = CardSurface, border = BorderStroke(1.dp, CardBorder), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text(bm.folder.label.uppercase(), fontFamily = CyberMono, fontSize = 10.sp, color = EmeraldText)
                                    Icon(Icons.Outlined.DeleteOutline, contentDescription = "Delete", tint = RoseText, modifier = Modifier.size(18.dp).clickable { onDeleteBookmark(bm.id) })
                                }
                                Text(bm.title, fontFamily = CyberMono, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Text(bm.content, fontSize = 13.sp, color = TextSecondary, lineHeight = 18.sp)
                                if (bm.citation.isNotBlank()) {
                                    Text("STATUTE: ${bm.citation}", fontFamily = CyberMono, fontSize = 11.sp, color = CreamAccent)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
