package com.github.audichuang.clipcode

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.vfs.VfsUtilCore

/**
 * 把 [GitContentResolver.ResolvedGitEntry] 清單組成剪貼簿文字。
 *
 * 從 [CopyGitFilesContentAction] 抽出，供 PR 面板等其他呼叫端複用同一份格式化邏輯。
 * 必須在 ReadAction 內呼叫（會讀 VFS bytes）。
 */
object GitClipboardPayloadBuilder {
    private val logger = Logger.getInstance(GitClipboardPayloadBuilder::class.java)

    data class Payload(val text: String, val summary: String)

    fun build(
        contentEntries: List<GitContentResolver.ResolvedGitEntry>,
        deletedMarkerEntries: List<GitContentResolver.ResolvedGitEntry>,
        pathResolver: ClipboardPathResolver,
        settings: CopyFileContentSettings?,
        indicator: ProgressIndicator
    ): Payload {
        val headerFormat = settings?.state?.headerFormat ?: "// file: \$FILE_PATH"
        val maxFileSizeBytes = settings?.state?.maxFileSizeKB?.times(1024L) ?: (500L * 1024L)
        val addExtraLine = settings?.state?.addExtraLineBetweenFiles == true
        val fileContents = mutableListOf<String>()
        var skippedSizeCount = 0

        if (!settings?.state?.preText.isNullOrEmpty()) {
            fileContents.add(ClipboardRestoreParser.escapeContent(settings!!.state.preText, headerFormat))
        }

        // VFS bytes 讀取需在 ReadAction 內 (2024.3+ strict mode)
        skippedSizeCount = ReadAction.compute<Int, RuntimeException> {
            var skipped = 0
            contentEntries.forEach { entry ->
                indicator.checkCanceled()
                fileContents.add(
                    GitClipboardFormatter.buildHeader(
                        headerFormat = headerFormat,
                        clipboardPath = pathResolver.toClipboardPath(entry.filePath),
                        changeType = entry.changeType
                    )
                )
                skipped += appendContent(fileContents, entry, maxFileSizeBytes, headerFormat)
                if (addExtraLine) {
                    fileContents.add("")
                }
            }
            skipped
        }

        deletedMarkerEntries.forEach { entry ->
            fileContents.add(
                GitClipboardFormatter.buildHeader(
                    headerFormat = headerFormat,
                    clipboardPath = pathResolver.toClipboardPath(entry.filePath),
                    changeType = entry.changeType
                )
            )
            fileContents.add("// This file has been deleted in this change")
            if (addExtraLine) {
                fileContents.add("")
            }
        }

        if (!settings?.state?.postText.isNullOrEmpty()) {
            fileContents.add(ClipboardRestoreParser.escapeContent(settings!!.state.postText, headerFormat))
        }

        val filesFromDisk = contentEntries.count { it.hasVirtualFileContent } - skippedSizeCount.coerceAtMost(contentEntries.count { it.hasVirtualFileContent })
        val filesFromHistory = contentEntries.count { !it.hasVirtualFileContent && it.contentFromRevision != null }
        val copiedWithContent = contentEntries.size - skippedSizeCount
        val totalCopied = copiedWithContent + deletedMarkerEntries.size
        val skippedSuffix = if (skippedSizeCount > 0) " ($skippedSizeCount skipped: size exceeded)" else ""

        val summary = when {
            contentEntries.isEmpty() && deletedMarkerEntries.size == 1 ->
                "1 deleted file marker copied."
            contentEntries.isEmpty() ->
                "${deletedMarkerEntries.size} deleted file markers copied."
            deletedMarkerEntries.isEmpty() && copiedWithContent == 1 && filesFromHistory == 1 ->
                "1 file copied (from Git history)$skippedSuffix."
            deletedMarkerEntries.isEmpty() && filesFromHistory > 0 ->
                "$copiedWithContent files copied ($filesFromDisk from disk, $filesFromHistory from Git history)$skippedSuffix."
            deletedMarkerEntries.isEmpty() ->
                "$copiedWithContent files copied$skippedSuffix."
            filesFromHistory > 0 ->
                "$totalCopied files copied ($filesFromDisk from disk, $filesFromHistory from Git history, ${deletedMarkerEntries.size} deleted)$skippedSuffix."
            else ->
                "$totalCopied files copied ($copiedWithContent with content, ${deletedMarkerEntries.size} deleted)$skippedSuffix."
        }

        return Payload(
            text = fileContents.joinToString("\n"),
            summary = summary
        )
    }

    private fun appendContent(
        fileContents: MutableList<String>,
        entry: GitContentResolver.ResolvedGitEntry,
        maxFileSizeBytes: Long,
        headerFormat: String
    ): Int {
        val revisionContent = entry.contentFromRevision
        if (revisionContent != null) {
            val contentSizeBytes = revisionContent.toByteArray(Charsets.UTF_8).size.toLong()
            if (contentSizeBytes > maxFileSizeBytes) {
                logger.info("Skipping oversized Git revision content: ${entry.filePath}")
                fileContents.add("// File skipped: size exceeds limit ($contentSizeBytes bytes)")
                return 1
            }

            fileContents.add(ClipboardRestoreParser.escapeContent(revisionContent, headerFormat))
            return 0
        }

        val virtualFile = entry.virtualFile
        if (virtualFile != null && entry.hasVirtualFileContent) {
            if (virtualFile.length > maxFileSizeBytes) {
                logger.info("Skipping oversized Git file: ${entry.filePath}")
                fileContents.add("// File skipped: size exceeds limit (${virtualFile.length} bytes)")
                return 1
            }

            return try {
                // 用 VfsUtilCore.loadText 走檔案編碼，而非強制 UTF-8
                fileContents.add(ClipboardRestoreParser.escapeContent(VfsUtilCore.loadText(virtualFile), headerFormat))
                0
            } catch (e: Exception) {
                logger.warn("Failed to read Git file content: ${entry.filePath}", e)
                fileContents.add("// Error reading file content")
                0
            }
        }

        fileContents.add("// Unable to read file content")
        return 0
    }
}
