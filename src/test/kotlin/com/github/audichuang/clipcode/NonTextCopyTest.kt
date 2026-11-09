package com.github.audichuang.clipcode

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NonTextCopyTest {
    private val kb = 1024L
    private val textLimit = 500 * kb
    private val nonTextLimit = 2048 * kb

    private fun nonTextBytes(size: Int) = ByteArray(size) { if (it % 7 == 0) 0 else (it % 251).toByte() }
    private fun textBytes(size: Int) = ByteArray(size) { 'a'.code.toByte() }

    private fun classify(bytes: ByteArray, isBinaryType: Boolean = false, text: Long = textLimit, nonText: Long = nonTextLimit) =
        NonTextCopy.classify(bytes.size.toLong(), isBinaryType, text, nonText) { bytes }

    @Test
    fun `700 KB json over the text limit becomes a size placeholder`() {
        val r = assertIs<NonTextCopy.Outcome.SizePlaceholder>(classify(textBytes(700 * 1024)))
        assertEquals(700L * 1024, r.size)
        assertFalse(r.unread)
        assertFalse(r.nonTextOverLimit)
    }

    @Test
    fun `700 KB non-text file is carried`() {
        val bytes = nonTextBytes(700 * 1024)
        val r = assertIs<NonTextCopy.Outcome.Carried>(classify(bytes))
        assertContentEquals(bytes, r.bytes)
    }

    @Test
    fun `3 MB non-text over both limits is not read and becomes a placeholder`() {
        var read = false
        val size = 3L * 1024 * 1024
        val r = NonTextCopy.classify(size, false, textLimit, nonTextLimit) { read = true; ByteArray(0) }
        val p = assertIs<NonTextCopy.Outcome.SizePlaceholder>(r)
        assertTrue(p.unread)
        assertFalse(read, "a file over both limits must not be read")
    }

    @Test
    fun `3 MB non-text under the text limit is held to the non-text limit`() {
        val r = assertIs<NonTextCopy.Outcome.SizePlaceholder>(classify(nonTextBytes(3 * 1024 * 1024), text = 5000 * kb))
        assertTrue(r.nonTextOverLimit)
        assertFalse(r.unread)
    }

    @Test
    fun `text under the limit is text, a read failure is unreadable`() {
        assertEquals("abc", assertIs<NonTextCopy.Outcome.Text>(classify("abc".toByteArray())).content)
        assertIs<NonTextCopy.Outcome.Unreadable>(NonTextCopy.classify(3, false, textLimit, nonTextLimit) { null })
    }

    @Test
    fun `binary file type decoding as UTF-8 keeps today's skip, non-UTF-8 is carried`() {
        assertIs<NonTextCopy.Outcome.Unreadable>(classify("plain".toByteArray(), isBinaryType = true))
        assertIs<NonTextCopy.Outcome.Carried>(classify(byteArrayOf(0x89.toByte(), 0x50, 0), isBinaryType = true))
    }

    @Test
    fun `Big5 bytes without NUL are non-text`() {
        assertIs<NonTextCopy.Outcome.Carried>(classify(byteArrayOf(0xA4.toByte(), 0xA4.toByte(), 0xA4.toByte(), 0xE5.toByte())))
    }

    @Test
    fun `formatter uses the shared non-text body`() {
        val bytes = nonTextBytes(200)
        val hf = "// file: \$FILE_PATH"
        val expectedBody = ClipboardRestoreParser.escapeContent(
            NonText.placeholderLine(200, NonText.sha256Hex(bytes)) + "\n" + NonText.encodeWrapped(bytes), hf
        )
        assertEquals(expectedBody, ClipboardPayloadFormatter.nonTextBody(bytes, hf))
        val payload = ClipboardPayloadFormatter.buildPayload(
            ClipboardPayloadFormatter.Options(
                headerFormat = hf, addExtraLineBetweenFiles = false,
                files = listOf(ClipboardPayloadFormatter.PayloadFile("a.png", nonText = bytes))
            )
        )
        assertEquals("\n// file: a.png\n$expectedBody\n", payload)
    }

    @Test
    fun `size formatting`() {
        assertEquals("0 bytes", NonTextCopy.formatSize(0))
        assertEquals("1 byte", NonTextCopy.formatSize(1))
        assertEquals("1023 bytes", NonTextCopy.formatSize(1023))
        assertEquals("4.0 KB", NonTextCopy.formatSize(4096))
        assertEquals("2.0 MB", NonTextCopy.formatSize(2L * 1024 * 1024))
    }

    @Test
    fun `short path is unchanged`() {
        assertEquals("static/logo.png", NonTextCopy.shortenMiddle("static/logo.png"))
    }

    @Test
    fun `three long names sharing a prefix stay distinguishable under a long folder`() {
        val names = listOf("alpha", "beta", "gamma1").map { "settings-dialog-screenshot-dark-mode-long-name-$it-01.png" }
        assertEquals(listOf(59, 58, 60), names.map { it.length })
        val folder = "d11long/" + "a-really-long-folder-name-".repeat(3) + "end"
        val shown = names.map { NonTextCopy.shortenMiddle("$folder/$it") }
        shown.zip(names).forEach { (s, name) ->
            assertTrue(s.codePointCount(0, s.length) <= 70, s)
            assertTrue(s.endsWith("…/$name"), "folder shrinks before the name: $s")
            assertTrue(s.startsWith("d11long"), s)
        }
        assertEquals(3, shown.toSet().size)
    }

    @Test
    fun `150 char folder keeps the whole file name`() {
        val folder = (1..15).joinToString("/") { "segment-%02d".format(it) }
        assertTrue(folder.length >= 150, "folder length ${folder.length}")
        val name = "important-report-final.pdf"
        val s = NonTextCopy.shortenMiddle("$folder/$name")
        assertTrue(s.endsWith("…/$name"), s)
        assertTrue(s.startsWith("segment-01/"), s)
        assertEquals(70, s.length, "folder keeps as much as fits: $s")
    }

    @Test
    fun `a name that alone does not fit is shortened in the middle keeping its extension`() {
        val name = "n".repeat(60) + "-tail-end.png"
        val s = NonTextCopy.shortenMiddle("dir/sub/$name", 40)
        assertEquals(40, s.length, s)
        assertTrue(s.endsWith("-tail-end.png"), s)
        assertTrue(s.startsWith("dir…/n"), s)
        assertEquals(2, s.count { it == '…' }, s)
    }

    @Test
    fun `shortening never splits a surrogate pair`() {
        val emoji = "😀"
        for (max in 20..40) {
            val s = NonTextCopy.shortenMiddle("${emoji.repeat(30)}/${emoji.repeat(50)}.png", max)
            assertTrue(s.codePointCount(0, s.length) <= max, "max $max: $s")
            for (i in s.indices) {
                val c = s[i]
                if (Character.isHighSurrogate(c)) assertTrue(i + 1 < s.length && Character.isLowSurrogate(s[i + 1]), s)
                if (Character.isLowSurrogate(c)) assertTrue(i > 0 && Character.isHighSurrogate(s[i - 1]), s)
            }
            assertTrue(s.endsWith(".png"), s)
        }
    }

    private fun summary(
        n: Int,
        overLimit: Int = 0,
        unread: Int = 0,
        leftOut: Int = 0,
        headerFormat: String = "// file: \$FILE_PATH"
    ) = NonTextCopySummary(
        carried = (1..n).map { NonTextCopySummary.Item("img/p$it.png", 1024L * it) },
        estimatedPayloadBytes = 123_456,
        nonTextOverLimit = overLimit,
        unreadOverLimit = unread,
        limitLeftOut = leftOut,
        nonTextLimitBytes = 2048 * kb,
        headerFormat = headerFormat
    )

    @Test
    fun `dialog text for one file`() {
        val lines = NonTextCopy.dialogText(summary(1))
        assertEquals("Non-text files found: 1 (total 1.0 KB).", lines.found)
        assertEquals("Estimated payload with them: 120.6 KB", lines.estimate)
        assertEquals(listOf("img/p1.png" to "1.0 KB"), lines.paths)
        assertNull(lines.more)
        assertTrue(lines.notes.isEmpty())
        assertNull(lines.includeUnavailable)
    }

    @Test
    fun `dialog text lists ten and the rest`() {
        val lines = NonTextCopy.dialogText(summary(12, overLimit = 2, unread = 1, leftOut = 1))
        assertEquals(10, lines.paths.size)
        assertEquals("and 2 more", lines.more)
        assertEquals(
            listOf(
                "Non-text files over the 2.0 MB limit, not copied: 2",
                "Files over both size limits, not read, copied as size placeholders: 1",
                "The file-count limit left out 1 file"
            ),
            lines.notes
        )
        assertEquals(
            "The file-count limit left out 5 files",
            NonTextCopy.dialogText(summary(1, leftOut = 5)).notes.single()
        )
    }

    @Test
    fun `unescapable header format disables include with the reason`() {
        val lines = NonTextCopy.dialogText(summary(1, headerFormat = "\$FILE_PATH"))
        assertEquals(
            "Include is unavailable: the header format \"\$FILE_PATH\" would make Base64 lines read as file headers.",
            lines.includeUnavailable
        )
    }

    @Test
    fun `include notice lines, one category per line`() {
        assertEquals(
            listOf("Non-text files copied as Base64: 1."),
            NonTextCopy.includeNoticeLines(1, 0, emptyList())
        )
        val paths = (1..7).map { "u$it" }
        assertEquals(
            listOf("Non-text files copied as Base64: 3.", "Unreadable: 7", "u1", "u2", "u3", "u4", "u5", "and 2 more"),
            NonTextCopy.includeNoticeLines(3, 7, paths)
        )
    }

    /** "and N more" counts what the count names but the list does not show, so the two always agree. */
    @Test
    fun `unreadable lines count the paths not shown`() {
        assertEquals(listOf("Unreadable: 2", "p", "and 1 more"), NonTextCopy.unreadableLines(2, listOf("p")))
        val paths = (1..6).map { "u$it" }
        assertEquals(
            listOf("Unreadable: 8", "u1", "u2", "u3", "u4", "u5", "and 3 more"),
            NonTextCopy.unreadableLines(8, paths)
        )
    }

    @Test
    fun `utf8 length`() {
        assertEquals(0L, NonTextCopy.utf8Length(""))
        assertEquals("a中😀".toByteArray(Charsets.UTF_8).size.toLong(), NonTextCopy.utf8Length("a中😀"))
    }
}
