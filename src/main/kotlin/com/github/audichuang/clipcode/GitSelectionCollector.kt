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
import git4idea.index.ui.GitFileStatusNode
import git4idea.index.ui.NodeKind
import javax.swing.JTree
import javax.swing.tree.TreePath

enum class SelectionSource {
    LOCAL_CHANGES_OR_COMMIT_UI,
    GIT_LOG_OR_HISTORY,
    UNKNOWN
}

class GitSelectionCollector(
    private val logger: Logger,
    private val localChangesProvider: (com.intellij.openapi.project.Project) -> Collection<Change> = {
        ChangeListManager.getInstance(it).allChanges
    }
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
        val gitStatusNodes: Set<GitStatusInfo>,
        val source: SelectionSource
    ) {
        val hasGitMetadata: Boolean
            get() = changes.isNotEmpty() || untrackedPaths.isNotEmpty() || gitStatusNodes.isNotEmpty()
    }

    fun collect(e: AnActionEvent): Selection {
        val project = e.project
        val allChangesMap = linkedMapOf<String, Change>()
        val untrackedFilePaths = linkedSetOf<String>()
        val gitStatusNodes = linkedSetOf<GitStatusInfo>()
        val commitWorkflowHandler = e.getData(VcsDataKeys.COMMIT_WORKFLOW_HANDLER) as? AbstractCommitWorkflowHandler<*, *>
        val commitWorkflowUi: CommitWorkflowUi? = e.getData(VcsDataKeys.COMMIT_WORKFLOW_UI)
            ?: commitWorkflowHandler?.ui
        val hasCommitWorkflowHint = commitWorkflowUi != null || commitWorkflowHandler != null

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

        val changes = allChangesMap.values.toList()
        return Selection(
            changes = changes,
            selectedFiles = selectedFiles,
            untrackedPaths = untrackedFilePaths,
            gitStatusNodes = gitStatusNodes,
            source = detectSource(
                project = project,
                component = component,
                changes = changes,
                hasCommitWorkflowHint = hasCommitWorkflowHint
            )
        )
    }

    private fun detectSource(
        project: com.intellij.openapi.project.Project?,
        component: Any?,
        changes: List<Change>,
        hasCommitWorkflowHint: Boolean
    ): SelectionSource {
        if (hasCommitWorkflowHint) {
            return SelectionSource.LOCAL_CHANGES_OR_COMMIT_UI
        }

        if (changes.isEmpty()) {
            return SelectionSource.UNKNOWN
        }

        if (project != null && component is ChangesTree && changes.any { matchesLocalChange(project, it) }) {
            return SelectionSource.LOCAL_CHANGES_OR_COMMIT_UI
        }

        return SelectionSource.GIT_LOG_OR_HISTORY
    }

    private fun matchesLocalChange(
        project: com.intellij.openapi.project.Project,
        selectedChange: Change
    ): Boolean =
        try {
            val selectedPaths = selectedChange.normalizedPaths()
            localChangesProvider(project).any { localChange ->
                localChange == selectedChange || localChange.normalizedPaths().any { it in selectedPaths }
            }
        } catch (e: Exception) {
            logger.warn("Failed to compare selected Git change with local changes", e)
            false
        }

    private fun Change.normalizedPaths(): Set<String> =
        listOfNotNull(afterRevision?.file?.path, beforeRevision?.file?.path)
            .map { it.replace('\\', '/') }
            .toSet()

    private fun collectGitStatusNode(
        userObject: Any?,
        untrackedFilePaths: MutableSet<String>,
        gitStatusNodes: MutableSet<GitStatusInfo>
    ) {
        if (userObject !is GitFileStatusNode) {
            return
        }

        val filePath = userObject.status.path.path
        if (filePath.isBlank()) {
            return
        }

        when (userObject.kind) {
            NodeKind.UNTRACKED -> {
                untrackedFilePaths.add(filePath)
            }
            NodeKind.STAGED, NodeKind.UNSTAGED -> {
                val isStaged = userObject.kind == NodeKind.STAGED
                val code = if (isStaged) userObject.status.index else userObject.status.workTree
                // Git index status codes: M/A/D/R/C/T (typechange) — 對應 GitIndexStatusUtil.kt 的分類
                val normalizedStatus = when (code) {
                    'D' -> "DELETED"
                    'M', 'T' -> "MODIFIED"
                    'A' -> "ADDED"
                    'R', 'C' -> "MOVED"
                    else -> null
                }
                if (normalizedStatus != null) {
                    gitStatusNodes.add(GitStatusInfo(filePath, normalizedStatus, isStaged))
                }
            }
            NodeKind.CONFLICTED, NodeKind.IGNORED -> {
                // Skip — conflicted/ignored 不在 copy 範圍內
            }
        }
    }

    @IdeBoundCode
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

    @IdeBoundCode
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
