package com.github.audichuang.clipcode

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException

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

    // The two "we could not produce content" bodies. Byte-identical to the strings
    // RestorePlan.isPlaceholderBody / restore.ts isPlaceholderBody refuse to write over a
    // real file, and to gitCopy.ts UNREADABLE_FILE_MARKER — a receiver recognises them, so
    // this side must not report them as copied files either. Accepted cost, same as the
    // restore guard's: a real file whose whole body IS one of these lines is counted as
    // skipped (it still reaches the clipboard verbatim).
    private const val UNREADABLE_MARKER = "// Unable to read file content"
    private const val READ_ERROR_MARKER = "// Error reading file content"

    private fun isPlaceholder(content: String?): Boolean =
        content == UNREADABLE_MARKER || content == READ_ERROR_MARKER

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
        var skippedUnreadableCount = 0
        var copiedCount = 0
        // The ordinary filters and the file-count limit apply HERE too. This builder is
        // where the same action lands whenever a selection mixes revision or deleted
        // entries, and it applied neither: an EXCLUDE rule stopped working the moment a
        // staged file was selected beside the excluded one, and a 200-file commit ignored
        // a fileCountLimit of 30 that the sibling path honours.
        val state = settings?.state
        val useFilters = state?.useFilters == true
        val enabled = { rules: List<CopyFileContentSettings.FilterRule>? ->
            rules.orEmpty().filter { it.enabled }
        }
        val includeRules = enabled(state?.filterRules?.filter { it.action == CopyFileContentSettings.FilterAction.INCLUDE })
        val excludeRules = enabled(state?.filterRules?.filter { it.action == CopyFileContentSettings.FilterAction.EXCLUDE })
        val patternCache = mutableMapOf<String, Regex>()
        val countLimit = if (state?.setMaxFileCount == true) state.fileCountLimit else Int.MAX_VALUE
        var fileLimitReached = false

        // Only files that actually carry content count against the limit — a placeholder is
        // a note that a file was NOT copied. Counting placeholders made a limit of 1 stop
        // after a single skipped file, while VS Code went on to copy the next real one.
        // `skippedReason` alone was not enough: the UNREADABLE / READ_ERROR placeholders
        // travel in `content`, so an unreadable file ate the limit AND was reported as
        // copied, and a real file behind it never reached the clipboard.
        fun copiedSoFar(): Int = copiedCount

        fun accepted(filePath: String): Boolean {
            if (copiedSoFar() >= countLimit) {
                fileLimitReached = true
                return false
            }
            if (!useFilters) return true
            return CopyFilterMatcher.passes(
                fileName = filePath.substringAfterLast('/').substringAfterLast('\\'),
                relativePath = CopyPathFormatter.relativeFilterPath(pathResolver, filePath),
                absolutePath = filePath,
                useIncludeFilters = state?.useIncludeFilters == true,
                useExcludeFilters = state?.useExcludeFilters == true,
                includeRules = includeRules,
                excludeRules = excludeRules,
                cache = patternCache
            )
        }

        // VFS bytes 讀取需在 ReadAction 內 (2024.3+ strict mode)；逐檔取 read lock，
        // 讓 EDT 的 write action（打字）可以在檔案之間插隊。組裝本身是純字串運算，
        // 不需 read lock，統一交給 ClipboardPayloadFormatter。
        // The summary must describe what actually reached the clipboard. Feeding it the
        // ORIGINAL lists reported "10 files copied" when 5 were filtered out and
        // "200 files copied" when the limit stopped it at 30 — the very bug the VS Code
        // half of this change fixed on its own notification path.
        // Entries that really carried content — NOT every accepted entry. A placeholder
        // must not be summarised as a copied file.
        val copiedContent = mutableListOf<GitContentResolver.ResolvedGitEntry>()
        val acceptedDeleted = mutableListOf<GitContentResolver.ResolvedGitEntry>()

        contentEntries.forEach { entry ->
            indicator.checkCanceled()
            if (!accepted(entry.filePath)) return@forEach
            val resolved = ApplicationManager.getApplication().runReadAction<ClipboardPayloadFormatter.PayloadFile> {
                resolveContentEntry(entry, pathResolver, maxFileSizeBytes)
            }
            when {
                resolved.skippedReason != null -> skippedSizeCount++
                isPlaceholder(resolved.content) -> skippedUnreadableCount++
                else -> {
                    copiedContent.add(entry)
                    copiedCount++
                }
            }
            payloadFiles.add(resolved)
        }

        deletedMarkerEntries.forEach { entry ->
            if (!accepted(entry.filePath)) return@forEach
            acceptedDeleted.add(entry)
            copiedCount++
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

        val limitSuffix = if (fileLimitReached) " File limit $countLimit reached." else ""
        return Payload(
            text = text,
            summary = buildSummary(copiedContent, acceptedDeleted, skippedSizeCount, skippedUnreadableCount) + limitSuffix
        )
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
        if (revisionContent != null && Utf8Text.looksLikeMojibake(revisionContent)) {
            // git4idea hands back an already-decoded String, so there are no bytes left to
            // validate the way the disk path does. A replacement character is the one
            // surviving trace of a lenient decode, and letting it through would put
            // mojibake on the clipboard for the other tool to write over a real file.
            logger.info("Skipping Git revision content that did not decode cleanly: ${entry.filePath}")
            return ClipboardPayloadFormatter.PayloadFile(
                path, content = UNREADABLE_MARKER, changeType = changeType
            )
        }
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
                // Strict UTF-8 or skip, same rule as the ordinary copy path — see Utf8Text.
                val text = Utf8Text.decodeOrNull(virtualFile.contentsToByteArray())
                    ?: return ClipboardPayloadFormatter.PayloadFile(
                        path, content = UNREADABLE_MARKER, changeType = changeType
                    )
                ClipboardPayloadFormatter.PayloadFile(path, content = text, changeType = changeType)
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Failed to read Git file content: ${entry.filePath}", e)
                ClipboardPayloadFormatter.PayloadFile(path, content = READ_ERROR_MARKER, changeType = changeType)
            }
        }

        return ClipboardPayloadFormatter.PayloadFile(path, content = UNREADABLE_MARKER, changeType = changeType)
    }

    // Reason string embedded into the formatter's "// File skipped: <reason>" line.
    private fun sizeReason(bytes: Long): String = "size exceeds limit ($bytes bytes)"

    /**
     * [contentEntries] are the entries that really carried content — placeholders and size
     * skips are counted separately, never summarised as copied files.
     */
    private fun buildSummary(
        contentEntries: List<GitContentResolver.ResolvedGitEntry>,
        deletedMarkerEntries: List<GitContentResolver.ResolvedGitEntry>,
        skippedSizeCount: Int,
        skippedUnreadableCount: Int
    ): String {
        // hasVirtualFileContent 讀 VirtualFile 的 isValid/exists，也要在 read lock 內
        val (filesFromDisk, filesFromHistory) = ApplicationManager.getApplication().runReadAction<Pair<Int, Int>> {
            contentEntries.count { it.hasVirtualFileContent } to
                contentEntries.count { !it.hasVirtualFileContent && it.contentFromRevision != null }
        }
        val copiedWithContent = contentEntries.size
        val totalCopied = copiedWithContent + deletedMarkerEntries.size
        val skipped = listOfNotNull(
            "$skippedSizeCount skipped: size exceeded".takeIf { skippedSizeCount > 0 },
            "$skippedUnreadableCount skipped: not UTF-8 text or unreadable".takeIf { skippedUnreadableCount > 0 }
        )
        val skippedSuffix = if (skipped.isEmpty()) "" else " (${skipped.joinToString(", ")})"

        return when {
            contentEntries.isEmpty() && deletedMarkerEntries.isEmpty() ->
                "0 files copied$skippedSuffix."
            contentEntries.isEmpty() && deletedMarkerEntries.size == 1 ->
                "1 deleted file marker copied$skippedSuffix."
            contentEntries.isEmpty() ->
                "${deletedMarkerEntries.size} deleted file markers copied$skippedSuffix."
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
