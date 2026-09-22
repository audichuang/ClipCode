package com.github.audichuang.clipcode

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PathRuleMatcherTest {
    @Test
    fun `matchesPath is segment aware`() {
        assertTrue(PathRuleMatcher.matchesPath("module-a/src/App.kt", "module-a"))
        assertTrue(PathRuleMatcher.matchesPath("module-a", "module-a"))
        assertFalse(PathRuleMatcher.matchesPath("module-alpha/src/App.kt", "module-a"))
    }

    @Test
    fun `blank root rule matches all project paths`() {
        assertTrue(PathRuleMatcher.matchesPath("src/App.kt", ""))
        assertTrue(PathRuleMatcher.matchesPath("module-a/src/App.kt", ""))
        assertTrue(PathRuleMatcher.overlapsDirectory("", ""))
        assertTrue(PathRuleMatcher.overlapsDirectory("module-a", ""))
    }

    @Test
    fun `overlapsDirectory is segment aware for traversal pruning`() {
        assertTrue(PathRuleMatcher.overlapsDirectory("", "module-a/src/App.kt"))
        assertTrue(PathRuleMatcher.overlapsDirectory("module-a", "module-a/src/App.kt"))
        assertTrue(PathRuleMatcher.overlapsDirectory("module-a/src", "module-a"))
        assertFalse(PathRuleMatcher.overlapsDirectory("module-alpha", "module-a/src/App.kt"))
    }

    @Test
    fun `isAbsolutePath supports unix and windows paths`() {
        assertTrue(PathRuleMatcher.isAbsolutePath("/"))
        assertTrue(PathRuleMatcher.isAbsolutePath("/workspace/module-a"))
        assertTrue(PathRuleMatcher.isAbsolutePath("C:/workspace/module-a"))
        assertFalse(PathRuleMatcher.isAbsolutePath("module-a/src/App.kt"))
    }

    @Test
    fun `isAbsolutePath keeps a drive path absolute when a name holds a line terminator`() {
        // Java's `.` skips these; with `.*` and a full-string match the path read as relative,
        // so a relative PATH rule could match a file VS Code (filterMatcher.ts) refused it for.
        for (terminator in listOf("\r", "\u0085", "\u2028", "\u2029")) {
            assertTrue(PathRuleMatcher.isAbsolutePath("C:/workspace/a${terminator}b.kt"))
        }
    }
}
