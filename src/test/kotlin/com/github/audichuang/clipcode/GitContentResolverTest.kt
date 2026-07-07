package com.github.audichuang.clipcode

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.vcsUtil.VcsUtil
import git4idea.GitVcs
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitContentResolverTest : BasePlatformTestCase() {
    private val resolver = GitContentResolver(Logger.getInstance(GitContentResolverTest::class.java))

    override fun tearDown() {
        try {
            ProjectLevelVcsManager.getInstance(project).setDirectoryMappings(emptyList())
        } finally {
            super.tearDown()
        }
    }

    fun testLocalSourceModifiedKeepsWorkingTreeResolution() {
        val file = createDiskFile("src/App.kt", "working tree")
        val path = VcsUtil.getFilePath(file)
        val change = Change(
            TestContentRevision("base", path, "base-sha"),
            CurrentContentRevision(path)
        )

        val entries = resolver.resolve(project, selection(change, SelectionSource.LOCAL_CHANGES_OR_COMMIT_UI))

        assertEquals(1, entries.size)
        assertEquals(file, entries.single().virtualFile)
        assertNull(entries.single().contentFromRevision)
    }

    fun testLocalSourceWithHistoricalAfterRevisionUsesRevisionContent() {
        // Vcs.Log 的變更樹在路徑跟本地變更重疊時會被歸類為 LOCAL 來源；
        // afterRevision 非本地就必須讀該版本內容，不能拿工作區檔案
        val file = createDiskFile("src/HistoricalHit.kt", "working tree edited")
        val path = VcsUtil.getFilePath(file)
        val change = Change(
            TestContentRevision("base revision", path, "base-sha"),
            TestContentRevision("commit revision", path, "commit-sha")
        )

        val entries = resolver.resolve(project, selection(change, SelectionSource.LOCAL_CHANGES_OR_COMMIT_UI))

        assertEquals(1, entries.size)
        assertNull(entries.single().virtualFile)
        assertEquals("commit revision", entries.single().contentFromRevision)
    }

    fun testLocalSourceWithHistoricalAfterRevisionDoesNotFallBackToBeforeRevision() {
        // afterRevision 讀不到時不能退回 beforeRevision（那是 base 版本，內容是錯的），
        // 也不能退回工作區 — 寧可標記為無內容讓 UI 顯示 unresolved
        val file = createDiskFile("src/HistoricalFail.kt", "working tree")
        val path = VcsUtil.getFilePath(file)
        val change = Change(
            TestContentRevision("base revision", path, "base-sha"),
            TestContentRevision(null, path, "commit-sha")
        )

        val entries = resolver.resolve(project, selection(change, SelectionSource.LOCAL_CHANGES_OR_COMMIT_UI))

        assertEquals(1, entries.size)
        assertNull(entries.single().virtualFile)
        assertNull(entries.single().contentFromRevision)
        assertFalse(entries.single().hasContent)
    }

    fun testGitLogSourceModifiedUsesRevisionContentInsteadOfWorkingTree() {
        val file = createDiskFile("src/App.kt", "working tree")
        val path = VcsUtil.getFilePath(file)
        val change = Change(
            TestContentRevision("base revision", path, "base-sha"),
            TestContentRevision("commit revision", path, "commit-sha")
        )

        val entries = resolver.resolve(project, selection(change, SelectionSource.GIT_LOG_OR_HISTORY))

        assertEquals(1, entries.size)
        assertNull(entries.single().virtualFile)
        assertEquals("commit revision", entries.single().contentFromRevision)
        assertFalse(entries.single().contentFromRevision == VfsUtil.loadText(file))
    }

    fun testGitLogSourceDeletedUsesBeforeRevisionAndDoesNotReadHeadFallback() {
        val file = createDiskFile("src/Deleted.kt", "head content")
        val path = VcsUtil.getFilePath(file)
        val change = Change(
            TestContentRevision("deleted revision content", path, "deleted-sha"),
            null
        )

        val entries = resolver.resolve(project, selection(change, SelectionSource.GIT_LOG_OR_HISTORY))

        assertEquals(1, entries.size)
        assertEquals(ChangeTypeLabel.DELETED, entries.single().changeType)
        assertNull(entries.single().virtualFile)
        assertEquals("deleted revision content", entries.single().contentFromRevision)
        assertFalse(entries.single().contentFromRevision == VfsUtil.loadText(file))
    }

    fun testGitLogSourceDoesNotFallbackToWorkingTreeWhenRevisionContentFails() {
        val file = createDiskFile("src/MissingRevision.kt", "working tree")
        val path = VcsUtil.getFilePath(file)
        val change = Change(
            TestContentRevision("base revision", path, "base-sha"),
            TestContentRevision(null, path, "commit-sha")
        )

        val entries = resolver.resolve(project, selection(change, SelectionSource.GIT_LOG_OR_HISTORY))

        assertEquals(1, entries.size)
        assertNull(entries.single().virtualFile)
        assertNull(entries.single().contentFromRevision)
        assertFalse(entries.single().hasContent)
    }

    fun testLocalSourceDeletedStillUsesDeletedContentFallbackChain() {
        val file = createDiskFile("src/LocalDeleted.kt", "head fallback content")
        val absolutePath = file.path
        val path = VcsUtil.getFilePath(file)
        val change = Change(
            TestContentRevision(null, path, "local-before"),
            null
        )
        initializeGitRepositoryWithHeadFile(absolutePath, "head fallback content")

        ApplicationManager.getApplication().runWriteAction {
            file.delete(this)
        }

        val entries = resolver.resolve(project, selection(change, SelectionSource.LOCAL_CHANGES_OR_COMMIT_UI))

        assertEquals(1, entries.size)
        assertEquals(ChangeTypeLabel.DELETED, entries.single().changeType)
        assertNull(entries.single().virtualFile)
        assertEquals("head fallback content", entries.single().contentFromRevision)
    }

    fun testUntrackedPathProducesNewEntry() {
        val file = createDiskFile("src/NewUntracked.kt", "fresh")
        val selection = GitSelectionCollector.Selection(
            changes = emptyList(),
            selectedFiles = emptyList(),
            untrackedPaths = setOf(file.path),
            gitStatusNodes = emptySet(),
            source = SelectionSource.LOCAL_CHANGES_OR_COMMIT_UI
        )
        val entries = resolver.resolve(project, selection)
        assertEquals(1, entries.size)
        assertEquals(ChangeTypeLabel.NEW, entries.single().changeType)
        assertNotNull(entries.single().virtualFile)
    }

    fun testGitStatusNodeModifiedProducesEntry() {
        val file = createDiskFile("src/StatusMod.kt", "x")
        val selection = GitSelectionCollector.Selection(
            changes = emptyList(),
            selectedFiles = emptyList(),
            untrackedPaths = emptySet(),
            gitStatusNodes = setOf(
                GitSelectionCollector.GitStatusInfo(file.path, "MODIFIED", isStaged = false)
            ),
            source = SelectionSource.LOCAL_CHANGES_OR_COMMIT_UI
        )
        val entries = resolver.resolve(project, selection)
        assertEquals(1, entries.size)
        assertEquals(ChangeTypeLabel.MODIFIED, entries.single().changeType)
        assertNotNull(entries.single().virtualFile)
    }

    fun testGitStatusNodeAddedProducesNewEntry() {
        val file = createDiskFile("src/StatusAdded.kt", "x")
        val selection = GitSelectionCollector.Selection(
            changes = emptyList(),
            selectedFiles = emptyList(),
            untrackedPaths = emptySet(),
            gitStatusNodes = setOf(
                GitSelectionCollector.GitStatusInfo(file.path, "ADDED", isStaged = true)
            ),
            source = SelectionSource.LOCAL_CHANGES_OR_COMMIT_UI
        )
        val entries = resolver.resolve(project, selection)
        assertEquals(1, entries.size)
        assertEquals(ChangeTypeLabel.NEW, entries.single().changeType)
    }

    fun testGitStatusNodeUnknownDefaultsToModified() {
        val file = createDiskFile("src/StatusUnknown.kt", "x")
        val selection = GitSelectionCollector.Selection(
            changes = emptyList(),
            selectedFiles = emptyList(),
            untrackedPaths = emptySet(),
            gitStatusNodes = setOf(
                GitSelectionCollector.GitStatusInfo(file.path, "SOMETHING_ELSE")
            ),
            source = SelectionSource.LOCAL_CHANGES_OR_COMMIT_UI
        )
        val entries = resolver.resolve(project, selection)
        assertEquals(1, entries.size)
        assertEquals(ChangeTypeLabel.MODIFIED, entries.single().changeType)
    }

    fun testResolvedGitEntryHasContent() {
        val withVirtualFile = createDiskFile("src/HasVf.kt", "vf-content")
        val withRevisionOnly = GitContentResolver.ResolvedGitEntry(
            changeType = ChangeTypeLabel.MODIFIED,
            filePath = "src/Rev.kt",
            virtualFile = null,
            contentFromRevision = "rev"
        )
        val withVfEntry = GitContentResolver.ResolvedGitEntry(
            changeType = ChangeTypeLabel.MODIFIED,
            filePath = withVirtualFile.path,
            virtualFile = withVirtualFile,
            contentFromRevision = null
        )
        val nothing = GitContentResolver.ResolvedGitEntry(
            changeType = ChangeTypeLabel.MODIFIED,
            filePath = "src/Empty.kt",
            virtualFile = null,
            contentFromRevision = null
        )

        assertTrue(withRevisionOnly.hasContent)
        assertTrue(withVfEntry.hasContent)
        assertTrue(withVfEntry.hasVirtualFileContent)
        assertFalse(nothing.hasContent)
        assertFalse(nothing.hasVirtualFileContent)
    }

    fun testUntrackedPathDoesNotOverrideExistingEntry() {
        // 確認當 path 同時在 changes 跟 untrackedPaths 時，change 優先
        val file = createDiskFile("src/Both.kt", "wt")
        val path = VcsUtil.getFilePath(file)
        val change = Change(
            TestContentRevision("before", path, "before-sha"),
            CurrentContentRevision(path)
        )
        val selection = GitSelectionCollector.Selection(
            changes = listOf(change),
            selectedFiles = emptyList(),
            untrackedPaths = setOf(file.path),
            gitStatusNodes = emptySet(),
            source = SelectionSource.LOCAL_CHANGES_OR_COMMIT_UI
        )
        val entries = resolver.resolve(project, selection)
        assertEquals(1, entries.size)
        // change 路徑會優先，所以 changeType 不會是 NEW（untracked path 的 default）
        assertFalse(entries.single().changeType == ChangeTypeLabel.NEW)
    }

    // === helpers ===

    private fun selection(
        change: Change,
        source: SelectionSource
    ): GitSelectionCollector.Selection =
        GitSelectionCollector.Selection(
            changes = listOf(change),
            selectedFiles = emptyList(),
            untrackedPaths = emptySet(),
            gitStatusNodes = emptySet(),
            source = source
        )

    private fun createDiskFile(relativePath: String, content: String): com.intellij.openapi.vfs.VirtualFile {
        val ioFile = File(project.basePath!!, relativePath)
        ioFile.parentFile.mkdirs()
        ioFile.writeText(content)
        return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile)
            ?: error("Unable to refresh test file: $ioFile")
    }

    private fun initializeGitRepositoryWithHeadFile(
        absolutePath: String,
        content: String
    ) {
        val root = File(project.basePath!!)
        val relativePath = File(root.toURI().relativize(File(absolutePath).toURI()).path)
            .invariantSeparatorsPath

        runGit("init")
        runGit("config", "user.email", "clipcode-test@example.com")
        runGit("config", "user.name", "ClipCode Test")
        runGit("add", relativePath)
        runGit("commit", "-m", "initial")
        registerGitRoot(root)

        assertEquals(content, runGit("show", "HEAD:$relativePath"))
    }

    private fun registerGitRoot(root: File) {
        ProjectLevelVcsManager.getInstance(project)
            .setDirectoryMappings(listOf(VcsDirectoryMapping(root.absolutePath, GitVcs.NAME)))
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(root)
            ?: error("Unable to refresh Git root: $root")
    }

    private fun runGit(vararg args: String): String {
        val process = ProcessBuilder(listOf(gitExecutable()) + args)
            .directory(File(project.basePath!!))
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        kotlin.test.assertEquals(0, exitCode, "git ${args.joinToString(" ")} failed:\n$output")
        return output
    }

    private fun gitExecutable(): String =
        listOf("/opt/homebrew/bin/git", "/usr/local/bin/git", "/usr/bin/git", "git")
            .firstOrNull { it == "git" || File(it).canExecute() }
            ?: "git"

    private class TestContentRevision(
        private val text: String?,
        private val filePath: FilePath,
        private val revision: String
    ) : ContentRevision {
        override fun getContent(): String? {
            if (text == null) {
                throw VcsException("Revision content unavailable")
            }
            return text
        }

        override fun getFile(): FilePath = filePath

        override fun getRevisionNumber(): VcsRevisionNumber =
            object : VcsRevisionNumber {
                override fun asString(): String = revision

                override fun compareTo(other: VcsRevisionNumber): Int =
                    asString().compareTo(other.asString())
            }
    }
}
