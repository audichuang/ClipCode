package com.github.audichuang.clipcode

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.vcs.log.CommitId
import com.intellij.vcs.log.VcsLog
import com.intellij.vcs.log.VcsLogCommitSelection
import com.intellij.vcs.log.VcsLogDataKeys
import com.intellij.vcs.log.impl.HashImpl
import git4idea.GitVcs
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import git4idea.changes.GitChangeUtils
import java.io.File
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MergeCommitCopyTest : BasePlatformTestCase() {
    private val logger = Logger.getInstance(MergeCommitCopyTest::class.java)
    private lateinit var root: File
    private val rootVf get() = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(root)!!

    override fun setUp() {
        super.setUp()
        root = java.nio.file.Files.createTempDirectory("clipcode-merge-test").toFile()
        git("init", "-b", "trust")
        git("config", "user.name", "Test")
        git("config", "user.email", "test@example.invalid")
        git("config", "commit.gpgsign", "false")
        write("shared.txt", "base\n")
        commit()
        ProjectLevelVcsManager.getInstance(project).setDirectoryMappings(
            listOf(VcsDirectoryMapping(root.path, GitVcs.NAME)))
    }

    override fun tearDown() {
        try {
            ProjectLevelVcsManager.getInstance(project).setDirectoryMappings(emptyList())
            root.deleteRecursively()
        } finally { super.tearDown() }
    }

    fun testCleanMergeCopiesIncomingFilesEvenWhenIdeChangesAreEmpty() {
        git("checkout", "-b", "history")
        write("history.txt", "incoming\n")
        commit()
        git("checkout", "trust")
        write("trust.txt", "existing\n")
        commit()
        git("merge", "--no-ff", "history", "-m", "merge")
        val merge = git("rev-parse", "HEAD")
        write("history.txt", "later working tree\n")
        val selection = GitSelectionCollector(logger).collect(event(merge))
        assertTrue(selection.hasGitMetadata, "A commit node must work without IDE file changes")
        val entries = resolve(selection)
        // Deliberately inverted: a merge is the UNION of its diffs against EVERY parent.
        // First-parent only hid everything that arrived through parents 2..N — silent loss
        // on every octopus merge, and a different file set from Snipcode's graph for the
        // same commit. trust.txt is what this merge differs from the `history` parent on.
        assertEquals(listOf("history.txt", "trust.txt"), entries.map { File(it.filePath).name }.sorted())
        assertEquals("incoming\n", entries.first { File(it.filePath).name == "history.txt" }.contentFromRevision)
        val settings = CopyFileContentSettings.getInstance(project)!!.state
        settings.showCopyNotification = false
        settings.setMaxFileCount = false
        CopyGitFilesContentAction().actionPerformed(event(merge))
        val text = Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as String
        assertTrue(text.contains("incoming"))
        assertTrue(!text.contains("later working tree"))
    }

    fun testConflictMergeUsesResolvedContentAndIncludesFilesMissingFromIdeList() {
        write("deleted.txt", "remove me\n")
        write("old.txt", "rename me\n")
        commit()
        git("checkout", "-b", "history")
        write("shared.txt", "history edit\n")
        write("incoming.txt", "new feature\n")
        git("rm", "deleted.txt")
        git("mv", "old.txt", "renamed.txt")
        commit()
        git("checkout", "trust")
        write("shared.txt", "trust edit\n")
        commit()
        val before = git("rev-parse", "HEAD")
        git("merge", "--no-ff", "history", expectedExit = 1)
        write("shared.txt", "resolved both sides\n")
        commit()
        val merge = git("rev-parse", "HEAD")
        val ideChanges = GitChangeUtils.getDiff(project, rootVf, before, merge, null)
            .filter { it.afterRevision?.file?.name == "shared.txt" }.toTypedArray()
        assertEquals(1, ideChanges.size)
        // A later commit and local edits must not change the selected merge's content.
        write("shared.txt", "later commit\n")
        commit()
        write("incoming.txt", "uncommitted\n")
        val entries = resolve(GitSelectionCollector(logger).collect(event(merge, changes = ideChanges)))
        val byName = entries.associateBy { File(it.filePath).name }
        assertEquals(setOf("shared.txt", "incoming.txt", "deleted.txt", "renamed.txt"), byName.keys)
        assertEquals("resolved both sides\n", byName.getValue("shared.txt").contentFromRevision)
        assertEquals("new feature\n", byName.getValue("incoming.txt").contentFromRevision)
        assertEquals(ChangeTypeLabel.DELETED, byName.getValue("deleted.txt").changeType)
        assertEquals(ChangeTypeLabel.MOVED, byName.getValue("renamed.txt").changeType)

        val filesOnly = resolve(GitSelectionCollector(logger).collect(
            event(merge, "Vcs.Log.ChangesBrowser.Popup", ideChanges)))
        assertEquals(listOf("shared.txt"), filesOnly.map { File(it.filePath).name })
    }

    fun testOursMergeReportsWhatItDiscardedRatherThanNothing() {
        git("checkout", "-b", "history")
        write("incoming.txt", "intentionally excluded\n")
        commit()
        git("checkout", "trust")
        git("merge", "--no-ff", "-s", "ours", "history", "-m", "keep trust tree")
        val merge = git("rev-parse", "HEAD")
        // Deliberately inverted, and the honest cost of union semantics: `-s ours` has no
        // net change against the FIRST parent, so this used to copy nothing. Against the
        // other parent it dropped incoming.txt, and that is exactly the kind of thing
        // first-parent-only hid. Reporting it beats silence.
        val entries = resolve(GitSelectionCollector(logger).collect(event(merge)))
        assertEquals(listOf("incoming.txt"), entries.map { File(it.filePath).name })
    }

    fun testInitialAndOrdinaryCommitNodes() {
        val initial = git("rev-parse", "HEAD")
        assertEquals("base\n", resolve(GitSelectionCollector(logger).collect(event(initial, useCommitSelection = false))).single().contentFromRevision)
        write("shared.txt", "ordinary edit\n")
        commit()
        val ordinary = git("rev-parse", "HEAD")
        assertEquals("ordinary edit\n", resolve(GitSelectionCollector(logger).collect(event(ordinary))).single().contentFromRevision)
    }

    fun testInterleavedBranchesMergedIntoDevelopUnionEveryParent() {
        git("branch", "-m", "develop")
        write("deleted.txt", "legacy\n")
        write("old.txt", "stable renamed content\n")
        commit()
        git("branch", "history")
        git("checkout", "-b", "trust")
        write("shared.txt", "trust\n")
        write("trust.txt", "already integrated\n")
        commit()
        git("checkout", "history")
        write("history.txt", "history v1\n")
        commit()
        git("merge", "--no-ff", "trust", "-m", "sync trust into history")
        write("shared.txt", "trust with history\n")
        write("history.txt", "history v2\n")
        git("rm", "deleted.txt")
        git("mv", "old.txt", "renamed.txt")
        commit()
        git("checkout", "develop")
        write("develop.txt", "develop only\n")
        commit()
        git("merge", "--no-ff", "trust", "-m", "trust into develop")
        write("shared.txt", "hardened trust\n")
        commit()
        val before = git("rev-parse", "HEAD")
        git("merge", "--no-ff", "history", expectedExit = 1)
        assertEquals("shared.txt", git("diff", "--name-only", "--diff-filter=U"))
        write("shared.txt", "RESOLVED hardened trust with history\n")
        commit()
        val merge = git("rev-parse", "HEAD")
        // Deliberately inverted: against the first parent alone this is the four-file set
        // below; the union adds develop.txt, which the `history` parent never had. That
        // file really did change in this merge relative to one of its parents.
        assertEquals(
            setOf("deleted.txt", "renamed.txt", "shared.txt", "history.txt"),
            git("diff", "--name-only", before, merge).lines().toSet()
        )
        val expectedPaths = setOf("deleted.txt", "renamed.txt", "shared.txt", "history.txt", "develop.txt")
        write("shared.txt", "LATER COMMIT\n")
        commit()
        write("history.txt", "UNCOMMITTED\n")
        val entries = resolve(GitSelectionCollector(logger).collect(event(merge)))
        assertEquals(expectedPaths, entries.map { File(it.filePath).name }.toSet())
        for (entry in entries) {
            val revision = if (entry.changeType == ChangeTypeLabel.DELETED) before else merge
            assertEquals(git("show", "$revision:${File(entry.filePath).name}"), entry.contentFromRevision?.trimEnd())
        }
        // Exercise the actual copy payload and restore parser without changing the
        // system clipboard while a user may be checking the live sandbox IDE.
        val payload = GitClipboardPayloadBuilder.build(entries, emptyList(),
            ClipboardPathResolver.fromRootPaths(listOf(root.path), root.path), null,
            com.intellij.openapi.progress.EmptyProgressIndicator())
        val parsed = ClipboardRestoreParser().parse(payload.text, "// file: \$FILE_PATH")
        assertEquals(expectedPaths, parsed.map { it.path }.toSet())
        assertTrue(payload.text.contains("RESOLVED hardened trust with history"))
        assertTrue(!payload.text.contains("LATER COMMIT"))
        assertTrue(!payload.text.contains("UNCOMMITTED"))
        assertTrue(!payload.text.contains("already integrated"))
        assertTrue(payload.text.contains("develop only"), "the second parent's side must be in the union")
    }

    fun testShallowBoundaryCommitIsRefusedInsteadOfCopyingTheWholeTree() {
        git("checkout", "-b", "history")
        write("incoming.txt", "incoming\n")
        commit()
        git("checkout", "trust")
        write("receiver.txt", "receiver\n")
        commit()
        git("merge", "--no-ff", "history", "-m", "merge")

        // --depth 1 grafts the tip so it looks parentless. file:// is required: git ignores
        // --depth for a plain local path clone.
        val shallow = java.nio.file.Files.createTempDirectory("clipcode-shallow").toFile()
        val clone = File(shallow, "clone")
        runIn(shallow, "git", "clone", "--depth", "1", "file://${root.absolutePath}", "clone")
        val cloneVf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(clone)!!
        ProjectLevelVcsManager.getInstance(project).setDirectoryMappings(
            listOf(VcsDirectoryMapping(clone.path, GitVcs.NAME)))
        try {
            // Precondition: git really does hide the parent, otherwise this test proves nothing.
            assertEquals(1, runIn(clone, "git", "rev-list", "--parents", "-n", "1", "HEAD")
                .trim().split(" ").size)
            val sha = runIn(clone, "git", "rev-parse", "HEAD").trim()
            val selection = GitSelectionCollector.Selection(
                emptyList(), emptyList(), emptySet(), emptySet(),
                SelectionSource.GIT_LOG_OR_HISTORY, CommitId(HashImpl.build(sha), cloneVf))
            val failure = try {
                resolve(selection); null
            } catch (e: java.util.concurrent.ExecutionException) {
                e.cause
            }
            assertTrue(failure?.message?.contains("shallow") == true,
                "a shallow boundary must be refused with a reason, got: ${failure?.message}")
        } finally {
            ProjectLevelVcsManager.getInstance(project).setDirectoryMappings(
                listOf(VcsDirectoryMapping(root.path, GitVcs.NAME)))
            shallow.deleteRecursively()
        }
    }

    fun testBinaryBlobInACommitIsNotDecodedAsText() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x01, 0x02, 0xFF.toByte(), 0xFE.toByte(), 0x00, 0x7F)
        File(root, "logo.png").writeBytes(png)
        rootVf.refresh(false, true)
        write("notes.txt", "plain text\n")
        commit()
        val sha = git("rev-parse", "HEAD")

        val entries = resolve(GitSelectionCollector(logger).collect(event(sha)))
            .associateBy { File(it.filePath).name }
        assertEquals(setOf("logo.png", "notes.txt"), entries.keys)
        assertEquals("plain text\n", entries.getValue("notes.txt").contentFromRevision)
        // Decoding a PNG as text yields U+FFFD soup that Paste & Restore would write back
        // over the real asset, so the revision read must decline it.
        // JUnit3's TestCase.assertNull(String, Object) would shadow kotlin.test's here.
        kotlin.test.assertNull(entries.getValue("logo.png").contentFromRevision,
            "binary blob must not be decoded as text")
    }

    private fun runIn(dir: File, vararg cmd: String): String {
        val process = ProcessBuilder(*cmd).directory(dir).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
        return output
    }

    private fun event(sha: String, place: String = "Vcs.Log.ContextMenu", changes: Array<Change> = emptyArray(), useCommitSelection: Boolean = true): AnActionEvent {
        val id = CommitId(HashImpl.build(sha), rootVf)
        val log = Proxy.newProxyInstance(VcsLog::class.java.classLoader, arrayOf(VcsLog::class.java)) { _, method, _ ->
            when (method.name) {
                "getSelectedCommits" -> listOf(id)
                "toString" -> "TestVcsLog"
                else -> null
            }
        } as VcsLog
        val commitSelection = Proxy.newProxyInstance(VcsLogCommitSelection::class.java.classLoader,
            arrayOf(VcsLogCommitSelection::class.java)) { _, method, _ ->
            when (method.name) {
                "getCommits" -> listOf(id)
                "toString" -> "TestCommitSelection"
                else -> null
            }
        } as VcsLogCommitSelection
        return AnActionEvent.createFromDataContext(place, Presentation(), SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(VcsLogDataKeys.VCS_LOG, if (useCommitSelection) null else log)
            .add(VcsLogDataKeys.VCS_LOG_COMMIT_SELECTION, if (useCommitSelection) commitSelection else null)
            .add(VcsDataKeys.CHANGES, changes)
            .build())
    }

    private fun resolve(selection: GitSelectionCollector.Selection) =
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread<List<GitContentResolver.ResolvedGitEntry>> {
            GitContentResolver(logger).resolve(project, selection)
        }.get(30, java.util.concurrent.TimeUnit.SECONDS)

    private fun write(path: String, content: String) {
        File(root, path).writeText(content)
        rootVf.refresh(false, true)
    }

    private fun commit() { git("add", "-A"); git("commit", "-m", "change") }

    private fun git(vararg args: String, expectedExit: Int = 0): String {
        val process = ProcessBuilder(listOf("git") + args).directory(root).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(expectedExit, process.waitFor(), output)
        return output.trim()
    }
}
