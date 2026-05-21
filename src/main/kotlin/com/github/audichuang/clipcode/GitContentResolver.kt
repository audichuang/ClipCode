package com.github.audichuang.clipcode

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import git4idea.GitContentRevision
import git4idea.GitRevisionNumber
import git4idea.GitUtil
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

        selection.changes.forEach { change ->
            val filePath = change.afterRevision?.file?.path ?: change.beforeRevision?.file?.path ?: return@forEach
            val changeType = ChangeTypeLabel.fromChangeType(change.type) ?: ChangeTypeLabel.MODIFIED
            entriesByPath.putIfAbsent(
                filePath,
                resolveChange(project, change, filePath, changeType, selection.source)
            )
        }

        selection.untrackedPaths.forEach { untrackedPath ->
            if (entriesByPath.containsKey(untrackedPath)) {
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

            if (changeType == ChangeTypeLabel.DELETED) {
                entriesByPath[statusInfo.path] = ResolvedGitEntry(
                    changeType = changeType,
                    filePath = statusInfo.path,
                    virtualFile = null,
                    contentFromRevision = resolveDeletedContent(project, statusInfo.path)
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
        source: SelectionSource
    ): ResolvedGitEntry {
        if (source != SelectionSource.LOCAL_CHANGES_OR_COMMIT_UI) {
            return ResolvedGitEntry(
                changeType = changeType,
                filePath = filePath,
                virtualFile = null,
                contentFromRevision = readSelectedRevisionContent(change, changeType)
            )
        }

        val virtualFile = if (changeType == ChangeTypeLabel.DELETED) {
            null
        } else {
            change.afterRevision?.file?.virtualFile
                ?: change.beforeRevision?.file?.virtualFile
                ?: findFile(filePath)
                ?: findFile(filePath.replace('\\', '/'))
        }

        val contentFromRevision = if (virtualFile == null || changeType == ChangeTypeLabel.DELETED) {
            readAnyRevisionContent(change) ?: if (changeType == ChangeTypeLabel.DELETED) {
                resolveDeletedContent(project, filePath)
            } else {
                null
            }
        } else {
            null
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
        changeType: ChangeTypeLabel
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

        return readRevisionContent(revision)
    }

    private fun readAnyRevisionContent(change: Change): String? =
        readRevisionContent(change.afterRevision) ?: readRevisionContent(change.beforeRevision)

    private fun readRevisionContent(revision: ContentRevision?): String? =
        try {
            revision?.content
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
    private fun getFileContentFromGit(project: Project, absolutePath: String): String? {
        return try {
            val parentPath = File(absolutePath).parent ?: return null
            val parentDir = findFile(parentPath) ?: return null
            val repository = GitUtil.getRepositoryManager(project).getRepositoryForFile(parentDir) ?: return null
            val normalizedAbsolutePath = absolutePath.replace('\\', '/')
            val normalizedRepositoryRoot = repository.root.path.replace('\\', '/')
            if (normalizedAbsolutePath != normalizedRepositoryRoot &&
                !normalizedAbsolutePath.startsWith("$normalizedRepositoryRoot/")
            ) {
                return null
            }

            val filePath = LocalFilePath(File(absolutePath).toPath(), false)
            val contentRevision = GitContentRevision.createRevision(filePath, GitRevisionNumber.HEAD, project)
            contentRevision.content
        } catch (e: VcsException) {
            logger.warn("Failed to read deleted file content via Git API", e)
            null
        } catch (e: Exception) {
            logger.warn("Unexpected error reading Git HEAD content", e)
            null
        }
    }

    private fun findFile(path: String): VirtualFile? =
        LocalFileSystem.getInstance().findFileByPath(path)
            ?: LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
}
