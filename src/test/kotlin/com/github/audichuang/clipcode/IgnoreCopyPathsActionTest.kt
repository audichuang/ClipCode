package com.github.audichuang.clipcode

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class IgnoreCopyPathsActionTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        CopyFileContentSettings.getInstance(project)!!.loadState(CopyFileContentSettings.State())
    }

    private fun event(vararg files: VirtualFile) = AnActionEvent.createFromDataContext("ProjectViewPopup", null,
        SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(*files)).build())

    fun testIgnoreFolderAndFileExcludesActualCopyAndKeepsSiblingPrefix() {
        myFixture.addFileToProject("node_modules/pkg/index.js", "ignored dependency")
        myFixture.addFileToProject("node_modules-other/index.js", "keep sibling")
        val secret = myFixture.addFileToProject("local.txt", "ignored file").virtualFile
        myFixture.addFileToProject("src/main.txt", "keep source")
        IgnoreCopyPathsAction().actionPerformed(event(myFixture.findFileInTempDir("node_modules"), secret))
        val settings = CopyFileContentSettings.getInstance(project)!!
        assertEquals(2, settings.state.filterRules.size)
        val method = CopyFileContentAction::class.java.getDeclaredMethod("buildCopyPayload", Project::class.java,
            Array<VirtualFile>::class.java, CopyFileContentSettings::class.java,
            kotlin.jvm.functions.Function2::class.java, ProgressIndicator::class.java).apply { isAccessible = true }
        val payload = method.invoke(CopyFileContentAction(), project, arrayOf(myFixture.tempDirFixture.getFile("")!!), settings, null, EmptyProgressIndicator())
        val text = payload.javaClass.getDeclaredField("text").apply { isAccessible = true }.get(payload) as String
        assertFalse(text.contains("ignored dependency"))
        assertFalse(text.contains("ignored file"))
        assertTrue(text.contains("keep sibling"))
        assertTrue(text.contains("keep source"))
    }

    fun testRepeatReenablesExistingRuleWithoutDuplicatesOrActivatingDormantIncludes() {
        val file = myFixture.addFileToProject("file.txt", "content").virtualFile
        val state = CopyFileContentSettings.getInstance(project)!!.state
        state.filterRules.add(CopyFileContentSettings.FilterRule(action = CopyFileContentSettings.FilterAction.INCLUDE, value = "elsewhere"))
        val action = IgnoreCopyPathsAction()
        action.actionPerformed(event(file, file))
        val rule = state.filterRules.single { it.action == CopyFileContentSettings.FilterAction.EXCLUDE }
        rule.enabled = false
        action.actionPerformed(event(file))
        assertTrue(state.filterRules.single { it.action == CopyFileContentSettings.FilterAction.EXCLUDE }.enabled)
        assertEquals(2, state.filterRules.size)
        assertTrue(state.useFilters && state.useExcludeFilters)
        assertFalse(state.useIncludeFilters)
    }

    fun testActiveIncludeSettingsArePreservedAndEmptySelectionIsHidden() {
        val file = myFixture.addFileToProject("file.txt", "content").virtualFile
        val state = CopyFileContentSettings.getInstance(project)!!.state
        state.useFilters = true
        state.useIncludeFilters = true
        val action = IgnoreCopyPathsAction()
        action.actionPerformed(event(file))
        assertTrue(state.useIncludeFilters)
        val empty = event()
        action.update(empty)
        assertFalse(empty.presentation.isEnabledAndVisible)
        val selected = event(file)
        action.update(selected)
        assertTrue(selected.presentation.isEnabledAndVisible)
    }
}
