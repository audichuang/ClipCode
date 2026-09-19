package com.github.audichuang.clipcode

/**
 * The per-file filter decision, in ONE place.
 *
 * It used to live inline in [CopyFileContentAction.processFile], so the git payload
 * builder — which the SAME action dispatches to the moment a selection mixes revision or
 * deleted entries — applied no filters at all. An `EXCLUDE PATH secrets.env` rule kept
 * that file off the clipboard when it was selected alone, and stopped applying when a
 * staged file was selected next to it. Toggling by sibling selection is the tell that it
 * was a bug, not a decision. Mirror of `ClipCodeVSCode/src/filterMatcher.ts`.
 */
object CopyFilterMatcher {

    /**
     * [cache] memoises the compiled pattern per copy, keyed by the raw pattern string.
     * Only successful compiles are cached — an invalid pattern still falls back to
     * contains on every call, exactly as before.
     */
    fun matchesPattern(fileName: String, pattern: String, cache: MutableMap<String, Regex>? = null): Boolean {
        cache?.get(pattern)?.let { return fileName.matches(it) }
        return try {
            // Convert wildcard pattern to regex if needed
            // `.` is NOT interchangeable across the two regex engines: Java's excludes
            // U+0085 (NEL) and JavaScript's does not, so `*.txt` rejected a file named
            // `a\u0085b.txt` here and accepted it in VS Code. Spell the class out to match
            // filterMatcher.ts, whose `.` excludes exactly \n \r U+2028 U+2029.
            val anyChar = "[^\n\r\u2028\u2029]"
            val regexPattern = if (pattern.contains("*") || pattern.contains("?")) {
                pattern.replace(".", "\\.")
                    .replace("*", "$anyChar*")
                    .replace("?", anyChar)
            } else {
                pattern
            }
            val regex = Regex(regexPattern)
            cache?.put(pattern, regex)
            fileName.matches(regex)
        } catch (e: Exception) {
            // If pattern is invalid, try simple contains match
            fileName.contains(pattern)
        }
    }

    /** True when the file survives the enabled exclude and include rules. */
    fun passes(
        fileName: String,
        relativePath: String?,
        absolutePath: String,
        useIncludeFilters: Boolean,
        useExcludeFilters: Boolean,
        includeRules: List<CopyFileContentSettings.FilterRule>,
        excludeRules: List<CopyFileContentSettings.FilterRule>,
        cache: MutableMap<String, Regex>? = null
    ): Boolean {
        if (useExcludeFilters && excludeRules.isNotEmpty() &&
            excludeRules.any { matches(it, fileName, relativePath, absolutePath, cache) }
        ) {
            return false
        }
        if (useIncludeFilters && includeRules.isNotEmpty() &&
            includeRules.none { matches(it, fileName, relativePath, absolutePath, cache) }
        ) {
            return false
        }
        return true
    }

    private fun matches(
        rule: CopyFileContentSettings.FilterRule,
        fileName: String,
        relativePath: String?,
        absolutePath: String,
        cache: MutableMap<String, Regex>?
    ): Boolean = when (rule.type) {
        CopyFileContentSettings.FilterType.PATTERN -> matchesPattern(fileName, rule.value, cache)
        CopyFileContentSettings.FilterType.PATH ->
            if (PathRuleMatcher.isAbsolutePath(rule.value)) {
                PathRuleMatcher.matchesPath(absolutePath, rule.value)
            } else {
                relativePath != null && PathRuleMatcher.matchesPath(relativePath, rule.value)
            }
    }
}
