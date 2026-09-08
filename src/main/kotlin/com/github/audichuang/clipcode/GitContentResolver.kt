package com.github.audichuang.clipcode

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vcs.impl.ContentRevisionCache
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import git4idea.GitContentRevision
import git4idea.GitRevisionNumber
import git4idea.GitUtil
import git4idea.index.GitIndexUtil
import java.io.File
import com.intellij.openapi.vcs.VcsException

class GitContentResolver(
    private val logger: Logger
) {
    data class ResolvedGitEntry(
        val changeType: ChangeTypeLabel,
        val filePath: String,
        val virtualFile: VirtualFile?,
        val contentFromRevision: String? = null
    ) {
        val hasVirtualFileContent: Boolean
            get() = virtualFile != null && virtualFile.isValid && virtualFile.exists()

        val hasContent: Boolean
            get() = hasVirtualFileContent || contentFromRevision != null
    }

    fun resolve(
        project: Project,
        selection: GitSelectionCollector.Selection
    ): List<ResolvedGitEntry> {
        val entriesByPath = linkedMapOf<String, ResolvedGitEntry>()
        val statusPaths = selection.gitStatusNodes.mapTo(hashSetOf()) { it.path }

        val batchContents = GitBatchContentReader.read(project, selection.changes
            .filter { (it.afterRevision?.file?.path ?: it.beforeRevision?.file?.path) !in statusPaths }
            .distinctBy { it.afterRevision?.file?.path ?: it.beforeRevision?.file?.path }
            .mapNotNull { if (it.type == Change.Type.DELETED) it.beforeRevision else it.afterRevision })

        selection.changes.forEach { change ->
            ProgressManager.checkCanceled()
            val filePath = change.afterRevision?.file?.path ?: change.beforeRevision?.file?.path ?: return@forEach
            if (filePath in entriesByPath || filePath in statusPaths) return@forEach
            val changeType = ChangeTypeLabel.fromChangeType(change.type) ?: ChangeTypeLabel.MODIFIED
            entriesByPath[filePath] = resolveChange(project, change, filePath, changeType, selection.source, batchContents)
        }

        selection.untrackedPaths.forEach { untrackedPath ->
            ProgressManager.checkCanceled()
            if (entriesByPath.containsKey(untrackedPath) || untrackedPath in statusPaths) {
                return@forEach
            }

            val virtualFile = findFile(untrackedPath)
            if (virtualFile != null) {
                entriesByPath[untrackedPath] = ResolvedGitEntry(
                    changeType = ChangeTypeLabel.NEW,
                    filePath = untrackedPath,
                    virtualFile = virtualFile
                )
            }
        }

        selection.gitStatusNodes.forEach { statusInfo ->
            ProgressManager.checkCanceled()
            if (entriesByPath.containsKey(statusInfo.path)) {
                return@forEach
            }

            val changeType = when (statusInfo.status) {
                "DELETED" -> ChangeTypeLabel.DELETED
                "MODIFIED" -> ChangeTypeLabel.MODIFIED
                "ADDED" -> ChangeTypeLabel.NEW
                "MOVED" -> ChangeTypeLabel.MOVED
                else -> ChangeTypeLabel.MODIFIED
            }

            if (statusInfo.isStaged || changeType == ChangeTypeLabel.DELETED) {
                entriesByPath[statusInfo.path] = ResolvedGitEntry(
                    changeType = changeType,
                    filePath = statusInfo.path,
                    virtualFile = null,
                    // Staged: index after-content, or HEAD before-content for deletions.
                    // Unstaged deletion: its before-content is the index, not HEAD.
                    contentFromRevision = getFileContentFromGit(
                        project, statusInfo.path, fromIndex = changeType != ChangeTypeLabel.DELETED || !statusInfo.isStaged
                    )
                )
            } else {
                val virtualFile = findFile(statusInfo.path)
                if (virtualFile != null) {
                    entriesByPath[statusInfo.path] = ResolvedGitEntry(
                        changeType = changeType,
                        filePath = statusInfo.path,
                        virtualFile = virtualFile
                    )
                }
            }
        }

        return entriesByPath.values.toList()
    }

    private fun resolveChange(
        project: Project,
        change: Change,
        filePath: String,
        changeType: ChangeTypeLabel,
        source: SelectionSource,
        batchContents: Map<ContentRevision, String>
    ): ResolvedGitEntry {
        if (source != SelectionSource.LOCAL_CHANGES_OR_COMMIT_UI) {
            return ResolvedGitEntry(
                changeType = changeType,
                filePath = filePath,
                virtualFile = null,
                contentFromRevision = readSelectedRevisionContent(change, changeType, batchContents)
            )
        }

        // afterRevision 非本地（歷史 commit 的變更，例如 Vcs.Log 的變更樹因路徑跟本地
        // 變更重疊而被歸類為 LOCAL 來源）時，必須讀該版本內容，不能拿工作區檔案 —
        // 否則檔案另有未提交修改時會複製到現在的內容而非該 commit 的版本。
        val afterRevision = change.afterRevision
        val isHistoricalAfterRevision = changeType != ChangeTypeLabel.DELETED &&
            afterRevision != null && !isLocalRevision(afterRevision)

        val virtualFile = if (changeType == ChangeTypeLabel.DELETED || isHistoricalAfterRevision) {
            null
        } else {
            afterRevision?.file?.virtualFile
                ?: change.beforeRevision?.file?.virtualFile
                ?: findFile(filePath)
                ?: findFile(filePath.replace('\\', '/'))
        }

        val contentFromRevision = when {
            // 歷史版本只准讀 afterRevision：讀不到就標記為無內容，
            // 不 fallback 到 beforeRevision（那是 base 版本，內容是錯的）
            isHistoricalAfterRevision -> batchContents[afterRevision] ?: readRevisionContent(afterRevision)
            virtualFile == null || changeType == ChangeTypeLabel.DELETED ->
                (batchContents[change.afterRevision] ?: batchContents[change.beforeRevision] ?: readAnyRevisionContent(change)) ?: if (changeType == ChangeTypeLabel.DELETED) {
                    resolveDeletedContent(project, filePath)
                } else {
                    null
                }
            else -> null
        }

        return ResolvedGitEntry(
            changeType = changeType,
            filePath = filePath,
            virtualFile = virtualFile,
            contentFromRevision = contentFromRevision
        )
    }

    private fun readSelectedRevisionContent(
        change: Change,
        changeType: ChangeTypeLabel,
        batchContents: Map<ContentRevision, String>
    ): String? {
        val revision = if (changeType == ChangeTypeLabel.DELETED) {
            change.beforeRevision
        } else {
            change.afterRevision
        }

        if (isLocalRevision(revision)) {
            logger.warn("Refusing to read local Git revision for non-local selection: ${revision?.file?.path}")
            return null
        }

        return batchContents[revision] ?: readRevisionContent(revision)
    }

    private fun readAnyRevisionContent(change: Change): String? =
        readRevisionContent(change.afterRevision) ?: readRevisionContent(change.beforeRevision)

    private fun readRevisionContent(revision: ContentRevision?): String? =
        try {
            revision?.content
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Failed to read Git content revision", e)
            null
        }

    private fun isLocalRevision(revision: ContentRevision?): Boolean {
        if (revision == null) {
            return false
        }
        if (revision is CurrentContentRevision) {
            return true
        }
        val revisionNumber = revision.revisionNumber
        return revisionNumber == VcsRevisionNumber.NULL ||
            revisionNumber.asString().equals("LOCAL", ignoreCase = true)
    }

    @IdeBoundCode
    private fun resolveDeletedContent(project: Project, absolutePath: String): String? {
        val matchingChange = ChangeListManager.getInstance(project).allChanges.find { change ->
            val changePath = change.beforeRevision?.file?.path ?: change.afterRevision?.file?.path
            changePath == absolutePath
        }

        val fromChangeList = matchingChange?.let(::readAnyRevisionContent)
        if (fromChangeList != null) {
            return fromChangeList
        }

        return getFileContentFromGit(project, absolutePath)
    }

    @IdeBoundCode
    private fun getFileContentFromGit(project: Project, absolutePath: String, fromIndex: Boolean = false): String? {
        return try {
            val filePath = LocalFilePath(File(absolutePath).toPath(), false)
            val repository = GitUtil.getRepositoryManager(project).getRepositoryForFile(filePath) ?: return null
            val normalizedAbsolutePath = absolutePath.replace('\\', '/')
            val normalizedRepositoryRoot = repository.root.path.replace('\\', '/')
            if (normalizedAbsolutePath != normalizedRepositoryRoot &&
                !normalizedAbsolutePath.startsWith("$normalizedRepositoryRoot/")
            ) {
                return null
            }

            if (fromIndex) {
                val isBinary = ApplicationManager.getApplication().runReadAction<Boolean> {
                    val fileType = filePath.fileType
                    // A deleted source file can have UnknownFileType without a VFS file.
                    fileType.isBinary && (fileType != UnknownFileType.INSTANCE || filePath.virtualFile != null)
                }
                if (isBinary) return null
                val relativePath = normalizedAbsolutePath.removePrefix("$normalizedRepositoryRoot/")
                ContentRevisionCache.getAsString(GitIndexUtil.read(repository, ":$relativePath"), filePath, null)
            } else {
                GitContentRevision.createRevision(filePath, GitRevisionNumber.HEAD, project).content
            }
        } catch (e: VcsException) {
            logger.warn("Failed to read Git content via Git API", e)
            null
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Unexpected error reading Git content", e)
            null
        }
    }

    private fun findFile(path: String): VirtualFile? =
        LocalFileSystem.getInstance().findFileByPath(path)
            ?: LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
}
