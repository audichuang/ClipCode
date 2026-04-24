package com.github.audichuang.clipcode

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class PasteAndRestoreFilesActionTest {
    @Test
    fun `buildConfirmationMessage splits create and overwrite sections`() {
        val action = PasteAndRestoreFilesAction()
        val plan = RestorePlan(
            createOperations = listOf(
                RestorePlan.CreateOperation(
                    relativePath = "inv-svc-adv/src/New.java",
                    absolutePath = "/workspace/inv-adv/inv-svc-adv/src/New.java",
                    rootPath = "/workspace/inv-adv",
                    content = "new",
                    existed = false
                ),
                RestorePlan.CreateOperation(
                    relativePath = "src/test/Existing.java",
                    absolutePath = "/workspace/inv-adv/src/test/Existing.java",
                    rootPath = "/workspace/inv-adv",
                    content = "updated",
                    existed = true
                )
            ),
            deleteOperations = emptyList(),
            skippedOperations = emptyList()
        )

        val message = action.confirmationMessage(plan)

        assertContains(message, "Files to CREATE (new) (1):")
        assertContains(message, "+ inv-svc-adv/src/New.java")
        assertContains(message, "/workspace/inv-adv/inv-svc-adv/src/New.java")
        assertContains(message, "Files to OVERWRITE (existing) (1):")
        assertContains(message, "~ src/test/Existing.java")
        assertContains(message, "/workspace/inv-adv/src/test/Existing.java")
        assertFalse(message.contains("Files to CREATE (2):"))
    }

    private fun PasteAndRestoreFilesAction.confirmationMessage(plan: RestorePlan): String {
        val method = PasteAndRestoreFilesAction::class.java.getDeclaredMethod(
            "buildConfirmationMessage",
            RestorePlan::class.java
        )
        method.isAccessible = true
        return method.invoke(this, plan) as String
    }
}
