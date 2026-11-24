package com.example.pocketlawyeruk.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pocketlawyeruk.model.*
import com.example.pocketlawyeruk.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val caseDateFormat = SimpleDateFormat("d MMM yyyy", Locale.UK)

private fun caseDate(timestamp: Long): String = caseDateFormat.format(Date(timestamp))

private fun CaseItemType.label(): String = when (this) {
    CaseItemType.AUDIT -> "AUDITS"
    CaseItemType.SCAN -> "DOCS"
    CaseItemType.BOOKMARK -> "BOOKMARKS"
}

@Composable
fun CasesListContent(
    cases: List<CaseRecord>,
    caseItems: List<CaseItem>,
    searchQuery: String,
    onOpenCase: (String) -> Unit,
    onCreateCase: (String) -> Unit
) {
    var showNewCase by remember { mutableStateOf(false) }

    val filtered = remember(cases, searchQuery) {
        if (searchQuery.isBlank()) cases
        else cases.filter { it.title.contains(searchQuery, true) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "CASES (${cases.size})",
                fontFamily = CyberMono,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                color = TextSecondary
            )
            TextButton(onClick = { showNewCase = true }) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("NEW CASE", fontFamily = CyberMono, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (filtered.isEmpty()) {
            Surface(
                shape = TacticalCardShape,
                color = CardSurface,
                border = BorderStroke(1.dp, CardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (searchQuery.isBlank()) "No cases yet." else "No matching cases.",
                        fontFamily = CyberMono,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = if (searchQuery.isBlank())
                            "Create one to group an audit, scans, bookmarks or a live transcript from the same incident."
                        else "Try a different case title.",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        lineHeight = 18.sp
                    )
                }
            }
        } else {
            filtered.forEach { case ->
                val links = caseItems.filter { it.caseId == case.id }
                CaseCard(
                    case = case,
                    auditCount = links.count { it.type == CaseItemType.AUDIT },
                    scanCount = links.count { it.type == CaseItemType.SCAN },
                    bookmarkCount = links.count { it.type == CaseItemType.BOOKMARK },
                    onClick = { onOpenCase(case.id) }
                )
            }
        }
    }

    if (showNewCase) {
        CaseTitleDialog(
            title = "NEW CASE",
            initialTitle = "Incident — ${caseDate(System.currentTimeMillis())}",
            confirmLabel = "CREATE",
            onDismiss = { showNewCase = false },
            onConfirm = { title ->
                showNewCase = false
                onCreateCase(title)
            }
        )
    }
}

@Composable
private fun CaseCard(
    case: CaseRecord,
    auditCount: Int,
    scanCount: Int,
    bookmarkCount: Int,
    onClick: () -> Unit
) {
    Surface(
        shape = TacticalCardShape,
        color = CardSurface,
        border = BorderStroke(1.dp, CardBorder),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = case.title,
                    fontFamily = CyberMono,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(10.dp))
                Icon(Icons.Outlined.FolderOpen, contentDescription = null, tint = CreamAccent, modifier = Modifier.size(20.dp))
            }
            Text(
                text = "CREATED ${caseDate(case.createdAt).uppercase()}",
                fontFamily = CyberMono,
                fontSize = 10.sp,
                color = TextSecondary
            )
            Text(
                text = "$auditCount ${if (auditCount == 1) "AUDIT" else "AUDITS"} · " +
                    "$scanCount ${if (scanCount == 1) "DOC" else "DOCS"} · " +
                    "$bookmarkCount SAVED",
                fontFamily = CyberMono,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = EmeraldText
            )
        }
    }
}

@Composable
fun CaseDetailContent(
    case: CaseRecord,
    caseItems: List<CaseItem>,
    audits: List<AuditReport>,
    scans: List<DocumentScanResult>,
    bookmarks: List<SavedBookmark>,
    onBack: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onAddItems: (List<CaseItem>) -> Unit,
    onRemoveItem: (CaseItemType, String) -> Unit,
    onOpenAudit: (String) -> Unit,
    onOpenScan: (DocumentScanResult) -> Unit
) {
    BackHandler(onBack = onBack)

    var menuExpanded by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showAddItems by remember { mutableStateOf(false) }

    val links = remember(caseItems, case.id) {
        caseItems.filter { it.caseId == case.id }.sortedByDescending { it.addedAt }
    }
    val linkedAudits = remember(links, audits) {
        links.filter { it.type == CaseItemType.AUDIT }
            .mapNotNull { link -> audits.find { it.id == link.itemId && !it.isExample } }
    }
    val linkedScans = remember(links, scans) {
        links.filter { it.type == CaseItemType.SCAN }
            .mapNotNull { link -> scans.find { it.id == link.itemId } }
    }
    val linkedBookmarks = remember(links, bookmarks) {
        links.filter { it.type == CaseItemType.BOOKMARK }
            .mapNotNull { link -> bookmarks.find { it.id == link.itemId } }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MidnightBg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "Back to cases", tint = CreamAccent)
            }
            Spacer(Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("CASE FILE", fontFamily = CyberMono, fontSize = 10.sp, color = CreamAccent, letterSpacing = 1.2.sp)
                Text(
                    text = case.title,
                    fontFamily = CyberMono,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "Case options", tint = TextSecondary)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    containerColor = CardSurface
                ) {
                    DropdownMenuItem(
                        text = { Text("RENAME", fontFamily = CyberMono, fontSize = 12.sp, color = TextPrimary) },
                        onClick = {
                            menuExpanded = false
                            showRename = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("DELETE CASE", fontFamily = CyberMono, fontSize = 12.sp, color = RoseText) },
                        onClick = {
                            menuExpanded = false
                            showDelete = true
                        }
                    )
                }
            }
        }

        Text(
            text = "CREATED ${caseDate(case.createdAt).uppercase()} · " +
                "${links.size} ${if (links.size == 1) "ITEM" else "ITEMS"}",
            fontFamily = CyberMono,
            fontSize = 10.sp,
            color = TextSecondary
        )

        OutlinedButton(
            onClick = { showAddItems = true },
            modifier = Modifier.fillMaxWidth().height(46.dp),
            shape = TacticalPillShape,
            border = BorderStroke(1.dp, CreamAccent.copy(alpha = 0.55f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = CreamAccent)
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
            Text("ADD ITEMS", fontFamily = CyberMono, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }

        if (linkedAudits.isEmpty() && linkedScans.isEmpty() && linkedBookmarks.isEmpty()) {
            Surface(
                shape = TacticalCardShape,
                color = CardSurface,
                border = BorderStroke(1.dp, CardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "This case is empty. Add an audit, scan or bookmark from the items already saved in your Vault.",
                    fontSize = 13.sp,
                    color = TextSecondary,
                    lineHeight = 19.sp,
                    modifier = Modifier.padding(18.dp)
                )
            }
        }

        if (linkedAudits.isNotEmpty()) {
            CaseSectionHeader("AUDITS", linkedAudits.size)
            linkedAudits.forEach { audit ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.weight(1f)) {
                        CyberAuditDossierCard(
                            report = audit,
                            onClick = { onOpenAudit(audit.id) },
                            showLiveBadge = audit.sourceUrl == null && audit.videoUriString == null
                        )
                    }
                    IconButton(onClick = { onRemoveItem(CaseItemType.AUDIT, audit.id) }) {
                        Icon(Icons.Outlined.Close, contentDescription = "Remove audit from case", tint = RoseText, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        if (linkedScans.isNotEmpty()) {
            CaseSectionHeader("DOCUMENTS", linkedScans.size)
            linkedScans.forEach { scan ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = TacticalCardShape,
                        color = CardSurface,
                        border = BorderStroke(1.dp, CardBorder),
                        modifier = Modifier.weight(1f).clickable { onOpenScan(scan) }
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Outlined.Description, contentDescription = null, tint = CreamAccent)
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    text = scan.title,
                                    fontFamily = CyberMono,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${scan.documentType} · ${scan.redFlags.size} flags",
                                    fontFamily = CyberMono,
                                    fontSize = 10.sp,
                                    color = TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    IconButton(onClick = { onRemoveItem(CaseItemType.SCAN, scan.id) }) {
                        Icon(Icons.Outlined.Close, contentDescription = "Remove document from case", tint = RoseText, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        if (linkedBookmarks.isNotEmpty()) {
            CaseSectionHeader("BOOKMARKS", linkedBookmarks.size)
            linkedBookmarks.forEach { bookmark ->
                Row(verticalAlignment = Alignment.Top) {
                    Surface(
                        shape = TacticalCardShape,
                        color = CardSurface,
                        border = BorderStroke(1.dp, CardBorder),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = bookmark.folder.label.uppercase(),
                                fontFamily = CyberMono,
                                fontSize = 9.sp,
                                color = EmeraldText
                            )
                            Text(
                                text = bookmark.title,
                                fontFamily = CyberMono,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = bookmark.content,
                                fontSize = 12.sp,
                                color = TextSecondary,
                                lineHeight = 18.sp
                            )
                            if (bookmark.citation.isNotBlank()) {
                                Text(
                                    text = "STATUTE: ${bookmark.citation}",
                                    fontFamily = CyberMono,
                                    fontSize = 10.sp,
                                    color = CreamAccent
                                )
                            }
                        }
                    }
                    IconButton(onClick = { onRemoveItem(CaseItemType.BOOKMARK, bookmark.id) }) {
                        Icon(Icons.Outlined.Close, contentDescription = "Remove bookmark from case", tint = RoseText, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }

    if (showRename) {
        CaseTitleDialog(
            title = "RENAME CASE",
            initialTitle = case.title,
            confirmLabel = "SAVE",
            onDismiss = { showRename = false },
            onConfirm = { title ->
                showRename = false
                onRename(title)
            }
        )
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            containerColor = CardSurface,
            shape = TacticalCardShape,
            title = {
                Text("DELETE CASE", fontFamily = CyberMono, fontWeight = FontWeight.Bold, color = CreamAccent)
            },
            text = {
                Text(
                    "Delete this case? The audits, scans and bookmarks stay in your Vault.",
                    fontSize = 13.sp,
                    color = TextPrimary,
                    lineHeight = 19.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    onDelete()
                }) {
                    Text("DELETE", fontFamily = CyberMono, color = RoseText, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) {
                    Text("CANCEL", fontFamily = CyberMono, color = TextSecondary)
                }
            }
        )
    }

    if (showAddItems) {
        AddItemsSheet(
            caseId = case.id,
            audits = audits,
            scans = scans,
            bookmarks = bookmarks,
            existingItems = caseItems.filter { it.caseId == case.id },
            onDismiss = { showAddItems = false },
            onAdd = { items ->
                showAddItems = false
                onAddItems(items)
            }
        )
    }
}

@Composable
private fun CaseSectionHeader(title: String, count: Int) {
    Text(
        text = "$title ($count)",
        fontFamily = CyberMono,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.1.sp,
        color = CreamAccent,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddItemsSheet(
    caseId: String,
    audits: List<AuditReport>,
    scans: List<DocumentScanResult>,
    bookmarks: List<SavedBookmark>,
    existingItems: List<CaseItem>,
    onDismiss: () -> Unit,
    onAdd: (List<CaseItem>) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedType by remember { mutableStateOf(CaseItemType.AUDIT) }
    var selected by remember { mutableStateOf<Map<String, CaseItem>>(emptyMap()) }

    val existingAuditIds = remember(existingItems) {
        existingItems.filter { it.type == CaseItemType.AUDIT }.map { it.itemId }.toSet()
    }
    val existingScanIds = remember(existingItems) {
        existingItems.filter { it.type == CaseItemType.SCAN }.map { it.itemId }.toSet()
    }
    val existingBookmarkIds = remember(existingItems) {
        existingItems.filter { it.type == CaseItemType.BOOKMARK }.map { it.itemId }.toSet()
    }

    val availableAudits = remember(audits, existingAuditIds) {
        audits.filter { !it.isExample && it.id !in existingAuditIds }
    }
    val availableScans = remember(scans, existingScanIds) {
        scans.filter { it.id !in existingScanIds }
    }
    val availableBookmarks = remember(bookmarks, existingBookmarkIds) {
        bookmarks.filter { it.id !in existingBookmarkIds }
    }

    fun key(type: CaseItemType, id: String) = "${type.name}:$id"

    fun toggle(type: CaseItemType, id: String) {
        val itemKey = key(type, id)
        selected = if (selected.containsKey(itemKey)) {
            selected - itemKey
        } else {
            selected + (itemKey to CaseItem(caseId = caseId, type = type, itemId = id))
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CardSurface,
        contentColor = TextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "ADD TO CASE",
                fontFamily = CyberMono,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                text = "Choose saved items from your Vault. Nothing is copied or moved.",
                fontSize = 12.sp,
                color = TextSecondary,
                lineHeight = 18.sp
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CaseItemType.entries.forEach { type ->
                    val isSelected = type == selectedType
                    Surface(
                        shape = TacticalPillShape,
                        color = if (isSelected) CreamAccent else MidnightBg,
                        border = BorderStroke(1.dp, if (isSelected) CreamAccent else CardBorder),
                        modifier = Modifier.weight(1f).clickable { selectedType = type }
                    ) {
                        Box(
                            modifier = Modifier.padding(vertical = 9.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = type.label(),
                                fontFamily = CyberMono,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) CreamTextDark else TextPrimary
                            )
                        }
                    }
                }
            }

            val itemCount = when (selectedType) {
                CaseItemType.AUDIT -> availableAudits.size
                CaseItemType.SCAN -> availableScans.size
                CaseItemType.BOOKMARK -> availableBookmarks.size
            }

            if (itemCount == 0) {
                Text(
                    text = "Nothing available to add yet.",
                    fontSize = 13.sp,
                    color = TextSecondary,
                    modifier = Modifier.padding(vertical = 18.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 350.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    when (selectedType) {
                        CaseItemType.AUDIT -> items(availableAudits, key = { "A:${it.id}" }) { audit ->
                            AddItemRow(
                                title = audit.title,
                                subtitle = if (audit.sourceUrl == null && audit.videoUriString == null)
                                    "LIVE TRANSCRIPT · ${audit.date}"
                                else "${audit.date} · ${audit.jurisdiction.displayName}",
                                checked = selected.containsKey(key(CaseItemType.AUDIT, audit.id)),
                                onToggle = { toggle(CaseItemType.AUDIT, audit.id) }
                            )
                        }
                        CaseItemType.SCAN -> items(availableScans, key = { "S:${it.id}" }) { scan ->
                            AddItemRow(
                                title = scan.title,
                                subtitle = "${scan.documentType} · ${scan.dateScanned}",
                                checked = selected.containsKey(key(CaseItemType.SCAN, scan.id)),
                                onToggle = { toggle(CaseItemType.SCAN, scan.id) }
                            )
                        }
                        CaseItemType.BOOKMARK -> items(availableBookmarks, key = { "B:${it.id}" }) { bookmark ->
                            AddItemRow(
                                title = bookmark.title,
                                subtitle = bookmark.folder.label,
                                checked = selected.containsKey(key(CaseItemType.BOOKMARK, bookmark.id)),
                                onToggle = { toggle(CaseItemType.BOOKMARK, bookmark.id) }
                            )
                        }
                    }
                }
            }

            Button(
                onClick = { onAdd(selected.values.toList()) },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = TacticalPillShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = CreamAccent,
                    contentColor = CreamTextDark,
                    disabledContainerColor = MutedButtonBg,
                    disabledContentColor = TextSecondary
                )
            ) {
                Text(
                    text = if (selected.isEmpty()) "ADD ITEMS" else "ADD ${selected.size} ${if (selected.size == 1) "ITEM" else "ITEMS"}",
                    fontFamily = CyberMono,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun AddItemRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        shape = TacticalCardShape,
        color = MidnightBg,
        border = BorderStroke(1.dp, if (checked) CreamAccent else CardBorder),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(
                    checkedColor = CreamAccent,
                    uncheckedColor = TextSecondary,
                    checkmarkColor = CreamTextDark
                )
            )
            Spacer(Modifier.width(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    fontFamily = CyberMono,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    fontFamily = CyberMono,
                    fontSize = 10.sp,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun CaseTitleDialog(
    title: String,
    initialTitle: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember(initialTitle) { mutableStateOf(initialTitle) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardSurface,
        shape = TacticalCardShape,
        title = {
            Text(title, fontFamily = CyberMono, fontWeight = FontWeight.Bold, color = CreamAccent)
        },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text("Case title", color = TextSecondary) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CreamAccent,
                    unfocusedBorderColor = CardBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedContainerColor = MidnightBg,
                    unfocusedContainerColor = MidnightBg,
                    cursorColor = CreamAccent
                ),
                shape = TacticalPillShape,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value.trim()) },
                enabled = value.isNotBlank()
            ) {
                Text(confirmLabel, fontFamily = CyberMono, color = CreamAccent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", fontFamily = CyberMono, color = TextSecondary)
            }
        }
    )
}
