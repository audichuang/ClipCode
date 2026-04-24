package com.github.audichuang.clipcode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClipboardRestoreParserTest {
    private val parser = ClipboardRestoreParser()

    @Test
    fun `parse returns empty list for blank input`() {
        val entries = parser.parse("", "// file: \$FILE_PATH")

        assertTrue(entries.isEmpty())
    }

    @Test
    fun `parses custom header format`() {
        val entries = parser.parse(
            """
            ### file: [MODIFIED] src/main/App.kt ###
            fun main() = Unit
            """.trimIndent(),
            "### file: \$FILE_PATH ###"
        )

        assertEquals(1, entries.size)
        assertEquals("src/main/App.kt", entries.single().path)
        assertEquals(setOf(ChangeTypeLabel.MODIFIED), entries.single().changeTypes)
        assertEquals("fun main() = Unit", entries.single().content)
    }

    @Test
    fun `falls back to generic file header`() {
        val entries = parser.parse(
            """
            file: src/main/App.kt
            println("hi")
            """.trimIndent(),
            "// file: \$FILE_PATH"
        )

        assertEquals(listOf("src/main/App.kt"), entries.map { it.path })
    }

    @Test
    fun `parse with header format missing placeholder uses fallback`() {
        val entries = parser.parse(
            """
            // file: src/main/Fallback.kt
            println("fallback")
            """.trimIndent(),
            "[PREFIX]"
        )

        assertEquals(1, entries.size)
        assertEquals("src/main/Fallback.kt", entries.single().path)
        assertEquals("println(\"fallback\")", entries.single().content)
    }

    @Test
    fun `parse handles regex special chars in header like bracketed PREFIX`() {
        val entries = parser.parse(
            """
            [PREFIX] [NEW] src/main/App.kt
            fun main() = Unit
            """.trimIndent(),
            "[PREFIX] \$FILE_PATH"
        )

        assertEquals(1, entries.size)
        assertEquals("src/main/App.kt", entries.single().path)
        assertEquals(setOf(ChangeTypeLabel.NEW), entries.single().changeTypes)
        assertEquals("fun main() = Unit", entries.single().content)
    }

    @Test
    fun `preserves empty content and deleted label`() {
        val entries = parser.parse(
            "// file: [DELETED] src/main/Old.kt",
            "// file: \$FILE_PATH"
        )

        assertEquals(1, entries.size)
        assertTrue(entries.single().isDeleted)
        assertEquals("", entries.single().content)
    }

    @Test
    fun `captures multiple leading labels`() {
        val entries = parser.parse(
            """
            // file: [MODIFIED] [DELETED] src/main/Old.kt
            legacy
            """.trimIndent(),
            "// file: \$FILE_PATH"
        )

        assertEquals(
            setOf(ChangeTypeLabel.MODIFIED, ChangeTypeLabel.DELETED),
            entries.single().changeTypes
        )
    }

    @Test
    fun `parses multiple files separated by CRLF`() {
        val entries = parser.parse(
            "// file: src/A.kt\r\n" +
                "fun a() = 1\r\n" +
                "// file: [NEW] src/B.kt\r\n" +
                "fun b() = 2\r\n",
            "// file: \$FILE_PATH"
        )

        assertEquals(listOf("src/A.kt", "src/B.kt"), entries.map { it.path })
        assertEquals(listOf("fun a() = 1", "fun b() = 2"), entries.map { it.content })
        assertEquals(setOf(ChangeTypeLabel.NEW), entries[1].changeTypes)
    }
}
