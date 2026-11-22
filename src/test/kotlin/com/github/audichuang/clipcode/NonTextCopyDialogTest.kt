package com.github.audichuang.clipcode

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class NonTextCopyDialogTest : BasePlatformTestCase() {
    private fun summary(headerFormat: String, estimate: Long = 100) = NonTextCopySummary(
        carried = listOf(NonTextCopySummary.Item("img/a.png", 10)),
        estimatedPayloadBytes = estimate,
        nonTextOverLimit = 0,
        unreadOverLimit = 0,
        limitLeftOut = 0,
        nonTextLimitBytes = 2048L * 1024,
        headerFormat = headerFormat
    )

    private fun withDialog(headerFormat: String, estimate: Long = 100, check: (NonTextCopyDialog) -> Unit) {
        ApplicationManager.getApplication().invokeAndWait {
            val dialog = NonTextCopyDialog(project, summary(headerFormat, estimate))
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

    fun testIncludeIsDisabledOverFiftyMiBAndAllowedAtExactlyFiftyMiB() {
        val cap = 50L * 1024 * 1024
        withDialog("// file: \$FILE_PATH", cap) { assertTrue(it.isOKActionEnabled) }
        withDialog("// file: \$FILE_PATH", cap + 1) { dialog ->
            assertFalse(dialog.isOKActionEnabled)
            // Exclude is the default and holds the focus, same as the unescapable-header case.
            assertEquals("Exclude", (dialog.preferredFocusedComponent as javax.swing.JButton).text)
        }
    }
}
