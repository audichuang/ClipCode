package com.github.audichuang.clipcode

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.diagnostic.Logger
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
import git4idea.index.GitFileStatus
import git4idea.index.ui.GitFileStatusNode
import git4idea.index.ui.NodeKind
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitSelectionCollectorTest : BasePlatformTestCase() {
    private val logger by lazy { Logger.getInstance(GitSelectionCollectorTest::class.java) }

    fun testSelectedChangesViaVcsDataKey() {
        val file = myFixture.addFileToProject("src/A.kt", "x").virtualFile
        val change = modificationChange(file.path)
        val event = actionEvent(
            SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(VcsDataKeys.SELECTED_CHANGES, arrayOf(change))
                .build()
        )

        val selection = GitSelectionCollector(logger).collect(event)
        assertEquals(1, selection.changes.size)
        assertTrue(selection.hasGitMetadata)
        assertEquals(SelectionSource.GIT_LOG_OR_HISTORY, selection.source)
    }

    fun testChangeLeadSelectionAlsoCollected() {
        val file = myFixture.addFileToProject("src/B.kt", "x").virtualFile
        val change = modificationChange(file.path)
        val event = actionEvent(
            SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(VcsDataKeys.CHANGE_LEAD_SELECTION, arrayOf(change))
                .build()
        )
        val selection = GitSelectionCollector(logger).collect(event)
        assertEquals(1, selection.changes.size)
    }

    fun testChangesViaPlainChangesDataKey() {
        val file = myFixture.addFileToProject("src/C.kt", "x").virtualFile
        val change = modificationChange(file.path)
        val event = actionEvent(
            SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(VcsDataKeys.CHANGES, arrayOf(change))
                .build()
        )
        val selection = GitSelectionCollector(logger).collect(event)
        assertEquals(1, selection.changes.size)
    }

    fun testSelectedFilesWithoutChangeAreUntracked() {
        val file = myFixture.addFileToProject("untracked/X.kt", "x").virtualFile
        val event = actionEvent(
            SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(file))
                .build()
        )
        val selection = GitSelectionCollector(logger).collect(event)
        assertTrue(selection.untrackedPaths.contains(file.path))
        assertTrue(selection.hasGitMetadata)
    }

    fun testCommitWorkflowUiIncludedChangesAreCollected() {
        val file = myFixture.addFileToProject("src/D.kt", "x").virtualFile
        val change = modificationChange(file.path)
        val ui = commitWorkflowUiWithIncludedChanges(listOf(change))
        val event = actionEvent(
            SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(VcsDataKeys.COMMIT_WORKFLOW_UI, ui)
                .add(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(file))
                .build()
        )
        val selection = GitSelectionCollector(logger).collect(event)
        // commit workflow path 應該命中
        assertTrue(selection.changes.isNotEmpty() || selection.untrackedPaths.isNotEmpty())
        // 至少 source 應該被認成 LOCAL
        assertEquals(SelectionSource.LOCAL_CHANGES_OR_COMMIT_UI, selection.source)
    }

    fun testEmptyDataContextProducesEmptySelection() {
        val event = actionEvent(SimpleDataContext.getProjectContext(project))
        val selection = GitSelectionCollector(logger).collect(event)
        assertEquals(0, selection.changes.size)
        assertEquals(0, selection.untrackedPaths.size)
        assertEquals(0, selection.gitStatusNodes.size)
        assertEquals(0, selection.selectedFiles.size)
        assertEquals(SelectionSource.UNKNOWN, selection.source)
        assertTrue(!selection.hasGitMetadata)
    }

    fun testSingleVirtualFileSelection() {
        val file = myFixture.addFileToProject("src/single/Foo.kt", "x").virtualFile
        val event = actionEvent(
            SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.VIRTUAL_FILE, file)
                .build()
        )
        val selection = GitSelectionCollector(logger).collect(event)
        assertEquals(1, selection.selectedFiles.size)
        assertEquals(file.path, selection.selectedFiles.first().path)
    }

    fun testChangesTreeSelectionWithChangeNode() {
        val file = myFixture.addFileToProject("src/treenode.kt", "x").virtualFile
        val change = modificationChange(file.path)
        val tree = changesTreeWithSelectedChange(project, change)
        val event = actionEvent(
            SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(PlatformDataKeys.CONTEXT_COMPONENT, tree)
                .build()
        )
        val collector = GitSelectionCollector(logger, localChangesProvider = { emptyList() })
        val selection = collector.collect(event)
        assertEquals(1, selection.changes.size)
        // 沒命中 ChangeListManager → 視為 GIT_LOG
        assertEquals(SelectionSource.GIT_LOG_OR_HISTORY, selection.source)
    }

    /**
     * 覆蓋我把反射改成 GitFileStatusNode 公開 API 的所有 status code 分支：
     * STAGED+'D'/'M'/'A'/'R'/'C'/'T' → DELETED/MODIFIED/ADDED/MOVED/MOVED/MODIFIED
     * UNSTAGED 同樣的對映（用 workTree）
     * 其他未對應 code 應忽略。
     */
    fun testStagingTreeStatusCodesMapToExpectedLabels() {
        val file = myFixture.addFileToProject("staging/Sample.kt", "x").virtualFile
        val filePath = VcsUtil.getFilePath(file)
        val rootVf = file.parent ?: error("No parent for staged sample file")

        data class Case(
            val description: String,
            val kind: NodeKind,
            val index: Char,
            val workTree: Char,
            val expectedStatus: String?,
            val expectedStaged: Boolean
        )

        val cases = listOf(
            Case("STAGED D → DELETED", NodeKind.STAGED, 'D', ' ', "DELETED", true),
            Case("STAGED M → MODIFIED", NodeKind.STAGED, 'M', ' ', "MODIFIED", true),
            Case("STAGED A → ADDED", NodeKind.STAGED, 'A', ' ', "ADDED", true),
            Case("STAGED R → MOVED", NodeKind.STAGED, 'R', ' ', "MOVED", true),
            Case("STAGED C → MOVED", NodeKind.STAGED, 'C', ' ', "MOVED", true),
            Case("STAGED T → MODIFIED", NodeKind.STAGED, 'T', ' ', "MODIFIED", true),
            Case("UNSTAGED M → MODIFIED", NodeKind.UNSTAGED, ' ', 'M', "MODIFIED", false),
            Case("UNSTAGED D → DELETED", NodeKind.UNSTAGED, ' ', 'D', "DELETED", false),
            Case("UNSTAGED R → MOVED", NodeKind.UNSTAGED, ' ', 'R', "MOVED", false),
            // 未對應 code 應跳過
            Case("STAGED ? not staged code → ignored", NodeKind.STAGED, '?', ' ', null, true),
            Case("UNSTAGED ! not tracked code → ignored", NodeKind.UNSTAGED, ' ', '!', null, false)
        )

        cases.forEach { case ->
            val (untracked, statusNodes) = invokeCollectGitStatusNode(
                GitFileStatusNode(
                    root = rootVf,
                    status = GitFileStatus(case.index, case.workTree, filePath),
                    kind = case.kind
                )
            )
            // 用 kotlin.test.fail + 顯式訊息，避免 JUnit3 assertEquals(message,expected,actual) 與 kotlin.test 簽名衝突
            if (untracked.isNotEmpty()) kotlin.test.fail("${case.description}: should not route to untracked, got $untracked")
            if (case.expectedStatus == null) {
                if (statusNodes.isNotEmpty()) kotlin.test.fail("${case.description}: should be ignored, got $statusNodes")
            } else {
                if (statusNodes.size != 1) kotlin.test.fail("${case.description}: expected single GitStatusInfo, got ${statusNodes.size}")
                val info = statusNodes.first()
                if (info.status != case.expectedStatus) kotlin.test.fail("${case.description}: status expected=${case.expectedStatus} actual=${info.status}")
                if (info.isStaged != case.expectedStaged) kotlin.test.fail("${case.description}: isStaged expected=${case.expectedStaged} actual=${info.isStaged}")
                if (info.path != filePath.path) kotlin.test.fail("${case.description}: path expected=${filePath.path} actual=${info.path}")
            }
        }
    }

    /**
     * GitFileStatusNode kind=UNTRACKED 必須路由到 untrackedFilePaths，
     * 不應出現在 gitStatusNodes (避免被當成 ADDED/MODIFIED 處理)。
     */
    fun testUntrackedNodeKindRoutesToUntracked() {
        val file = myFixture.addFileToProject("staging/untracked/New.kt", "x").virtualFile
        val filePath = VcsUtil.getFilePath(file)
        val rootVf = file.parent ?: error("No parent for untracked sample file")

        val (untracked, statusNodes) = invokeCollectGitStatusNode(
            GitFileStatusNode(
                root = rootVf,
                status = GitFileStatus('?', '?', filePath),
                kind = NodeKind.UNTRACKED
            )
        )

        assertEquals(setOf(filePath.path), untracked)
        assertTrue(statusNodes.isEmpty(), "UNTRACKED kind must not be added to gitStatusNodes")
    }

    /**
     * CONFLICTED 與 IGNORED 都應被靜默跳過 (不在 copy 範圍)。
     */
    fun testConflictedAndIgnoredKindsAreSkipped() {
        val file = myFixture.addFileToProject("staging/skip/Skipped.kt", "x").virtualFile
        val filePath = VcsUtil.getFilePath(file)
        val rootVf = file.parent ?: error("No parent for skip sample file")

        listOf(NodeKind.CONFLICTED, NodeKind.IGNORED).forEach { kind ->
            val (untracked, statusNodes) = invokeCollectGitStatusNode(
                GitFileStatusNode(
                    root = rootVf,
                    status = GitFileStatus('M', 'M', filePath),
                    kind = kind
                )
            )
            assertTrue(untracked.isEmpty(), "$kind kind should produce no untracked entry")
            assertTrue(statusNodes.isEmpty(), "$kind kind should produce no gitStatusNodes entry")
        }
    }

    /**
     * 非 GitFileStatusNode 型別的 userObject 必須安全 ignore，避免反射時代留下的 fragility 重現。
     */
    fun testNonGitFileStatusNodeUserObjectIsIgnored() {
        val (untracked, statusNodes) = invokeCollectGitStatusNode(userObject = "some random string from a foreign tree")
        assertTrue(untracked.isEmpty(), "Random string userObject must not leak as untracked")
        assertTrue(statusNodes.isEmpty(), "Random string userObject must not leak as gitStatusNodes")

        val (untracked2, statusNodes2) = invokeCollectGitStatusNode(userObject = null)
        assertTrue(untracked2.isEmpty())
        assertTrue(statusNodes2.isEmpty())
    }

    /**
     * 透過反射呼叫 private collectGitStatusNode；回傳收集到的 (untrackedPaths, gitStatusNodes)。
     */
    private fun invokeCollectGitStatusNode(
        userObject: Any?
    ): Pair<Set<String>, Set<GitSelectionCollector.GitStatusInfo>> {
        val untracked = linkedSetOf<String>()
        val gitStatusNodes = linkedSetOf<GitSelectionCollector.GitStatusInfo>()
        val method = GitSelectionCollector::class.java.getDeclaredMethod(
            "collectGitStatusNode",
            Any::class.java,
            MutableSet::class.java,
            MutableSet::class.java
        )
        method.isAccessible = true
        method.invoke(GitSelectionCollector(logger), userObject, untracked, gitStatusNodes)
        // assertFalse 在這裡只是固定一個未用 import 不會被優化掉
        assertFalse(untracked.size < 0)
        return untracked.toSet() to gitStatusNodes.toSet()
    }

    // === helpers ===

    private fun actionEvent(dataContext: DataContext): AnActionEvent =
        AnActionEvent.createFromDataContext("ClipCodeTest", Presentation(), dataContext)

    private fun modificationChange(absolutePath: String): Change {
        val path = VcsUtil.getFilePath(absolutePath, false)
        return Change(
            TestRevision("before", path, "before-sha"),
            TestRevision("after", path, "after-sha")
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

    private fun changesTreeWithSelectedChange(
        project: com.intellij.openapi.project.Project,
        change: Change
    ): ChangesTree {
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

    private class TestRevision(
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
