package com.github.audichuang.clipcode

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class NonTextCopyDialogTest : BasePlatformTestCase() {
    private fun summary(headerFormat: String) = NonTextCopySummary(
        carried = listOf(NonTextCopySummary.Item("img/a.png", 10)),
        estimatedPayloadBytes = 100,
        nonTextOverLimit = 0,
        unreadOverLimit = 0,
        limitLeftOut = 0,
        nonTextLimitBytes = 2048L * 1024,
        headerFormat = headerFormat
    )

    private fun withDialog(headerFormat: String, check: (NonTextCopyDialog) -> Unit) {
        ApplicationManager.getApplication().invokeAndWait {
            val dialog = NonTextCopyDialog(project, summary(headerFormat))
            try {
                check(dialog)
            } finally {
                Disposer.dispose(dialog.disposable)
            }
        }
    }

    fun testIncludeIsEnabledAndDefaultForAnEscapableHeader() = withDialog("// file: \$FILE_PATH") { dialog ->
        assertEquals("Copy non-text files?", dialog.title)
        assertTrue(dialog.isOKActionEnabled)
    }

    fun testIncludeIsDisabledForAnUnescapableHeader() = withDialog("\$FILE_PATH") { dialog ->
        assertFalse(dialog.isOKActionEnabled)
    }
}
