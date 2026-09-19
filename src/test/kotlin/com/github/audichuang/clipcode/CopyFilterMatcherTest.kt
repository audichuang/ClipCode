package com.github.audichuang.clipcode

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The per-file filter decision used to be inline in CopyFileContentAction.processFile, so
 * the git payload builder — where the same action lands whenever a selection mixes
 * revision or deleted entries — applied no filters at all. These pin the shared predicate
 * both callers now use. The builder's own wiring needs the IDE runtime and is verified by
 * hand via TESTING_GUIDE.md.
 */
class CopyFilterMatcherTest {

    private fun rule(
        type: CopyFileContentSettings.FilterType,
        action: CopyFileContentSettings.FilterAction,
        value: String
    ) = CopyFileContentSettings.FilterRule(type = type, action = action, value = value, enabled = true)

    @Test
    fun `an exclude rule keeps the file off the clipboard`() {
        val excludes = listOf(
            rule(CopyFileContentSettings.FilterType.PATH, CopyFileContentSettings.FilterAction.EXCLUDE, "secrets.env")
        )
        assertFalse(
            CopyFilterMatcher.passes(
                fileName = "secrets.env", relativePath = "secrets.env", absolutePath = "/p/secrets.env",
                useIncludeFilters = false, useExcludeFilters = true,
                includeRules = emptyList(), excludeRules = excludes
            ),
            "an excluded file must never reach the payload, whatever else was selected with it"
        )
        assertTrue(
            CopyFilterMatcher.passes(
                fileName = "App.kt", relativePath = "src/App.kt", absolutePath = "/p/src/App.kt",
                useIncludeFilters = false, useExcludeFilters = true,
                includeRules = emptyList(), excludeRules = excludes
            )
        )
    }

    @Test
    fun `a PATTERN include is honoured beside a PATH include`() {
        // Mixed INCLUDE sets are why directory pruning may only run when the set is
        // PATH-only: `*.md` says nothing about which directories can hold a match.
        val includes = listOf(
            rule(CopyFileContentSettings.FilterType.PATH, CopyFileContentSettings.FilterAction.INCLUDE, "src"),
            rule(CopyFileContentSettings.FilterType.PATTERN, CopyFileContentSettings.FilterAction.INCLUDE, "*.md")
        )
        assertTrue(
            CopyFilterMatcher.passes(
                fileName = "README.md", relativePath = "docs/README.md", absolutePath = "/p/docs/README.md",
                useIncludeFilters = true, useExcludeFilters = false,
                includeRules = includes, excludeRules = emptyList()
            ),
            "the pattern include must still admit a file outside the include PATH"
        )
        assertFalse(
            CopyFilterMatcher.passes(
                fileName = "notes.txt", relativePath = "docs/notes.txt", absolutePath = "/p/docs/notes.txt",
                useIncludeFilters = true, useExcludeFilters = false,
                includeRules = includes, excludeRules = emptyList()
            )
        )
    }

    @Test
    fun `an invalid pattern falls back to contains instead of throwing`() {
        assertTrue(CopyFilterMatcher.matchesPattern("a[b.kt", "a[b"))
    }
}
