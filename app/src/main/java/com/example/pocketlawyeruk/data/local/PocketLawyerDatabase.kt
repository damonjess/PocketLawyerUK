package com.example.pocketlawyeruk.data.local

import android.content.Context
import androidx.room.*
import com.example.pocketlawyeruk.model.*
import com.example.pocketlawyeruk.ui.screens.EncounterType
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

// ---------------- ENTITIES ----------------

@Entity(tableName = "audits")
data class AuditEntity(
    @PrimaryKey val id: String,
    val title: String,
    val date: String,
    val jurisdiction: String,
    val complianceScore: Int,
    val officerViolationCount: Int,
    val searchBlob: String, // Full-text searchable blob (title + transcript + citations + takeaway)
    val reportJson: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "document_scans")
data class DocumentScanEntity(
    @PrimaryKey val id: String,
    val title: String,
    val documentType: String,
    val dateScanned: String,
    val scanJson: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val isUser: Boolean,
    val text: String,
    val sourcesJson: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey val id: String,
    val folder: String,
    val title: String,
    val content: String,
    val citation: String,
    val createdAt: Long = System.currentTimeMillis()
)

// ---------------- DAOs ----------------

@Dao
interface AuditDao {
    @Query("SELECT * FROM audits ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<AuditEntity>>

    @Query("SELECT * FROM audits WHERE searchBlob LIKE '%' || :query || '%' ORDER BY createdAt DESC")
    fun search(query: String): Flow<List<AuditEntity>>

    @Query("SELECT * FROM audits WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AuditEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AuditEntity)

    @Query("DELETE FROM audits WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface DocumentScanDao {
    @Query("SELECT * FROM document_scans ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DocumentScanEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DocumentScanEntity)

    @Query("DELETE FROM document_scans WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_messages ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<ChatMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ChatMessageEntity)

    @Query("DELETE FROM chat_messages")
    suspend fun clearAll()
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%' OR citation LIKE '%' || :query || '%' ORDER BY createdAt DESC")
    fun search(query: String): Flow<List<BookmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteById(id: String)
}

// ---------------- DATABASE ----------------

@Database(
    entities = [AuditEntity::class, DocumentScanEntity::class, ChatMessageEntity::class, BookmarkEntity::class],
    version = 1,
    exportSchema = false
)
abstract class PocketLawyerDatabase : RoomDatabase() {
    abstract fun auditDao(): AuditDao
    abstract fun documentScanDao(): DocumentScanDao
    abstract fun chatDao(): ChatDao
    abstract fun bookmarkDao(): BookmarkDao

    companion object {
        @Volatile private var INSTANCE: PocketLawyerDatabase? = null

        fun getInstance(context: Context): PocketLawyerDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PocketLawyerDatabase::class.java,
                    "pocket_lawyer_uk.db"
                ).build().also { INSTANCE = it }
            }
    }
}

// ---------------- ZERO-DEPENDENCY JSON MAPPERS ----------------

object VaultMappers {

    fun AuditReport.toEntity(): AuditEntity {
        val json = JSONObject().apply {
            put("id", id)
            put("title", title)
            put("date", date)
            put("sourceUrl", sourceUrl ?: JSONObject.NULL)
            put("videoUriString", videoUriString ?: JSONObject.NULL)
            put("encounterType", encounterType.name)
            put("jurisdiction", jurisdiction.name)
            put("complianceScore", complianceScore)
            put("scoreSummary", scoreSummary)
            put("officerViolationCount", officerViolationCount)
            put("staffViolationCount", staffViolationCount)
            put("publicMythCount", publicMythCount)
            put("takeaway", takeaway)
            put("isExample", isExample)
            put("yourRights", JSONArray(yourRights))
            put("whatIfGuide", JSONObject().apply {
                put("whatYouCanRefuse", JSONArray(whatIfGuide.whatYouCanRefuse))
                put("whatYouMustDo", JSONArray(whatIfGuide.whatYouMustDo))
                put("exactScriptToSay", whatIfGuide.exactScriptToSay)
            })
            put("fullTranscript", JSONArray().apply {
                fullTranscript.forEach {
                    put(JSONObject().apply {
                        put("timestampSeconds", it.timestampSeconds)
                        put("speaker", it.speaker)
                        put("text", it.text)
                    })
                }
            })
            put("gowiselyChecklist", JSONArray().apply {
                gowiselyChecklist.forEach {
                    put(JSONObject().apply {
                        put("step", it.step)
                        put("status", it.status.name)
                        put("note", it.note)
                    })
                }
            })
            put("timeline", JSONArray().apply {
                timeline.forEach {
                    put(JSONObject().apply {
                        put("timestampSeconds", it.timestampSeconds)
                        put("speaker", it.speaker)
                        put("side", it.side.name)
                        put("quote", it.quote)
                        put("verdict", it.verdict.name)
                        put("citation", it.citation)
                        put("explanation", it.explanation)
                    })
                }
            })
        }

        val searchText = buildString {
            append(title).append(' ')
            append(jurisdiction.displayName).append(' ')
            append(takeaway).append(' ')
            fullTranscript.forEach { append(it.text).append(' ') }
            timeline.forEach { append(it.quote).append(' ').append(it.citation).append(' ') }
        }

        return AuditEntity(
            id = id,
            title = title,
            date = date,
            jurisdiction = jurisdiction.name,
            complianceScore = complianceScore,
            officerViolationCount = officerViolationCount,
            searchBlob = searchText,
            reportJson = json.toString()
        )
    }

    fun AuditEntity.toModel(): AuditReport {
        val json = JSONObject(reportJson)
        val rights = mutableListOf<String>()
        json.optJSONArray("yourRights")?.let { arr ->
            for (i in 0 until arr.length()) rights.add(arr.optString(i))
        }
        val whatIfJson = json.optJSONObject("whatIfGuide")
        val refuse = mutableListOf<String>()
        val mustDo = mutableListOf<String>()
        whatIfJson?.optJSONArray("whatYouCanRefuse")?.let { arr ->
            for (i in 0 until arr.length()) refuse.add(arr.optString(i))
        }
        whatIfJson?.optJSONArray("whatYouMustDo")?.let { arr ->
            for (i in 0 until arr.length()) mustDo.add(arr.optString(i))
        }
        val transcript = mutableListOf<TranscriptLine>()
        json.optJSONArray("fullTranscript")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                transcript.add(TranscriptLine(o.optInt("timestampSeconds"), o.optString("speaker"), o.optString("text")))
            }
        }
        val gowisely = mutableListOf<GowiselyItem>()
        json.optJSONArray("gowiselyChecklist")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                gowisely.add(
                    GowiselyItem(
                        step = o.optString("step"),
                        status = runCatching { GowiselyStatus.valueOf(o.optString("status")) }.getOrDefault(GowiselyStatus.SKIPPED),
                        note = o.optString("note")
                    )
                )
            }
        }
        val timeline = mutableListOf<AuditedStatement>()
        json.optJSONArray("timeline")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                timeline.add(
                    AuditedStatement(
                        timestampSeconds = o.optInt("timestampSeconds"),
                        speaker = o.optString("speaker"),
                        side = runCatching { SpeakerSide.valueOf(o.optString("side")) }.getOrDefault(SpeakerSide.OFFICER),
                        quote = o.optString("quote"),
                        verdict = runCatching { StatementVerdict.valueOf(o.optString("verdict")) }.getOrDefault(StatementVerdict.LAWFUL),
                        citation = o.optString("citation"),
                        explanation = o.optString("explanation")
                    )
                )
            }
        }

        return AuditReport(
            id = json.optString("id", id),
            title = json.optString("title", title),
            date = json.optString("date", date),
            sourceUrl = json.optString("sourceUrl").takeIf { it.isNotBlank() && it != "null" },
            videoUriString = json.optString("videoUriString").takeIf { it.isNotBlank() && it != "null" },
            encounterType = runCatching { EncounterType.valueOf(json.optString("encounterType")) }.getOrDefault(EncounterType.PEDESTRIAN),
            jurisdiction = runCatching { UkJurisdiction.valueOf(json.optString("jurisdiction")) }.getOrDefault(UkJurisdiction.ENGLAND_WALES),
            complianceScore = json.optInt("complianceScore", complianceScore),
            scoreSummary = json.optString("scoreSummary", ""),
            officerViolationCount = json.optInt("officerViolationCount", officerViolationCount),
            staffViolationCount = json.optInt("staffViolationCount", 0),
            publicMythCount = json.optInt("publicMythCount", 0),
            fullTranscript = transcript,
            yourRights = rights,
            whatIfGuide = WhatIfGuide(refuse, mustDo, whatIfJson?.optString("exactScriptToSay").orEmpty()),
            gowiselyChecklist = gowisely,
            timeline = timeline,
            takeaway = json.optString("takeaway"),
            isExample = json.optBoolean("isExample", false)
        )
    }

    fun DocumentScanResult.toEntity(): DocumentScanEntity {
        val json = JSONObject().apply {
            put("id", id)
            put("title", title)
            put("documentType", documentType)
            put("dateScanned", dateScanned)
            put("jurisdiction", jurisdiction.name)
            put("summary", summary)
            put("keyDeadlines", JSONArray(keyDeadlines))
            put("appealOrReplyLetter", appealOrReplyLetter)
            put("redFlags", JSONArray().apply {
                redFlags.forEach {
                    put(JSONObject().apply {
                        put("severity", it.severity)
                        put("clause", it.clause)
                        put("issue", it.issue)
                        put("citation", it.citation)
                    })
                }
            })
        }
        return DocumentScanEntity(id, title, documentType, dateScanned, json.toString())
    }

    fun DocumentScanEntity.toModel(): DocumentScanResult {
        val json = JSONObject(scanJson)
        val deadlines = mutableListOf<String>()
        json.optJSONArray("keyDeadlines")?.let { arr ->
            for (i in 0 until arr.length()) deadlines.add(arr.optString(i))
        }
        val flags = mutableListOf<DocumentRedFlag>()
        json.optJSONArray("redFlags")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                flags.add(DocumentRedFlag(o.optString("severity"), o.optString("clause"), o.optString("issue"), o.optString("citation")))
            }
        }
        return DocumentScanResult(
            id = json.optString("id", id),
            title = json.optString("title", title),
            documentType = json.optString("documentType", documentType),
            dateScanned = json.optString("dateScanned", dateScanned),
            jurisdiction = runCatching { UkJurisdiction.valueOf(json.optString("jurisdiction")) }.getOrDefault(UkJurisdiction.ENGLAND_WALES),
            summary = json.optString("summary"),
            keyDeadlines = deadlines,
            redFlags = flags,
            appealOrReplyLetter = json.optString("appealOrReplyLetter")
        )
    }

    fun ChatMessage.toEntity(): ChatMessageEntity {
        val arr = JSONArray()
        sources.forEach {
            arr.put(JSONObject().put("title", it.title).put("url", it.url))
        }
        return ChatMessageEntity(id = id, isUser = isUser, text = text, sourcesJson = arr.toString())
    }

    fun ChatMessageEntity.toModel(): ChatMessage {
        val list = mutableListOf<LegalSource>()
        runCatching {
            val arr = JSONArray(sourcesJson)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(LegalSource(o.optString("title"), o.optString("url")))
            }
        }
        return ChatMessage(id = id, isUser = isUser, text = text, sources = list)
    }

    fun SavedBookmark.toEntity() = BookmarkEntity(id, folder.name, title, content, citation, createdAt)

    fun BookmarkEntity.toModel() = SavedBookmark(
        id = id,
        folder = runCatching { BookmarkFolder.valueOf(folder) }.getOrDefault(BookmarkFolder.POLICE_AND_PUBLIC),
        title = title,
        content = content,
        citation = citation,
        createdAt = createdAt
    )
}
