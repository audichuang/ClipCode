package com.github.audichuang.clipcode

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import kotlin.test.assertContains
import kotlin.test.assertFalse

class CopyFileContentActionTest : BasePlatformTestCase() {
    fun testCopyAllOpenTabsUsesProjectRootRelativeHeaderWhenNestedContentRootIsFirst() {
        val file = myFixture.addFileToProject("inv-svc-adv/src/main/Foo.kt", "class Foo").virtualFile
        configureOnlyNestedContentRoot("inv-svc-adv")
        configureSettings()
        closeOpenFiles()

        myFixture.openFileInEditor(file)
        CopyAllOpenTabsAction().actionPerformed(actionEvent())

        val clipboard = clipboardText()
        assertContains(clipboard, "inv-svc-adv/src/main/Foo.kt")
        assertFalse(clipboard.contains("// file: src/main/Foo.kt"))
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
