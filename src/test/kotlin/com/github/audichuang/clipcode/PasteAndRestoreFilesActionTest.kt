package com.github.audichuang.clipcode

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PasteAndRestoreFilesActionTest : BasePlatformTestCase() {
    // ==================== 純函數測試（不需要 IDE） ====================

    fun testBuildConfirmationMessageSplitsCreateAndOverwriteSections() {
        val plan = RestorePlan(
            createOperations = listOf(
                createOp("inv-svc-adv/src/New.java", "/workspace/inv-adv/inv-svc-adv/src/New.java", existed = false),
                createOp("src/test/Existing.java", "/workspace/inv-adv/src/test/Existing.java", existed = true)
            ),
            deleteOperations = emptyList(),
            skippedOperations = emptyList()
        )

        val message = PasteAndRestoreFilesAction().confirmationMessage(plan)

        assertContains(message, "Files to CREATE (new) (1):")
        assertContains(message, "+ inv-svc-adv/src/New.java")
        assertContains(message, "/workspace/inv-adv/inv-svc-adv/src/New.java")
        assertContains(message, "Files to OVERWRITE (existing) (1):")
        assertContains(message, "~ src/test/Existing.java")
        assertContains(message, "Do you want to proceed?")
        assertFalse(message.contains("Files to CREATE (2):"))
    }

    fun testBuildConfirmationMessageIncludesDeleteSection() {
        val plan = RestorePlan(
            createOperations = emptyList(),
            deleteOperations = listOf(deleteOp("src/Old.kt", "/workspace/src/Old.kt")),
            skippedOperations = emptyList()
        )
        val message = PasteAndRestoreFilesAction().confirmationMessage(plan)
        assertContains(message, "Files to DELETE (1):")
        assertContains(message, "- src/Old.kt")
    }

    fun testBuildConfirmationMessageIncludesSkippedSection() {
        val plan = RestorePlan(
            createOperations = listOf(createOp("src/A.kt", "/r/src/A.kt", existed = false)),
            deleteOperations = emptyList(),
            skippedOperations = listOf(
                skipped("src/X.kt", "src/X.kt", RestorePlan.SkipReason.ALREADY_ABSENT),
                skipped("src/Y.kt", "src/Y.kt", RestorePlan.SkipReason.AMBIGUOUS_TARGET),
                skipped("@nope", null, RestorePlan.SkipReason.UNRESOLVED_PATH)
            )
        )
        val message = PasteAndRestoreFilesAction().confirmationMessage(plan)
        assertContains(message, "Will be SKIPPED:")
        assertContains(message, "already absent: 1")
        assertContains(message, "ambiguous target: 1")
        assertContains(message, "path unresolved: 1")
    }

    fun testBuildDialogTitleReflectsPlanContent() {
        val action = PasteAndRestoreFilesAction()
        val createOnly = RestorePlan(
            createOperations = listOf(createOp("a", "/r/a", false)),
            deleteOperations = emptyList(),
            skippedOperations = emptyList()
        )
        val deleteOnly = RestorePlan(
            createOperations = emptyList(),
            deleteOperations = listOf(deleteOp("a", "/r/a")),
            skippedOperations = emptyList()
        )
        val both = RestorePlan(
            createOperations = listOf(createOp("a", "/r/a", false)),
            deleteOperations = listOf(deleteOp("b", "/r/b")),
            skippedOperations = emptyList()
        )
        kotlin.test.assertEquals("Restore Files from Clipboard", action.dialogTitle(createOnly))
        kotlin.test.assertEquals("Delete Files", action.dialogTitle(deleteOnly))
        kotlin.test.assertEquals("Restore and Delete Files", action.dialogTitle(both))
    }

    fun testPreviewPathsWithTargetsTruncatesBeyond15Entries() {
        val action = PasteAndRestoreFilesAction()
        val paths = (1..20).map { "rel/$it.kt" to "/abs/$it.kt" }
        val preview = action.previewPaths(paths, "+")
        assertContains(preview, "+ rel/1.kt")
        assertContains(preview, "target: /abs/1.kt")
        assertContains(preview, "... and 5 more")
        assertFalse(preview.contains("rel/16.kt"), "Expected only 15 entries shown")
    }

    fun testPreviewPathsWithoutOverflow() {
        val action = PasteAndRestoreFilesAction()
        val paths = listOf("a.kt" to "/abs/a.kt", "b.kt" to "/abs/b.kt")
        val preview = action.previewPaths(paths, "~")
        assertContains(preview, "~ a.kt")
        assertContains(preview, "~ b.kt")
        assertFalse(preview.contains("more"))
    }

    fun testBuildSkippedSummaryListsEachReason() {
        val action = PasteAndRestoreFilesAction()
        val skipped = listOf(
            skipped("a", "a", RestorePlan.SkipReason.ALREADY_ABSENT),
            skipped("b", "b", RestorePlan.SkipReason.ALREADY_ABSENT),
            skipped("c", "c", RestorePlan.SkipReason.UNRESOLVED_PATH),
            skipped("d", "d", RestorePlan.SkipReason.AMBIGUOUS_TARGET)
        )
        val summary = action.skippedSummary(skipped)
        assertContains(summary, "already absent: 2")
        assertContains(summary, "path unresolved: 1")
        assertContains(summary, "ambiguous target: 1")
    }

    fun testBuildSkippedSummaryEmpty() {
        val action = PasteAndRestoreFilesAction()
        kotlin.test.assertEquals("", action.skippedSummary(emptyList()))
    }

    fun testBuildNoActionMessageWithNoSkipped() {
        val action = PasteAndRestoreFilesAction()
        kotlin.test.assertEquals(
            "No actionable files found in clipboard content.",
            action.noActionMessage(emptyList())
        )
    }

    fun testBuildNoActionMessageWithSkippedReportsDetails() {
        val action = PasteAndRestoreFilesAction()
        val msg = action.noActionMessage(
            listOf(skipped("x", "x", RestorePlan.SkipReason.UNRESOLVED_PATH))
        )
        assertContains(msg, "No actionable files found.")
        assertContains(msg, "path unresolved: 1")
    }

    fun testOverwriteChoiceEnumHasExpectedValues() {
        val enumClass = Class.forName(
            "com.github.audichuang.clipcode.PasteAndRestoreFilesAction\$OverwriteChoice"
        )
        val values = enumClass.enumConstants
        kotlin.test.assertEquals(3, values.size)
        val names = values.map { (it as Enum<*>).name }.toSet()
        assertTrue(names.contains("OVERWRITE_ALL"))
        assertTrue(names.contains("SKIP_EXISTING"))
        assertTrue(names.contains("CANCEL"))
    }

    // ==================== IDE 整合測試 ====================

    fun testActionPerformedShowsWarningWhenClipboardEmpty() {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(""), null)
        // 不該丟例外
        PasteAndRestoreFilesAction().actionPerformed(actionEvent())
    }

    fun testActionPerformedShowsWarningWhenClipboardHasOnlyWhitespace() {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection("    \n\n   "), null)
        PasteAndRestoreFilesAction().actionPerformed(actionEvent())
    }

    fun testActionPerformedWithoutProjectExitsCleanly() {
        val event = AnActionEvent.createFromDataContext(
            "ClipCodeTest",
            Presentation(),
            SimpleDataContext.EMPTY_CONTEXT
        )
        PasteAndRestoreFilesAction().actionPerformed(event)
    }

    fun testGetClipboardContentReturnsStringWhenAvailable() {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection("hello-clipboard"), null)
        val text = PasteAndRestoreFilesAction().getClipboardContentReflective()
        kotlin.test.assertEquals("hello-clipboard", text)
    }

    fun testUpdateUsesProjectPresence() {
        val event = AnActionEvent.createFromDataContext(
            "ClipCodeTest",
            Presentation(),
            SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build()
        )
        PasteAndRestoreFilesAction().update(event)
        assertTrue(event.presentation.isEnabledAndVisible)
    }

    fun testUpdateDisablesWhenProjectAbsent() {
        val event = AnActionEvent.createFromDataContext(
            "ClipCodeTest",
            Presentation(),
            SimpleDataContext.EMPTY_CONTEXT
        )
        PasteAndRestoreFilesAction().update(event)
        assertFalse(event.presentation.isEnabledAndVisible)
    }

    // === reflection helpers ===

    private fun PasteAndRestoreFilesAction.confirmationMessage(plan: RestorePlan): String =
        invokePrivate("buildConfirmationMessage", arrayOf(RestorePlan::class.java), arrayOf<Any>(plan)) as String

    private fun PasteAndRestoreFilesAction.dialogTitle(plan: RestorePlan): String =
        invokePrivate("buildDialogTitle", arrayOf(RestorePlan::class.java), arrayOf<Any>(plan)) as String

    private fun PasteAndRestoreFilesAction.previewPaths(paths: List<Pair<String, String>>, prefix: String): String =
        invokePrivate(
            "previewPathsWithTargets",
            arrayOf(List::class.java, String::class.java),
            arrayOf<Any>(paths, prefix)
        ) as String

    private fun PasteAndRestoreFilesAction.skippedSummary(items: List<RestorePlan.SkippedOperation>): String =
        invokePrivate("buildSkippedSummary", arrayOf(List::class.java), arrayOf<Any>(items)) as String

    private fun PasteAndRestoreFilesAction.noActionMessage(items: List<RestorePlan.SkippedOperation>): String =
        invokePrivate("buildNoActionMessage", arrayOf(List::class.java), arrayOf<Any>(items)) as String

    private fun PasteAndRestoreFilesAction.getClipboardContentReflective(): String? {
        val m = PasteAndRestoreFilesAction::class.java.getDeclaredMethod("getClipboardContent")
        m.isAccessible = true
        return m.invoke(this) as String?
    }

    private fun PasteAndRestoreFilesAction.invokePrivate(
        name: String,
        paramTypes: Array<Class<*>>,
        args: Array<Any>
    ): Any {
        val m = PasteAndRestoreFilesAction::class.java.getDeclaredMethod(name, *paramTypes)
        m.isAccessible = true
        return m.invoke(this, *args)
    }

    private fun actionEvent(): AnActionEvent =
        AnActionEvent.createFromDataContext(
            "ClipCodeTest",
            Presentation(),
            SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build()
        )

    private fun createOp(relative: String, absolute: String, existed: Boolean) =
        RestorePlan.CreateOperation(
            relativePath = relative,
            absolutePath = absolute,
            rootPath = "/r",
            content = "x",
            existed = existed
        )

    private fun deleteOp(relative: String, absolute: String) =
        RestorePlan.DeleteOperation(relativePath = relative, absolutePath = absolute)

    private fun skipped(
        rawPath: String,
        relative: String?,
        reason: RestorePlan.SkipReason
    ) = RestorePlan.SkippedOperation(
        rawPath = rawPath,
        relativePath = relative,
        reason = reason
    )
}
