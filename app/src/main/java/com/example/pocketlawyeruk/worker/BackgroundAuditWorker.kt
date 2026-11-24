package com.example.pocketlawyeruk.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.example.pocketlawyeruk.MainActivity
import com.example.pocketlawyeruk.data.LegalAuditService
import com.example.pocketlawyeruk.data.VideoAudioExtractor
import com.example.pocketlawyeruk.data.local.PocketLawyerDatabase
import com.example.pocketlawyeruk.data.local.VaultMappers.toEntity
import com.example.pocketlawyeruk.ui.screens.EncounterType
import com.example.pocketlawyeruk.model.ConfrontingParty
import java.io.File

class BackgroundAuditWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL)?.takeIf { it.isNotBlank() } ?: return Result.failure()
        val typeName = inputData.getString(KEY_ENCOUNTER_TYPE) ?: EncounterType.PEDESTRIAN.name
        val encounterType = runCatching { EncounterType.valueOf(typeName) }.getOrDefault(EncounterType.PEDESTRIAN)
        
        val partyName = inputData.getString(KEY_CONFRONTING_PARTY) ?: ConfrontingParty.AUTO.name
        val confrontingParty = runCatching { ConfrontingParty.valueOf(partyName) }.getOrDefault(ConfrontingParty.AUTO)

        ensureChannel()
        if (LegalAuditService.getStoredApiKey(applicationContext).isBlank()) {
            postCompletionNotification(
                auditId = null,
                title = "Background Audit Skipped",
                body = LegalAuditService.MISSING_KEY_MESSAGE
            )
            return Result.failure()
        }

        val extractor = VideoAudioExtractor()
        val service = LegalAuditService(applicationContext)
        var downloadedAudio: File? = null

        return try {
            downloadedAudio = extractor.downloadAudioFromUrl(applicationContext, url)
            val report = service.analyzeAudioFile(
                audioFile = downloadedAudio,
                sourceUrl = url,
                encounterType = encounterType,
                confrontingParty = confrontingParty
            )

            // Save offline to Room Vault
            PocketLawyerDatabase.getInstance(applicationContext)
                .auditDao()
                .upsert(report.toEntity())

            val flagSummary = buildList {
                if (report.officerViolationCount > 0 || report.staffViolationCount == 0) {
                    add("${report.officerViolationCount} officer flags")
                }
                if (report.staffViolationCount > 0) {
                    add("${report.staffViolationCount} staff flags")
                }
                add("${report.publicMythCount} myths")
            }.joinToString(", ")

            postCompletionNotification(
                auditId = report.id,
                title = "Audit Ready: ${report.complianceScore}% Compliance",
                body = "${report.title} (${report.jurisdiction.displayName}) • $flagSummary."
            )
            Result.success()
        } catch (e: Exception) {
            postCompletionNotification(
                auditId = null,
                title = "Background Legal Audit Failed",
                body = e.localizedMessage ?: "Could not extract or analyse the shared link."
            )
            Result.failure()
        } finally {
            downloadedAudio?.parentFile?.deleteRecursively()
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Legal Audit Alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            nm.createNotificationChannel(channel)
        }
    }

    private fun postCompletionNotification(auditId: String?, title: String, body: String) {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (auditId != null) putExtra(EXTRA_OPEN_AUDIT_ID, auditId)
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            auditId?.hashCode() ?: 99,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(auditId?.hashCode() ?: 99, notification)
    }

    companion object {
        const val KEY_URL = "audit_url"
        const val KEY_ENCOUNTER_TYPE = "encounter_type"
        const val KEY_CONFRONTING_PARTY = "confronting_party"
        const val EXTRA_OPEN_AUDIT_ID = "open_audit_id"
        private const val CHANNEL_ID = "pocket_lawyer_audits"

        fun enqueue(
            context: Context,
            url: String,
            encounterType: EncounterType,
            confrontingParty: ConfrontingParty = ConfrontingParty.AUTO
        ) {
            val request = OneTimeWorkRequestBuilder<BackgroundAuditWorker>()
                .setInputData(
                    workDataOf(
                        KEY_URL to url,
                        KEY_ENCOUNTER_TYPE to encounterType.name,
                        KEY_CONFRONTING_PARTY to confrontingParty.name
                    )
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
