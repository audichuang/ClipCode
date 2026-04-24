package com.github.audichuang.clipcode

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode
import com.intellij.openapi.vcs.changes.ui.ChangesTree
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcs.commit.AbstractCommitWorkflowHandler
import com.intellij.vcs.commit.CommitWorkflowUi
import com.intellij.vcsUtil.VcsUtil
import javax.swing.JTree
import javax.swing.tree.TreePath

class GitSelectionCollector(
    private val logger: Logger
) {
    data class GitStatusInfo(
        val path: String,
        val status: String,
        val isStaged: Boolean = false
    )

    data class Selection(
        val changes: List<Change>,
        val selectedFiles: List<VirtualFile>,
        val untrackedPaths: Set<String>,
        val gitStatusNodes: Set<GitStatusInfo>
    ) {
        val hasGitMetadata: Boolean
            get() = changes.isNotEmpty() || untrackedPaths.isNotEmpty() || gitStatusNodes.isNotEmpty()
    }

    fun collect(e: AnActionEvent): Selection {
        val project = e.project
        val allChangesMap = linkedMapOf<String, Change>()
        val untrackedFilePaths = linkedSetOf<String>()
        val gitStatusNodes = linkedSetOf<GitStatusInfo>()

        fun addChange(change: Change) {
            val path = change.afterRevision?.file?.path ?: change.beforeRevision?.file?.path ?: return
            allChangesMap.putIfAbsent(path, change)
        }

        val component = e.getData(PlatformDataKeys.CONTEXT_COMPONENT)
        if (component is ChangesTree) {
            try {
                val selectedUserObjects = com.intellij.openapi.vcs.changes.ui.VcsTreeModelData
                    .selected(component)
                    .userObjects()

                selectedUserObjects.forEach { userObject ->
                    when (userObject) {
                        is Change -> addChange(userObject)
                        else -> collectGitStatusNode(userObject, untrackedFilePaths, gitStatusNodes)
                    }
                }
            } catch (e: Exception) {
                logger.warn("Failed to read selected Git nodes from ChangesTree", e)
            }
        }

        e.getData(VcsDataKeys.SELECTED_CHANGES)?.forEach(::addChange)
        e.getData(VcsDataKeys.CHANGE_LEAD_SELECTION)?.forEach(::addChange)
        e.getData(VcsDataKeys.CHANGES)?.forEach(::addChange)

        val selectedFiles = getSelectedFiles(e).toList()
        if (project != null && selectedFiles.isNotEmpty()) {
            val changeListManager = ChangeListManager.getInstance(project)
            selectedFiles.forEach { file ->
                val change = changeListManager.getChange(file)
                if (change != null) {
                    addChange(change)
                } else {
                    untrackedFilePaths.add(file.path)
                }
            }
        }

        if (project != null && allChangesMap.isEmpty() && untrackedFilePaths.isEmpty()) {
            val commitWorkflowUi: CommitWorkflowUi? = e.getData(VcsDataKeys.COMMIT_WORKFLOW_UI)
                ?: (e.getData(VcsDataKeys.COMMIT_WORKFLOW_HANDLER) as? AbstractCommitWorkflowHandler<*, *>)?.ui

            val includedChanges = commitWorkflowUi?.getIncludedChanges() ?: emptyList()
            if (includedChanges.isNotEmpty() && selectedFiles.isNotEmpty()) {
                val selectedFilePaths = selectedFiles.map(VcsUtil::getFilePath).toSet()
                includedChanges
                    .filter { change ->
                        val changeFilePath = change.afterRevision?.file ?: change.beforeRevision?.file
                        changeFilePath != null && changeFilePath in selectedFilePaths
                    }
                    .forEach(::addChange)
            }
        }

        return Selection(
            changes = allChangesMap.values.toList(),
            selectedFiles = selectedFiles,
            untrackedPaths = untrackedFilePaths,
            gitStatusNodes = gitStatusNodes
        )
    }

    private fun collectGitStatusNode(
        userObject: Any?,
        untrackedFilePaths: MutableSet<String>,
        gitStatusNodes: MutableSet<GitStatusInfo>
    ) {
        if (userObject == null) {
            return
        }

        try {
            val statusObject = userObject.javaClass.getMethod("getStatus").invoke(userObject)
            val statusText = statusObject?.toString().orEmpty()
            val workTreeCode = Regex("workTree=([A-Z?])").find(statusText)?.groupValues?.get(1)?.trim()
            val indexCode = Regex("index=([A-Z?])").find(statusText)?.groupValues?.get(1)?.trim()
            val nodeText = userObject.toString()
            val kind = Regex("kind=([A-Z]+)").find(nodeText)?.groupValues?.get(1)
            val isStaged = kind == "STAGED"
            val effectiveCode = if (isStaged) indexCode else workTreeCode
            val normalizedStatus = when (effectiveCode) {
                "D" -> "DELETED"
                "M" -> "MODIFIED"
                "A" -> "ADDED"
                "?" -> "UNTRACKED"
                else -> when {
                    workTreeCode == "D" || indexCode == "D" -> "DELETED"
                    workTreeCode == "M" || indexCode == "M" -> "MODIFIED"
                    workTreeCode == "A" || indexCode == "A" -> "ADDED"
                    workTreeCode == "?" || indexCode == "?" -> "UNTRACKED"
                    else -> null
                }
            }

            val filePath = userObject.javaClass.getMethod("getFilePath").invoke(userObject)?.toString()
                ?: Regex("path=([^,)]+)").find(nodeText)?.groupValues?.get(1)

            if (filePath.isNullOrBlank()) {
                return
            }

            if (normalizedStatus == null || normalizedStatus == "UNTRACKED") {
                untrackedFilePaths.add(filePath)
            } else {
                gitStatusNodes.add(GitStatusInfo(filePath, normalizedStatus, isStaged))
            }
        } catch (_: Exception) {
            val path = Regex("path=([^,)]+)").find(userObject.toString())?.groupValues?.get(1)
            if (!path.isNullOrBlank()) {
                untrackedFilePaths.add(path)
            }
        }
    }

    private fun getSelectedFiles(e: AnActionEvent): Array<VirtualFile> {
        val component = e.getData(PlatformDataKeys.CONTEXT_COMPONENT)

        if (component is ChangesTree) {
            try {
                val files = com.intellij.openapi.vcs.changes.ui.VcsTreeModelData
                    .selected(component)
                    .userObjects()
                    .mapNotNull { userObject ->
                        when (userObject) {
                            is Change -> userObject.virtualFile
                            is VirtualFile -> userObject
                            else -> null
                        }
                    }
                    .distinct()

                if (files.isNotEmpty()) {
                    return files.toTypedArray()
                }
            } catch (e: Exception) {
                logger.warn("Failed to derive selected files from ChangesTree", e)
            }
        }

        if (component is JTree) {
            val selectionPaths: Array<TreePath> = component.selectionPaths ?: emptyArray()
            if (selectionPaths.isNotEmpty()) {
                val files = mutableListOf<VirtualFile>()
                selectionPaths.forEach { path -> extractFilesFromNode(path.lastPathComponent, files) }
                if (files.isNotEmpty()) {
                    return files.distinct().toTypedArray()
                }
            }
        }

        e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.takeIf { it.isNotEmpty() }?.let { return it }
        e.getData(CommonDataKeys.VIRTUAL_FILE)?.let { return arrayOf(it) }
        return emptyArray()
    }

    private fun extractFilesFromNode(node: Any?, files: MutableList<VirtualFile>) {
        if (node !is ChangesBrowserNode<*>) {
            return
        }

        when (val userObject = node.userObject) {
            is Change -> userObject.virtualFile?.let(files::add)
            is VirtualFile -> files.add(userObject)
        }
    }
}
