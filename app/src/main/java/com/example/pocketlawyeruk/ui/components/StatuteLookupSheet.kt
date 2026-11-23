package com.example.pocketlawyeruk.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pocketlawyeruk.model.BookmarkFolder
import com.example.pocketlawyeruk.model.StatuteLookupResult
import com.example.pocketlawyeruk.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatuteLookupSheet(
    citationQuery: String,
    isLoading: Boolean,
    result: StatuteLookupResult?,
    errorMessage: String?,
    onBookmark: (title: String, content: String, citation: String, folder: BookmarkFolder) -> Unit,
    onDismiss: () -> Unit
) {
    val uriHandler = LocalUriHandler.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = CardSurface,
        contentColor = TextPrimary,
        dragHandle = { BottomSheetDefaults.DragHandle(color = CreamAccent) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Gavel, contentDescription = null, tint = CreamAccent, modifier = Modifier.size(20.dp))
                Text(
                    text = "STATUTE INTEL // ${citationQuery.uppercase()}",
                    fontFamily = CyberMono,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = CreamAccent
                )
            }

            HorizontalDivider(color = CardBorder)

            when {
                isLoading -> {
                    Row(
                        modifier = Modifier.padding(vertical = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(color = CreamAccent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        Text("QUERYING UK LEGISLATION DATABASE...", fontFamily = CyberMono, fontSize = 12.sp, color = TextSecondary)
                    }
                }
                errorMessage != null -> {
                    Text(errorMessage, color = RoseText, fontSize = 13.sp)
                }
                result != null -> {
                    Text(
                        text = result.actTitle,
                        fontFamily = CyberMono,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Surface(
                        shape = TacticalPillShape,
                        color = MidnightBg,
                        border = BorderStroke(1.dp, EmeraldText.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = "JURISDICTION: ${result.jurisdiction.uppercase()}",
                            fontFamily = CyberMono,
                            fontSize = 11.sp,
                            color = EmeraldText,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }

                    Text(
                        text = result.plainEnglishSummary,
                        fontSize = 14.sp,
                        color = TextPrimary,
                        lineHeight = 21.sp
                    )

                    if (result.keyExceptions.isNotEmpty()) {
                        Text(
                            text = "KEY EXCEPTIONS & LIMITS",
                            fontFamily = CyberMono,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = AmberText
                        )
                        result.keyExceptions.forEach { ex ->
                            Text("▸ $ex", fontSize = 13.sp, color = TextSecondary, lineHeight = 19.sp)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { runCatching { uriHandler.openUri(result.legislationUrl) } },
                            shape = TacticalPillShape,
                            colors = ButtonDefaults.buttonColors(containerColor = CreamAccent, contentColor = CreamTextDark),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("LEGISLATION.GOV", fontFamily = CyberMono, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                onBookmark(
                                    result.actTitle,
                                    result.plainEnglishSummary,
                                    result.citation,
                                    BookmarkFolder.POLICE_AND_PUBLIC
                                )
                                onDismiss()
                            },
                            shape = TacticalPillShape,
                            border = BorderStroke(1.dp, EmeraldText),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = EmeraldText)
                        ) {
                            Icon(Icons.Outlined.BookmarkAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("SAVE", fontFamily = CyberMono, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
