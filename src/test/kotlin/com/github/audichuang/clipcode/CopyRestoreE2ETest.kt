package com.github.audichuang.clipcode

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.ChangeListManagerImpl
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.vcsUtil.VcsUtil
import git4idea.GitVcs
import git4idea.changes.GitChangeUtils
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CopyRestoreE2ETest : BasePlatformTestCase() {
    private val logger = Logger.getInstance(CopyRestoreE2ETest::class.java)
    private val resolver = GitContentResolver(logger)
    private val targetDirs = mutableListOf<Path>()

    override fun setUp() {
        super.setUp()
        configureSettings()
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(""), null)
    }

    override fun tearDown() {
        try {
            ProjectLevelVcsManager.getInstance(project).setDirectoryMappings(emptyList())
            targetDirs.forEach { it.toFile().deleteRecursively() }
            File(project.basePath!!, ".git").deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun testScenario1GitLogModifiedCopiesCommitRevisionInsteadOfWorkingTree() {
        initGitRepo()
        writeRepoFile("src/User.kt", "val version = \"v1\"")
        val commitA = commit("A")
        writeRepoFile("src/User.kt", "val version = \"v2\"")
        val commitB = commit("B")
        writeRepoFile("src/User.kt", "val version = \"wt\"")

        val change = gitRangeChange(commitA, commitB, "src/User.kt")
        val entries = resolver.resolve(project, gitLogSelection(change))
        val output = copyResolvedEntries(entries)

        assertEquals(1, entries.size)
        assertNull(entries.single().virtualFile)
        assertContains(output, "v2")
        assertFalse(output.contains("wt"), "Git Log copy must not read working-tree content")
    }

    fun testScenario2GitLogDeletedCopiesBeforeRevisionInsteadOfHeadFallback() {
        initGitRepo()
        writeRepoFile("src/Tmp.kt", "val version = \"v1\"")
        val commitA = commit("A")
        deleteRepoFile("src/Tmp.kt")
        val commitB = commit("B")
        writeRepoFile("src/Tmp.kt", "val version = \"v3\"")
        commit("C")

        val change = gitRangeChange(commitA, commitB, "src/Tmp.kt")
        val entries = resolver.resolve(project, gitLogSelection(change))
        val output = copyResolvedEntries(entries)

        assertEquals(1, entries.size)
        assertEquals(ChangeTypeLabel.DELETED, entries.single().changeType)
        assertNull(entries.single().virtualFile)
        assertContains(output, "[DELETED]")
        assertContains(output, "v1")
        assertFalse(output.contains("v3"), "Git Log deleted copy must not read HEAD fallback content")
    }

    fun testScenario3LocalChangesCopyUsesWorkingTreeContent() {
        initGitRepo()
        writeRepoFile("src/Local.kt", "val version = \"v1\"")
        commit("A")
        writeRepoFile("src/Local.kt", "val version = \"wt\"")

        val change = localChange("src/Local.kt")
        val entries = resolver.resolve(project, localSelection(change))
        val output = copyResolvedEntries(entries)

        assertEquals(1, entries.size)
        assertTrue(entries.single().hasVirtualFileContent)
        assertNull(entries.single().contentFromRevision)
        assertContains(output, "wt")
        assertFalse(output.contains("v1"), "Local Changes copy should use working-tree content")
    }

    fun testScenario4CopyThenPasteCreatesTargetFileWithCommitContent() {
        initGitRepo()
        writeRepoFile("src/Pair.kt", "val version = \"commit\"")
        val commitA = commit("A")
        writeRepoFile("src/Pair.kt", "val version = \"working-tree\"")
        val targetRoot = createTargetRoot("clipcode-e2e-paste-create")

        val change = commitChange(commitA, "src/Pair.kt")
        val copiedText = copyResolvedEntries(resolver.resolve(project, gitLogSelection(change)))
        val parsedEntries = ClipboardRestoreParser().parse(copiedText, HEADER_FORMAT)
        val plan = RestorePlanBuilder(targetResolver(targetRoot)).build(parsedEntries)
        val result = RestoreExecutor(project).execute(plan, overwriteExisting = false, skipExisting = false)
        val restoredFile = targetRoot.resolve("src/Pair.kt")

        assertEquals(1, plan.createOperations.size)
        assertEquals(plan.createOperations.size, result.createdCount)
        assertTrue(result.errors.isEmpty(), "Restore errors: ${result.errors}")
        assertTrue(restoredFile.exists())
        assertEquals("val version = \"commit\"", restoredFile.readText())
        assertFalse(restoredFile.readText().contains("working-tree"))
    }

    /**
     * Scenario 6: git mv 後從 Git Log 複製，clipboard header 應含 [MOVED] 標籤。
     * 覆蓋這次新增的 'R'/'C' status code 與 ChangeTypeLabel.MOVED 端到端路徑。
     */
    fun testScenario6CopyMovedFileFromGitLogProducesMovedLabel() {
        initGitRepo()
        writeRepoFile("src/Renamed.kt", "val version = \"v1\"")
        commit("A")
        // git mv 觸發 rename detection
        runGit("mv", "src/Renamed.kt", "src/RenamedNew.kt")
        runGit("commit", "-m", "rename")
        refreshRepoRoot()
        val commitB = runGit("rev-parse", "HEAD").trim()

        // 用 commitChange (getRevisionChanges 內建 rename detection)
        // 而非 gitRangeChange (filePath 過濾會讓 rename detection 拿不到 pair)
        val change = commitChange(commitB, "src/RenamedNew.kt")
        val entries = resolver.resolve(project, gitLogSelection(change))
        val output = copyResolvedEntries(entries)

        assertEquals(1, entries.size)
        val entry = entries.single()
        // Git rename detection 啟用後，change.type 應為 MOVED
        assertEquals(ChangeTypeLabel.MOVED, entry.changeType)
        assertContains(output, "[MOVED]")
        // 內容仍應拿到（commit B 的 RenamedNew.kt = v1 內容）
        assertContains(output, "v1")
    }

    /**
     * Scenario 7: 非 UTF-8 編碼檔案複製必須走檔案 charset 解碼，而非強制 UTF-8。
     * 覆蓋 VfsUtilCore.loadText 取代 String(bytes, UTF_8) 的改動。
     */
    fun testScenario7CopyNonUtf8FileUsesFileCharset() {
        val chineseText = "你好世界\n版本 = v1"
        // UTF-16 LE BOM 0xFF 0xFE + UTF-16 bytes
        val utf16Bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            chineseText.toByteArray(Charsets.UTF_16LE)
        val targetFile = File(project.basePath!!, "src/Utf16.kt").apply {
            parentFile.mkdirs()
            writeBytes(utf16Bytes)
        }
        val vFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(targetFile)
            ?: error("Failed to refresh UTF-16 file")

        val pathResolver = ClipboardPathResolver.fromRootPaths(listOf(repoRootPath), repoRootPath)
        CopyFileContentAction().performCopyFilesContent(project, arrayOf(vFile)) { _, _ ->
            "// file: ${pathResolver.toClipboardPath(vFile.path)}"
        }
        val output = clipboardText()

        // 必須拿到正確解碼的中文，而非 UTF-8 強制解碼後的亂碼
        assertContains(output, "你好世界")
        assertContains(output, "版本 = v1")
        // 若被當 UTF-8 解碼，BOM 0xFF 0xFE 會出現 REPLACEMENT CHARACTER 或損壞字
        assertFalse(output.contains('�'), "UTF-8 強制解碼會出現 \\uFFFD replacement char")
    }

    /**
     * Scenario 8: paste 對既存目標檔，overwriteExisting=true 應覆蓋。
     * 覆蓋 PasteAndRestoreFilesAction 改用 onSuccess() callback 後的 overwrite 路徑。
     */
    fun testScenario8PasteOverwriteReplacesExistingFile() {
        initGitRepo()
        writeRepoFile("src/Overwrite.kt", "val version = \"new-from-clipboard\"")
        val commitA = commit("A")
        val targetRoot = createTargetRoot("clipcode-e2e-paste-overwrite")
        val targetFile = targetRoot.resolve("src/Overwrite.kt")
        targetFile.parent.createDirectories()
        targetFile.writeText("// stale content that must be replaced")
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(targetFile)

        val change = commitChange(commitA, "src/Overwrite.kt")
        val copiedText = copyResolvedEntries(resolver.resolve(project, gitLogSelection(change)))
        val parsedEntries = ClipboardRestoreParser().parse(copiedText, HEADER_FORMAT)
        val plan = RestorePlanBuilder(targetResolver(targetRoot)).build(parsedEntries)

        // overwrite path：overwriteExisting=true
        val result = RestoreExecutor(project).execute(plan, overwriteExisting = true, skipExisting = false)

        assertEquals(0, result.createdCount, "已存在的檔不應被當 created")
        assertEquals(1, result.overwrittenCount)
        assertEquals(0, result.skippedExistingCount)
        assertTrue(result.errors.isEmpty(), "Restore errors: ${result.errors}")
        assertEquals("val version = \"new-from-clipboard\"", targetFile.readText())
        assertFalse(targetFile.readText().contains("stale content"))
    }

    /**
     * Scenario 9: paste 對既存目標檔，skipExisting=true 應保留原檔不覆蓋。
     * 覆蓋 RestoreExecutor.execute 的 skipExisting 分支。
     */
    fun testScenario9PasteSkipKeepsExistingFile() {
        initGitRepo()
        writeRepoFile("src/Skip.kt", "val version = \"from-clipboard-should-not-apply\"")
        val commitA = commit("A")
        val targetRoot = createTargetRoot("clipcode-e2e-paste-skip")
        val targetFile = targetRoot.resolve("src/Skip.kt")
        targetFile.parent.createDirectories()
        val originalContent = "// existing content that must be preserved"
        targetFile.writeText(originalContent)
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(targetFile)

        val change = commitChange(commitA, "src/Skip.kt")
        val copiedText = copyResolvedEntries(resolver.resolve(project, gitLogSelection(change)))
        val parsedEntries = ClipboardRestoreParser().parse(copiedText, HEADER_FORMAT)
        val plan = RestorePlanBuilder(targetResolver(targetRoot)).build(parsedEntries)

        // skip path：skipExisting=true (overwriteExisting 必須 false 才會走 skip)
        val result = RestoreExecutor(project).execute(plan, overwriteExisting = false, skipExisting = true)

        assertEquals(0, result.createdCount)
        assertEquals(0, result.overwrittenCount)
        assertEquals(1, result.skippedExistingCount, "Existing file 必須計入 skippedExistingCount")
        assertTrue(result.errors.isEmpty(), "Restore errors: ${result.errors}")
        // 原檔內容必須保留
        assertEquals(originalContent, targetFile.readText())
    }

    fun testScenario5CopyDeletedThenPasteDeletesTargetFile() {
        initGitRepo()
        writeRepoFile("src/DeleteMe.kt", "val version = \"v1\"")
        val commitA = commit("A")
        deleteRepoFile("src/DeleteMe.kt")
        val commitB = commit("B")
        val targetRoot = createTargetRoot("clipcode-e2e-paste-delete")
        val targetFile = targetRoot.resolve("src/DeleteMe.kt")
        targetFile.parent.createDirectories()
        targetFile.writeText("target content")

        val change = gitRangeChange(commitA, commitB, "src/DeleteMe.kt")
        val copiedText = copyResolvedEntries(resolver.resolve(project, gitLogSelection(change)))
        val parsedEntries = ClipboardRestoreParser().parse(copiedText, HEADER_FORMAT)
        val plan = RestorePlanBuilder(targetResolver(targetRoot)).build(parsedEntries)
        val result = RestoreExecutor(project).execute(plan, overwriteExisting = false, skipExisting = false)

        assertEquals(1, plan.deleteOperations.size)
        assertEquals(1, result.deletedCount)
        assertTrue(result.errors.isEmpty(), "Restore errors: ${result.errors}")
        assertFalse(targetFile.exists())
    }

    private fun initGitRepo() {
        repoRoot.mkdirs()
        File(repoRoot, ".git").deleteRecursively()
        File(repoRoot, "src").deleteRecursively()
        runGit("init")
        runGit("config", "user.email", "clipcode-test@example.com")
        runGit("config", "user.name", "ClipCode Test")
        runGit("config", "commit.gpgsign", "false")
        registerGitRoot()
    }

    private fun registerGitRoot() {
        ProjectLevelVcsManager.getInstance(project)
            .setDirectoryMappings(listOf(VcsDirectoryMapping(repoRoot.absolutePath, GitVcs.NAME)))
        refreshRepoRoot()
    }

    private fun writeRepoFile(relativePath: String, content: String) {
        val file = File(repoRoot, relativePath)
        file.parentFile.mkdirs()
        file.writeText(content)
        refreshRepoRoot()
    }

    private fun deleteRepoFile(relativePath: String) {
        File(repoRoot, relativePath).delete()
        refreshRepoRoot()
    }

    private fun commit(message: String): String {
        runGit("add", "-A")
        runGit("commit", "-m", message)
        refreshRepoRoot()
        return runGit("rev-parse", "HEAD").trim()
    }

    private fun gitRangeChange(fromRevision: String, toRevision: String, relativePath: String): Change {
        val filePath = VcsUtil.getFilePath(File(repoRoot, relativePath), false)
        val changes = GitChangeUtils.getDiff(project, repoRootVf(), fromRevision, toRevision, listOf(filePath))
        return changes.singleFor(relativePath)
    }

    private fun commitChange(revision: String, relativePath: String): Change =
        GitChangeUtils.getRevisionChanges(project, repoRootVf(), revision, false, true, false)
            .changes
            .singleFor(relativePath)

    private fun localChange(relativePath: String): Change {
        val filePath = VcsUtil.getFilePath(File(repoRoot, relativePath), false)
        VcsDirtyScopeManager.getInstance(project).fileDirty(filePath)
        ChangeListManagerImpl.getInstanceImpl(project).ensureUpToDate()
        ChangeListManagerImpl.getInstanceImpl(project).waitEverythingDoneInTestMode()

        return ChangeListManager.getInstance(project)
            .allChanges
            .singleFor(relativePath)
    }

    private fun gitLogSelection(change: Change): GitSelectionCollector.Selection =
        selection(change, SelectionSource.GIT_LOG_OR_HISTORY)

    private fun localSelection(change: Change): GitSelectionCollector.Selection =
        selection(change, SelectionSource.LOCAL_CHANGES_OR_COMMIT_UI)

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

    private fun copyResolvedEntries(entries: List<GitContentResolver.ResolvedGitEntry>): String {
        val contentEntries = entries.filter { it.hasContent }
        val deletedMarkerEntries = entries.filter { it.changeType == ChangeTypeLabel.DELETED && !it.hasContent }
        invokeCopyResolvedEntries(
            project = project,
            contentEntries = contentEntries,
            deletedMarkerEntries = deletedMarkerEntries,
            pathResolver = ClipboardPathResolver.fromRootPaths(listOf(repoRootPath), repoRootPath),
            settings = CopyFileContentSettings.getInstance(project)
        )
        return clipboardText()
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

    private fun targetResolver(targetRoot: Path): ClipboardPathResolver =
        ClipboardPathResolver.fromRootPaths(
            listOf(targetRoot.systemIndependentPath()),
            targetRoot.systemIndependentPath()
        )

    private fun createTargetRoot(prefix: String): Path =
        Files.createTempDirectory(prefix).also {
            targetDirs.add(it)
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it)
        }

    private fun refreshRepoRoot() {
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(repoRoot)?.refresh(false, true)
    }

    private fun repoRootVf() =
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(repoRoot)
            ?: error("Unable to refresh repo root: $repoRoot")

    private fun runGit(vararg args: String): String {
        val process = ProcessBuilder(listOf(gitExecutable()) + args)
            .directory(repoRoot)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        assertEquals(0, exitCode, "git ${args.joinToString(" ")} failed:\n$output")
        return output
    }

    private fun configureSettings() {
        val state = CopyFileContentSettings.getInstance(project)!!.state
        state.headerFormat = HEADER_FORMAT
        state.preText = ""
        state.postText = ""
        state.addExtraLineBetweenFiles = false
        state.setMaxFileCount = false
        state.showCopyNotification = false
        state.maxFileSizeKB = 500
    }

    private fun clipboardText(): String =
        Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as String

    private fun Collection<Change>.singleFor(relativePath: String): Change {
        val normalizedSuffix = "/${relativePath.replace('\\', '/')}"
        return singleOrNull { change ->
            listOfNotNull(change.afterRevision?.file?.path, change.beforeRevision?.file?.path)
                .map { it.replace('\\', '/') }
                .any { it.endsWith(normalizedSuffix) }
        } ?: error(
            "Expected one Change for $relativePath, got ${size}: " +
                joinToString { change ->
                    listOfNotNull(change.afterRevision?.file?.path, change.beforeRevision?.file?.path)
                        .joinToString(" -> ")
                }
        )
    }

    private fun gitExecutable(): String =
        listOf("/opt/homebrew/bin/git", "/usr/local/bin/git", "/usr/bin/git", "git")
            .firstOrNull { it == "git" || File(it).canExecute() }
            ?: "git"

    private val repoRoot: File
        get() = File(project.basePath!!)

    private val repoRootPath: String
        get() = repoRoot.absolutePath.replace('\\', '/')

    private fun Path.systemIndependentPath(): String = toString().replace('\\', '/')

    companion object {
        private const val HEADER_FORMAT = "// file: \$FILE_PATH"
    }
}
