package com.github.audichuang.clipcode

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import kotlin.test.assertContains
import kotlin.test.assertFalse

class CopyAllOpenTabsActionTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        configureSettings()
        // 清空剪貼簿避免被先前測試污染
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(""), null)
    }

    fun testCopyAllOpenTabsIncludesEachOpenFile() {
        closeOpenFiles()
        val a = myFixture.addFileToProject("a.kt", "alpha-content").virtualFile
        val b = myFixture.addFileToProject("b.kt", "beta-content").virtualFile
        myFixture.openFileInEditor(a)
        myFixture.openFileInEditor(b)

        CopyAllOpenTabsAction().actionPerformed(actionEvent())

        val text = clipboard()
        assertContains(text, "alpha-content")
        assertContains(text, "beta-content")
    }

    fun testCopyAllOpenTabsWithNoOpenFilesDoesNotCrash() {
        closeOpenFiles()
        // 沒檔案開著時，action 應該短路出（不複製，不丟例外）
        CopyAllOpenTabsAction().actionPerformed(actionEvent())
        // 不檢查 clipboard 內容（可能是先前的）；重點是沒丟例外
    }

    fun testCopyAllOpenTabsAppliesHeaderFormat() {
        closeOpenFiles()
        val state = CopyFileContentSettings.getInstance(project)!!.state
        state.headerFormat = "### \$FILE_PATH"

        val a = myFixture.addFileToProject("path/to/Foo.kt", "body").virtualFile
        myFixture.openFileInEditor(a)

        CopyAllOpenTabsAction().actionPerformed(actionEvent())

        val text = clipboard()
        assertContains(text, "### ")
        assertContains(text, "Foo.kt")
        assertFalse(text.contains("// file:"))
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
        state.filterRules = mutableListOf()
        state.maxFileSizeKB = 500
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

    private fun clipboard(): String =
        Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as String
}
