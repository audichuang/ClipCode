package com.github.audichuang.clipcode

// "Off by one folder level" detection for Paste & Restore.
//
// When a bundle was copied from a different folder level than the target project
// (e.g. copied with the repo as root → restored into the parent that contains the
// repo, or vice-versa), the clipboard's relative paths are all off by the same one
// level. We infer that single offset by checking which interpretation lands the
// files inside directories that ALREADY exist in the target — then the caller asks
// the user to confirm before applying it.
//
// Mirror of the VS Code sibling's restoreBase.ts — keep the two in sync.

sealed class RestoreBase {
    /** Drop a redundant leading `segment/`. */
    data class Strip(val segment: String) : RestoreBase()

    /** Nest everything under an existing `prefix/`. */
    data class Add(val prefix: String) : RestoreBase()
}

data class RestoreBaseSuggestion(
    val base: RestoreBase,
    /** Human description for the confirmation prompt. */
    val label: String,
    /** Subdir-bearing files that land in an existing dir under this base. */
    val matched: Int,
    /** Subdir-bearing files considered. */
    val total: Int
)

interface DirProbe {
    fun isDir(absPath: String): Boolean
    fun childDirs(rootAbsPath: String): List<String>
}

object RestoreBaseDetector {
    private val WINDOWS_DRIVE = Regex("^[A-Za-z]:[\\\\/]")

    fun applyRestoreBase(base: RestoreBase, relativePath: String): String = when (base) {
        is RestoreBase.Add -> "${base.prefix}/$relativePath"
        is RestoreBase.Strip -> {
            val slash = relativePath.indexOf('/')
            if (slash >= 0 && relativePath.substring(0, slash) == base.segment) {
                relativePath.substring(slash + 1)
            } else {
                relativePath
            }
        }
    }

    /** Reject POSIX absolutes, Windows drive paths (C:/ or C:\) and UNC (\\server). */
    fun isRelativeEntryPath(p: String): Boolean =
        p.isNotEmpty() && !p.startsWith("/") && !p.startsWith("\\") && !WINDOWS_DRIVE.containsMatchIn(p)

    private fun joinPath(root: String, rel: String): String = "${root.trimEnd('/')}/$rel"

    private fun baseNameOf(p: String): String =
        p.replace('\\', '/').trimEnd('/').substringAfterLast('/')

    // Deterministic alignment when the clipboard carries the source root name.
    // `rels` is every plain relative path (any depth, may be empty).
    private fun suggestFromSourceRoot(
        primaryRoot: String,
        rels: List<String>,
        probe: DirProbe,
        sourceRoot: String
    ): RestoreBaseSuggestion? {
        if (rels.isEmpty()) return null
        val targetName = baseNameOf(primaryRoot)
        // Target is the same-named root → paths are already correctly anchored.
        if (targetName == sourceRoot) return null

        // The bundle was copied from the parent (subdir-bearing paths carry this repo's
        // own folder name as a redundant leading segment) → strip it.
        val multi = rels.filter { it.contains('/') }
        val firstSegments = multi.map { it.substringBefore('/') }.toSet()
        if (multi.isNotEmpty() && firstSegments.size == 1 && firstSegments.first() == targetName) {
            return RestoreBaseSuggestion(
                base = RestoreBase.Strip(targetName),
                label = "remove the leading \"$targetName/\"",
                matched = rels.size,
                total = rels.size
            )
        }

        // The bundle was copied with the repo as root, and that repo folder exists here
        // (project opened one level up) → nest everything under it.
        if (probe.isDir("${primaryRoot.trimEnd('/')}/$sourceRoot")) {
            return RestoreBaseSuggestion(
                base = RestoreBase.Add(sourceRoot),
                label = "place everything under \"$sourceRoot/\"",
                matched = rels.size,
                total = rels.size
            )
        }

        // Different root name, no matching wrapper here → likely a genuinely different
        // location; don't guess (leave paths as-is).
        return null
    }

    fun suggestRestoreBase(
        primaryRoot: String,
        relativePaths: List<String>,
        probe: DirProbe,
        sourceRoot: String? = null
    ): RestoreBaseSuggestion? {
        val rels = relativePaths.filter { isRelativeEntryPath(it) }

        // With source-root metadata we can align deterministically — even for a single
        // file or root-level-only paths — so this runs before the heuristic's threshold.
        if (sourceRoot != null) return suggestFromSourceRoot(primaryRoot, rels, probe, sourceRoot)

        // The heuristic needs ≥2 subdir-bearing paths so one coincidental path can't
        // drive a relocation.
        val multi = rels.filter { it.contains('/') }
        if (multi.size < 2) return null

        fun parentExists(rel: String): Boolean {
            val slash = rel.lastIndexOf('/')
            return slash >= 0 && probe.isDir(joinPath(primaryRoot, rel.substring(0, slash)))
        }

        fun scoreBase(base: RestoreBase?): Int =
            multi.count { parentExists(if (base != null) applyRestoreBase(base, it) else it) }

        val identityScore = scoreBase(null)

        data class Candidate(val base: RestoreBase, val score: Int, val label: String)

        val candidates = mutableListOf<Candidate>()

        // strip-1: only when every subdir-bearing path shares one leading segment AND
        // that segment is the project folder's own name — i.e. the bundle was copied
        // from the parent that contains this repo. Anchoring to the basename avoids
        // stripping a legitimately-named top folder like "examples/".
        val firstSegments = multi.map { it.substringBefore('/') }.toSet()
        if (firstSegments.size == 1 && firstSegments.first() == baseNameOf(primaryRoot)) {
            val segment = firstSegments.first()
            val strip = RestoreBase.Strip(segment)
            candidates.add(Candidate(strip, scoreBase(strip), "remove the leading \"$segment/\""))
        }

        // add-prefix: nest under each directory that already exists at the project root.
        for (prefix in probe.childDirs(primaryRoot)) {
            val add = RestoreBase.Add(prefix)
            candidates.add(Candidate(add, scoreBase(add), "place everything under \"$prefix/\""))
        }
        if (candidates.isEmpty()) return null

        // The winning score must be UNIQUE — except a basename-anchored strip is allowed
        // to win a tie since it's unambiguous. Two child dirs that match equally well
        // (e.g. repo-a/src and repo-b/src) are ambiguous → suggest nothing.
        val maxScore = candidates.maxOf { it.score }
        val top = candidates.filter { it.score == maxScore }
        val best = top.singleOrNull() ?: top.firstOrNull { it.base is RestoreBase.Strip }

        // Surface only a confident, non-trivial offset: beat leaving paths as-is and
        // match a majority of the subdir-bearing files.
        if (best == null || best.score <= identityScore || best.score * 2 < multi.size) {
            return null
        }
        return RestoreBaseSuggestion(base = best.base, label = best.label, matched = best.score, total = multi.size)
    }
}
