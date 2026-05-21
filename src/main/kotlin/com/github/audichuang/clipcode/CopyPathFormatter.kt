package com.github.audichuang.clipcode

object CopyPathFormatter {
    fun displayPath(pathResolver: ClipboardPathResolver, absolutePath: String): String =
        pathResolver.toClipboardPath(absolutePath)

    fun displayPathOrFallback(pathResolver: ClipboardPathResolver, absolutePath: String, fallbackPath: String): String {
        val displayPath = displayPath(pathResolver, absolutePath)
        return if (isAbsolutePath(displayPath)) fallbackPath else displayPath
    }

    fun relativeFilterPath(pathResolver: ClipboardPathResolver, absolutePath: String): String? {
        val clipboardPath = pathResolver.toClipboardPath(absolutePath)
        return clipboardPath.takeUnless(::isAbsolutePath)
    }

    private fun isAbsolutePath(path: String): Boolean =
        path.startsWith("/") || path.matches(Regex("^[A-Za-z]:/.*"))
}
