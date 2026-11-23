package com.example.pocketlawyeruk.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.pocketlawyeruk.model.BookmarkFolder
import com.example.pocketlawyeruk.model.ChatMessage
import com.example.pocketlawyeruk.ui.theme.*
import com.example.pocketlawyeruk.util.VoiceChatController

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LegalChatScreen(
    messages: List<ChatMessage>,
    isLoading: Boolean,
    currentApiKey: String,
    onUpdateApiKey: (String) -> Unit,
    onSendMessage: (String) -> Unit,
    onBookmarkAnswer: (title: String, content: String, folder: BookmarkFolder) -> Unit,
    onClearChat: () -> Unit
) {
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    var isListening by remember { mutableStateOf(false) }
    var autoSpeakReplies by remember { mutableStateOf(false) }
    var showApiKeyDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val voiceController = remember { VoiceChatController(context) }
    DisposableEffect(voiceController) {
        voiceController.onTtsError = { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
        onDispose { voiceController.release() }
    }

    fun triggerVoiceCapture() {
        voiceController.startListening(
            onListeningState = { isListening = it },
            onResult = { spoken -> onSendMessage(spoken) },
            onError = { err -> Toast.makeText(context, err, Toast.LENGTH_SHORT).show() }
        )
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) triggerVoiceCapture()
        else Toast.makeText(context, "Microphone permission required for voice mode.", Toast.LENGTH_SHORT).show()
    }

    // Auto-scroll and optionally read newest AI answer aloud
    LaunchedEffect(messages.size, isLoading) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
            val last = messages.last()
            if (autoSpeakReplies && !last.isUser && !isLoading) {
                voiceController.speak(last.text)
            }
        }
    }

    val quickPrompts = remember {
        listOf(
            "Do I have to give police my name in England vs Scotland?",
            "What are my refund rights for a faulty item after 30 days?",
            "Can a landlord use Section 21 after the Renters' Rights Act?",
            "How many days do police have to send a speeding NIP?",
            "When can private parking companies pursue the registered keeper?"
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(MidnightBg)) {
        // Header with Read-Aloud Toggle + Clear
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("SECURE TERMINAL // UK LAW AI", fontFamily = CyberMono, fontSize = 11.sp, color = CreamAccent, letterSpacing = 1.sp)
                Text("ASK POCKET LAWYER", fontFamily = CyberMono, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { showApiKeyDialog = true }) {
                    Icon(
                        imageVector = Icons.Outlined.Key,
                        contentDescription = "Set Gemini API Key",
                        tint = CreamAccent
                    )
                }
                IconButton(onClick = {
                    autoSpeakReplies = !autoSpeakReplies
                    if (!autoSpeakReplies) voiceController.stopSpeaking()
                    Toast.makeText(context, if (autoSpeakReplies) "Voice read-aloud ON" else "Voice read-aloud OFF", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(
                        imageVector = if (autoSpeakReplies) Icons.Outlined.VolumeUp else Icons.Outlined.VolumeOff,
                        contentDescription = "Toggle Read Aloud",
                        tint = if (autoSpeakReplies) EmeraldText else TextSecondary
                    )
                }
                if (messages.isNotEmpty()) {
                    IconButton(onClick = {
                        voiceController.stopSpeaking()
                        onClearChat()
                    }) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = "Clear Chat", tint = TextSecondary)
                    }
                }
            }
        }
        HorizontalDivider(color = CardBorder)

        if (currentApiKey.isBlank()) {
            Surface(
                shape = TacticalCardShape,
                color = CardSurface,
                border = BorderStroke(1.dp, CreamAccent.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "GEMINI API KEY REQUIRED",
                        fontFamily = CyberMono,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = CreamAccent
                    )
                    Text(
                        "No Gemini API key yet. Create one in Google AI Studio (aistudio.google.com/apikey) or Google Cloud Console → Credentials, restrict it to the Generative Language API, then paste it here.",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        lineHeight = 17.sp
                    )
                    TextButton(onClick = { showApiKeyDialog = true }, contentPadding = PaddingValues(0.dp)) {
                        Text("ADD API KEY", fontFamily = CyberMono, color = CreamAccent, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (showApiKeyDialog) {
            var draftKey by remember(currentApiKey) { mutableStateOf(currentApiKey) }
            AlertDialog(
                onDismissRequest = { showApiKeyDialog = false },
                containerColor = CardSurface,
                titleContentColor = CreamAccent,
                textContentColor = TextPrimary,
                shape = TacticalCardShape,
                title = { Text("GEMINI API KEY", fontFamily = CyberMono, fontWeight = FontWeight.Bold, fontSize = 15.sp) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Paste a Gemini API key from Google AI Studio (aistudio.google.com/apikey) or Google Cloud Console → APIs & Services → Credentials, restricted to the Generative Language API. Both \"AIza...\" and \"AQ....\" key formats are supported.",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                        OutlinedTextField(
                            value = draftKey,
                            onValueChange = { draftKey = it },
                            placeholder = { Text("API key", fontSize = 12.sp) },
                            singleLine = true,
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
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        onUpdateApiKey(draftKey)
                        showApiKeyDialog = false
                        Toast.makeText(context, "Gemini API Key saved", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("SAVE KEY", fontFamily = CyberMono, color = CreamAccent, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showApiKeyDialog = false }) {
                        Text("CANCEL", fontFamily = CyberMono, color = TextSecondary)
                    }
                }
            )
        }

        if (messages.isEmpty()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("LIVE SEARCH-GROUNDED UK LEGAL DATABASE", fontFamily = CyberMono, fontSize = 11.sp, color = EmeraldText)
                Text(
                    "Ask by typing or tapping the mic. Covers PACE, RTA, Consumer Rights, Renters' Rights, Employment & civil disputes.",
                    fontSize = 13.sp,
                    color = TextSecondary,
                    lineHeight = 19.sp
                )
                Spacer(Modifier.height(4.dp))
                quickPrompts.forEach { prompt ->
                    Surface(
                        shape = TacticalPillShape,
                        color = CardSurface,
                        border = BorderStroke(1.dp, CardBorder),
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !isLoading) { onSendMessage(prompt) }
                    ) {
                        Text("▸ $prompt", fontSize = 13.sp, color = TextPrimary, modifier = Modifier.padding(13.dp))
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    CyberChatBubble(
                        message = msg,
                        onSpeak = { voiceController.speak(msg.text) },
                        onBookmark = {
                            val title = msg.text.lineSequence().firstOrNull()?.take(55) ?: "Saved UK Law Ruling"
                            onBookmarkAnswer(title, msg.text, BookmarkFolder.MYTHBUSTER)
                            Toast.makeText(context, "Saved to Vault Bookmarks", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
                if (isLoading) {
                    item {
                        Surface(shape = TacticalPillShape, color = CardSurface, border = BorderStroke(1.dp, CreamAccent.copy(alpha = 0.4f))) {
                            Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                CircularProgressIndicator(color = CreamAccent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                                Text("VERIFYING UK STATUTES...", fontFamily = CyberMono, fontSize = 11.sp, color = CreamAccent)
                            }
                        }
                    }
                }
            }
        }

        // Input Bar with Voice Mic + Send
        Surface(color = CardSurface, border = BorderStroke(1.dp, CardBorder)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = {
                        if (isListening) {
                            voiceController.stopListening()
                            isListening = false
                        } else {
                            val hasPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                            if (hasPerm) triggerVoiceCapture() else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    modifier = Modifier
                        .size(46.dp)
                        .background(if (isListening) RoseText else MidnightBg, TacticalPillShape)
                ) {
                    Icon(
                        imageVector = if (isListening) Icons.Outlined.Stop else Icons.Outlined.Mic,
                        contentDescription = "Voice Input",
                        tint = if (isListening) TextPrimary else CreamAccent
                    )
                }

                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = {
                        Text(
                            if (isListening) "Listening (UK English)..." else "Ask any UK law question...",
                            fontSize = 13.sp,
                            color = if (isListening) RoseText else TextSecondary
                        )
                    },
                    maxLines = 4,
                    shape = TacticalPillShape,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CreamAccent,
                        unfocusedBorderColor = CardBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedContainerColor = MidnightBg,
                        unfocusedContainerColor = MidnightBg
                    ),
                    modifier = Modifier.weight(1f)
                )

                IconButton(
                    onClick = {
                        val trimmed = inputText.trim()
                        if (trimmed.isNotEmpty()) {
                            onSendMessage(trimmed)
                            inputText = ""
                        }
                    },
                    enabled = !isLoading && inputText.isNotBlank(),
                    modifier = Modifier
                        .size(46.dp)
                        .background(if (inputText.isNotBlank() && !isLoading) CreamAccent else MutedButtonBg, TacticalPillShape)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = CreamTextDark)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CyberChatBubble(
    message: ChatMessage,
    onSpeak: () -> Unit,
    onBookmark: () -> Unit
) {
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = TacticalCardShape,
            color = if (message.isUser) CreamAccent else CardSurface,
            border = if (message.isUser) null else BorderStroke(1.dp, CardBorder),
            modifier = Modifier.widthIn(max = 330.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = message.text,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = if (message.isUser) CreamTextDark else TextPrimary
                )

                if (!message.isUser) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            modifier = Modifier.clickable(onClick = onSpeak),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Outlined.VolumeUp, contentDescription = "Read", tint = CreamAccent, modifier = Modifier.size(14.dp))
                            Text("SPEAK", fontFamily = CyberMono, fontSize = 10.sp, color = CreamAccent)
                        }
                        Row(
                            modifier = Modifier.clickable(onClick = onBookmark),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Outlined.BookmarkAdd, contentDescription = "Save", tint = EmeraldText, modifier = Modifier.size(14.dp))
                            Text("SAVE", fontFamily = CyberMono, fontSize = 10.sp, color = EmeraldText)
                        }
                    }
                }

                if (message.sources.isNotEmpty()) {
                    HorizontalDivider(color = CardBorder)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        message.sources.forEach { src ->
                            Surface(
                                shape = TacticalPillShape,
                                color = MidnightBg,
                                border = BorderStroke(1.dp, CardBorder),
                                modifier = Modifier.clickable { runCatching { uriHandler.openUri(src.url) } }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(Icons.Outlined.OpenInNew, contentDescription = null, tint = CreamAccent, modifier = Modifier.size(11.dp))
                                    Text(src.title, fontFamily = CyberMono, fontSize = 10.sp, color = CreamAccent, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 170.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
