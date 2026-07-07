package com.github.audichuang.clipcode

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.vfs.VfsUtilCore

/**
 * 把 [GitContentResolver.ResolvedGitEntry] 清單組成剪貼簿文字。
 *
 * 從 [CopyGitFilesContentAction] 抽出，供 PR 面板等其他呼叫端複用同一份格式化邏輯。
 * 這裡只負責「解析每個 entry 的內容」（逐檔取 ReadAction 讀 VFS bytes，讓 EDT 的
 * write action 可在檔案之間插隊）與計算通知摘要；實際的 wire 格式組裝一律委派給
 * [ClipboardPayloadFormatter]，與 VS Code 端 buildGitPayload 共用同一份格式。
 */
object GitClipboardPayloadBuilder {
    private val logger = Logger.getInstance(GitClipboardPayloadBuilder::class.java)
    private const val DELETED_MARKER = "// This file has been deleted in this change"

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

        val payloadFiles = mutableListOf<ClipboardPayloadFormatter.PayloadFile>()
        var skippedSizeCount = 0

        // VFS bytes 讀取需在 ReadAction 內 (2024.3+ strict mode)；逐檔取 read lock，
        // 讓 EDT 的 write action（打字）可以在檔案之間插隊。組裝本身是純字串運算，
        // 不需 read lock，統一交給 ClipboardPayloadFormatter。
        contentEntries.forEach { entry ->
            indicator.checkCanceled()
            val resolved = ReadAction.compute<ClipboardPayloadFormatter.PayloadFile, RuntimeException> {
                resolveContentEntry(entry, pathResolver, maxFileSizeBytes)
            }
            if (resolved.skippedReason != null) skippedSizeCount++
            payloadFiles.add(resolved)
        }

        deletedMarkerEntries.forEach { entry ->
            payloadFiles.add(
                ClipboardPayloadFormatter.PayloadFile(
                    path = pathResolver.toClipboardPath(entry.filePath),
                    content = DELETED_MARKER,
                    changeType = entry.changeType
                )
            )
        }

        val text = ClipboardPayloadFormatter.buildGitPayload(
            ClipboardPayloadFormatter.Options(
                headerFormat = headerFormat,
                preText = settings?.state?.preText ?: "",
                postText = settings?.state?.postText ?: "",
                addExtraLineBetweenFiles = settings?.state?.addExtraLineBetweenFiles == true,
                files = payloadFiles,
                sourceRoot = pathResolver.singleRootName()
            )
        )

        return Payload(text = text, summary = buildSummary(contentEntries, deletedMarkerEntries, skippedSizeCount))
    }

    /**
     * Resolve one content entry into a [ClipboardPayloadFormatter.PayloadFile].
     * Prefers Git-revision content over the working-tree VirtualFile. Must be called
     * under a ReadAction (reads VirtualFile length / text / validity).
     */
    private fun resolveContentEntry(
        entry: GitContentResolver.ResolvedGitEntry,
        pathResolver: ClipboardPathResolver,
        maxFileSizeBytes: Long
    ): ClipboardPayloadFormatter.PayloadFile {
        val path = pathResolver.toClipboardPath(entry.filePath)
        val changeType = entry.changeType

        val revisionContent = entry.contentFromRevision
        if (revisionContent != null) {
            val contentSizeBytes = revisionContent.toByteArray(Charsets.UTF_8).size.toLong()
            if (contentSizeBytes > maxFileSizeBytes) {
                logger.info("Skipping oversized Git revision content: ${entry.filePath}")
                return ClipboardPayloadFormatter.PayloadFile(path, skippedReason = sizeReason(contentSizeBytes), changeType = changeType)
            }
            return ClipboardPayloadFormatter.PayloadFile(path, content = revisionContent, changeType = changeType)
        }

        val virtualFile = entry.virtualFile
        if (virtualFile != null && entry.hasVirtualFileContent) {
            if (virtualFile.length > maxFileSizeBytes) {
                logger.info("Skipping oversized Git file: ${entry.filePath}")
                return ClipboardPayloadFormatter.PayloadFile(path, skippedReason = sizeReason(virtualFile.length), changeType = changeType)
            }
            return try {
                // 用 VfsUtilCore.loadText 走檔案編碼，而非強制 UTF-8
                ClipboardPayloadFormatter.PayloadFile(path, content = VfsUtilCore.loadText(virtualFile), changeType = changeType)
            } catch (e: Exception) {
                logger.warn("Failed to read Git file content: ${entry.filePath}", e)
                ClipboardPayloadFormatter.PayloadFile(path, content = "// Error reading file content", changeType = changeType)
            }
        }

        return ClipboardPayloadFormatter.PayloadFile(path, content = "// Unable to read file content", changeType = changeType)
    }

    // Reason string embedded into the formatter's "// File skipped: <reason>" line.
    private fun sizeReason(bytes: Long): String = "size exceeds limit ($bytes bytes)"

    private fun buildSummary(
        contentEntries: List<GitContentResolver.ResolvedGitEntry>,
        deletedMarkerEntries: List<GitContentResolver.ResolvedGitEntry>,
        skippedSizeCount: Int
    ): String {
        // hasVirtualFileContent 讀 VirtualFile 的 isValid/exists，也要在 read lock 內
        val (diskBackedCount, filesFromHistory) = ReadAction.compute<Pair<Int, Int>, RuntimeException> {
            contentEntries.count { it.hasVirtualFileContent } to
                contentEntries.count { !it.hasVirtualFileContent && it.contentFromRevision != null }
        }
        val filesFromDisk = diskBackedCount - skippedSizeCount.coerceAtMost(diskBackedCount)
        val copiedWithContent = contentEntries.size - skippedSizeCount
        val totalCopied = copiedWithContent + deletedMarkerEntries.size
        val skippedSuffix = if (skippedSizeCount > 0) " ($skippedSizeCount skipped: size exceeded)" else ""

        return when {
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
    }
}
