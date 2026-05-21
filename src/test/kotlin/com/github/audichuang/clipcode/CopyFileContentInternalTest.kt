package com.github.audichuang.clipcode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CopyFileContentInternalTest {
    private val action = CopyFileContentAction()

    @Test
    fun `estimateTokens for empty content`() {
        assertEquals(0, action.estimateTokens(""))
    }

    @Test
    fun `estimateTokens counts single word`() {
        assertEquals(1, action.estimateTokens("hello"))
    }

    @Test
    fun `estimateTokens counts words and punctuation`() {
        // "a b" → 2 words, 0 punctuation
        assertEquals(2, action.estimateTokens("a b"))
        // "a; b;" → split → ["a;", "b;"] → 2 words; punctuation `;` ×2 → 4
        assertEquals(4, action.estimateTokens("a; b;"))
        // "a {} b" → split → ["a", "{}", "b"] → 3 words; punctuation `{`, `}` → 2 → 5
        assertEquals(5, action.estimateTokens("a {} b"))
    }

    @Test
    fun `estimateTokens counts each punctuation char`() {
        // "(){}[],;" 全部單字 → 1 word; 8 punctuation → 9
        val n = action.estimateTokens("(){}[],;")
        assertTrue(n >= 8, "Expected at least 8 punctuation hits, got $n")
    }

    @Test
    fun `matchesPattern wildcard star matches extension`() {
        assertTrue(action.matchesPattern("Foo.java", "*.java"))
        assertTrue(action.matchesPattern("Bar.java", "*.java"))
        assertFalse(action.matchesPattern("Foo.kt", "*.java"))
    }

    @Test
    fun `matchesPattern wildcard question mark`() {
        assertTrue(action.matchesPattern("a.kt", "?.kt"))
        assertFalse(action.matchesPattern("ab.kt", "?.kt"))
    }

    @Test
    fun `matchesPattern wildcard prefix and suffix`() {
        assertTrue(action.matchesPattern("test_a", "test_*"))
        assertTrue(action.matchesPattern("test_b.kt", "test_*"))
        assertFalse(action.matchesPattern("other_a", "test_*"))
    }

    @Test
    fun `matchesPattern exact match without wildcard`() {
        assertTrue(action.matchesPattern("Exact", "Exact"))
        assertFalse(action.matchesPattern("ExactLong", "Exact"))
    }

    @Test
    fun `matchesPattern invalid regex falls back to contains`() {
        // 不含 * 或 ? 的字串會直接 regex 比對；無效 regex 由 catch fallback 到 contains
        // 用一個一定無效的 regex 來測 fallback
        val invalidPattern = "[unclosed"
        assertTrue(action.matchesPattern("contains[unclosed inside", invalidPattern))
        assertFalse(action.matchesPattern("none", invalidPattern))
    }

    // === reflection helpers ===

    private fun CopyFileContentAction.estimateTokens(content: String): Int {
        val m = CopyFileContentAction::class.java.getDeclaredMethod("estimateTokens", String::class.java)
        m.isAccessible = true
        return m.invoke(this, content) as Int
    }

    private fun CopyFileContentAction.matchesPattern(fileName: String, pattern: String): Boolean {
        val m = CopyFileContentAction::class.java.getDeclaredMethod(
            "matchesPattern",
            String::class.java,
            String::class.java
        )
        m.isAccessible = true
        return m.invoke(this, fileName, pattern) as Boolean
    }
}
