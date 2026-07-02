package com.github.audichuang.clipcode

import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import com.intellij.ui.CheckBoxList
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * PR 面板：選 base ref → 顯示 base..HEAD 變更 + remote 新鮮度橫幅 → 勾選後用既有
 * [GitClipboardPayloadBuilder] 複製，維持與 commit history 複製完全一致的剪貼簿格式。
 *
 * 所有 Git/VFS 存取都在 [Task.Backgroundable] 內完成；Swing 更新只在 onSuccess()（EDT）內做。
 */
@IdeBoundCode
class ClipCodePrPanel(private val project: Project) : JPanel(BorderLayout()) {
    private val logger = Logger.getInstance(ClipCodePrPanel::class.java)
    private val branchDiffProvider = BranchDiffProvider(logger)
    private val pathResolver = ClipboardPathResolver.fromProject(project)

    private val baseRefCombo = JComboBox<String>()
    private val refreshButton = JButton("Refresh")
    private val remoteBanner = JLabel(" ")
    private val fetchButton = JButton("Fetch")
    private val changesList = CheckBoxList<Change>()
    private val copyButton = JButton("Copy to Clipboard").apply { isEnabled = false }

    /** 忽略 populateBaseRefs() 內程式化 setSelectedItem 觸發的 actionListener，避免重複 reload。 */
    private var isUpdatingCombo = false

    /** 每次 reload 遞增；onSuccess 只在自己仍是最新一代且 base 未變時套用結果，避免舊 task 覆蓋。 */
    private var reloadGeneration = 0

    init {
        val baseRow = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JLabel("Base:"))
            add(baseRefCombo)
            add(refreshButton)
        }
        val bannerRow = JPanel(BorderLayout()).apply {
            add(remoteBanner, BorderLayout.CENTER)
            add(fetchButton, BorderLayout.EAST)
        }
        val topPanel = JPanel(BorderLayout()).apply {
            add(baseRow, BorderLayout.NORTH)
            add(bannerRow, BorderLayout.SOUTH)
        }

        add(topPanel, BorderLayout.NORTH)
        add(JBScrollPane(changesList), BorderLayout.CENTER)
        add(copyButton, BorderLayout.SOUTH)

        changesList.setCheckBoxListListener { _, _ -> updateCopyEnabled() }
        refreshButton.addActionListener { selectedBaseRef()?.let { reload(it, doFetch = true) } }
        fetchButton.addActionListener { selectedBaseRef()?.let { reload(it, doFetch = true) } }
        baseRefCombo.addActionListener {
            if (isUpdatingCombo) return@addActionListener
            selectedBaseRef()?.let { reload(it, doFetch = false) }
        }
        copyButton.addActionListener { copySelection() }

        loadBaseRefs()
    }

    private fun selectedBaseRef(): String? = baseRefCombo.selectedItem as? String

    private fun loadBaseRefs() {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Loading ClipCode PR base refs…", false) {
            private var refs: List<String> = emptyList()

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                refs = branchDiffProvider.candidateBaseRefs(project)
            }

            override fun onSuccess() {
                if (project.isDisposed) return
                populateBaseRefs(refs)
            }
        })
    }

    private fun populateBaseRefs(refs: List<String>) {
        isUpdatingCombo = true
        try {
            baseRefCombo.removeAllItems()
            refs.forEach(baseRefCombo::addItem)
            refs.firstOrNull()?.let { baseRefCombo.selectedItem = it }
        } finally {
            isUpdatingCombo = false
        }

        val defaultBase = refs.firstOrNull()
        if (defaultBase != null) {
            reload(defaultBase, doFetch = true)
        } else {
            remoteBanner.text = "No candidate base ref found (no origin remote branches)."
            fetchButton.isVisible = false
            changesList.clear()
            updateCopyEnabled()
        }
    }

    private fun reload(baseRef: String, doFetch: Boolean) {
        val generation = ++reloadGeneration
        copyButton.isEnabled = false
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Loading ClipCode PR diff…", true) {
            private var changes: List<Change> = emptyList()
            private var remoteStatus: BranchDiffProvider.RemoteStatus? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                // fetch 先落地（在 remoteStatus 內），diff 才會算在 post-fetch 的 ref 上
                remoteStatus = branchDiffProvider.remoteStatus(project, doFetch)
                indicator.checkCanceled()
                changes = branchDiffProvider.diffChanges(project, baseRef)
            }

            override fun onSuccess() {
                if (project.isDisposed || generation != reloadGeneration || selectedBaseRef() != baseRef) return
                populateChangesList(changes)
                remoteStatus?.let(::applyRemoteBanner)
            }
        })
    }

    private fun populateChangesList(changes: List<Change>) {
        changesList.clear()
        changes.forEach { change ->
            val filePath = change.afterRevision?.file?.path ?: change.beforeRevision?.file?.path ?: return@forEach
            val label = ChangeTypeLabel.fromChangeType(change.type)?.label.orEmpty()
            changesList.addItem(change, "$label ${pathResolver.toClipboardPath(filePath)}", true)
        }
        updateCopyEnabled()
    }

    private fun applyRemoteBanner(status: BranchDiffProvider.RemoteStatus) {
        val banner = formatRemoteBanner(status)
        remoteBanner.text = banner.text
        fetchButton.isVisible = banner.showFetchButton
    }

    private fun updateCopyEnabled() {
        copyButton.isEnabled = changesList.checkedItems.isNotEmpty()
    }

    private fun copySelection() {
        val checked = changesList.checkedItems
        if (checked.isEmpty()) {
            CopyFileContentAction.showNotification("No changes selected to copy.", NotificationType.WARNING, project)
            return
        }

        copyButton.isEnabled = false
        val settings = CopyFileContentSettings.getInstance(project)

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Copying ClipCode PR diff…", true) {
            private var payload: GitClipboardPayloadBuilder.Payload? = null
            private var noChangesToCopy = false

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val selection = GitSelectionCollector.Selection(
                    changes = checked,
                    selectedFiles = emptyList(),
                    untrackedPaths = emptySet(),
                    gitStatusNodes = emptySet(),
                    source = SelectionSource.GIT_LOG_OR_HISTORY
                )
                val entries = GitContentResolver(logger).resolve(project, selection)
                val content = entries.filter { it.hasContent }
                val deleted = entries.filter { it.changeType == ChangeTypeLabel.DELETED && !it.hasContent }
                if (content.isEmpty() && deleted.isEmpty()) {
                    noChangesToCopy = true
                    return
                }
                indicator.checkCanceled()
                payload = ReadAction.compute<GitClipboardPayloadBuilder.Payload, RuntimeException> {
                    GitClipboardPayloadBuilder.build(content, deleted, pathResolver, settings, indicator)
                }
            }

            override fun onSuccess() {
                if (project.isDisposed) return
                updateCopyEnabled()

                if (noChangesToCopy) {
                    CopyFileContentAction.showNotification("No changes to copy.", NotificationType.WARNING, project)
                    return
                }

                val result = payload ?: return
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(result.text), null)
                if (settings?.state?.showCopyNotification == true) {
                    CopyFileContentAction.showNotification(
                        "<html><b>${result.summary}</b></html>",
                        NotificationType.INFORMATION,
                        project
                    )
                }
            }
        })
    }

    companion object {
        data class RemoteBanner(val text: String, val showFetchButton: Boolean)

        /**
         * 純函式：把 [BranchDiffProvider.RemoteStatus] 轉成橫幅文字 + 是否顯示 Fetch 按鈕。
         * 抽出以便單元測試（無 IDE 依賴）。
         */
        fun formatRemoteBanner(status: BranchDiffProvider.RemoteStatus): RemoteBanner {
            val offlineNote = if (status.fetchAttempted && !status.fetched) "（未能連線 remote，以下為本地快取狀態）" else ""
            return when {
                status.upstream == null ->
                    RemoteBanner("本分支無對應 origin 分支", showFetchButton = false)

                status.behind > 0 ->
                    RemoteBanner(
                        "⚠ ${status.upstream ?: "origin"} 有 ${status.behind} 個新 commit，建議 pull$offlineNote",
                        showFetchButton = true
                    )

                else ->
                    RemoteBanner(
                        "✓ 與 ${status.upstream} 同步（ahead ${status.ahead}）$offlineNote",
                        showFetchButton = true
                    )
            }
        }
    }
}
