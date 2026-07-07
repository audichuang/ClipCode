package com.github.audichuang.clipcode

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.vfs.VfsUtilCore

/**
 * 把 [GitContentResolver.ResolvedGitEntry] 清單組成剪貼簿文字。
 *
 * 從 [CopyGitFilesContentAction] 抽出，供 PR 面板等其他呼叫端複用同一份格式化邏輯。
 * 內部會逐檔取 ReadAction 讀 VFS bytes；呼叫端不需（也不應）再自己包 ReadAction，
 * 否則又變回單一長 read action，複製期間會擋住 EDT 的 write action。
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

        // Metadata line first (before any header) so parsers drop it as pre-header
        // text. Skip it if the configured header is permissive enough to parse the
        // marker line as a file, which would make it a phantom entry. Mirrors the
        // VS Code buildPayloadInternal.
        pathResolver.singleRootName()?.let { sourceRoot ->
            val metaLine = ClipboardRestoreParser.SOURCE_ROOT_MARKER + sourceRoot
            if (!ClipboardRestoreParser.wouldParseAsHeader(metaLine, headerFormat)) {
                fileContents.add(metaLine)
            }
        }

        if (!settings?.state?.preText.isNullOrEmpty()) {
            fileContents.add(ClipboardRestoreParser.escapeContent(settings!!.state.preText, headerFormat))
        }

        // VFS bytes 讀取需在 ReadAction 內 (2024.3+ strict mode)；逐檔取 read lock，
        // 讓 EDT 的 write action（打字）可以在檔案之間插隊
        contentEntries.forEach { entry ->
            indicator.checkCanceled()
            skippedSizeCount += ReadAction.compute<Int, RuntimeException> {
                fileContents.add(
                    GitClipboardFormatter.buildHeader(
                        headerFormat = headerFormat,
                        clipboardPath = pathResolver.toClipboardPath(entry.filePath),
                        changeType = entry.changeType
                    )
                )
                appendContent(fileContents, entry, maxFileSizeBytes, headerFormat)
            }
            if (addExtraLine) {
                fileContents.add("")
            }
        }

        deletedMarkerEntries.forEach { entry ->
            fileContents.add(
                GitClipboardFormatter.buildHeader(
                    headerFormat = headerFormat,
                    clipboardPath = pathResolver.toClipboardPath(entry.filePath),
                    changeType = entry.changeType
                )
            )
            // Escape like real content so a permissive headerFormat can't parse the
            // marker line as a header (VS Code escapes these lines too)
            fileContents.add(ClipboardRestoreParser.escapeContent("// This file has been deleted in this change", headerFormat))
            if (addExtraLine) {
                fileContents.add("")
            }
        }

        if (!settings?.state?.postText.isNullOrEmpty()) {
            fileContents.add(ClipboardRestoreParser.escapeContent(settings!!.state.postText, headerFormat))
        }

        // hasVirtualFileContent 讀 VirtualFile 的 isValid/exists，也要在 read lock 內
        val (diskBackedCount, filesFromHistory) = ReadAction.compute<Pair<Int, Int>, RuntimeException> {
            contentEntries.count { it.hasVirtualFileContent } to
                contentEntries.count { !it.hasVirtualFileContent && it.contentFromRevision != null }
        }
        val filesFromDisk = diskBackedCount - skippedSizeCount.coerceAtMost(diskBackedCount)
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
                fileContents.add(
                    ClipboardRestoreParser.escapeContent("// File skipped: size exceeds limit ($contentSizeBytes bytes)", headerFormat)
                )
                return 1
            }

            fileContents.add(ClipboardRestoreParser.escapeContent(revisionContent, headerFormat))
            return 0
        }

        val virtualFile = entry.virtualFile
        if (virtualFile != null && entry.hasVirtualFileContent) {
            if (virtualFile.length > maxFileSizeBytes) {
                logger.info("Skipping oversized Git file: ${entry.filePath}")
                fileContents.add(
                    ClipboardRestoreParser.escapeContent("// File skipped: size exceeds limit (${virtualFile.length} bytes)", headerFormat)
                )
                return 1
            }

            return try {
                // 用 VfsUtilCore.loadText 走檔案編碼，而非強制 UTF-8
                fileContents.add(ClipboardRestoreParser.escapeContent(VfsUtilCore.loadText(virtualFile), headerFormat))
                0
            } catch (e: Exception) {
                logger.warn("Failed to read Git file content: ${entry.filePath}", e)
                fileContents.add(ClipboardRestoreParser.escapeContent("// Error reading file content", headerFormat))
                0
            }
        }

        fileContents.add(ClipboardRestoreParser.escapeContent("// Unable to read file content", headerFormat))
        return 0
    }
}
