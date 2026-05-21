package com.github.audichuang.clipcode

object PathRuleMatcher {
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
            .replace(Regex("/+"), "/")
            .trim()
            .trimEnd('/')
        return path.trim() == "/" ||
            normalizedPath.startsWith("/") ||
            normalizedPath.matches(Regex("^[A-Za-z]:/.*"))
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
            .replace(Regex("/+"), "/")
            .trim()
            .trimEnd('/')
            .trimStart('/')
}
