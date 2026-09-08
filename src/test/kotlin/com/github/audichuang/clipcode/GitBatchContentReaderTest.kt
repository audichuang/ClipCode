package com.github.audichuang.clipcode

import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails

class GitBatchContentReaderTest {
    @Test fun parsesByteLengthsInsteadOfSplittingContentLines() {
        val first = "中文\nheader blob 999\n".toByteArray()
        val second = byteArrayOf(0, 10, -1, 13)
        val bytes = "abc blob ${first.size} path with spaces\n".toByteArray() + first + byteArrayOf(10) +
            "def blob ${second.size}\n".toByteArray() + second + byteArrayOf(10) + "ghi blob 0\n\n".toByteArray()
        val result = GitBatchContentReader.parse(bytes, 3)
        assertContentEquals(first, result[0]); assertContentEquals(second, result[1]); assertContentEquals(byteArrayOf(), result[2])
    }

    @Test fun missingAndMalformedResponsesCannotShiftFiles() {
        assertEquals(listOf(null), GitBatchContentReader.parse("unknown missing\n".toByteArray(), 1))
        for (text in listOf("abc blob 9\nx\n", "abc tree 1\nx\n", "abc blob -1\n", "abc blob 0\nx", "abc blob 0\n\nextra")) {
            assertFails(text) { GitBatchContentReader.parse(text.toByteArray(), 1) }
        }
    }
}
