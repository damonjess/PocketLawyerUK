package com.example.pocketlawyeruk.model

import java.util.UUID

enum class CaseItemType {
    AUDIT,
    SCAN,
    BOOKMARK
}

data class CaseRecord(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class CaseItem(
    val caseId: String,
    val type: CaseItemType,
    val itemId: String,
    val addedAt: Long = System.currentTimeMillis()
)
