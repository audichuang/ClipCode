package com.github.audichuang.clipcode

import com.intellij.openapi.vcs.changes.Change

/**
 * Unified change type labels for Git operations.
 * Ensures consistency between copy (CopyGitFilesContentAction)
 * and paste (PasteAndRestoreFilesAction) operations.
 */
enum class ChangeTypeLabel(val label: String) {
    NEW("[NEW]"),
    MODIFIED("[MODIFIED]"),
    DELETED("[DELETED]"),
    MOVED("[MOVED]");

    companion object {
        /** All valid label strings for regex pattern building */
        val ALL_LABELS: List<String> = entries.map { it.name }

        /** Regex pattern matching any single label: \[(NEW|MODIFIED|DELETED|MOVED)\] */
        val SINGLE_LABEL_PATTERN: Regex = Regex("\\[(${ALL_LABELS.joinToString("|")})\\]")

        /** Regex pattern matching one or more labels at start of string */
        val MULTI_LABEL_PATTERN: Regex = Regex("^(?:\\[(${ALL_LABELS.joinToString("|")})\\]\\s*)+")

        /** Convert IntelliJ Change.Type to ChangeTypeLabel */
        fun fromChangeType(type: Change.Type): ChangeTypeLabel? = when (type) {
            Change.Type.NEW -> NEW
            Change.Type.DELETED -> DELETED
            Change.Type.MODIFICATION -> MODIFIED
            Change.Type.MOVED -> MOVED
            else -> null
        }

        /** Parse label string to enum (e.g., "[DELETED]" -> DELETED) */
        fun fromLabel(labelStr: String): ChangeTypeLabel? =
            entries.find { it.label.equals(labelStr, ignoreCase = true) }

        /** Check if path contains DELETED label */
        fun isDeleted(path: String): Boolean = path.contains(DELETED.label)

        /** Strip all change type labels from path */
        fun stripLabels(path: String): String =
            path.replace(MULTI_LABEL_PATTERN, "").trim()
    }
}
