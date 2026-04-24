package com.github.audichuang.clipcode

object CopyPathFormatter {
    fun displayPath(pathResolver: ClipboardPathResolver, absolutePath: String): String =
        pathResolver.toClipboardPath(absolutePath)

    fun relativeFilterPath(pathResolver: ClipboardPathResolver, absolutePath: String): String? {
        val clipboardPath = pathResolver.toClipboardPath(absolutePath)
        return clipboardPath.takeUnless(::isAbsolutePath)
    }

    private fun isAbsolutePath(path: String): Boolean =
        path.startsWith("/") || path.matches(Regex("^[A-Za-z]:/.*"))
}
