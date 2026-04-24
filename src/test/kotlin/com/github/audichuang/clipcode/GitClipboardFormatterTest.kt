package com.github.audichuang.clipcode

import kotlin.test.Test
import kotlin.test.assertEquals

class GitClipboardFormatterTest {
    @Test
    fun `formats deleted header with leading label`() {
        assertEquals(
            "// file: [DELETED] src/legacy/Old.kt",
            GitClipboardFormatter.buildHeader(
                headerFormat = "// file: \$FILE_PATH",
                clipboardPath = "src/legacy/Old.kt",
                changeType = ChangeTypeLabel.DELETED
            )
        )
    }

    @Test
    fun `preserves relative path produced for secondary content root`() {
        assertEquals(
            "### file: [MODIFIED] nested/App.kt ###",
            GitClipboardFormatter.buildHeader(
                headerFormat = "### file: \$FILE_PATH ###",
                clipboardPath = "nested/App.kt",
                changeType = ChangeTypeLabel.MODIFIED
            )
        )
    }

    @Test
    fun `formats header without change type label`() {
        assertEquals(
            "// file: src/main/App.kt",
            GitClipboardFormatter.buildHeader(
                headerFormat = "// file: \$FILE_PATH",
                clipboardPath = "src/main/App.kt",
                changeType = null
            )
        )
    }
}
