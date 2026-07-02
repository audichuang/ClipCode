package com.github.audichuang.clipcode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BranchDiffProviderTest {
    @Test
    fun testParsesBehindTabAhead() {
        // left=behind, right=ahead → 函式回 Pair(ahead, behind)
        assertEquals(5 to 3, BranchDiffProvider.parseAheadBehind("3\t5"))
    }

    @Test
    fun testZeroZero() = assertEquals(0 to 0, BranchDiffProvider.parseAheadBehind("0\t0"))

    @Test
    fun testMalformedReturnsNull() = assertNull(BranchDiffProvider.parseAheadBehind("garbage"))

    @Test
    fun testEmptyReturnsNull() = assertNull(BranchDiffProvider.parseAheadBehind(""))
}
