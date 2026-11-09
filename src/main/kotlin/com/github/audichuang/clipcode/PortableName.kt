package com.github.audichuang.clipcode

/**
 * The "Name not portable" rule of a files and folders copy (spec non-text-files.md, "What is carried";
 * decision D2). A path is portable exactly when the reader keeps it unchanged:
 * [ClipboardPathResolver.sanitizeRelativePath] of the path equals the path. That is OpenIDEA's
 * `is_exportable_relative_path` (`crates/ide-clip/src/paths.rs`, `sanitize_relative_path(v) == Some(v)`)
 * and the VS Code `pathResolver.ts sanitizeRelativePath`, so a header the writer emits is one every
 * reader restores to the same file. Not portable:
 * - a segment that is `..`, or `.` / empty (a `./` or `//` the reader would drop);
 * - a segment containing `<` `>` `:` `"` `|` `?` `*`, `\` (the reader turns it into a separator) or a
 *   control character U+0000-U+001F (tab and newline included);
 * - the WHOLE path starting or ending with ASCII whitespace (space, tab, LF, VT, FF, CR).
 * Everything else is allowed: U+007F, U+0085, non-ASCII letters, emoji, spaces inside a name.
 * Pure: no IDE dependency.
 */
object PortableName {
    fun isPortableRelativePath(path: String): Boolean =
        path.isNotEmpty() && ClipboardPathResolver.sanitizeRelativePath(path) == path

    /**
     * A folder is judged by its segments only: a trailing space on a folder in the middle of a path is
     * allowed (spec), so the folder path is checked as the parent of a file. "" is the root itself.
     */
    fun isPortableFolderPath(path: String): Boolean =
        isPortableRelativePath(if (path.isEmpty()) "x" else "$path/x")

    /**
     * What the rule is applied to: the raw path relative to the root the header is built from, or, for a
     * file outside every root (its header is the absolute path), the absolute path without its leading
     * `/` or `X:/`.
     */
    fun checkedPath(resolver: ClipboardPathResolver, absolutePath: String): String =
        resolver.rawRelativePath(absolutePath) ?: stripAbsoluteRoot(absolutePath)

    private val DRIVE_ROOT = Regex("^[A-Za-z]:/")

    private fun stripAbsoluteRoot(path: String): String =
        DRIVE_ROOT.find(path)?.let { path.substring(it.range.last + 1) } ?: path.removePrefix("/")

    /** A name for a notice: control characters (a tab, a newline) are made visible. Mirror of OpenIDEA visibleName. */
    fun visibleName(name: String): String = buildString {
        for (c in name) {
            when {
                c == '\t' -> append("\\t")
                c.code < 0x20 -> append("\\x").append(String.format(java.util.Locale.ROOT, "%02x", c.code))
                else -> append(c)
            }
        }
    }
}
