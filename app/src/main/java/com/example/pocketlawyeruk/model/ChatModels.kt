package com.example.pocketlawyeruk.model

import java.util.UUID

data class LegalSource(
    val title: String,
    val url: String
)

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val isUser: Boolean,
    val text: String,
    val sources: List<LegalSource> = emptyList()
)
