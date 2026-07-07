package com.github.audichuang.clipcode

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
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CopyFileContentActionTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        configureSettings()
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(""), null)
    }

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

    fun testPerformCopyForSingleFileEmitsContent() {
        val file = myFixture.addFileToProject("src/Single.kt", "single-content").virtualFile
        CopyFileContentAction().performCopyFilesContent(project, arrayOf(file))
        val text = clipboardText()
        assertContains(text, "single-content")
        assertContains(text, "Single.kt")
    }

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

    fun testPreAndPostTextWrapClipboard() {
        val state = CopyFileContentSettings.getInstance(project)!!.state
        state.preText = "=== BEFORE ==="
        state.postText = "=== AFTER ==="
        val file = myFixture.addFileToProject("Wrap.kt", "wrapped").virtualFile
        CopyFileContentAction().performCopyFilesContent(project, arrayOf(file))
        val text = clipboardText()
        assertTrue(text.startsWith("=== BEFORE ==="), "Expected pre text; got: $text")
        assertTrue(text.trimEnd().endsWith("=== AFTER ==="), "Expected post text; got: $text")
    }

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

    fun testFileSizeLimitSkipsOversizedFile() {
        val state = CopyFileContentSettings.getInstance(project)!!.state
        state.maxFileSizeKB = 1  // 1 KB
        val big = myFixture.addFileToProject("Big.kt", "x".repeat(2048)).virtualFile
        CopyFileContentAction().performCopyFilesContent(project, arrayOf(big))
        val text = clipboardText()
        // 跳過的檔仍會輸出 header + skip marker
        assertContains(text, "Big.kt")
        assertContains(text, "File skipped")
    }

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

    fun testDeduplicatesSamePath() {
        val file = myFixture.addFileToProject("Dup.kt", "dup-content").virtualFile
        CopyFileContentAction().performCopyFilesContent(project, arrayOf(file, file, file))
        val text = clipboardText()
        // 即使選了 3 次，應該只出現一次
        val occurrences = text.split("dup-content").size - 1
        kotlin.test.assertEquals(1, occurrences, "Expected single occurrence, got $occurrences")
    }

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

    fun testEmptyFilesProcessedGracefully() {
        val empty = myFixture.addFileToProject("Empty.kt", "").virtualFile
        CopyFileContentAction().performCopyFilesContent(project, arrayOf(empty))
        // 空檔不會丟例外，但可能不會被加進輸出（readFileContents 返回空字串就 skip）
    }

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

    private fun clipboardText(): String =
        Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as String
}
