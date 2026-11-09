package com.github.audichuang.clipcode

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
    fun `the Turkish dotless i is not a header on either side`() {
        val format = "// file: \$FILE_PATH"
        // Kotlin's IGNORE_CASE is CASE_INSENSITIVE|UNICODE_CASE and folds U+0131 onto `i`;
        // JavaScript's /i refuses that fold. This line used to be a header here and plain
        // content in VS Code, so a Snipcode payload pasted here was truncated into a
        // phantom file.
        assertTrue(parser.parse("// f\u0131le: phantom.ts", format).isEmpty(),
            "U+0131 must not make this a header")
        // ASCII case-insensitivity is preserved.
        assertEquals("a.ts", parser.parse("// FILE: a.ts\nbody", format).single().path)
        assertEquals("b.ts", parser.parse("// File: b.ts\nbody", format).single().path)
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
    fun `round trips paths with dollar signs through repeated header placeholders`() {
        val format = "### \$FILE_PATH -> \$FILE_PATH ###"
        val path = "src/Cost\$&\$\$plan[1].kt"
        val payload = ClipboardPayloadFormatter.buildPayload(
            ClipboardPayloadFormatter.Options(
                headerFormat = format,
                addExtraLineBetweenFiles = false,
                files = listOf(ClipboardPayloadFormatter.PayloadFile(path, "content"))
            )
        )

        val entry = parser.parse(payload, format).single()
        assertEquals(path, entry.path)
        assertEquals("content", entry.content)
        assertTrue(parser.parse("### first -> second ###\ncontent", format).isEmpty())
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

class NonTextWireTest {
    private val parser = ClipboardRestoreParser()
    private val default = "// file: \$FILE_PATH"
    private val esc = ClipboardRestoreParser.ESCAPE_MARKER
    private val fake = "// File skipped: non-text file, 3 bytes, sha256 039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81; base64 follows"

    private fun file(path: String, text: String) = ClipboardPayloadFormatter.PayloadFile(path, text)
    private fun nonText(path: String, bytes: ByteArray) = ClipboardPayloadFormatter.PayloadFile(path, nonText = bytes)
    private fun build(header: String, files: List<ClipboardPayloadFormatter.PayloadFile>) =
        ClipboardPayloadFormatter.buildPayload(
            ClipboardPayloadFormatter.Options(header, "", "", true, files, null)
        )

    private fun hdr(header: String, p: String) = header.replace("\$FILE_PATH", p)

    @Test
    fun `placeholder shaped text gets two layers and the reader never guesses`() {
        val escFake = esc + fake
        for (header in listOf(default, "// \$FILE_PATH", "A\$FILE_PATH")) {
            val texts = listOf("$fake\nYWJj", "$fake\nnot base64!", "$escFake\nYWJj", "$esc$escFake")
            val files = listOf(nonText("a.bin", byteArrayOf(0, 255.toByte(), 1))) +
                texts.mapIndexed { i, t -> file("t$i.txt", t) }
            val payload = build(header, files)
            assertTrue(payload.contains("${hdr(header, "t0.txt")}\n$esc$esc$fake\n"), payload)
            assertTrue(payload.contains("${hdr(header, "t2.txt")}\n$esc$esc$esc$fake\n"), payload)
            val entries = parser.parse(payload, header)
            assertEquals(5, entries.size, "$header: $payload")
            assertTrue(!entries[0].escapedPlaceholder, header)
            assertContentEquals(byteArrayOf(0, 255.toByte(), 1), (NonText.parseBody(entries[0].content) as NonText.DecodeResult.Ok).bytes)
            entries.drop(1).zip(texts).forEach { (entry, text) ->
                assertTrue(entry.escapedPlaceholder, "$header: ${entry.path}")
                assertEquals(text, entry.content, "$header: ${entry.path}")
            }
        }
    }

    @Test
    fun `header formats that cannot escape base64 lines are detected`() {
        for (f in listOf("\$FILE_PATH", "\$FILE_PATH=", "//\$FILE_PATH", "\$FILE_PATH==", "/\$FILE_PATH", "\$FILE_PATHQ")) {
            assertTrue(ClipboardRestoreParser.headerFormatUnescapable(f), f)
        }
        for (f in listOf(default, "// \$FILE_PATH", "A\$FILE_PATH", "### \$FILE_PATH", "\$FILE_PATH.md", "no placeholder", "")) {
            assertTrue(!ClipboardRestoreParser.headerFormatUnescapable(f), f)
        }
    }

    @Test
    fun `leading blank lines do not hide a placeholder shaped first line`() {
        val escFake = esc + fake
        for (header in listOf(default, "// \$FILE_PATH", "A\$FILE_PATH", "#\$FILE_PATH")) {
            val texts = listOf("\n$esc$fake\nAQID", "\n$fake\nAQID", "\n  \n$escFake\nAQID")
            val payload = build(header, texts.mapIndexed { i, t -> file("t$i.txt", t) })
            val entries = parser.parse(payload, header)
            assertEquals(3, entries.size, "$header: $payload")
            entries.zip(texts).forEach { (entry, text) ->
                assertTrue(entry.escapedPlaceholder, "$header: ${entry.path}")
                assertEquals(text.trimStart('\n', ' '), entry.content, "$header: ${entry.path}")
            }
        }
    }

    @Test
    fun `an escaped whitespace line before a placeholder shaped line keeps the text`() {
        val escFake = esc + fake
        for (header in listOf(" \$FILE_PATH", "\t\$FILE_PATH", "  \$FILE_PATH")) {
            val lead = if (header.startsWith("\t")) "\t\t" else "   "
            val texts = listOf("$lead\n$fake\nAQID", "$lead\n$escFake\nAQID", "\n$lead\n\n$fake\nAQID")
            val payload = build(header, texts.mapIndexed { i, t -> file("t$i.txt", t) })
            val entries = parser.parse(payload, header)
            assertEquals(3, entries.size, "'$header': $payload")
            entries.zip(texts).forEach { (entry, text) ->
                assertTrue(entry.escapedPlaceholder, "'$header': ${entry.path}\n$payload")
                assertEquals(text.trimStart('\n'), entry.content, "'$header': ${entry.path}")
                assertNull(NonText.parseBody(entry.content), "'$header': ${entry.path}")
            }
        }
    }
}
