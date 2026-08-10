package com.github.audichuang.clipcode

object PathRuleMatcher {
    private val DUPLICATE_SEPARATORS = Regex("/+")
    private val WINDOWS_ABSOLUTE_PATH = Regex("^[A-Za-z]:/.*")

    fun matchesPath(path: String, rulePath: String): Boolean =
        isSameOrChild(normalizePath(path), normalizePath(rulePath))

    fun overlapsDirectory(directoryPath: String, rulePath: String): Boolean {
        val normalizedDirectory = normalizePath(directoryPath)
        val normalizedRule = normalizePath(rulePath)
        if (normalizedDirectory.isBlank()) {
            return true
        }
        return isSameOrChild(normalizedDirectory, normalizedRule) ||
            isSameOrChild(normalizedRule, normalizedDirectory)
    }

    fun isAbsolutePath(path: String): Boolean {
        val normalizedPath = path.replace('\\', '/')
            .replace(DUPLICATE_SEPARATORS, "/")
            .trim()
            .trimEnd('/')
        return path.trim() == "/" ||
            normalizedPath.startsWith("/") ||
            normalizedPath.matches(WINDOWS_ABSOLUTE_PATH)
    }

    private fun isSameOrChild(path: String, parentPath: String): Boolean {
        if (parentPath.isBlank()) {
            return true
        }
        if (path.isBlank()) {
            return false
        }
        return path == parentPath || path.startsWith("$parentPath/")
    }

    private fun normalizePath(path: String): String =
        path.replace('\\', '/')
            .replace(DUPLICATE_SEPARATORS, "/")
            .trim()
            .trimEnd('/')
            .trimStart('/')
}
