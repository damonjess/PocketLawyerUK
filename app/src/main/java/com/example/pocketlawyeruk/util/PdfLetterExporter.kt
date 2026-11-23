package com.example.pocketlawyeruk.util

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import java.io.File

object PdfLetterExporter {

    private const val A4_WIDTH = 595
    private const val A4_HEIGHT = 842
    private const val MARGIN = 52

    fun exportAndSharePdf(context: Context, title: String, letterBody: String) {
        val pdfDocument = PdfDocument()
        val usableWidth = A4_WIDTH - (MARGIN * 2)

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 15f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }

        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(30, 30, 30)
            textSize = 11f
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        }

        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GRAY
            textSize = 8.5f
        }

        val layout = StaticLayout.Builder
            .obtain(letterBody, 0, letterBody.length, bodyPaint, usableWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(4f, 1.15f)
            .build()

        var pageNumber = 1
        var lineIndex = 0

        while (lineIndex < layout.lineCount) {
            val pageInfo = PdfDocument.PageInfo.Builder(A4_WIDTH, A4_HEIGHT, pageNumber).create()
            val page = pdfDocument.startPage(pageInfo)
            val canvas = page.canvas

            var cursorY = MARGIN.toFloat()

            if (pageNumber == 1) {
                canvas.drawText(title.take(65), MARGIN.toFloat(), cursorY + 14f, titlePaint)
                cursorY += 32f
                val dividerPaint = Paint().apply {
                    color = Color.LTGRAY
                    strokeWidth = 1f
                }
                canvas.drawLine(MARGIN.toFloat(), cursorY, (A4_WIDTH - MARGIN).toFloat(), cursorY, dividerPaint)
                cursorY += 20f
            }

            val maxY = A4_HEIGHT - MARGIN - 24f
            while (lineIndex < layout.lineCount) {
                val lineHeight = (layout.getLineBottom(lineIndex) - layout.getLineTop(lineIndex)).toFloat()
                if (cursorY + lineHeight > maxY) break

                val lineStart = layout.getLineStart(lineIndex)
                val lineEnd = layout.getLineEnd(lineIndex)
                val lineText = letterBody.substring(lineStart, lineEnd).trimEnd('\n')
                canvas.drawText(lineText, MARGIN.toFloat(), cursorY - layout.getLineAscent(lineIndex), bodyPaint)
                cursorY += lineHeight
                lineIndex++
            }

            canvas.drawText(
                "Drafted with Pocket Lawyer UK (England & Wales legal information) • Page $pageNumber",
                MARGIN.toFloat(),
                (A4_HEIGHT - 24).toFloat(),
                footerPaint
            )

            pdfDocument.finishPage(page)
            pageNumber++
        }

        val dir = File(context.cacheDir, "letters").apply { mkdirs() }
        val safeName = title.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(36).ifBlank { "UK_Legal_Letter" }
        val outFile = File(dir, "$safeName.pdf")
        outFile.outputStream().use { pdfDocument.writeTo(it) }
        pdfDocument.close()

        val contentUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Save or Send Legal Letter PDF"))
    }
}
