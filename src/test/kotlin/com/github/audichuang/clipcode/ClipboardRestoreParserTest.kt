package com.github.audichuang.clipcode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClipboardRestoreParserTest {
    private val parser = ClipboardRestoreParser()

    @Test
    fun `escapes a CRLF content line that would parse as a custom header`() {
        val format = "### \$FILE_PATH"
        // escapeContent splits on \n, so this line still carries its \r; the parser
        // splits on \r?\n and would see it as a header unless it is escaped.
        val body = "### src/a.kt\r\nreal body line"
        val escaped = ClipboardRestoreParser.escapeContent(body, format)
        assertTrue(escaped.startsWith("//clipcode-esc: ### src/a.kt"),
            "CRLF header-looking line must be escaped, got: ${escaped.lines().first()}")

        val payload = "### src/main.kt\r\n" + escaped
        val entries = parser.parse(payload, format)
        assertEquals(1, entries.size, "a phantom file must not appear: ${entries.map { it.path }}")
        assertEquals("src/main.kt", entries[0].path)
        assertTrue(entries[0].content.contains("### src/a.kt"), "the escaped line must come back as content")
        assertTrue(entries[0].content.contains("real body line"))
    }

    @Test
    fun `U+0085 in a header path behaves the same as in the VS Code authority`() {
        val format = "// file: \$FILE_PATH"
        val nel = "\u0085"

        // Java's regex `.` excludes U+0085 while JavaScript's does not. Before the explicit
        // character class, VS Code parsed this as a header and IntelliJ returned 0 entries —
        // the whole file vanished on a VS Code -> IntelliJ paste, with no error.
        val entries = parser.parse("// file: src/a.ts$nel\nbody();", format)
        assertEquals(1, entries.size, "a NEL-bearing header must parse, as it does in VS Code")
        assertEquals("src/a.ts$nel", entries[0].path)
        assertEquals("body();", entries[0].content)

        // Same regex drives the copy-side escape: a content line carrying NEL must be
        // escaped here too, or IntelliJ ships a payload that shatters in VS Code.
        val escaped = ClipboardRestoreParser.escapeContent("// file: evil.txt$nel", format)
        assertTrue(escaped.startsWith("//clipcode-esc: "),
            "a NEL header-looking content line must be escaped, got: $escaped")
    }

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

    @Test
    fun `does not split javascript object property named file as header`() {
        val clipboard = """
            // file: inv-web-console/node_modules/cub-lib-view-rootng/fesm2022/cub-lib-view-rootng-component-upload.mjs
            let error = {
                file: undefined,
                errorTypes: [],
            };
            export { CubUpload, CubUploadModule };
        """.trimIndent()

        val entries = parser.parse(clipboard, "// file: \$FILE_PATH")

        assertEquals(1, entries.size)
        assertEquals(
            """
            let error = {
                file: undefined,
                errorTypes: [],
            };
            export { CubUpload, CubUploadModule };
            """.trimIndent(),
            entries.single().content
        )
    }

    @Test
    fun `does not split inline header text inside source content`() {
        val clipboard = """
            // file: src/app.js
            const marker = "// file: docs/readme.md";
            console.log(marker);
        """.trimIndent()

        val entries = parser.parse(clipboard, "// file: \$FILE_PATH")

        assertEquals(1, entries.size)
        assertEquals(
            """
            const marker = "// file: docs/readme.md";
            console.log(marker);
            """.trimIndent(),
            entries.single().content
        )
    }

    @Test
    fun `drops leading clipcode-root metadata line`() {
        val clipboard = """
            // clipcode-root: myrepo
            // file: src/App.kt
            fun main() = Unit
        """.trimIndent()

        val entries = parser.parse(clipboard, "// file: \$FILE_PATH")

        assertEquals(1, entries.size)
        assertEquals("src/App.kt", entries.single().path)
        assertEquals("fun main() = Unit", entries.single().content)
    }

    @Test
    fun `clipcode-root line cannot become a phantom file under a permissive header format`() {
        val clipboard = """
            // clipcode-root: myrepo
            // src/App.kt
            fun main() = Unit
        """.trimIndent()

        val entries = parser.parse(clipboard, "// \$FILE_PATH")

        assertEquals(1, entries.size)
        assertEquals("src/App.kt", entries.single().path)
    }

    @Test
    fun `extractSourceRoot reads the first-line metadata`() {
        assertEquals(
            "myrepo",
            ClipboardRestoreParser.extractSourceRoot("// clipcode-root: myrepo\n// file: a.kt\nx")
        )
        assertEquals(null, ClipboardRestoreParser.extractSourceRoot("// file: a.kt\nx"))
        assertEquals(null, ClipboardRestoreParser.extractSourceRoot("// clipcode-root: \n// file: a.kt"))
    }

    @Test
    fun `deleted header with non-empty old content still parses as deleted`() {
        // IntelliJ's git-history copy places the deleted file's old content under the
        // [DELETED] header (VS Code places a marker line). Restore must treat the
        // entry as a deletion either way — content is carried but ignored downstream.
        val clipboard = """
            // file: [DELETED] src/Old.kt
            fun legacy() = Unit
        """.trimIndent()

        val entries = parser.parse(clipboard, "// file: \$FILE_PATH")

        assertEquals(1, entries.size)
        assertTrue(entries.single().isDeleted)
        assertEquals("src/Old.kt", entries.single().path)
        assertEquals("fun legacy() = Unit", entries.single().content)
    }
}
