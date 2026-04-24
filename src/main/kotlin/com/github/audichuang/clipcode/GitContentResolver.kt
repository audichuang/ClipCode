package com.github.audichuang.clipcode

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import git4idea.GitContentRevision
import git4idea.GitRevisionNumber
import git4idea.GitUtil
import java.io.File

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
                resolveChange(project, change, filePath, changeType)
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
        changeType: ChangeTypeLabel
    ): ResolvedGitEntry {
        val virtualFile = if (changeType == ChangeTypeLabel.DELETED) {
            null
        } else {
            change.afterRevision?.file?.virtualFile
                ?: change.beforeRevision?.file?.virtualFile
                ?: findFile(filePath)
                ?: findFile(filePath.replace('\\', '/'))
        }

        val contentFromRevision = if (virtualFile == null || changeType == ChangeTypeLabel.DELETED) {
            readRevisionContent(change) ?: if (changeType == ChangeTypeLabel.DELETED) {
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

    private fun readRevisionContent(change: Change): String? =
        try {
            change.afterRevision?.content ?: change.beforeRevision?.content
        } catch (e: Exception) {
            logger.warn("Failed to read Git content revision", e)
            null
        }

    private fun resolveDeletedContent(project: Project, absolutePath: String): String? {
        val matchingChange = ChangeListManager.getInstance(project).allChanges.find { change ->
            val changePath = change.beforeRevision?.file?.path ?: change.afterRevision?.file?.path
            changePath == absolutePath
        }

        val fromChangeList = matchingChange?.let(::readRevisionContent)
        if (fromChangeList != null) {
            return fromChangeList
        }

        return getFileContentFromGit(project, absolutePath)
    }

    private fun getFileContentFromGit(project: Project, absolutePath: String): String? {
        var repositoryRoot: String? = null
        var relativePath: String? = null
        try {
            val parentPath = File(absolutePath).parent ?: return null
            val parentDir = findFile(parentPath) ?: return null
            val repository = GitUtil.getRepositoryManager(project).getRepositoryForFile(parentDir) ?: return null
            val resolvedRepositoryRoot = repository.root.path
            val normalizedAbsolutePath = absolutePath.replace('\\', '/')
            val normalizedRepositoryRoot = resolvedRepositoryRoot.replace('\\', '/')
            if (normalizedAbsolutePath != normalizedRepositoryRoot && !normalizedAbsolutePath.startsWith("$normalizedRepositoryRoot/")) {
                return null
            }
            val resolvedRelativePath = normalizedAbsolutePath
                .removePrefix(normalizedRepositoryRoot)
                .trimStart('/')
                .takeIf(String::isNotBlank)
                ?: return null
            repositoryRoot = resolvedRepositoryRoot
            relativePath = resolvedRelativePath

            val filePath = LocalFilePath(File(absolutePath).toPath(), false)
            val contentRevision = GitContentRevision.createRevision(filePath, GitRevisionNumber.HEAD, project)
            return contentRevision.content ?: getFileContentFromGitCommand(resolvedRepositoryRoot, resolvedRelativePath)
        } catch (e: Exception) {
            logger.warn("Failed to read deleted file content via Git API", e)
        }

        return if (repositoryRoot != null && relativePath != null) {
            getFileContentFromGitCommand(repositoryRoot, relativePath)
        } else {
            null
        }
    }

    private fun getFileContentFromGitCommand(repositoryRoot: String, relativePath: String): String? {
        return try {
            val process = ProcessBuilder("git", "show", "HEAD:$relativePath")
                .directory(File(repositoryRoot))
                .redirectErrorStream(false)
                .start()

            val content = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            if (exitCode == 0 && content.isNotEmpty()) {
                content
            } else {
                null
            }
        } catch (e: Exception) {
            logger.warn("Failed to read deleted file content via git show", e)
            null
        }
    }

    private fun findFile(path: String): VirtualFile? =
        LocalFileSystem.getInstance().findFileByPath(path)
            ?: LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
}
