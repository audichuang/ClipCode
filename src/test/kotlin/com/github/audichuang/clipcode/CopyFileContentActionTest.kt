package com.github.audichuang.clipcode

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import kotlin.io.path.createDirectories
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * JUnit4 runner (IntelliJ's UsefulTestCase.runBareTestRule keeps setUp/tearDown) so an `org.junit.Assume`
 * reports a test as skipped: under the JUnit3 runner an AssumptionViolatedException is a failure. Windows
 * cannot create some fixtures (control characters or `\\` in a name, chmod-unreadable items, symlinks
 * without the privilege); those parts are guarded by trying the real operation. The OS name is read only
 * by [posix], to fail loudly if a guard ever drops a fixture on Linux or macOS.
 */
@RunWith(JUnit4::class)
class CopyFileContentActionTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        configureSettings()
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(""), null)
    }

    @Test
    fun testCopyAllOpenTabsUsesProjectRootRelativeHeaderWhenNestedContentRootIsFirst() {
        val file = myFixture.addFileToProject("inv-svc-adv/src/main/Foo.kt", "class Foo").virtualFile
        configureOnlyNestedContentRoot("inv-svc-adv")
        closeOpenFiles()

        myFixture.openFileInEditor(file)
        CopyAllOpenTabsAction().actionPerformed(actionEvent())

        val clipboard = clipboardText()
        assertContains(clipboard, "inv-svc-adv/src/main/Foo.kt")
        assertFalse(clipboard.contains("// file: src/main/Foo.kt"))
    }

    @Test
    fun testCopyAllOpenTabsPrefixesSiblingContentRootLabelFromProjectRoots() {
        val projectRoot = project.baseDir!!
        val siblingRootPath = Files.createTempDirectory("clipcode-sibling-root")
            .resolve("shared-lib")
            .createDirectories()
        val siblingRoot = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(siblingRootPath)
            ?: error("Unable to create sibling content root")
        closeOpenFiles()

        lateinit var file: VirtualFile
        ApplicationManager.getApplication().runWriteAction {
            PsiTestUtil.removeAllRoots(module, null)
            PsiTestUtil.addContentRoot(module, projectRoot)
            PsiTestUtil.addContentRoot(module, siblingRoot)
            val sourceDirectory = VfsUtil.createDirectories("${siblingRoot.path}/src")
            file = sourceDirectory.findChild("App.kt")
                ?: sourceDirectory.createChildData(this, "App.kt")
            VfsUtil.saveText(file, "class App")
        }

        assertEquals(
            siblingRoot.path,
            ProjectRootManager.getInstance(project).fileIndex.getContentRootForFile(file)?.path
        )

        myFixture.openFileInEditor(file)
        CopyAllOpenTabsAction().actionPerformed(actionEvent())

        val clipboard = clipboardText()
        assertContains(clipboard, "shared-lib/src/App.kt")
        assertFalse(clipboard.contains(file.path))
    }

    @Test
    fun testPerformCopyForSingleFileEmitsContent() {
        val file = myFixture.addFileToProject("src/Single.kt", "single-content").virtualFile
        CopyFileContentAction().performCopyFilesContent(project, arrayOf(file))
        val text = clipboardText()
        assertContains(text, "single-content")
        assertContains(text, "Single.kt")
    }

    @Test
    fun testPerformCopyRecursesIntoDirectory() {
        myFixture.addFileToProject("pkg/sub/A.kt", "AAA")
        myFixture.addFileToProject("pkg/sub/B.kt", "BBB")
        myFixture.addFileToProject("pkg/C.kt", "CCC")
        val dir = myFixture.findFileInTempDir("pkg") ?: error("dir not found")
        CopyFileContentAction().performCopyFilesContent(project, arrayOf(dir))
        val text = clipboardText()
        assertContains(text, "AAA")
        assertContains(text, "BBB")
        assertContains(text, "CCC")
    }

    @Test
    fun testPreAndPostTextWrapClipboard() {
        val state = CopyFileContentSettings.getInstance(project)!!.state
        state.preText = "=== BEFORE ==="
        state.postText = "=== AFTER ==="
        val file = myFixture.addFileToProject("Wrap.kt", "wrapped").virtualFile
        CopyFileContentAction().performCopyFilesContent(project, arrayOf(file))
        val text = clipboardText()
        // The `// clipcode-root:` line legitimately precedes the pre text — it always has,
        // and it now appears for projects that previously had no single root name.
        val body = text.lineSequence()
            .dropWhile { it.startsWith(ClipboardRestoreParser.SOURCE_ROOT_MARKER) }
            .joinToString("\n")
        assertTrue(body.startsWith("=== BEFORE ==="), "Expected pre text; got: $text")
        assertTrue(text.trimEnd().endsWith("=== AFTER ==="), "Expected post text; got: $text")
    }

    @Test
    fun testExcludePatternSkipsFile() {
        val state = CopyFileContentSettings.getInstance(project)!!.state
        state.useFilters = true
        state.useExcludeFilters = true
        state.filterRules = mutableListOf(
            CopyFileContentSettings.FilterRule(
                type = CopyFileContentSettings.FilterType.PATTERN,
                action = CopyFileContentSettings.FilterAction.EXCLUDE,
                value = "*.txt",
                enabled = true
            )
        )
        val kt = myFixture.addFileToProject("Keep.kt", "keep-me").virtualFile
        val txt = myFixture.addFileToProject("Skip.txt", "skip-me").virtualFile
        CopyFileContentAction().performCopyFilesContent(project, arrayOf(kt, txt))
        val text = clipboardText()
        assertContains(text, "keep-me")
        assertFalse(text.contains("skip-me"), "Excluded file content should not appear")
    }

    @Test
    fun testIncludePatternKeepsOnlyMatching() {
        val state = CopyFileContentSettings.getInstance(project)!!.state
        state.useFilters = true
        state.useIncludeFilters = true
        state.filterRules = mutableListOf(
            CopyFileContentSettings.FilterRule(
                type = CopyFileContentSettings.FilterType.PATTERN,
                action = CopyFileContentSettings.FilterAction.INCLUDE,
                value = "*.kt",
                enabled = true
            )
        )
        val kt = myFixture.addFileToProject("Inc.kt", "kt-content").virtualFile
        val java = myFixture.addFileToProject("Out.java", "java-content").virtualFile
        CopyFileContentAction().performCopyFilesContent(project, arrayOf(kt, java))
        val text = clipboardText()
        assertContains(text, "kt-content")
        assertFalse(text.contains("java-content"), "Non-matching file should be filtered")
    }

    @Test
    fun testDisabledFilterRuleIsIgnored() {
        val state = CopyFileContentSettings.getInstance(project)!!.state
        state.useFilters = true
        state.useExcludeFilters = true
        state.filterRules = mutableListOf(
            CopyFileContentSettings.FilterRule(
                type = CopyFileContentSettings.FilterType.PATTERN,
                action = CopyFileContentSettings.FilterAction.EXCLUDE,
                value = "*.kt",
                enabled = false   // disabled
            )
        )
        val kt = myFixture.addFileToProject("Active.kt", "still-here").virtualFile
        CopyFileContentAction().performCopyFilesContent(project, arrayOf(kt))
        val text = clipboardText()
        assertContains(text, "still-here")
    }

    @Test
    fun testFileSizeLimitSkipsOversizedFile() {
        val state = CopyFileContentSettings.getInstance(project)!!.state
        state.maxFileSizeKB = 1  // 1 KB
        val big = myFixture.addFileToProject("Big.kt", "x".repeat(2048)).virtualFile
        val small = myFixture.addFileToProject("Small.kt", "small").virtualFile
        CopyFileContentAction().performCopyFilesContent(project, arrayOf(big, small))
        val text = clipboardText()
        // 跳過的檔仍會輸出 header + skip marker
        assertContains(text, "Big.kt")
        assertContains(text, "File skipped")
        assertContains(text, "small")
    }

    // === X3: when everything selected is skipped, the clipboard is left alone and the skips are named ===

    /** The one "Nothing to copy" notice; shown even with the copy notification turned off. */
    private fun nothingToCopyNotice(messages: List<String>): String {
        // kotlin.test explicitly: TestCase.assertEquals(String, String, String) takes the message first.
        kotlin.test.assertEquals(SENTINEL, clipboardText(), "the clipboard must be left untouched")
        assertTrue(messages.none { it.contains("files copied") || it.contains("file copied") }, "$messages")
        return messages.singleOrNull { it.contains("<b>Nothing to copy</b>") } ?: error("$messages")
    }

    private fun setSentinel() = Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(SENTINEL), null)

    @Test
    fun testOnlyOversizedFileCopiesNothing() {
        CopyFileContentSettings.getInstance(project)!!.state.maxFileSizeKB = 1
        val big = myFixture.addFileToProject("OnlyBig.kt", "x".repeat(2048)).virtualFile
        setSentinel()
        val messages = collectNotifications()
        val chooser = RecordingChooser(NonTextChoice.INCLUDE)
        CopyFileContentAction(chooser).performCopyFilesContent(project, arrayOf(big))
        assertTrue(chooser.summaries.isEmpty())
        assertContains(nothingToCopyNotice(messages), "1 skipped: size exceeded.")
    }

    @Test
    fun testOnlyNonTextFileExcludedCopiesNothing() {
        val png = binaryFile("onlypng/logo.png", pngBytes(31))
        setSentinel()
        val messages = collectNotifications()
        val chooser = RecordingChooser(NonTextChoice.EXCLUDE)
        CopyFileContentAction(chooser).performCopyFilesContent(project, arrayOf(png))
        assertEquals(1, chooser.summaries.size, "the dialog is asked first")
        val notice = nothingToCopyNotice(messages)
        assertContains(notice, "1 skipped: not UTF-8 text or unreadable.<br>")
        assertContains(notice, "logo.png")
    }

    @Test
    fun testOnlyUnportableNameCopiesNothing() {
        val root = Files.createTempDirectory("clipcode-only-unportable")
        try {
            val odd = oddFolder(root)
            val lead = odd.dir.findChild(" lead.txt") ?: error("no lead vfs")
            setSentinel()
            val messages = collectNotifications()
            CopyFileContentAction(RecordingChooser(NonTextChoice.INCLUDE)).performCopyFilesContent(project, arrayOf(lead))
            val notice = nothingToCopyNotice(messages)
            assertContains(notice, "Name not portable: 1<br>")
            assertContains(notice, " lead.txt")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun testOnlyUnreadableFolderCopiesNothing() {
        val root = Files.createTempDirectory("clipcode-only-locked")
        val lockedDir = root.resolve("lockedDir").createDirectories()
        Files.writeString(lockedDir.resolve("inner.txt"), "inner")
        try {
            val dir = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root) ?: error("no vfs dir")
            VfsUtil.markDirtyAndRefresh(false, true, true, dir)
            val lockedVfs = dir.findChild("lockedDir") ?: error("no locked vfs")
            lockedDir.toFile().setReadable(false, false)
            lockedDir.toFile().setExecutable(false, false)
            if (posix) assertFalse(Files.isReadable(lockedDir), "test setup: chmod must take effect on POSIX")
            assumeFalse("chmod cannot make a folder unreadable here (Windows)", Files.isReadable(lockedDir))
            VfsUtil.markDirtyAndRefresh(false, true, true, dir)
            setSentinel()
            val messages = collectNotifications()
            CopyFileContentAction(RecordingChooser(NonTextChoice.INCLUDE)).performCopyFilesContent(project, arrayOf(lockedVfs))
            val notice = nothingToCopyNotice(messages)
            assertContains(notice, "1 skipped: not UTF-8 text or unreadable.<br>")
            assertContains(notice, "lockedDir")
        } finally {
            lockedDir.toFile().setReadable(true, false)
            lockedDir.toFile().setExecutable(true, false)
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun testFileCountLimitTruncates() {
        val state = CopyFileContentSettings.getInstance(project)!!.state
        state.setMaxFileCount = true
        state.fileCountLimit = 2
        val files = (1..5).map {
            myFixture.addFileToProject("multi/F$it.kt", "content-$it").virtualFile
        }.toTypedArray()
        CopyFileContentAction().performCopyFilesContent(project, files)
        val text = clipboardText()
        // 至少前兩個檔應該在裡面
        assertContains(text, "content-1")
        assertContains(text, "content-2")
        // 第 5 個不應該在
        assertFalse(text.contains("content-5"), "File count limit should truncate")
    }

    @Test
    fun testExtraLineBetweenFilesAddsBlankLines() {
        val state = CopyFileContentSettings.getInstance(project)!!.state
        state.addExtraLineBetweenFiles = true
        val a = myFixture.addFileToProject("A.kt", "alpha").virtualFile
        val b = myFixture.addFileToProject("B.kt", "beta").virtualFile
        CopyFileContentAction().performCopyFilesContent(project, arrayOf(a, b))
        val text = clipboardText()
        // 兩個 file 之間應該有空白行
        val alphaIdx = text.indexOf("alpha")
        val betaIdx = text.indexOf("beta")
        assertTrue(alphaIdx >= 0 && betaIdx > alphaIdx)
        val between = text.substring(alphaIdx + "alpha".length, betaIdx)
        assertTrue(between.contains("\n\n"), "Expected blank line between files; got: '$between'")
    }

    @Test
    fun testDeduplicatesSamePath() {
        val file = myFixture.addFileToProject("Dup.kt", "dup-content").virtualFile
        CopyFileContentAction().performCopyFilesContent(project, arrayOf(file, file, file))
        val text = clipboardText()
        // 即使選了 3 次，應該只出現一次
        val occurrences = text.split("dup-content").size - 1
        kotlin.test.assertEquals(1, occurrences, "Expected single occurrence, got $occurrences")
    }

    @Test
    fun testActionPerformedWithoutSelectionShowsNotification() {
        // 沒有任何 selection 應該不丟例外，且不更動 clipboard
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection("untouched"), null)
        val event = AnActionEvent.createFromDataContext(
            "ClipCodeTest",
            Presentation(),
            SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .build()
        )
        CopyFileContentAction().actionPerformed(event)
        // clipboard 不會被覆蓋（因為早 return）
        assertEquals("untouched", clipboardText())
    }

    @Test
    fun testActionPerformedWithVirtualFileArray() {
        val a = myFixture.addFileToProject("X/A.kt", "alpha").virtualFile
        val b = myFixture.addFileToProject("X/B.kt", "beta").virtualFile
        val event = AnActionEvent.createFromDataContext(
            "ClipCodeTest",
            Presentation(),
            SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(a, b))
                .build()
        )
        CopyFileContentAction().actionPerformed(event)
        val text = clipboardText()
        assertContains(text, "alpha")
        assertContains(text, "beta")
    }

    @Test
    fun testActionPerformedWithSingleVirtualFile() {
        val a = myFixture.addFileToProject("only.kt", "only-content").virtualFile
        val event = AnActionEvent.createFromDataContext(
            "ClipCodeTest",
            Presentation(),
            SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.VIRTUAL_FILE, a)
                .build()
        )
        CopyFileContentAction().actionPerformed(event)
        assertContains(clipboardText(), "only-content")
    }

    @Test
    fun testPathFilterExcludeByAbsolutePath() {
        val state = CopyFileContentSettings.getInstance(project)!!.state
        val keepFile = myFixture.addFileToProject("keep/Keep.kt", "keep-content").virtualFile
        val skipFile = myFixture.addFileToProject("skip-me/Junk.kt", "junk-content").virtualFile
        state.useFilters = true
        state.useExcludeFilters = true
        state.filterRules = mutableListOf(
            CopyFileContentSettings.FilterRule(
                type = CopyFileContentSettings.FilterType.PATH,
                action = CopyFileContentSettings.FilterAction.EXCLUDE,
                value = skipFile.parent.path,  // absolute path
                enabled = true
            )
        )
        CopyFileContentAction().performCopyFilesContent(project, arrayOf(keepFile, skipFile))
        val text = clipboardText()
        assertContains(text, "keep-content")
        assertFalse(text.contains("junk-content"))
    }

    @Test
    fun testPathFilterIncludeByAbsolutePath() {
        val state = CopyFileContentSettings.getInstance(project)!!.state
        val included = myFixture.addFileToProject("only-this/A.kt", "match-in").virtualFile
        val excluded = myFixture.addFileToProject("other/B.kt", "match-out").virtualFile
        state.useFilters = true
        state.useIncludeFilters = true
        state.filterRules = mutableListOf(
            CopyFileContentSettings.FilterRule(
                type = CopyFileContentSettings.FilterType.PATH,
                action = CopyFileContentSettings.FilterAction.INCLUDE,
                value = included.parent.path,
                enabled = true
            )
        )
        CopyFileContentAction().performCopyFilesContent(project, arrayOf(included, excluded))
        val text = clipboardText()
        assertContains(text, "match-in")
        assertFalse(text.contains("match-out"), "Out-of-path file should not appear")
    }

    @Test
    fun testDirectoryFilteredOutByPathExcludeIsSkipped() {
        val state = CopyFileContentSettings.getInstance(project)!!.state
        val innerFile = myFixture.addFileToProject("skipdir/Sub.kt", "skipped-dir-content").virtualFile
        val dir = innerFile.parent
        state.useFilters = true
        state.useExcludeFilters = true
        state.filterRules = mutableListOf(
            CopyFileContentSettings.FilterRule(
                type = CopyFileContentSettings.FilterType.PATH,
                action = CopyFileContentSettings.FilterAction.EXCLUDE,
                value = dir.path,
                enabled = true
            )
        )
        CopyFileContentAction().performCopyFilesContent(project, arrayOf(dir))
        val text = clipboardText()
        assertFalse(text.contains("skipped-dir-content"), "Excluded directory content should not appear")
    }

    @Test
    fun testDirectoryFilteredInByPathIncludeIsKept() {
        val state = CopyFileContentSettings.getInstance(project)!!.state
        val inner = myFixture.addFileToProject("includedir/Inner.kt", "matched-dir-content").virtualFile
        val dir = inner.parent
        state.useFilters = true
        state.useIncludeFilters = true
        state.filterRules = mutableListOf(
            CopyFileContentSettings.FilterRule(
                type = CopyFileContentSettings.FilterType.PATH,
                action = CopyFileContentSettings.FilterAction.INCLUDE,
                value = dir.path,
                enabled = true
            )
        )
        CopyFileContentAction().performCopyFilesContent(project, arrayOf(dir))
        val text = clipboardText()
        assertContains(text, "matched-dir-content")
    }

    // Bridge: the real regular-copy path must produce byte-for-byte what the shared
    // ClipboardPayloadFormatter produces for the same inputs. This pins CopyFileContentAction
    // to the single cross-tool format surface that the golden fixtures verify against VS Code.
    @Test
    fun testRegularPayloadMatchesSharedFormatter() {
        val a = myFixture.addFileToProject("src/A.kt", "alpha();").virtualFile
        val b = myFixture.addFileToProject("src/B.kt", "beta();").virtualFile
        CopyFileContentAction().performCopyFilesContent(project, arrayOf(a, b))
        val actual = clipboardText()

        val resolver = com.intellij.openapi.application.ReadAction.compute<ClipboardPathResolver, RuntimeException> {
            ClipboardPathResolver.fromProject(project)
        }
        val expected = ClipboardPayloadFormatter.buildPayload(
            ClipboardPayloadFormatter.Options(
                headerFormat = "// file: \$FILE_PATH",
                preText = "",
                postText = "",
                addExtraLineBetweenFiles = false,
                files = listOf(
                    ClipboardPayloadFormatter.PayloadFile(resolver.toClipboardPath(a.path), "alpha();"),
                    ClipboardPayloadFormatter.PayloadFile(resolver.toClipboardPath(b.path), "beta();")
                ),
                sourceRoot = resolver.singleRootName()
            )
        )
        assertEquals(expected, actual)
    }

    @Test
    fun testEmptyFilesProcessedGracefully() {
        val empty = myFixture.addFileToProject("Empty.kt", "").virtualFile
        CopyFileContentAction().performCopyFilesContent(project, arrayOf(empty))
        // 空檔不會丟例外，但可能不會被加進輸出（readFileContents 返回空字串就 skip）
    }

    /**
     * A non-UTF-8 file is dropped on purpose — the wire carries no encoding, so it cannot
     * round-trip — but it used to be dropped in SILENCE, which is indistinguishable from
     * the file not being there. VS Code counts and reports the same drop
     * (copy.ts skippedUnreadableCount); the work-root contract requires both to.
     */
    @Test
    fun testNonUtf8FileIsCountedInTheCopyNotification() {
        val state = CopyFileContentSettings.getInstance(project)!!.state
        state.showCopyNotification = true
        val readable = myFixture.addFileToProject("Readable.txt", "readable").virtualFile
        val big5 = myFixture.addFileToProject("Big5.txt", "placeholder").virtualFile
        // Big5 bytes for 中文 — a valid file, and not valid UTF-8.
        ApplicationManager.getApplication().runWriteAction {
            big5.setBinaryContent(byteArrayOf(0xA4.toByte(), 0xA4.toByte(), 0xA4.toByte(), 0xE5.toByte()))
        }

        // Thread-safe for the same reason as collectNotifications.
        val messages: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
        project.messageBus.connect(testRootDisposable).subscribe(
            Notifications.TOPIC,
            object : Notifications {
                override fun notify(notification: Notification) {
                    messages.add(notification.content)
                }
            }
        )

        // Big5 is a non-text file now: Exclude is today's behaviour, which this test pins.
        CopyFileContentAction(RecordingChooser(NonTextChoice.EXCLUDE)).performCopyFilesContent(project, arrayOf(readable, big5))

        val text = clipboardText()
        assertContains(text, "readable")
        assertFalse(text.contains("Big5.txt"), "a non-UTF-8 file must not reach the clipboard")
        assertTrue(
            messages.any { it.contains("1 skipped: not UTF-8 text or unreadable") },
            "the drop must be reported, not silent: $messages"
        )
    }

    @Test
    fun testNotificationShownWhenSettingEnabled() {
        val state = CopyFileContentSettings.getInstance(project)!!.state
        state.showCopyNotification = true
        state.setMaxFileCount = true
        state.fileCountLimit = 1
        val a = myFixture.addFileToProject("F1.kt", "one").virtualFile
        val b = myFixture.addFileToProject("F2.kt", "two").virtualFile
        // file count limit 觸發 → 應該顯示 limit notification + statistics
        CopyFileContentAction().performCopyFilesContent(project, arrayOf(a, b))
        val text = clipboardText()
        assertContains(text, "one")
    }

    @Test
    fun testActionPerformedWithDirectory() {
        myFixture.addFileToProject("pack/A.kt", "AAA")
        myFixture.addFileToProject("pack/B.kt", "BBB")
        val dir = myFixture.findFileInTempDir("pack") ?: error("dir not found")
        val event = AnActionEvent.createFromDataContext(
            "ClipCodeTest",
            Presentation(),
            SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(dir))
                .build()
        )
        CopyFileContentAction().actionPerformed(event)
        val text = clipboardText()
        assertContains(text, "AAA")
        assertContains(text, "BBB")
    }

    // ---- Non-text files (spec non-text-files.md) ----

    private class RecordingChooser(private val choice: NonTextChoice) : NonTextChooser {
        val summaries = mutableListOf<NonTextCopySummary>()
        override fun choose(project: com.intellij.openapi.project.Project, summary: NonTextCopySummary): NonTextChoice {
            summaries.add(summary)
            return choice
        }
    }

    private fun binaryFile(path: String, bytes: ByteArray): VirtualFile {
        val file = myFixture.addFileToProject(path, "placeholder").virtualFile
        ApplicationManager.getApplication().runWriteAction { file.setBinaryContent(bytes) }
        return file
    }

    private fun pngBytes(seed: Int, size: Int = 64): ByteArray =
        ByteArray(size) { i -> if (i == 0) 0x89.toByte() else if (i == 1) 0 else (i * 31 + seed).toByte() }

    private val big5Bytes = byteArrayOf(0xA4.toByte(), 0xA4.toByte(), 0xA4.toByte(), 0xE5.toByte(), 0x2C, 0x31)

    private fun pathResolver(): ClipboardPathResolver =
        com.intellij.openapi.application.ReadAction.compute<ClipboardPathResolver, RuntimeException> {
            ClipboardPathResolver.fromProject(project)
        }

    private fun formatterPayload(files: List<ClipboardPayloadFormatter.PayloadFile>): String =
        ClipboardPayloadFormatter.buildPayload(
            ClipboardPayloadFormatter.Options(
                headerFormat = "// file: \$FILE_PATH",
                addExtraLineBetweenFiles = false,
                files = files,
                sourceRoot = pathResolver().singleRootName()
            )
        )

    private fun collectNotifications(): MutableList<String> {
        // Thread-safe: showPayloadNotification posts its stats from a pooled thread while the EDT posts
        // the "N files copied" notice; two concurrent ArrayList.add calls lost one of them (seen once
        // in a full run: only the stats message had been collected).
        val messages: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
        project.messageBus.connect(testRootDisposable).subscribe(
            Notifications.TOPIC,
            object : Notifications {
                override fun notify(notification: Notification) {
                    messages.add(notification.content)
                }
            }
        )
        return messages
    }

    /** The inline copy path and the shared formatter produce identical bytes for text + non-text files. */
    @Test
    fun testIncludeNonTextMatchesSharedFormatter() {
        val text = myFixture.addFileToProject("mix/A.kt", "alpha();").virtualFile
        val png = binaryFile("mix/logo.png", pngBytes(1))
        val csv = binaryFile("mix/data.csv", big5Bytes)
        val chooser = RecordingChooser(NonTextChoice.INCLUDE)
        CopyFileContentAction(chooser).performCopyFilesContent(project, arrayOf(text, png, csv))

        val r = pathResolver()
        val expected = formatterPayload(
            listOf(
                ClipboardPayloadFormatter.PayloadFile(r.toClipboardPath(text.path), "alpha();"),
                ClipboardPayloadFormatter.PayloadFile(r.toClipboardPath(png.path), nonText = pngBytes(1)),
                ClipboardPayloadFormatter.PayloadFile(r.toClipboardPath(csv.path), nonText = big5Bytes)
            )
        )
        val actual = clipboardText()
        assertEquals(expected, actual)
        val summary = chooser.summaries.single()
        assertEquals(
            listOf(r.toClipboardPath(png.path) to 64L, r.toClipboardPath(csv.path) to big5Bytes.size.toLong()),
            summary.carried.map { it.path to it.size }
        )
        assertEquals(NonTextCopy.utf8Length(actual), summary.estimatedPayloadBytes)
    }

    /**
     * X16: classification uses the bytes only, never IntelliJ's file type. A file of a binary file type
     * whose bytes are valid UTF-8 is text in both modes (Exclude changes here on purpose, spec case 11
     * exception); one whose bytes are not UTF-8 is carried by Include and skipped by Exclude, as before.
     */
    @Test
    fun testBinaryFileTypeHoldingUtf8IsTextInBothModes() {
        val utf8 = myFixture.addFileToProject("typed/plain.jar", "placeholder").virtualFile
        ApplicationManager.getApplication().runWriteAction { utf8.setBinaryContent("JAR-AS-TEXT".toByteArray()) }
        val raw = binaryFile("typed/raw.jar", big5Bytes)
        assertTrue(utf8.fileType.isBinary && raw.fileType.isBinary, "test setup: .jar must be a binary file type")
        val r = pathResolver()

        val include = RecordingChooser(NonTextChoice.INCLUDE)
        CopyFileContentAction(include).performCopyFilesContent(project, arrayOf(utf8, raw))
        assertEquals(
            formatterPayload(
                listOf(
                    ClipboardPayloadFormatter.PayloadFile(r.toClipboardPath(utf8.path), "JAR-AS-TEXT"),
                    ClipboardPayloadFormatter.PayloadFile(r.toClipboardPath(raw.path), nonText = big5Bytes)
                )
            ),
            clipboardText()
        )
        assertEquals(listOf(r.toClipboardPath(raw.path)), include.summaries.single().carried.map { it.path })

        CopyFileContentAction(RecordingChooser(NonTextChoice.EXCLUDE)).performCopyFilesContent(project, arrayOf(utf8, raw))
        assertEquals(
            formatterPayload(listOf(ClipboardPayloadFormatter.PayloadFile(r.toClipboardPath(utf8.path), "JAR-AS-TEXT"))),
            clipboardText()
        )
    }

    /** A placeholder-shaped first text line is escaped by the inline path exactly as by the formatter. */
    @Test
    fun testTextBodyUsesPlaceholderEscapeInBothModes() {
        val shaped = NonText.placeholderLine(3, "0".repeat(64)) + "\nYWJj"
        val file = myFixture.addFileToProject("shaped.txt", shaped).virtualFile
        val chooser = RecordingChooser(NonTextChoice.INCLUDE)
        CopyFileContentAction(chooser).performCopyFilesContent(project, arrayOf(file))
        assertTrue(chooser.summaries.isEmpty())
        val expected = formatterPayload(
            listOf(ClipboardPayloadFormatter.PayloadFile(pathResolver().toClipboardPath(file.path), shaped))
        )
        assertEquals(expected, clipboardText())
    }

    @Test
    fun testExcludeKeepsTodaysPayloadForFolderWithPng() {
        myFixture.addFileToProject("assets/readme.txt", "read me")
        binaryFile("assets/logo.png", pngBytes(2))
        val dir = myFixture.findFileInTempDir("assets") ?: error("dir not found")
        val readme = dir.findChild("readme.txt")!!
        val chooser = RecordingChooser(NonTextChoice.EXCLUDE)
        CopyFileContentAction(chooser).performCopyFilesContent(project, arrayOf(dir))

        assertEquals(1, chooser.summaries.size)
        val actual = clipboardText()
        assertFalse(actual.contains("base64 follows"))
        assertEquals(
            formatterPayload(
                listOf(ClipboardPayloadFormatter.PayloadFile(pathResolver().toClipboardPath(readme.path), "read me"))
            ),
            actual
        )
    }

    @Test
    fun testCancelCopiesNothing() {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection("untouched"), null)
        val png = binaryFile("c/logo.png", pngBytes(3))
        val chooser = RecordingChooser(NonTextChoice.CANCEL)
        CopyFileContentAction(chooser).performCopyFilesContent(project, arrayOf(png))
        assertEquals(1, chooser.summaries.size)
        assertEquals("untouched", clipboardText())
    }

    @Test
    fun testNoNonTextFileNeverAsks() {
        val a = myFixture.addFileToProject("plain/A.kt", "alpha").virtualFile
        val chooser = RecordingChooser(NonTextChoice.CANCEL)
        CopyFileContentAction(chooser).performCopyFilesContent(project, arrayOf(a))
        assertTrue(chooser.summaries.isEmpty())
        assertEquals(
            formatterPayload(listOf(ClipboardPayloadFormatter.PayloadFile(pathResolver().toClipboardPath(a.path), "alpha"))),
            clipboardText()
        )
    }

    /** 40 small non-text files, limit 30, two size placeholders first: dialog numbers equal the Include payload. */
    @Test
    fun testFileCountLimitDialogMatchesIncludePayload() {
        val state = CopyFileContentSettings.getInstance(project)!!.state
        state.setMaxFileCount = true
        state.fileCountLimit = 30
        state.maxFileSizeKB = 1
        state.maxNonTextFileSizeKB = 1
        state.showCopyNotification = true
        val messages = collectNotifications()
        val big = (1..2).map { myFixture.addFileToProject("lim/big$it.txt", "x".repeat(3000)).virtualFile }
        val images = (1..40).map { binaryFile("lim/img%02d.png".format(it), pngBytes(it)) }
        val chooser = RecordingChooser(NonTextChoice.INCLUDE)
        CopyFileContentAction(chooser).performCopyFilesContent(project, (big + images).toTypedArray())

        val summary = chooser.summaries.single()
        val actual = clipboardText()
        val r = pathResolver()
        assertEquals(images.take(30).map { r.toClipboardPath(it.path) }, summary.carried.map { it.path })
        assertEquals(30, actual.split("base64 follows").size - 1)
        assertEquals(2, actual.split("size exceeds limit").size - 1)
        assertEquals(2, summary.unreadOverLimit)
        assertEquals(10, summary.limitLeftOut)
        assertEquals(NonTextCopy.utf8Length(actual), summary.estimatedPayloadBytes)
        assertTrue(messages.any { it.contains("Non-text files copied as Base64: 30.") }, "$messages")
        assertTrue(messages.any { it.contains("left out 10 files") }, "$messages")
    }

    /** Text files fill the limit before any non-text file: no dialog, notice reports what was left out. */
    @Test
    fun testTextFirstFillsLimitWithoutDialog() {
        val state = CopyFileContentSettings.getInstance(project)!!.state
        state.setMaxFileCount = true
        state.fileCountLimit = 30
        val messages = collectNotifications()
        val texts = (1..30).map { myFixture.addFileToProject("tf/t%02d.kt".format(it), "text-$it").virtualFile }
        val images = (1..5).map { binaryFile("tf/i$it.png", pngBytes(it)) }
        val chooser = RecordingChooser(NonTextChoice.INCLUDE)
        CopyFileContentAction(chooser).performCopyFilesContent(project, (texts + images).toTypedArray())

        assertTrue(chooser.summaries.isEmpty(), "no dialog when Include would carry no non-text file")
        val actual = clipboardText()
        assertFalse(actual.contains("base64 follows"))
        assertContains(actual, "text-30")
        // Spec case 15: no dialog, but the notice still says how many files the limit left out.
        val warning = messages.firstOrNull { it.contains("File Limit Reached") } ?: error("$messages")
        assertContains(warning, "The file limit of 30 files was reached. The file-count limit left out 5 files.")
    }

    /** No non-text file carried when the limit is reached: the Include walk still counts the rest exactly. */
    @Test
    fun testIncludeWalkCountsLeftOutWithoutNonText() {
        val settings = CopyFileContentSettings.getInstance(project)!!
        settings.state.setMaxFileCount = true
        settings.state.fileCountLimit = 30
        (1..200).forEach { myFixture.addFileToProject("big/t%03d.kt".format(it), "text-$it") }
        val dir = myFixture.findFileInTempDir("big") ?: error("dir not found")
        val indicator = com.intellij.openapi.progress.EmptyProgressIndicator()
        val method = CopyFileContentAction::class.java.getDeclaredMethod(
            "buildCopyPayload",
            com.intellij.openapi.project.Project::class.java, Array<VirtualFile>::class.java,
            CopyFileContentSettings::class.java, kotlin.jvm.functions.Function2::class.java,
            com.intellij.openapi.progress.ProgressIndicator::class.java, Boolean::class.javaPrimitiveType
        ).apply { isAccessible = true }
        val result = method.invoke(CopyFileContentAction(), project, arrayOf(dir), settings, null, indicator, true)!!
        fun field(name: String) = result.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(result)

        assertEquals(30, field("fileCount"))
        assertEquals(true, field("fileLimitReached"))
        assertEquals(170, field("limitLeftOut"))
        assertEquals(false, field("limitLeftOutCapped"))
    }

    private class CountingIndicator(
        private val delegate: com.intellij.openapi.progress.ProgressIndicator = com.intellij.openapi.progress.EmptyProgressIndicator()
    ) : com.intellij.openapi.progress.ProgressIndicator by delegate {
        var checks = 0
        override fun checkCanceled() {
            checks++
            delegate.checkCanceled()
        }
    }

    private fun buildIncludePayload(dir: VirtualFile, indicator: com.intellij.openapi.progress.ProgressIndicator): Any {
        val settings = CopyFileContentSettings.getInstance(project)!!
        val method = CopyFileContentAction::class.java.getDeclaredMethod(
            "buildCopyPayload",
            com.intellij.openapi.project.Project::class.java, Array<VirtualFile>::class.java,
            CopyFileContentSettings::class.java, kotlin.jvm.functions.Function2::class.java,
            com.intellij.openapi.progress.ProgressIndicator::class.java, Boolean::class.javaPrimitiveType
        ).apply { isAccessible = true }
        return method.invoke(CopyFileContentAction(), project, arrayOf(dir), settings, null, indicator, true)!!
    }

    private fun Any.field(name: String) = javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this)

    /**
     * D1 / spec lines 84-92: the walk stops once the limit is filled; the 1170 remaining entries of the
     * already-listed folder are counted up to 1000, so the number reads 1000+. Changed from "counts up to
     * 1000 more files while walking": the old bound (`checks < 1100`) allowed a counting-only walk past
     * the limit, which the spec forbids, so it now pins that only the 30 copied files are visited.
     */
    @Test
    fun testLeftOutCountStopsTheWalkAt1000Plus() {
        val settings = CopyFileContentSettings.getInstance(project)!!
        settings.state.setMaxFileCount = true
        settings.state.fileCountLimit = 30
        (1..1200).forEach { myFixture.addFileToProject("cap/t%04d.kt".format(it), "text-$it") }
        val dir = myFixture.findFileInTempDir("cap") ?: error("dir not found")
        val indicator = CountingIndicator()

        val result = buildIncludePayload(dir, indicator)

        assertEquals(30, result.field("fileCount"))
        assertEquals(1000, result.field("limitLeftOut"))
        assertEquals(true, result.field("limitLeftOutCapped"))
        // One checkCanceled per visited file: only the 30 copied files (plus the folder) are visited.
        assertTrue(indicator.checks < 40, "walk went past the limit: ${indicator.checks}")
    }

    /**
     * Spec lines 89-91: a folder walk stops once the limit is filled. Past the limit the two picked files
     * are known (the selection is already listed) and counted; the picked folder was never listed, so the
     * number is a lower bound and reads "2+". The selection order is fixed, so VFS child order does not
     * matter here.
     */
    @Test
    fun testWalkStopsAtLimitAndUnlistedFoldersMakeThePlus() {
        val settings = CopyFileContentSettings.getInstance(project)!!
        settings.state.setMaxFileCount = true
        settings.state.fileCountLimit = 30
        (1..30).forEach { myFixture.addFileToProject("stopA/a%02d.kt".format(it), "text-$it") }
        val picked = (1..2).map { myFixture.addFileToProject("stopP/p$it.kt", "picked-$it").virtualFile }
        for (sub in listOf("z1", "z2")) {
            (1..200).forEach { myFixture.addFileToProject("stopB/$sub/t%03d.kt".format(it), "deep-$it") }
        }
        val first = myFixture.findFileInTempDir("stopA") ?: error("dir not found")
        val deep = myFixture.findFileInTempDir("stopB") ?: error("dir not found")
        val indicator = CountingIndicator()
        val method = CopyFileContentAction::class.java.getDeclaredMethod(
            "buildCopyPayload",
            com.intellij.openapi.project.Project::class.java, Array<VirtualFile>::class.java,
            CopyFileContentSettings::class.java, kotlin.jvm.functions.Function2::class.java,
            com.intellij.openapi.progress.ProgressIndicator::class.java, Boolean::class.javaPrimitiveType
        ).apply { isAccessible = true }

        val result = method.invoke(
            CopyFileContentAction(), project, arrayOf(first) + picked + deep, settings, null, indicator, true
        )!!

        assertEquals(30, result.field("fileCount"))
        assertEquals(true, result.field("fileLimitReached"))
        assertEquals(2, result.field("limitLeftOut"))
        assertEquals(true, result.field("limitLeftOutCapped"))
        assertTrue(indicator.checks < 40, "walk went into the unlisted folder: ${indicator.checks}")
    }

    /** OpenIDEA refuses an unportable name before it counts it: past the limit it is never "left out". */
    @Test
    fun testUnportableNamePastTheLimitIsNotCountedAsLeftOut() {
        val root = Files.createTempDirectory("clipcode-leftout-odd")
        try {
            // The unportable name must sort past the limit. The only one Windows can create (a leading
            // space) sorts before a.txt, so on Windows this test cannot be built and is skipped.
            assumeTrue("Windows file names cannot contain a tab", nameRoundTrips(root, "t\tname.txt"))
            Files.writeString(root.resolve("a.txt"), "A")
            Files.writeString(root.resolve("c.txt"), "C")
            Files.writeString(root.resolve("t\tname.txt"), "T")
            val dir = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root) ?: error("no vfs dir")
            VfsUtil.markDirtyAndRefresh(false, true, true, dir)
            val settings = CopyFileContentSettings.getInstance(project)!!
            settings.state.setMaxFileCount = true
            settings.state.fileCountLimit = 1

            val result = buildIncludePayload(dir, com.intellij.openapi.progress.EmptyProgressIndicator())

            assertEquals(1, result.field("fileCount"))
            assertEquals(1, result.field("limitLeftOut"))
            assertEquals(false, result.field("limitLeftOutCapped"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun testExactly1000LeftOutIsNotCapped() {
        val settings = CopyFileContentSettings.getInstance(project)!!
        settings.state.setMaxFileCount = true
        settings.state.fileCountLimit = 30
        (1..1030).forEach { myFixture.addFileToProject("cap1030/t%04d.kt".format(it), "text-$it") }
        val dir = myFixture.findFileInTempDir("cap1030") ?: error("dir not found")

        val result = buildIncludePayload(dir, com.intellij.openapi.progress.EmptyProgressIndicator())

        assertEquals(1000, result.field("limitLeftOut"))
        assertEquals(false, result.field("limitLeftOutCapped"))
    }

    /** 1031 text files, limit 30: no dialog, the notice says 1000+ and the payload is the first 30 files. */
    @Test
    fun testNoDialogNoticeSays1000PlusWhenCapped() {
        val state = CopyFileContentSettings.getInstance(project)!!.state
        state.setMaxFileCount = true
        state.fileCountLimit = 30
        val messages = collectNotifications()
        (1..1031).forEach { myFixture.addFileToProject("cap31/t%04d.kt".format(it), "text-$it") }
        val dir = myFixture.findFileInTempDir("cap31") ?: error("dir not found")
        val chooser = RecordingChooser(NonTextChoice.INCLUDE)

        CopyFileContentAction(chooser).performCopyFilesContent(project, arrayOf(dir))

        assertTrue(chooser.summaries.isEmpty())
        val warning = messages.firstOrNull { it.contains("File Limit Reached") } ?: error("$messages")
        assertContains(warning, "The file-count limit left out 1000+ files.")
        assertFalse(warning.contains("1000 files"), warning)
        assertEquals(30, clipboardText().split("// file: ").size - 1)
    }

    /** Dialog case: non-text carried and more than 1000 text files left out: the summary and notice say 1000+. */
    @Test
    fun testDialogAndNoticeShow1000PlusWhenCapped() {
        val state = CopyFileContentSettings.getInstance(project)!!.state
        state.setMaxFileCount = true
        state.fileCountLimit = 30
        state.showCopyNotification = true
        val messages = collectNotifications()
        val images = (1..3).map { binaryFile("capd/i$it.png", pngBytes(it)) }
        (1..1100).forEach { myFixture.addFileToProject("capd/t%04d.kt".format(it), "text-$it") }
        val dir = myFixture.findFileInTempDir("capd") ?: error("dir not found")
        val chooser = RecordingChooser(NonTextChoice.INCLUDE)

        CopyFileContentAction(chooser).performCopyFilesContent(project, (images + dir).toTypedArray())

        val summary = chooser.summaries.single()
        assertEquals(1000, summary.limitLeftOut)
        assertTrue(summary.limitLeftOutCapped)
        assertTrue(messages.any { it.contains("left out 1000+ files") }, "$messages")
    }

    /** The no-dialog notice names unreadable items from the walk whose payload went on the clipboard. */
    @Test
    fun testNoDialogNoticeNamesUnreadableFolder() {
        val root = Files.createTempDirectory("clipcode-nodialog")
        val lockedDir = root.resolve("lockedDir").createDirectories()
        Files.writeString(lockedDir.resolve("inner.txt"), "inner")
        Files.writeString(root.resolve("ok.txt"), "fine")
        try {
            lockedDir.toFile().setReadable(false, false)
            lockedDir.toFile().setExecutable(false, false)
            assumeFalse("chmod cannot make a folder unreadable here (Windows)", Files.isReadable(lockedDir))
            val dir = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root) ?: error("no vfs dir")
            com.intellij.openapi.vfs.VfsUtil.markDirtyAndRefresh(false, true, true, dir)
            CopyFileContentSettings.getInstance(project)!!.state.showCopyNotification = true
            val messages = collectNotifications()
            val chooser = RecordingChooser(NonTextChoice.INCLUDE)
            CopyFileContentAction(chooser).performCopyFilesContent(project, arrayOf(dir))

            assertTrue(chooser.summaries.isEmpty())
            val notice = messages.firstOrNull { it.contains("file copied") || it.contains("files copied") } ?: error("$messages")
            assertContains(notice, "<b>1 file copied.</b> 1 skipped: not UTF-8 text or unreadable.<br>")
            assertContains(notice, "lockedDir")
        } finally {
            lockedDir.toFile().setReadable(true, false)
            lockedDir.toFile().setExecutable(true, false)
            root.toFile().deleteRecursively()
        }
    }

    /** Spec case 19: a selected unreadable folder plus a readable file — the file is copied, the folder named. */
    @Test
    fun testSelectedUnreadableFolderIsNamedAndFileCopied() {
        val root = Files.createTempDirectory("clipcode-selectedlocked")
        val lockedDir = root.resolve("lockedDir").createDirectories()
        Files.writeString(lockedDir.resolve("inner.txt"), "inner")
        Files.writeString(root.resolve("ok.txt"), "fine")
        try {
            val dir = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root) ?: error("no vfs dir")
            com.intellij.openapi.vfs.VfsUtil.markDirtyAndRefresh(false, true, true, dir)
            val lockedVfs = dir.findChild("lockedDir") ?: error("no locked vfs")
            val okVfs = dir.findChild("ok.txt") ?: error("no ok vfs")
            lockedDir.toFile().setReadable(false, false)
            lockedDir.toFile().setExecutable(false, false)
            assumeFalse("chmod cannot make a folder unreadable here (Windows)", Files.isReadable(lockedDir))
            com.intellij.openapi.vfs.VfsUtil.markDirtyAndRefresh(false, true, true, dir)
            CopyFileContentSettings.getInstance(project)!!.state.showCopyNotification = true
            val messages = collectNotifications()
            val chooser = RecordingChooser(NonTextChoice.INCLUDE)
            CopyFileContentAction(chooser).performCopyFilesContent(project, arrayOf(lockedVfs, okVfs))

            assertTrue(chooser.summaries.isEmpty())
            assertContains(clipboardText(), "fine")
            val notice = messages.firstOrNull { it.contains("file copied") || it.contains("files copied") } ?: error("$messages")
            assertContains(notice, "<b>1 file copied.</b> 1 skipped: not UTF-8 text or unreadable.<br>")
            assertContains(notice, "lockedDir")
        } finally {
            lockedDir.toFile().setReadable(true, false)
            lockedDir.toFile().setExecutable(true, false)
            root.toFile().deleteRecursively()
        }
    }

    /** Every unreadable item the Include notice counts is also named: an external-library .dll too. */
    @Test
    fun testIncludeNoticeNamesExternalLibraryDrops() {
        val libRoot = java.io.File(project.basePath!!, "$name/vendorlib").apply { mkdirs() }
        java.io.File(libRoot, "native.dll").writeBytes(byteArrayOf(1, 2, 3))
        val lib = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(libRoot)!!
        lib.refresh(false, true)
        com.intellij.openapi.roots.ModuleRootModificationUtil.addModuleLibrary(module, lib.url)
        val dll = lib.findChild("native.dll")!!
        assertTrue(ExternalLibraryHandler(project).isFromExternalLibrary(dll), "test setup: dll must be a library file")
        val png = binaryFile("libmix/logo.png", pngBytes(5))
        CopyFileContentSettings.getInstance(project)!!.state.showCopyNotification = true
        val messages = collectNotifications()
        val chooser = RecordingChooser(NonTextChoice.INCLUDE)
        CopyFileContentAction(chooser).performCopyFilesContent(project, arrayOf(png, dll))

        assertEquals(1, chooser.summaries.size)
        val notice = messages.firstOrNull { it.contains("Non-text files copied as Base64: 1.") } ?: error("$messages")
        assertContains(notice, "Unreadable: 1")
        assertContains(notice, "native.dll")
    }

    /**
     * chmod 000 file, dangling symlink and an unlistable folder are named after Include. Windows ignores
     * the chmod, so there the file and folder are rightly copied and only the dangling link is named.
     */
    @Test
    fun testIncludeNamesUnreadableItems() {
        val root = Files.createTempDirectory("clipcode-unreadable")
        val locked = root.resolve("locked.txt").also { Files.writeString(it, "secret") }
        val lockedDir = root.resolve("lockedDir").createDirectories()
        Files.writeString(lockedDir.resolve("inner.txt"), "inner")
        Files.write(root.resolve("ok.png"), pngBytes(9))
        Files.createSymbolicLink(root.resolve("dangling.txt"), root.resolve("missing-target"))
        try {
            locked.toFile().setReadable(false, false)
            lockedDir.toFile().setReadable(false, false)
            lockedDir.toFile().setExecutable(false, false)
            val chmodWorks = !Files.isReadable(locked) && !Files.isReadable(lockedDir)
            if (posix) assertTrue(chmodWorks, "test setup: chmod must take effect on POSIX")
            val dir = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root) ?: error("no vfs dir")
            com.intellij.openapi.vfs.VfsUtil.markDirtyAndRefresh(false, true, true, dir)
            val state = CopyFileContentSettings.getInstance(project)!!.state
            state.showCopyNotification = true
            val messages = collectNotifications()
            val chooser = RecordingChooser(NonTextChoice.INCLUDE)
            CopyFileContentAction(chooser).performCopyFilesContent(project, arrayOf(dir))

            assertEquals(1, chooser.summaries.size)
            val notice = messages.firstOrNull { it.contains("Non-text files copied as Base64: 1.") } ?: error("$messages")
            assertContains(notice, "Unreadable: ${if (chmodWorks) 3 else 1}")
            assertContains(notice, "dangling.txt")
            if (chmodWorks) {
                assertContains(notice, "locked.txt")
                assertContains(notice, "lockedDir")
                assertFalse(clipboardText().contains("secret"))
            }
        } finally {
            lockedDir.toFile().setReadable(true, false)
            lockedDir.toFile().setExecutable(true, false)
            locked.toFile().setReadable(true, false)
            root.toFile().deleteRecursively()
        }
    }

    /**
     * A real folder under the project's primary root: temp:// has no symlinks, and a temp dir is outside
     * every copy root, where a link to a file is not followed (X7).
     */
    private fun projectDiskDir(): java.nio.file.Path =
        java.nio.file.Path.of(project.basePath!!, name).also { it.toFile().deleteRecursively() }.createDirectories()

    /** A symlink to a file inside a copy root carries the target's bytes under the link's own path. */
    @Test
    fun testSymlinkedFileCarriesTargetBytesUnderLinkPath() {
        val root = projectDiskDir()
        try {
            val target = root.resolve("real.png")
            Files.write(target, pngBytes(5))
            val linked = runCatching { Files.createSymbolicLink(root.resolve("link.png"), target) }.isSuccess
            if (posix) assertTrue(linked, "test setup: the symlink must exist on POSIX")
            assumeTrue("Windows refuses to create a symlink without the privilege", linked)
            val dir = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root) ?: error("no vfs dir")
            VfsUtil.markDirtyAndRefresh(false, true, true, dir)
            val link = dir.findChild("link.png") ?: error("no link vfs")
            val chooser = RecordingChooser(NonTextChoice.INCLUDE)
            CopyFileContentAction(chooser).performCopyFilesContent(project, arrayOf(link))
            val expected = formatterPayload(
                listOf(ClipboardPayloadFormatter.PayloadFile(pathResolver().toClipboardPath(link.path), nonText = pngBytes(5)))
            )
            assertEquals(expected, clipboardText())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /**
     * X7: a symlink to a file is followed only when its target lies inside a copy root. A target outside
     * every root (`CLAUDE.md -> ~/.agents/...`) is not read in either mode and is named as Unreadable; a
     * link to a file inside the root still carries the target's content.
     */
    @Test
    fun testSymlinkToFileOutsideEveryRootIsNotFollowed() {
        val outside = Files.createTempDirectory("clipcode-outside")
        val root = projectDiskDir()
        try {
            Files.writeString(outside.resolve("secret.txt"), "OUTSIDE-SECRET")
            Files.write(outside.resolve("secret.png"), pngBytes(21))
            Files.writeString(root.resolve("ok.txt"), "FINE-CONTENT")
            Files.write(root.resolve("inside.png"), pngBytes(22))
            val linked = runCatching {
                Files.createSymbolicLink(root.resolve("CLAUDE.md"), outside.resolve("secret.txt"))
                Files.createSymbolicLink(root.resolve("logo.png"), outside.resolve("secret.png"))
                Files.createSymbolicLink(root.resolve("alias.txt"), root.resolve("ok.txt"))
            }.isSuccess
            if (posix) assertTrue(linked, "test setup: the symlinks must exist on POSIX")
            assumeTrue("Windows refuses to create a symlink without the privilege", linked)
            val dir = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root) ?: error("no vfs dir")
            VfsUtil.markDirtyAndRefresh(false, true, true, dir)
            val alias = dir.findChild("alias.txt") ?: error("no alias vfs")
            CopyFileContentSettings.getInstance(project)!!.state.showCopyNotification = true
            val secretPng = NonText.placeholderLine(pngBytes(21).size.toLong(), NonText.sha256Hex(pngBytes(21)))

            val messages = collectNotifications()
            val include = RecordingChooser(NonTextChoice.INCLUDE)
            CopyFileContentAction(include).performCopyFilesContent(project, arrayOf(dir))
            var text = clipboardText()
            assertContains(text, pathResolver().toClipboardPath(alias.path) + "\nFINE-CONTENT")
            assertFalse(text.contains("OUTSIDE-SECRET"), text)
            assertFalse(text.contains(secretPng), text)
            assertEquals(listOf("inside.png"), include.summaries.single().carried.map { it.path.substringAfterLast('/') })
            val includeNotice = messages.firstOrNull { it.contains("Non-text files copied as Base64: 1.") } ?: error("$messages")
            assertContains(includeNotice, "Unreadable: 2")
            assertContains(includeNotice, "CLAUDE.md")
            assertContains(includeNotice, "logo.png")

            messages.clear()
            CopyFileContentAction(RecordingChooser(NonTextChoice.EXCLUDE)).performCopyFilesContent(project, arrayOf(dir))
            text = clipboardText()
            assertContains(text, "FINE-CONTENT")
            assertFalse(text.contains("OUTSIDE-SECRET"), text)
            val excludeNotice = messages.firstOrNull { it.contains("files copied") } ?: error("$messages")
            assertContains(excludeNotice, "3 skipped: not UTF-8 text or unreadable.")
            assertContains(excludeNotice, "CLAUDE.md")
            assertContains(excludeNotice, "logo.png")
        } finally {
            root.toFile().deleteRecursively()
            outside.toFile().deleteRecursively()
        }
    }

    // === D2: names that are not portable and symlinked folders are skipped and named ===

    private val posix = !System.getProperty("os.name").startsWith("Windows")

    /**
     * Whether this file system can hold [name] as one entry of exactly that name. Windows cannot: a
     * control character or `:` throws InvalidPathException, and `\\` is a separator there, so
     * `bad\\dir` would silently become a portable nested `bad/dir`.
     */
    private fun nameRoundTrips(dir: java.nio.file.Path, name: String): Boolean =
        runCatching { dir.resolve(name).fileName.toString() == name }.getOrDefault(false)

    /** [unportable]: the visible names of the unportable items this file system could create and the VFS lists. */
    private class OddFolder(val dir: VirtualFile, val unportable: List<String>, val symlinked: Boolean)

    /**
     * Real disk (temp:// has no symlinks): `odd/` holds normal files beside every kind of D2 skip this
     * file system can create. ` lead.txt` (the path starts with whitespace) is the one unportable name
     * Windows can create, so the category is still asserted there.
     */
    private fun oddFolder(root: java.nio.file.Path): OddFolder {
        val odd = root.resolve("odd").createDirectories()
        Files.writeString(odd.resolve("ok.txt"), "FINE-CONTENT")
        Files.write(odd.resolve("img.png"), pngBytes(11))
        val created = mutableListOf<String>()
        for ((name, content) in listOf(" lead.txt" to "LEAD-CONTENT", "back\\slash.txt" to "BS-CONTENT", "tab\tname.txt" to "TAB-CONTENT")) {
            if (!nameRoundTrips(odd, name)) continue
            Files.writeString(odd.resolve(name), content)
            created += name
        }
        if (nameRoundTrips(odd, "bad\\dir")) {
            val badDir = odd.resolve("bad\\dir").createDirectories()
            Files.writeString(badDir.resolve("inside.txt"), "BADDIR-CONTENT")
            created += "bad\\dir"
        }
        val target = root.resolve("target").createDirectories()
        Files.writeString(target.resolve("linked.txt"), "LINKED-CONTENT")
        // Windows refuses a symlink without the SeCreateSymbolicLinkPrivilege / Developer Mode.
        val symlinked = runCatching { Files.createSymbolicLink(odd.resolve("linkdir"), target) }.isSuccess
        if (posix) {
            assertEquals(4, created.size, "test setup: every unportable name must exist on POSIX")
            assertTrue(symlinked, "test setup: the symlink must exist on POSIX")
        }
        val dir = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root) ?: error("no vfs dir")
        VfsUtil.markDirtyAndRefresh(false, true, true, dir)
        val oddVfs = dir.findChild("odd") ?: error("no odd vfs")
        // X36: a name the platform's file view does not list need not be named. The test VFS hides
        // `back\slash.txt` and `bad\dir` (a real macOS project tree lists them), so only listed names are
        // expected; leading whitespace and a tab must be listed on POSIX, so the category is always asserted.
        val listed = oddVfs.children.map { it.name }.toSet()
        if (posix) assertTrue(listed.containsAll(listOf(" lead.txt", "tab\tname.txt")), "test setup: VFS must list $listed")
        val unportable = created.filter { it in listed }.map { PortableName.visibleName(it) }
        return OddFolder(oddVfs, unportable, symlinked)
    }

    private fun assertOddSkipsNamed(odd: OddFolder, notice: String) {
        if (odd.symlinked) {
            assertContains(notice, "Symlinked folder: 1")
            assertContains(notice, "linkdir")
        }
        assertContains(notice, "Name not portable: ${odd.unportable.size}")
        odd.unportable.forEach { assertContains(notice, it) }
        assertFalse(notice.contains("inside.txt"), "an unportable folder is named once: $notice")
    }

    private fun assertOnlyNormalFiles(text: String) {
        assertContains(text, "FINE-CONTENT")
        listOf("LEAD-CONTENT", "BS-CONTENT", "TAB-CONTENT", "BADDIR-CONTENT", "LINKED-CONTENT").forEach {
            assertFalse(text.contains(it), "$it must not be copied: $text")
        }
    }

    @Test
    fun testIncludeSkipsAndNamesUnportableNamesAndSymlinkedFolder() {
        val root = Files.createTempDirectory("clipcode-odd-include")
        try {
            val odd = oddFolder(root)
            CopyFileContentSettings.getInstance(project)!!.state.showCopyNotification = true
            val messages = collectNotifications()
            val chooser = RecordingChooser(NonTextChoice.INCLUDE)
            CopyFileContentAction(chooser).performCopyFilesContent(project, arrayOf(odd.dir))

            assertEquals(1, chooser.summaries.size)
            val text = clipboardText()
            assertOnlyNormalFiles(text)
            assertContains(text, "base64 follows")
            val notice = messages.firstOrNull { it.contains("Non-text files copied as Base64: 1.") } ?: error("$messages")
            assertOddSkipsNamed(odd, notice)
            assertFalse(notice.contains("Unreadable"), notice)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun testExcludeSkipsAndNamesUnportableNamesAndSymlinkedFolder() {
        val root = Files.createTempDirectory("clipcode-odd-exclude")
        try {
            val odd = oddFolder(root)
            CopyFileContentSettings.getInstance(project)!!.state.showCopyNotification = true
            val messages = collectNotifications()
            val chooser = RecordingChooser(NonTextChoice.EXCLUDE)
            CopyFileContentAction(chooser).performCopyFilesContent(project, arrayOf(odd.dir))

            assertEquals(1, chooser.summaries.size)
            val text = clipboardText()
            assertOnlyNormalFiles(text)
            assertFalse(text.contains("base64 follows"))
            val notice = messages.firstOrNull { it.contains("file copied") } ?: error("$messages")
            // Today's sentence for the not-UTF-8 png, then one block per new category.
            assertContains(notice, "<b>1 file copied.</b> 1 skipped: not UTF-8 text or unreadable.<br>")
            assertContains(notice, "img.png")
            assertOddSkipsNamed(odd, notice)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /** Spec case 22: a symlinked folder selected directly is not followed, alone or beside a file. */
    @Test
    fun testDirectlySelectedSymlinkedFolderIsNotFollowed() {
        val root = Files.createTempDirectory("clipcode-odd-direct")
        try {
            val odd = oddFolder(root)
            assumeTrue("Windows refuses to create a symlink without the privilege", odd.symlinked)
            val link = odd.dir.findChild("linkdir") ?: error("no link vfs")
            val ok = odd.dir.findChild("ok.txt") ?: error("no ok vfs")
            CopyFileContentSettings.getInstance(project)!!.state.showCopyNotification = true
            val messages = collectNotifications()
            val chooser = RecordingChooser(NonTextChoice.INCLUDE)

            // X3: the link alone leaves nothing to copy, so the clipboard is not written.
            setSentinel()
            CopyFileContentAction(chooser).performCopyFilesContent(project, arrayOf(link))
            val alone = nothingToCopyNotice(messages)
            assertContains(alone, "Symlinked folder: 1<br>")
            assertContains(alone, "linkdir")

            messages.clear()
            CopyFileContentAction(chooser).performCopyFilesContent(project, arrayOf(link, ok))
            val text = clipboardText()
            assertContains(text, "FINE-CONTENT")
            assertFalse(text.contains("LINKED-CONTENT"))
            val multi = messages.firstOrNull { it.contains("file copied") } ?: error("$messages")
            assertContains(multi, "<b>1 file copied.</b>")
            assertContains(multi, "Symlinked folder: 1")
            assertTrue(chooser.summaries.isEmpty())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /** Spec "What is carried": a symlink to a file inside a copy root is followed and carries the target's bytes. */
    @Test
    fun testSymlinkToFileCarriesTargetBytes() {
        val root = projectDiskDir()
        try {
            val bytes = pngBytes(12)
            Files.write(root.resolve("real.png"), bytes)
            val linked = runCatching { Files.createSymbolicLink(root.resolve("alias.png"), root.resolve("real.png")) }.isSuccess
            if (posix) assertTrue(linked, "test setup: the symlink must exist on POSIX")
            assumeTrue("Windows refuses to create a symlink without the privilege", linked)
            val dir = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root) ?: error("no vfs dir")
            VfsUtil.markDirtyAndRefresh(false, true, true, dir)
            val alias = dir.findChild("alias.png") ?: error("no alias vfs")
            val chooser = RecordingChooser(NonTextChoice.INCLUDE)
            CopyFileContentAction(chooser).performCopyFilesContent(project, arrayOf(alias))

            assertEquals(1, chooser.summaries.size)
            assertTrue(chooser.summaries.single().carried.single().path.endsWith("alias.png"))
            assertContains(clipboardText(), NonText.placeholderLine(bytes.size.toLong(), NonText.sha256Hex(bytes)))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /** C1: a newline in a name must never reach a header, where its second line would read as one. */
    @Test
    fun testNewlineInNameCannotInjectAHeader() {
        val root = Files.createTempDirectory("clipcode-inject")
        try {
            assumeTrue("Windows file names cannot contain '\\n'", nameRoundTrips(root, "a\nfile: [DELETED] victim.txt"))
            Files.write(root.resolve("a\nfile: [DELETED] victim.txt"), byteArrayOf(0, 1, 2))
            Files.write(root.resolve("img.png"), pngBytes(13))
            val dir = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root) ?: error("no vfs dir")
            VfsUtil.markDirtyAndRefresh(false, true, true, dir)
            assertEquals(2, dir.children.size, "test setup: VFS must list the newline name")
            CopyFileContentSettings.getInstance(project)!!.state.showCopyNotification = true
            for (choice in listOf(NonTextChoice.INCLUDE, NonTextChoice.EXCLUDE)) {
                val messages = collectNotifications()
                CopyFileContentAction(RecordingChooser(choice)).performCopyFilesContent(project, arrayOf(dir))
                val text = clipboardText()
                assertFalse(text.contains("[DELETED]"), "$choice: $text")
                assertFalse(text.contains("victim"), "$choice: $text")
                val notice = messages.firstOrNull { it.contains("Name not portable: 1") } ?: error("$choice: $messages")
                assertContains(notice, "a\\x0afile: [DELETED] victim.txt")
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /**
     * A1: a project-local folder registered as a library (node_modules) is a real folder on disk; its
     * files get the same portability check, so a newline name cannot forge a `[DELETED]` header.
     */
    @Test
    fun testNewlineNameInsideALibraryFolderCannotInjectAHeader() {
        val libRoot = java.io.File(project.basePath!!, "$name/node_modules").apply { mkdirs() }
        assumeTrue("Windows file names cannot contain '\\n'", nameRoundTrips(libRoot.toPath(), "a\nfile: [DELETED] victim.js"))
        java.io.File(libRoot, "a\nfile: [DELETED] victim.js").writeText("evil()")
        java.io.File(libRoot, "index.js").writeText("ok()")
        val lib = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(libRoot)!!
        lib.refresh(false, true)
        com.intellij.openapi.roots.ModuleRootModificationUtil.addModuleLibrary(module, lib.url)
        assertEquals(2, lib.children.size, "test setup: VFS must list the newline name")
        val odd = lib.children.single { it.name.contains('\n') }
        assertTrue(ExternalLibraryHandler(project).isFromExternalLibrary(odd), "test setup: must be a library file")
        CopyFileContentSettings.getInstance(project)!!.state.showCopyNotification = true
        for (choice in listOf(NonTextChoice.INCLUDE, NonTextChoice.EXCLUDE)) {
            val messages = collectNotifications()
            CopyFileContentAction(RecordingChooser(choice)).performCopyFilesContent(project, arrayOf(lib))
            val text = clipboardText()
            assertContains(text, "ok()")
            assertFalse(text.contains("[DELETED]"), "$choice: $text")
            assertFalse(text.contains("evil()"), "$choice: $text")
            val notice = messages.firstOrNull { it.contains("Name not portable: 1") } ?: error("$choice: $messages")
            assertContains(notice, "a\\x0afile: [DELETED] victim.js")
        }
    }

    /** B5: a folder selected together with its parent is named once per category, not once per visit. */
    @Test
    fun testFolderVisitedTwiceIsNamedOnce() {
        val root = Files.createTempDirectory("clipcode-odd-twice")
        try {
            val odd = oddFolder(root)
            assumeTrue("Windows refuses to create a symlink without the privilege", odd.symlinked)
            val link = odd.dir.findChild("linkdir") ?: error("no link vfs")
            CopyFileContentSettings.getInstance(project)!!.state.showCopyNotification = true
            val messages = collectNotifications()
            CopyFileContentAction(RecordingChooser(NonTextChoice.INCLUDE)).performCopyFilesContent(project, arrayOf(odd.dir, link))

            val notice = messages.firstOrNull { it.contains("Non-text files copied as Base64: 1.") } ?: error("$messages")
            assertContains(notice, "Symlinked folder: 1")
            assertEquals(1, notice.split("linkdir").size - 1, notice)
            assertContains(notice, "Name not portable: ${odd.unportable.size}")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun configureSettings() {
        val state = CopyFileContentSettings.getInstance(project)!!.state
        state.headerFormat = "// file: \$FILE_PATH"
        state.preText = ""
        state.postText = ""
        state.addExtraLineBetweenFiles = false
        state.setMaxFileCount = false
        state.showCopyNotification = false
        state.useFilters = false
        state.useIncludeFilters = true
        state.useExcludeFilters = true
        state.filterRules = mutableListOf()
        state.maxFileSizeKB = 500
        state.maxNonTextFileSizeKB = 2048
        state.fileCountLimit = 30
    }

    private fun configureOnlyNestedContentRoot(moduleDirectory: String): VirtualFile {
        val projectRoot = project.baseDir!!
        val moduleRoot = projectRoot.findFileByRelativePath(moduleDirectory)
            ?: VfsUtil.createDirectories("${projectRoot.path}/$moduleDirectory")

        ApplicationManager.getApplication().runWriteAction {
            PsiTestUtil.removeAllRoots(module, null)
            PsiTestUtil.addContentRoot(module, moduleRoot)
        }

        val contentRoots = ProjectRootManager.getInstance(project).contentRoots.toList()
        kotlin.test.assertEquals(
            moduleRoot.path,
            contentRoots.first().path,
            "Test setup requires the nested root to be first"
        )
        return moduleRoot
    }

    private fun actionEvent(): AnActionEvent =
        AnActionEvent.createFromDataContext(
            "ClipCodeTest",
            Presentation(),
            SimpleDataContext.getProjectContext(project)
        )

    private fun closeOpenFiles() {
        val fileEditorManager = FileEditorManager.getInstance(project)
        fileEditorManager.openFiles.forEach(fileEditorManager::closeFile)
    }

    private companion object {
        const val SENTINEL = "untouched-by-clipcode"
    }

    private fun clipboardText(): String =
        Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as String
}
