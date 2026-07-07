package com.github.audichuang.clipcode

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode
import com.intellij.openapi.vcs.changes.ui.ChangesTree
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.vcs.commit.CommitWorkflowUi
import com.intellij.vcsUtil.VcsUtil
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CopyGitFilesContentActionTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        configureSettings()
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(""), null)
    }

    fun testActionPerformedWithoutProjectSilentlyExits() {
        val event = AnActionEvent.createFromDataContext(
            "ClipCodeTest",
            Presentation(),
            SimpleDataContext.EMPTY_CONTEXT
        )
        // 不該丟例外
        CopyGitFilesContentAction().actionPerformed(event)
    }

    fun testCollectorMarksCommitWorkflowSelectionAsLocalSource() {
        val file = myFixture.addFileToProject("src/CommitUi.kt", "working tree").virtualFile
        val change = modificationChange(file.path, "before", "after")
        val commitWorkflowUi = commitWorkflowUiWithIncludedChanges(listOf(change))
        val event = actionEvent(
            SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(file))
                .add(VcsDataKeys.COMMIT_WORKFLOW_UI, commitWorkflowUi)
                .build()
        )

        val selection = GitSelectionCollector(Logger.getInstance(CopyGitFilesContentActionTest::class.java))
            .collect(event)

        assertEquals(SelectionSource.LOCAL_CHANGES_OR_COMMIT_UI, selection.source)
    }

    fun testCollectorMarksPlainChangeSelectionAsGitLogSource() {
        val file = myFixture.addFileToProject("src/History.kt", "working tree").virtualFile
        val change = modificationChange(file.path, "before", "after")
        val event = actionEvent(
            SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(VcsDataKeys.SELECTED_CHANGES, arrayOf(change))
                .build()
        )

        val selection = GitSelectionCollector(Logger.getInstance(CopyGitFilesContentActionTest::class.java))
            .collect(event)

        assertEquals(SelectionSource.GIT_LOG_OR_HISTORY, selection.source)
    }

    fun testCollectorMarksChangesTreeSelectionWithLocalChangeHitAsLocalSource() {
        val file = myFixture.addFileToProject("src/LocalTree.kt", "working tree").virtualFile
        val change = modificationChange(file.path, "before", "after")
        val tree = changesTreeWithSelectedChange(project, change)
        val event = actionEvent(
            SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(PlatformDataKeys.CONTEXT_COMPONENT, tree)
                .build()
        )

        val selection = GitSelectionCollector(
            Logger.getInstance(CopyGitFilesContentActionTest::class.java),
            localChangesProvider = { listOf(change) }
        ).collect(event)

        assertEquals(SelectionSource.LOCAL_CHANGES_OR_COMMIT_UI, selection.source)
    }

    fun testCollectorMarksNoGitMetadataSelectionAsUnknownSource() {
        val event = actionEvent(SimpleDataContext.getProjectContext(project))
        val selection = GitSelectionCollector(Logger.getInstance(CopyGitFilesContentActionTest::class.java))
            .collect(event)
        assertEquals(SelectionSource.UNKNOWN, selection.source)
    }

    fun testCollectorMarksChangesTreeSelectionWithoutLocalChangeHitAsGitLogSource() {
        val file = myFixture.addFileToProject("src/HistoryTree.kt", "working tree").virtualFile
        val change = modificationChange(file.path, "before", "after")
        val tree = changesTreeWithSelectedChange(project, change)
        val event = actionEvent(
            SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(PlatformDataKeys.CONTEXT_COMPONENT, tree)
                .build()
        )

        val selection = GitSelectionCollector(Logger.getInstance(CopyGitFilesContentActionTest::class.java))
            .collect(event)

        assertEquals(SelectionSource.GIT_LOG_OR_HISTORY, selection.source)
    }

    fun testCopyResolvedEntriesEmitsDeletedMarker() {
        val deletedEntry = GitContentResolver.ResolvedGitEntry(
            changeType = ChangeTypeLabel.DELETED,
            filePath = "src/Old.kt",
            virtualFile = null,
            contentFromRevision = null
        )
        val resolver = ClipboardPathResolver.fromProject(project)
        invokeCopyResolvedEntries(
            project = project,
            contentEntries = emptyList(),
            deletedMarkerEntries = listOf(deletedEntry),
            pathResolver = resolver,
            settings = CopyFileContentSettings.getInstance(project)
        )
        val text = clipboardText()
        assertContains(text, "[DELETED]")
        assertContains(text, "src/Old.kt")
        assertContains(text, "deleted in this change")
    }

    fun testCopyResolvedEntriesWritesContentAndPrePostText() {
        val state = CopyFileContentSettings.getInstance(project)!!.state
        state.preText = "<<PRE>>"
        state.postText = "<<POST>>"
        state.addExtraLineBetweenFiles = true

        val entry = GitContentResolver.ResolvedGitEntry(
            changeType = ChangeTypeLabel.MODIFIED,
            filePath = "src/Body.kt",
            virtualFile = null,
            contentFromRevision = "body content"
        )
        val resolver = ClipboardPathResolver.fromProject(project)
        invokeCopyResolvedEntries(
            project = project,
            contentEntries = listOf(entry),
            deletedMarkerEntries = emptyList(),
            pathResolver = resolver,
            settings = CopyFileContentSettings.getInstance(project)
        )
        val text = clipboardText()
        assertContains(text, "<<PRE>>")
        assertContains(text, "<<POST>>")
        assertContains(text, "body content")
        assertContains(text, "[MODIFIED]")
    }

    fun testHandleResolvedEntriesNotifiesWhenAllEmpty() {
        // 空 entries 應該短路通知（不更動 clipboard）
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection("untouched"), null)
        val resolver = ClipboardPathResolver.fromProject(project)
        invokeHandleResolvedEntries(project, resolver, emptyList())
        assertEquals("untouched", clipboardText())
    }

    fun testHandleResolvedEntriesWithMixedEntriesEmitsResolvedPath() {
        val entry = GitContentResolver.ResolvedGitEntry(
            changeType = ChangeTypeLabel.MODIFIED,
            filePath = "src/Mix.kt",
            virtualFile = null,
            contentFromRevision = "history-content"
        )
        val resolver = ClipboardPathResolver.fromProject(project)
        invokeHandleResolvedEntries(project, resolver, listOf(entry))
        assertContains(clipboardText(), "history-content")
    }

    // === helpers ===

    private fun configureSettings() {
        val state = CopyFileContentSettings.getInstance(project)!!.state
        state.headerFormat = "// file: \$FILE_PATH"
        state.preText = ""
        state.postText = ""
        state.addExtraLineBetweenFiles = false
        state.setMaxFileCount = false
        state.showCopyNotification = false
        state.maxFileSizeKB = 500
    }

    private fun invokeCopyResolvedEntries(
        project: Project,
        contentEntries: List<GitContentResolver.ResolvedGitEntry>,
        deletedMarkerEntries: List<GitContentResolver.ResolvedGitEntry>,
        pathResolver: ClipboardPathResolver,
        settings: CopyFileContentSettings?
    ) {
        val method = CopyGitFilesContentAction::class.java.getDeclaredMethod(
            "copyResolvedEntries",
            Project::class.java,
            List::class.java,
            List::class.java,
            ClipboardPathResolver::class.java,
            CopyFileContentSettings::class.java
        )
        method.isAccessible = true
        method.invoke(
            CopyGitFilesContentAction(),
            project,
            contentEntries,
            deletedMarkerEntries,
            pathResolver,
            settings
        )
    }

    private fun invokeHandleResolvedEntries(
        project: Project,
        pathResolver: ClipboardPathResolver,
        resolved: List<GitContentResolver.ResolvedGitEntry>
    ) {
        val method = CopyGitFilesContentAction::class.java.getDeclaredMethod(
            "handleResolvedEntries",
            Project::class.java,
            ClipboardPathResolver::class.java,
            List::class.java
        )
        method.isAccessible = true
        method.invoke(CopyGitFilesContentAction(), project, pathResolver, resolved)
    }

    private fun actionEvent(dataContext: DataContext): AnActionEvent =
        AnActionEvent.createFromDataContext("ClipCodeTest", Presentation(), dataContext)

    private fun modificationChange(
        absolutePath: String,
        beforeContent: String,
        afterContent: String
    ): Change {
        val path = VcsUtil.getFilePath(absolutePath, false)
        return Change(
            TestContentRevision(beforeContent, path, "before-sha"),
            TestContentRevision(afterContent, path, "after-sha")
        )
    }

    private fun commitWorkflowUiWithIncludedChanges(changes: List<Change>): CommitWorkflowUi {
        val handler = InvocationHandler { _, method, _ ->
            when (method.name) {
                "getIncludedChanges", "getDisplayedChanges" -> changes
                "getIncludedUnversionedFiles", "getDisplayedUnversionedFiles" -> emptyList<FilePath>()
                "getDefaultCommitActionName" -> "Commit"
                "activate" -> true
                else -> null
            }
        }
        return Proxy.newProxyInstance(
            CommitWorkflowUi::class.java.classLoader,
            arrayOf(CommitWorkflowUi::class.java),
            handler
        ) as CommitWorkflowUi
    }

    private fun changesTreeWithSelectedChange(project: Project, change: Change): ChangesTree {
        val root = ChangesBrowserNode.createRoot()
        val changeNode = ChangesBrowserNode.createChange(project, change)
        root.add(changeNode)

        return object : ChangesTree(project, false, false) {
            init {
                model = DefaultTreeModel(root)
                selectionPath = TreePath(changeNode.path)
            }

            override fun rebuildTree() = Unit
        }
    }

    private fun clipboardText(): String =
        Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as String

    private class TestContentRevision(
        private val text: String,
        private val filePath: FilePath,
        private val revision: String
    ) : ContentRevision {
        override fun getContent(): String = text

        override fun getFile(): FilePath = filePath

        override fun getRevisionNumber(): VcsRevisionNumber =
            object : VcsRevisionNumber {
                override fun asString(): String = revision

                override fun compareTo(other: VcsRevisionNumber): Int =
                    asString().compareTo(other.asString())
            }
    }
}
