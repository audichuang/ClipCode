package com.github.audichuang.clipcode

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode
import com.intellij.openapi.vcs.changes.ui.ChangesTree
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcs.commit.CommitWorkflowUi
import com.intellij.vcs.commit.AbstractCommitWorkflowHandler
import com.intellij.vcsUtil.VcsUtil
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.JTree
import javax.swing.tree.TreePath

class CopyGitFilesContentAction : AnAction() {
    private val logger = Logger.getInstance(CopyGitFilesContentAction::class.java)

    /**
     * Data class to hold Git status information from GitFileStatusNode.
     * Used for files in Git Staging Area that are not represented as Change objects.
     */
    data class GitStatusInfo(
        val path: String,
        val status: String,  // DELETED, MODIFIED, ADDED, etc.
        val isStaged: Boolean = false  // true if from STAGED area, false if from UNSTAGED/workTree
    )

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    /**
     * Get selected changes using a "Greedy/Shotgun" strategy to overcome IntelliJ API limitations.
     * It collects data from ALL available sources and merges them to ensure nothing is missed.
     *
     * Problem: In Commit Tool Window, DataKeys like SELECTED_CHANGES, CHANGES, and even
     * VIRTUAL_FILE_ARRAY may return incomplete results when multiple files are selected.
     * Additionally, UNTRACKED files appear as GitFileStatusNode, not Change objects.
     *
     * Solution: Collect from all sources without early returns, then union them with a Set.
     * For UNTRACKED files (GitFileStatusNode), create synthetic Change-like entries.
     * Priority: VcsTreeModelData > DataKeys > ChangeListManager > UI Fallback
     */
    private fun getSelectedChanges(e: AnActionEvent): List<Change> {
        val project = e.project ?: return emptyList()
        // 🔧 使用 Map 以檔案路徑為 key 進行去重（避免同一檔案從不同來源被重複加入）
        val allChangesMap = mutableMapOf<String, Change>()

        // Helper function to add change by path (deduplication)
        fun addChange(change: Change) {
            val path = change.afterRevision?.file?.path ?: change.beforeRevision?.file?.path
            if (path != null && !allChangesMap.containsKey(path)) {
                allChangesMap[path] = change
            }
        }

        // 🔧 策略 0 (最優先): 直接從 ChangesTree 使用 VcsTreeModelData
        // 這是最可靠的方式，因為它直接讀取 tree model 而非依賴 DataProvider
        val component = e.getData(PlatformDataKeys.CONTEXT_COMPONENT)
        logger.warn("DEBUG getSelectedChanges: component = ${component?.javaClass?.name}")

        // 收集 UNTRACKED 檔案的路徑（這些檔案不會有對應的 Change 物件）
        val untrackedFilePaths = mutableSetOf<String>()
        // 收集 GitFileStatusNode 中的非 UNTRACKED 檔案（如 DELETED, MODIFIED 等）
        val gitStatusNodes = mutableSetOf<GitStatusInfo>()

        if (component is ChangesTree) {
            try {
                val selectedUserObjects = com.intellij.openapi.vcs.changes.ui.VcsTreeModelData
                    .selected(component)
                    .userObjects()
                logger.warn("DEBUG VcsTreeModelData.selected().userObjects() size = ${selectedUserObjects.size}")

                for ((index, obj) in selectedUserObjects.withIndex()) {
                    logger.warn("DEBUG   userObject[$index]: ${obj?.javaClass?.name} = $obj")

                    when (obj) {
                        is Change -> {
                            addChange(obj)
                        }
                        else -> {
                            // 處理 GitFileStatusNode 等其他類型
                            // GitFileStatusNode 包含 status, kind, path 資訊，需要特別處理
                            try {
                                // 嘗試取得 status - GitFileStatusNode.getStatus() 回傳 GitFileStatus 物件
                                // GitFileStatus 包含 index 和 workTree 狀態碼：
                                // D=Deleted, M=Modified, A=Added, ?=Untracked, 空格=未變更
                                var normalizedStatus: String? = null
                                var isStaged = false  // 判斷是 STAGED 還是 UNSTAGED
                                try {
                                    val statusMethod = obj?.javaClass?.getMethod("getStatus")
                                    val statusObj = statusMethod?.invoke(obj)
                                    val statusStr = statusObj?.toString() ?: ""
                                    logger.warn("DEBUG   -> GitFileStatusNode status object: $statusStr")

                                    // 解析 GitFileStatus(index=X, workTree=Y, ...) 格式
                                    // 優先檢查 workTree，然後是 index
                                    val workTreeMatch = Regex("workTree=([A-Z?])").find(statusStr)
                                    val indexMatch = Regex("index=([A-Z?])").find(statusStr)

                                    val workTreeCode = workTreeMatch?.groupValues?.get(1)?.trim()
                                    val indexCode = indexMatch?.groupValues?.get(1)?.trim()

                                    // 判斷是來自 STAGED 還是 UNSTAGED
                                    // 從 obj.toString() 解析 kind=STAGED 或 kind=UNSTAGED
                                    val objStr = obj.toString()
                                    val kindMatch = Regex("kind=([A-Z]+)").find(objStr)
                                    val kind = kindMatch?.groupValues?.get(1)
                                    isStaged = (kind == "STAGED")
                                    logger.warn("DEBUG   -> Parsed kind: $kind, isStaged: $isStaged")

                                    // 根據 kind 決定使用哪個狀態碼
                                    // STAGED: 使用 indexCode
                                    // UNSTAGED: 使用 workTreeCode
                                    val effectiveCode = if (isStaged) indexCode else workTreeCode
                                    normalizedStatus = when (effectiveCode) {
                                        "D" -> "DELETED"
                                        "M" -> "MODIFIED"
                                        "A" -> "ADDED"
                                        "?" -> "UNTRACKED"
                                        else -> when {
                                            // Fallback: 如果 effectiveCode 不匹配，嘗試任一匹配
                                            workTreeCode == "D" || indexCode == "D" -> "DELETED"
                                            workTreeCode == "M" || indexCode == "M" -> "MODIFIED"
                                            workTreeCode == "A" || indexCode == "A" -> "ADDED"
                                            workTreeCode == "?" || indexCode == "?" -> "UNTRACKED"
                                            else -> null
                                        }
                                    }
                                    logger.warn("DEBUG   -> Normalized status: $normalizedStatus (workTree=$workTreeCode, index=$indexCode, isStaged=$isStaged)")
                                } catch (ex: Exception) {
                                    // 從 toString 解析 status（備用方案）
                                    val objStr = obj.toString()
                                    val statusMatch = Regex("status=([A-Z_]+)").find(objStr)
                                    normalizedStatus = statusMatch?.groupValues?.get(1)
                                    // 嘗試解析 kind
                                    val kindMatch = Regex("kind=([A-Z]+)").find(objStr)
                                    isStaged = (kindMatch?.groupValues?.get(1) == "STAGED")
                                    logger.warn("DEBUG   -> Fallback status from toString: $normalizedStatus, isStaged: $isStaged")
                                }

                                // 取得 filePath
                                val pathMethod = obj?.javaClass?.getMethod("getFilePath")
                                val filePath = pathMethod?.invoke(obj)
                                if (filePath != null) {
                                    val pathStr = filePath.toString()
                                    logger.warn("DEBUG   -> Extracted filePath: $pathStr")

                                    // 根據 status 決定如何處理
                                    if (normalizedStatus != null && normalizedStatus != "UNTRACKED") {
                                        // 非 UNTRACKED 的狀態（如 DELETED, MODIFIED, ADDED）
                                        gitStatusNodes.add(GitStatusInfo(pathStr, normalizedStatus, isStaged))
                                        logger.warn("DEBUG   -> Added to gitStatusNodes: $pathStr with status $normalizedStatus, isStaged=$isStaged")
                                    } else {
                                        // UNTRACKED 或無法取得狀態的檔案
                                        untrackedFilePaths.add(pathStr)
                                        logger.warn("DEBUG   -> Added to untrackedFilePaths: $pathStr")
                                    }
                                }
                            } catch (ex: Exception) {
                                // 嘗試其他方式取得路徑
                                val objStr = obj.toString()
                                val pathMatch = Regex("path=([^,)]+)").find(objStr)
                                if (pathMatch != null) {
                                    val pathStr = pathMatch.groupValues[1]
                                    logger.warn("DEBUG   -> Extracted path from toString: $pathStr")
                                    untrackedFilePaths.add(pathStr)
                                }
                            }
                        }
                    }
                }

                if (allChangesMap.isNotEmpty()) {
                    logger.warn("DEBUG Source VcsTreeModelData (ChangesTree): found ${allChangesMap.size} Changes")
                }
                if (untrackedFilePaths.isNotEmpty()) {
                    logger.warn("DEBUG Source VcsTreeModelData: found ${untrackedFilePaths.size} untracked file paths")
                }
            } catch (ex: Exception) {
                logger.warn("Failed to get selection from VcsTreeModelData: ${ex.message}")
            }
        }

        // 策略 1: 收集所有可能的 Change DataKeys (不進行數量驗證，全部收集)
        e.getData(VcsDataKeys.SELECTED_CHANGES)?.let {
            logger.warn("DEBUG Source SELECTED_CHANGES: found ${it.size}")
            it.forEach { change -> addChange(change) }
        }

        e.getData(VcsDataKeys.CHANGE_LEAD_SELECTION)?.let {
            logger.warn("DEBUG Source CHANGE_LEAD_SELECTION: found ${it.size}")
            it.forEach { change -> addChange(change) }
        }

        e.getData(VcsDataKeys.CHANGES)?.let {
            logger.warn("DEBUG Source CHANGES: found ${it.size}")
            it.forEach { change -> addChange(change) }
        }

        // 策略 2: 透過 VirtualFile 反查 Change (這是最強的補強)
        // 如果 DataKeys 的 Change 列表不完整，我們用選中的檔案去 ChangeListManager 查
        val selectedFiles = getSelectedFiles(e)
        logger.warn("DEBUG getSelectedFiles returned: ${selectedFiles.size} files")
        if (selectedFiles.isNotEmpty()) {
            val changeListManager = ChangeListManager.getInstance(project)
            val changesFromFiles = selectedFiles.mapNotNull { changeListManager.getChange(it) }

            logger.warn("DEBUG Source VirtualFiles -> ChangeListManager: found ${changesFromFiles.size}")
            changesFromFiles.forEach { change -> addChange(change) }

            // 🔧 對於 UNTRACKED 檔案，ChangeListManager.getChange() 會回傳 null
            // 我們需要把這些檔案加到 untrackedFilePaths
            for (file in selectedFiles) {
                if (changeListManager.getChange(file) == null) {
                    untrackedFilePaths.add(file.path)
                    logger.warn("DEBUG   File not in ChangeListManager (likely UNTRACKED): ${file.path}")
                }
            }
        }

        // 策略 3: 如果以上全部加起來還是空的，才嘗試 UI Fallback
        if (allChangesMap.isEmpty() && untrackedFilePaths.isEmpty()) {
            val uiFromKey: CommitWorkflowUi? = e.getData(VcsDataKeys.COMMIT_WORKFLOW_UI)
                ?: (e.getData(VcsDataKeys.COMMIT_WORKFLOW_HANDLER) as? AbstractCommitWorkflowHandler<*, *>)?.ui

            val uiChanges = uiFromKey?.getIncludedChanges() ?: emptyList()
            if (uiChanges.isNotEmpty() && selectedFiles.isNotEmpty()) {
                val selectedFilePaths = selectedFiles.map { VcsUtil.getFilePath(it) }.toSet()
                val matchedChanges = uiChanges.filter { change ->
                    val changeFilePath = change.afterRevision?.file ?: change.beforeRevision?.file
                    changeFilePath != null && selectedFilePaths.contains(changeFilePath)
                }
                logger.warn("DEBUG Source UI Fallback: matched ${matchedChanges.size}")
                matchedChanges.forEach { change -> addChange(change) }
            }
        }

        logger.warn("DEBUG Final merged changes count: ${allChangesMap.size}, untracked paths: ${untrackedFilePaths.size}, git status nodes: ${gitStatusNodes.size}")

        // 儲存 untracked 檔案路徑供 actionPerformed 使用
        this.pendingUntrackedPaths = untrackedFilePaths
        // 儲存 GitFileStatusNode 資訊供 actionPerformed 使用
        this.pendingGitStatusNodes = gitStatusNodes

        return allChangesMap.values.toList()
    }

    // 暫存 UNTRACKED 檔案路徑
    private var pendingUntrackedPaths: Set<String> = emptySet()
    // 暫存 GitFileStatusNode 資訊（非 UNTRACKED 狀態的檔案，如 DELETED, MODIFIED 等）
    private var pendingGitStatusNodes: Set<GitStatusInfo> = emptySet()

    /**
     * Get selected files as fallback for Git Staging Area.
     * When VcsDataKeys.CHANGES is not available, try CommonDataKeys.
     *
     * IMPORTANT: In Commit Tool Window, DataKeys may return incomplete selection.
     * This method prioritizes VcsTreeModelData which is the most reliable source.
     */
    private fun getSelectedFiles(e: AnActionEvent): Array<VirtualFile> {
        val component = e.getData(PlatformDataKeys.CONTEXT_COMPONENT)

        // 1️⃣ 最優先：使用 VcsTreeModelData（最可靠，直接讀取 tree model）
        if (component is ChangesTree) {
            try {
                val selectedUserObjects = com.intellij.openapi.vcs.changes.ui.VcsTreeModelData
                    .selected(component)
                    .userObjects()
                val files = selectedUserObjects.mapNotNull { obj ->
                    when (obj) {
                        is Change -> obj.virtualFile
                        is VirtualFile -> obj
                        else -> null
                    }
                }.distinct()
                if (files.isNotEmpty()) {
                    logger.info("getSelectedFiles: Found ${files.size} files from VcsTreeModelData (most reliable)")
                    return files.toTypedArray()
                }
            } catch (ex: Exception) {
                logger.warn("Failed to get selection from VcsTreeModelData: ${ex.message}")
            }
        }

        // 2️⃣ 備援：從 JTree 直接取得選中的節點
        if (component is JTree) {
            val paths: Array<TreePath>? = component.selectionPaths
            if (paths != null && paths.isNotEmpty()) {
                val files = mutableListOf<VirtualFile>()
                for (path in paths) {
                    val node = path.lastPathComponent
                    extractFilesFromNode(node, files)
                }
                if (files.isNotEmpty()) {
                    val distinctFiles = files.distinct()
                    logger.info("getSelectedFiles: Found ${distinctFiles.size} files from JTree selection (${paths.size} paths)")
                    return distinctFiles.toTypedArray()
                }
            }
        }

        // 3️⃣ Try VIRTUAL_FILE_ARRAY (common in tree views)
        e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.let {
            if (it.isNotEmpty()) {
                logger.info("getSelectedFiles: Found ${it.size} files from VIRTUAL_FILE_ARRAY")
                return it
            }
        }

        // 4️⃣ Try single file
        e.getData(CommonDataKeys.VIRTUAL_FILE)?.let {
            logger.info("getSelectedFiles: Found single file from VIRTUAL_FILE: ${it.path}")
            return arrayOf(it)
        }

        logger.info("getSelectedFiles: No files found")
        return emptyArray()
    }

    /**
     * Recursively extract VirtualFiles from tree nodes.
     */
    private fun extractFilesFromNode(node: Any?, files: MutableList<VirtualFile>) {
        when (node) {
            is ChangesBrowserNode<*> -> {
                val userObject = node.userObject
                when (userObject) {
                    is Change -> {
                        userObject.virtualFile?.let { files.add(it) }
                    }
                    is VirtualFile -> {
                        files.add(userObject)
                    }
                }
                // 如果是目錄節點，可能需要遍歷子節點
                // 但通常選中的檔案節點就足夠了
            }
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: run {
            CopyFileContentAction.showNotification("No project found. Action cannot proceed.", NotificationType.ERROR, null)
            return
        }

        // 🔍 DEBUG: 檢查 component 類型
        val component = e.getData(PlatformDataKeys.CONTEXT_COMPONENT)
        logger.warn("DEBUG actionPerformed: component type = ${component?.javaClass?.name}")

        // Get selected changes from Git Staging Area, Commit UI, or Changes view
        val selectedChanges = getSelectedChanges(e)
        logger.warn("DEBUG actionPerformed: selectedChanges.size = ${selectedChanges.size}")
        selectedChanges.forEachIndexed { index, change ->
            logger.warn("DEBUG   Change[$index]: ${change.afterRevision?.file?.path ?: change.beforeRevision?.file?.path}")
        }

        // If no changes found, check if we have GitFileStatusNodes (e.g., DELETED files in unstaged area)
        // Only fallback to selectedFiles if we also don't have any pending git status nodes
        if (selectedChanges.isEmpty() && pendingGitStatusNodes.isEmpty() && pendingUntrackedPaths.isEmpty()) {
            val selectedFiles = getSelectedFiles(e)
            logger.warn("DEBUG actionPerformed: Fallback - selectedFiles.size = ${selectedFiles.size}")

            if (selectedFiles.isEmpty()) {
                CopyFileContentAction.showNotification(
                    "No files selected in Git commit/staging view.",
                    NotificationType.WARNING,
                    project
                )
                return
            }

            // Use CopyFileContentAction directly with selected files (without change type labels)
            val copyFileContentAction = CopyFileContentAction()
            copyFileContentAction.performCopyFilesContent(e, selectedFiles, null)
            return
        }

        // Process changes with type information
        val changeInfoList = mutableListOf<ChangeInfo>()

        for (change in selectedChanges) {
            val changeType = getChangeTypeLabel(change)
            val filePath = change.afterRevision?.file?.path ?: change.beforeRevision?.file?.path

            if (filePath == null) {
                logger.info("Skipping change: Unable to determine file path")
                continue
            }

            // Try to get virtualFile from change with multi-step fallback
            var virtualFile = change.afterRevision?.file?.virtualFile
                ?: change.beforeRevision?.file?.virtualFile

            // 用於儲存從 Git 歷史讀取的內容（當 virtualFile 無法解析時）
            var contentFromRevision: String? = null

            // If virtualFile is null, try multiple resolution strategies (IntelliJ 2025 fix)
            if (virtualFile == null && change.type != Change.Type.DELETED) {
                // 嘗試 1: 直接查找（快速，不強制 VFS 刷新）
                virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath)

                // 嘗試 2: 正規化路徑後查找（解決 Windows 路徑分隔符問題）
                if (virtualFile == null) {
                    val normalizedPath = filePath.replace('\\', '/')
                    virtualFile = LocalFileSystem.getInstance().findFileByPath(normalizedPath)
                }

                // 嘗試 3: 強制刷新 VFS（最後手段，較慢但可靠）
                if (virtualFile == null) {
                    virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(filePath)
                }

                if (virtualFile != null) {
                    logger.info("Resolved virtualFile after fallback resolution: $filePath")
                }

                // 🔧 嘗試 4: 從 Git ContentRevision 讀取內容（適用於 Git Log 歷史版本）
                if (virtualFile == null) {
                    try {
                        contentFromRevision = change.afterRevision?.content
                            ?: change.beforeRevision?.content
                        if (contentFromRevision != null) {
                            logger.info("Resolved content from ContentRevision (Git history): $filePath")
                        }
                    } catch (ex: Exception) {
                        // 捕捉所有異常（VcsException, IOException, RuntimeException 等）
                        logger.warn("Failed to get content from ContentRevision: ${ex.message}")
                    }
                }
            }

            changeInfoList.add(ChangeInfo(
                change = change,
                changeType = changeType,
                filePath = filePath,
                virtualFile = virtualFile,
                contentFromRevision = contentFromRevision
            ))
        }

        // 🔧 處理 UNTRACKED 檔案（這些檔案沒有對應的 Change 物件）
        val untrackedPaths = pendingUntrackedPaths
        logger.warn("DEBUG Processing ${untrackedPaths.size} untracked file paths")
        for (untrackedPath in untrackedPaths) {
            // 檢查是否已經在 changeInfoList 中
            if (changeInfoList.any { it.filePath == untrackedPath }) {
                logger.warn("DEBUG   Skipping duplicate untracked path: $untrackedPath")
                continue
            }

            var virtualFile = LocalFileSystem.getInstance().findFileByPath(untrackedPath)
            if (virtualFile == null) {
                virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(untrackedPath)
            }

            if (virtualFile != null) {
                logger.warn("DEBUG   Added untracked file: $untrackedPath")
                changeInfoList.add(ChangeInfo(
                    change = null,  // UNTRACKED 檔案沒有 Change 物件
                    changeType = ChangeTypeLabel.NEW.label,  // 標記為新檔案
                    filePath = untrackedPath,
                    virtualFile = virtualFile
                ))
            } else {
                logger.warn("DEBUG   Could not resolve untracked file: $untrackedPath")
            }
        }
        // 清空暫存
        pendingUntrackedPaths = emptySet()

        // 🔧 處理 GitFileStatusNode 檔案（這些檔案沒有 Change 物件，但有狀態資訊）
        val gitStatusInfos = pendingGitStatusNodes
        logger.warn("DEBUG Processing ${gitStatusInfos.size} GitFileStatusNode entries")
        for (statusInfo in gitStatusInfos) {
            // 檢查是否已經在 changeInfoList 中
            if (changeInfoList.any { it.filePath == statusInfo.path }) {
                logger.warn("DEBUG   Skipping duplicate GitFileStatusNode path: ${statusInfo.path}")
                continue
            }

            // 將 Git 狀態轉換為 ChangeTypeLabel
            val changeType = when (statusInfo.status) {
                "DELETED" -> ChangeTypeLabel.DELETED.label
                "MODIFIED" -> ChangeTypeLabel.MODIFIED.label
                "ADDED" -> ChangeTypeLabel.NEW.label
                else -> ChangeTypeLabel.MODIFIED.label  // 預設為 MODIFIED
            }

            // 對於 DELETED 檔案，嘗試從 Git 讀取內容
            if (statusInfo.status == "DELETED") {
                logger.warn("DEBUG   Processing DELETED file from GitFileStatusNode: ${statusInfo.path}")
                logger.warn("DEBUG   -> isStaged: ${statusInfo.isStaged}")
                var contentFromRevision: String? = null

                // 嘗試從 Git 讀取刪除前的內容
                try {
                    val filePath = VcsUtil.getFilePath(statusInfo.path)
                    val changeListManager = ChangeListManager.getInstance(project)
                    // 嘗試透過 ChangeListManager 取得 Change 物件
                    val changes = changeListManager.allChanges
                    logger.warn("DEBUG   -> ChangeListManager.allChanges count: ${changes.size}")
                    val matchingChange = changes.find { change ->
                        val changePath = change.beforeRevision?.file?.path ?: change.afterRevision?.file?.path
                        changePath == statusInfo.path
                    }
                    if (matchingChange != null) {
                        logger.warn("DEBUG   -> Found matching Change in ChangeListManager")
                        logger.warn("DEBUG   -> Change type: ${matchingChange.type}")
                        logger.warn("DEBUG   -> beforeRevision: ${matchingChange.beforeRevision}")
                        contentFromRevision = matchingChange.beforeRevision?.content
                        logger.warn("DEBUG   -> Content from beforeRevision available: ${contentFromRevision != null}")
                        if (contentFromRevision != null) {
                            logger.warn("DEBUG   -> Content length: ${contentFromRevision.length} chars")
                        }
                    } else {
                        logger.warn("DEBUG   -> No matching Change found in ChangeListManager")
                    }

                    // 🔧 Fallback: 使用 git show 命令讀取內容
                    // 對於 STAGED 的刪除：從 HEAD 讀取（因為 index 沒有這個檔案了）
                    // 對於 UNSTAGED 的刪除：從 HEAD 讀取（因為 workTree 沒有這個檔案了）
                    if (contentFromRevision == null) {
                        logger.warn("DEBUG   -> Trying git show to read deleted file content")
                        // 對於刪除的檔案，無論是 staged 還是 unstaged，都從 HEAD 讀取
                        // 因為刪除意味著檔案在目標位置（index 或 workTree）已經不存在了
                        contentFromRevision = getFileContentFromGit(project, statusInfo.path, fromIndex = false)
                        if (contentFromRevision != null) {
                            logger.warn("DEBUG   -> Successfully read content from git show HEAD:path")
                            logger.warn("DEBUG   -> Content length: ${contentFromRevision.length} chars")
                        } else {
                            logger.warn("DEBUG   -> Failed to read content from git show HEAD:path")
                        }
                    }
                } catch (ex: Exception) {
                    logger.warn("DEBUG   -> Failed to get content for DELETED file: ${ex.message}")
                    logger.warn("DEBUG   -> Exception type: ${ex.javaClass.name}")
                }

                changeInfoList.add(ChangeInfo(
                    change = null,
                    changeType = changeType,
                    filePath = statusInfo.path,
                    virtualFile = null,  // DELETED 檔案在磁碟上不存在
                    contentFromRevision = contentFromRevision
                ))
                logger.warn("DEBUG   -> Added DELETED file to changeInfoList: ${statusInfo.path}, hasContent: ${contentFromRevision != null}")
            } else {
                // 對於非 DELETED 檔案，嘗試解析 VirtualFile
                var virtualFile = LocalFileSystem.getInstance().findFileByPath(statusInfo.path)
                if (virtualFile == null) {
                    virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(statusInfo.path)
                }

                if (virtualFile != null) {
                    logger.warn("DEBUG   Added ${statusInfo.status} file from GitFileStatusNode: ${statusInfo.path}")
                    changeInfoList.add(ChangeInfo(
                        change = null,
                        changeType = changeType,
                        filePath = statusInfo.path,
                        virtualFile = virtualFile
                    ))
                } else {
                    logger.warn("DEBUG   Could not resolve GitFileStatusNode file: ${statusInfo.path}")
                }
            }
        }
        // 清空暫存
        pendingGitStatusNodes = emptySet()

        if (changeInfoList.isEmpty()) {
            CopyFileContentAction.showNotification("No files found in selection.", NotificationType.WARNING, project)
            return
        }

        // Separate deleted files from accessible files
        // 🔧 DELETED 檔案的處理邏輯：
        // - 如果有 contentFromRevision：放入 accessibleFiles（會顯示完整內容）
        // - 如果沒有 contentFromRevision：放入 deletedFiles（只顯示刪除標記）
        val deletedFilesWithoutContent = changeInfoList.filter {
            (it.change?.type == Change.Type.DELETED || it.changeType == ChangeTypeLabel.DELETED.label) &&
            it.contentFromRevision == null  // 只有沒有內容的 DELETED 檔案才放入 deletedFiles
        }
        val accessibleFiles = changeInfoList.filter {
            (it.virtualFile != null && it.virtualFile.isValid && it.virtualFile.exists()) ||
            it.contentFromRevision != null  // 包含有內容的 DELETED 檔案
        }
        // 為了向後兼容，保留 deletedFiles 變數名
        val deletedFiles = deletedFilesWithoutContent

        // Log files that couldn't be accessed and warn user
        // 🔧 同時檢查 DELETED 標籤
        val skippedFiles = changeInfoList.filter {
            it.change?.type != Change.Type.DELETED &&
            it.changeType != ChangeTypeLabel.DELETED.label &&
            (it.virtualFile == null || !it.virtualFile.isValid || !it.virtualFile.exists()) &&
            it.contentFromRevision == null  // 🔧 只有當也沒有 contentFromRevision 時才算 skipped
        }
        if (skippedFiles.isNotEmpty()) {
            val skippedPaths = skippedFiles.map { it.filePath.substringAfterLast('/') }
            logger.warn("Skipped ${skippedFiles.size} files due to missing virtualFile: ${skippedFiles.map { it.filePath }}")
            CopyFileContentAction.showNotification(
                "<html><b>${skippedFiles.size} files could not be resolved:</b><br>${skippedPaths.joinToString(", ")}</html>",
                NotificationType.WARNING,
                project
            )
        }

        val settings = CopyFileContentSettings.getInstance(project)

        // 🔧 分離出只有 contentFromRevision 的檔案（這些無法使用 performCopyFilesContent）
        val filesWithVirtualFile = accessibleFiles.filter {
            it.virtualFile != null && it.virtualFile.isValid && it.virtualFile.exists()
        }
        val filesWithOnlyRevisionContent = accessibleFiles.filter {
            it.contentFromRevision != null &&
            (it.virtualFile == null || !it.virtualFile.isValid || !it.virtualFile.exists())
        }

        // Case 1: Only files with virtualFile (no deleted, no revision-only) - use performCopyFilesContent
        if (filesWithVirtualFile.isNotEmpty() && deletedFiles.isEmpty() && filesWithOnlyRevisionContent.isEmpty()) {
            val copyFileContentAction = CopyFileContentAction()
            val virtualFiles = filesWithVirtualFile.mapNotNull { it.virtualFile }.toTypedArray()

            // Create custom header generator that includes change type
            val customHeaderGenerator: (VirtualFile, String) -> String = { file, relativePath ->
                // Use path comparison instead of object identity (VFS refresh may change reference)
                val changeInfo = accessibleFiles.find { it.virtualFile?.path == file.path }
                val changeTypeLabel = changeInfo?.changeType ?: ""
                val headerFormat = settings?.state?.headerFormat ?: "// file: \$FILE_PATH"

                if (changeTypeLabel.isNotEmpty()) {
                    headerFormat.replace("\$FILE_PATH", "$changeTypeLabel $relativePath")
                } else {
                    headerFormat.replace("\$FILE_PATH", relativePath)
                }
            }

            copyFileContentAction.performCopyFilesContent(e, virtualFiles, customHeaderGenerator)
            return
        }

        // Case 2 & 3: Only deleted files OR both accessible and deleted files
        // Build all content ourselves to avoid clipboard overwrite
        val fileContents = mutableListOf<String>()
        var skippedSizeCount = 0  // Track files skipped due to size limit

        if (settings != null && settings.state.preText.isNotEmpty()) {
            fileContents.add(settings.state.preText)
        }

        // Process accessible files (when there are also deleted files)
        for (accessibleInfo in accessibleFiles) {
            val relativePath = getRelativePath(project, accessibleInfo.filePath)
            val headerFormat = settings?.state?.headerFormat ?: "// file: \$FILE_PATH"
            val header = if (accessibleInfo.changeType.isNotEmpty()) {
                headerFormat.replace("\$FILE_PATH", "${accessibleInfo.changeType} $relativePath")
            } else {
                headerFormat.replace("\$FILE_PATH", relativePath)
            }

            fileContents.add(header)

            // ✅ 修復 OOM 風險：先檢查檔案大小
            val maxFileSizeBytes = settings?.state?.maxFileSizeKB?.times(1024L) ?: (500L * 1024L)

            // 🔧 優先使用 virtualFile，若無則使用 contentFromRevision（Git Log 歷史版本）
            val file = accessibleInfo.virtualFile
            if (file != null && file.isValid && file.exists()) {
                // 從 virtualFile 讀取（標準路徑）
                if (file.length > maxFileSizeBytes) {
                    logger.info("Skipping file in Git changes: ${accessibleInfo.filePath} - File size (${file.length} bytes) exceeds limit")
                    skippedSizeCount++
                    fileContents.add("// File skipped: size exceeds limit (${file.length} bytes)")
                } else {
                    try {
                        val content = String(file.contentsToByteArray(), Charsets.UTF_8)
                        fileContents.add(content)
                    } catch (ex: Exception) {
                        logger.warn("Failed to read file content: ${accessibleInfo.filePath}", ex)
                        fileContents.add("// Error reading file content")
                    }
                }
            } else if (accessibleInfo.contentFromRevision != null) {
                // 🔧 從 Git ContentRevision 讀取（Git Log 歷史版本）
                val content = accessibleInfo.contentFromRevision
                // 使用 UTF-8 byte 大小檢查（而非字元數），因為 maxFileSizeBytes 是 bytes
                val contentSizeBytes = content.toByteArray(Charsets.UTF_8).size.toLong()
                if (contentSizeBytes > maxFileSizeBytes) {
                    logger.info("Skipping file from Git history: ${accessibleInfo.filePath} - Content size ($contentSizeBytes bytes) exceeds limit")
                    skippedSizeCount++
                    fileContents.add("// File skipped: size exceeds limit ($contentSizeBytes bytes)")
                } else {
                    fileContents.add(content)
                }
            } else {
                // 無法讀取內容
                logger.warn("No content available for: ${accessibleInfo.filePath}")
                fileContents.add("// Unable to read file content")
            }

            if (settings?.state?.addExtraLineBetweenFiles == true) {
                fileContents.add("")
            }
        }

        // Process deleted files
        for (deletedInfo in deletedFiles) {
            val relativePath = getRelativePath(project, deletedInfo.filePath)
            val headerFormat = settings?.state?.headerFormat ?: "// file: \$FILE_PATH"
            val header = headerFormat.replace("\$FILE_PATH", "${deletedInfo.changeType} $relativePath")

            fileContents.add(header)
            fileContents.add("// This file has been deleted in this change")

            if (settings?.state?.addExtraLineBetweenFiles == true) {
                fileContents.add("")
            }
        }

        if (settings != null && settings.state.postText.isNotEmpty()) {
            fileContents.add(settings.state.postText)
        }

        val clipboardText = fileContents.joinToString(separator = "\n")
        copyToClipboard(clipboardText)

        // Show notification
        if (settings?.state?.showCopyNotification == true) {
            val fromDiskCount = filesWithVirtualFile.size - skippedSizeCount.coerceAtMost(filesWithVirtualFile.size)
            val fromGitHistoryCount = filesWithOnlyRevisionContent.size
            val actualCopied = accessibleFiles.size - skippedSizeCount
            val totalFiles = actualCopied + deletedFiles.size

            // Build skipped suffix if any files were skipped
            val skippedSuffix = if (skippedSizeCount > 0) " ($skippedSizeCount skipped: size exceeded)" else ""

            val message = when {
                accessibleFiles.isEmpty() && deletedFiles.size == 1 ->
                    "1 deleted file marker copied."
                accessibleFiles.isEmpty() ->
                    "${deletedFiles.size} deleted file markers copied."
                deletedFiles.isEmpty() && actualCopied == 1 && fromGitHistoryCount == 1 ->
                    "1 file copied (from Git history)$skippedSuffix."
                deletedFiles.isEmpty() && actualCopied == 1 ->
                    "1 file copied$skippedSuffix."
                deletedFiles.isEmpty() && fromGitHistoryCount > 0 ->
                    "$actualCopied files copied ($fromDiskCount from disk, $fromGitHistoryCount from Git history)$skippedSuffix."
                deletedFiles.isEmpty() ->
                    "$actualCopied files copied$skippedSuffix."
                fromGitHistoryCount > 0 ->
                    "$totalFiles files copied ($fromDiskCount from disk, $fromGitHistoryCount from Git history, ${deletedFiles.size} deleted)$skippedSuffix."
                else ->
                    "$totalFiles files copied ($actualCopied with content, ${deletedFiles.size} deleted)$skippedSuffix."
            }
            CopyFileContentAction.showNotification("<html><b>$message</b></html>", NotificationType.INFORMATION, project)
        }
    }

    private fun getChangeTypeLabel(change: Change): String {
        return ChangeTypeLabel.fromChangeType(change.type)?.label ?: ""
    }

    private fun getRelativePath(project: Project, absolutePath: String): String {
        val projectRoot = ProjectRootManager.getInstance(project).contentRoots.firstOrNull()
        return if (projectRoot != null) {
            val projectPath = projectRoot.path
            if (absolutePath.startsWith(projectPath)) {
                absolutePath.substring(projectPath.length).trimStart('/')
            } else {
                absolutePath
            }
        } else {
            absolutePath
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val data = StringSelection(text)
        clipboard.setContents(data, null)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isVisible = project != null
        e.presentation.isEnabled = project != null
    }

    /**
     * 使用 Git 命令讀取檔案內容（適用於已刪除或不存在的檔案）
     * @param project 當前專案
     * @param absolutePath 檔案的絕對路徑
     * @param fromIndex 如果為 true，從 index (staged) 讀取；否則從 HEAD 讀取
     * @return 檔案內容，如果讀取失敗則為 null
     */
    private fun getFileContentFromGit(project: Project, absolutePath: String, fromIndex: Boolean = false): String? {
        try {
            // 找到檔案所在的 Git repository root
            val file = java.io.File(absolutePath)
            val vFile = LocalFileSystem.getInstance().findFileByPath(file.parent ?: return null)
                ?: LocalFileSystem.getInstance().refreshAndFindFileByPath(file.parent ?: return null)
                ?: return null

            val gitRepo = git4idea.GitUtil.getRepositoryManager(project)
                .getRepositoryForFile(vFile)
                ?: return null

            val repoRoot = gitRepo.root.path
            // 計算相對路徑
            val relativePath = if (absolutePath.startsWith(repoRoot)) {
                absolutePath.substring(repoRoot.length).trimStart('/')
            } else {
                return null
            }

            // 使用 Git4Idea 的 GitFileUtils 讀取內容
            val filePath = VcsUtil.getFilePath(absolutePath)

            // 根據來源決定使用哪個 revision
            // fromIndex=true: 從 staged (index) 讀取 -> git show :path
            // fromIndex=false: 從 HEAD 讀取 -> git show HEAD:path
            val revision = if (fromIndex) {
                git4idea.repo.GitRepositoryManager.getInstance(project)
                    .getRepositoryForFile(vFile)
                    ?.let { git4idea.GitRevisionNumber(":") }  // Index revision
            } else {
                git4idea.GitRevisionNumber.HEAD
            }

            if (revision != null) {
                val contentRevision = git4idea.GitContentRevision.createRevision(
                    filePath,
                    revision,
                    project
                )
                return contentRevision.content
            }
        } catch (ex: Exception) {
            logger.warn("Failed to read file content from Git: ${ex.message}")
        }

        // Fallback: 嘗試使用 ProcessBuilder 直接執行 git 命令
        return getFileContentFromGitCommand(project, absolutePath, fromIndex)
    }

    /**
     * 使用 git 命令列直接讀取檔案內容（作為 fallback）
     */
    private fun getFileContentFromGitCommand(project: Project, absolutePath: String, fromIndex: Boolean = false): String? {
        try {
            val file = java.io.File(absolutePath)
            val workDir = file.parentFile ?: return null

            // 找到 git repository root
            var currentDir = workDir
            while (!java.io.File(currentDir, ".git").exists()) {
                currentDir = currentDir.parentFile ?: return null
            }

            val repoRoot = currentDir.absolutePath
            val relativePath = if (absolutePath.startsWith(repoRoot)) {
                absolutePath.substring(repoRoot.length).trimStart('/')
            } else {
                return null
            }

            // 建立 git show 命令
            // fromIndex=true: git show :relativePath (從 staged/index 讀取)
            // fromIndex=false: git show HEAD:relativePath (從 HEAD 讀取)
            val gitRef = if (fromIndex) ":$relativePath" else "HEAD:$relativePath"
            val processBuilder = ProcessBuilder("git", "show", gitRef)
                .directory(currentDir)
                .redirectErrorStream(false)

            val process = processBuilder.start()
            val content = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()

            if (exitCode == 0 && content.isNotEmpty()) {
                logger.warn("DEBUG   Read ${content.length} chars from git show $gitRef")
                return content
            }
        } catch (ex: Exception) {
            logger.warn("Failed to execute git show command: ${ex.message}")
        }
        return null
    }

    private data class ChangeInfo(
        val change: Change?,  // 可為 null（UNTRACKED 檔案沒有 Change 物件）
        val changeType: String,
        val filePath: String,
        val virtualFile: VirtualFile?,
        val contentFromRevision: String? = null  // 從 Git 歷史讀取的內容（適用於 Git Log）
    )
}