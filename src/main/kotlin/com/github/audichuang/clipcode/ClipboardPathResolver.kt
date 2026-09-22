package com.github.audichuang.clipcode

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/**
 * NOTE on emptiness: this file uses isEmpty/isNotEmpty and NEVER isBlank/isNotBlank.
 * Kotlin's blank test is Unicode-aware and the TypeScript mirror simply drops empty
 * segments, so a path segment consisting of U+001C or U+00A0 was discarded here and kept
 * there — the two tools then wrote DIFFERENT files for one clipboard path, and deletion
 * followed the same split. Trimming, where it is needed at all, goes through
 * ClipboardRestoreParser.asciiTrim.
 */
class ClipboardPathResolver private constructor(
    private val orderedRoots: List<RootEntry>,
    private val primaryRoot: RootEntry?,
    private val primaryReservedLabels: Set<String>
) {
    private data class RootEntry(
        val path: Path,
        val isPrimary: Boolean,
        val clipboardLabel: String?,
        val hasAmbiguousLabel: Boolean
    )

    private data class TargetCandidate(
        val root: RootEntry,
        val target: Path,
        val rootRelativePath: String
    )

    data class ResolvedTarget(
        val relativePath: String,
        val absolutePath: String,
        val rootPath: String,
        val existed: Boolean
    )

    sealed interface WriteResolution {
        data class Resolved(val target: ResolvedTarget) : WriteResolution
        data class Ambiguous(val relativePath: String, val candidates: List<String>) : WriteResolution
        data class Unresolved(val rawPath: String) : WriteResolution
    }

    sealed interface DeleteResolution {
        data class Resolved(val target: ResolvedTarget) : DeleteResolution
        data class Missing(val relativePath: String) : DeleteResolution
        data class Ambiguous(val relativePath: String, val candidates: List<String>) : DeleteResolution
        data class Unresolved(val rawPath: String) : DeleteResolution
    }

    companion object {
        /**
         * The same containment question, answerable without a resolver instance, so the
         * executor can re-ask it immediately before touching the filesystem. A plan-time
         * verdict is a verdict on the layout that existed THEN; the user has clicked through
         * modal dialogs since, and a directory can have become a link in between.
         */
        fun escapesRoots(rootPaths: List<String>, absolutePath: String): Boolean {
            val real = containmentTarget(Path.of(absolutePath)) ?: return true
            return rootPaths.none { rootPath ->
                val root = runCatching { Path.of(rootPath) }.getOrNull() ?: return@none false
                val rootReal = containmentTarget(root) ?: return@none false
                real == rootReal || real.startsWith(rootReal)
            }
        }

        /**
         * Where this path REALLY lands, with every symlink on it resolved, or null when that
         * cannot be established.
         *
         * toRealPath does the whole job when the path exists, and the OS is the only thing that
         * gets symlink resolution right. When it does not exist — a file about to be created —
         * the deepest existing ancestor is resolved and the remaining names appended, and the
         * first name below it, which may be a DANGLING symlink, is read relative to that
         * RESOLVED parent.
         *
         * Three things here were each wrong once. Skipping the leaf link meant
         * `[DELETED] link/keep.txt` reached a file outside the project. Resolving the link text
         * against the path we walked IN by — rather than against the link's real parent —
         * fabricated an in-project answer whenever a directory symlink was on the way in. And
         * counting MISSING ANCESTORS against a fixed budget meant a path with more missing
         * levels than the budget gave up before reaching the link above them, and gave up by
         * ALLOWING. The ancestor walk is therefore unbounded — it terminates at the filesystem
         * root on its own — and only symlink HOPS are capped, because only they can cycle.
         * Mirror of pathResolver.ts containmentTarget.
         */
        private fun containmentTarget(target: Path, hops: Int = 0): Path? {
            // normalize() only — never toAbsolutePath(). The resolver's own targets are built
            // from the configured roots and are already absolute in real use; resolving against
            // the process CWD would instead rebase a Windows-style path handed to a POSIX JVM
            // and make it look like an escape from its own root.
            val normalized = target.normalize()
            runCatching { return normalized.toRealPath() }
            if (hops > 40) return null // pathological symlink nest: cannot establish, so refuse

            val missing = ArrayDeque<Path>()
            var probe: Path = normalized
            while (true) {
                val parent = probe.parent ?: return normalized // nothing on the path exists at all
                probe.fileName?.let { missing.addFirst(it) }
                probe = parent
                val realProbe = runCatching { probe.toRealPath() }.getOrNull() ?: continue
                // Only the FIRST name below the deepest existing ancestor can be a dangling
                // symlink; anything deeper does not exist at all.
                val first = realProbe.resolve(missing.first())
                if (Files.isSymbolicLink(first)) {
                    val link = runCatching { Files.readSymbolicLink(first) }.getOrNull()
                    if (link != null) {
                        var linked = realProbe.resolve(link).normalize()
                        missing.drop(1).forEach { linked = linked.resolve(it) }
                        return containmentTarget(linked, hops + 1)
                    }
                }
                var resolved = realProbe
                missing.forEach { resolved = resolved.resolve(it) }
                return resolved.normalize()
            }
        }


        private val DUPLICATE_SEPARATORS = Regex("/+")
        private val WINDOWS_DRIVE_ROOT = Regex("^[A-Za-z]:/$")
        // `[\s\S]`, never `.`: Java's `.` skips five line terminators (\n \r U+0085 U+2028
        // U+2029) and JavaScript's skips four (not U+0085), while a lone \r survives the
        // \r?\n line split into a header path. With `.` and a full-string match,
        // `D:/a\rb.txt` was not absolute here and was absolute in VS Code, so one payload
        // restored a file in one tool and nothing in the other — while the POSIX twin
        // `/a\rb.txt` was absolute here all along. Same fix in CopyPathFormatter and
        // PathRuleMatcher; the TS mirror is pathResolver.ts isWindowsStylePath.
        private val WINDOWS_STYLE_PATH = Regex("^[A-Za-z]:($|/[\\s\\S]*)")
        private val WINDOWS_ABSOLUTE_PATH = Regex("^[A-Za-z]:/[\\s\\S]*")

        fun fromProject(project: Project): ClipboardPathResolver {
            val projectBasePath = ProjectPathRoots.primaryRootPath(project)
            val rootManager = ProjectRootManager.getInstance(project)
            val fileIndex = rootManager.fileIndex
            val rootPaths = buildList {
                if (!projectBasePath.isNullOrBlank()) {
                    add(projectBasePath)
                }
                addAll(
                    rootManager.contentRoots.flatMap { contentRoot ->
                        listOfNotNull(
                            contentRoot.path,
                            fileIndex.getContentRootForFile(contentRoot)?.path
                        )
                    }
                )
            }
            return fromRootPaths(rootPaths, projectBasePath)
        }

        fun fromRootPaths(rootPaths: List<String>, primaryRootPath: String? = rootPaths.firstOrNull()): ClipboardPathResolver {
            val normalizedRootPaths = rootPaths
                .map(::normalizeSystemPath)
                .filter(String::isNotEmpty)
                .distinctBy(::systemPathLookupKey)

            val normalizedPrimary = primaryRootPath
                ?.let(::normalizeSystemPath)
                ?.takeIf(String::isNotEmpty)
                ?: normalizedRootPaths.firstOrNull()

            val allRootPaths = buildList {
                normalizedPrimary?.let(::add)
                addAll(normalizedRootPaths)
            }
                .filter(String::isNotEmpty)
                .distinctBy(::systemPathLookupKey)

            val primaryPath = normalizedPrimary?.let(Path::of)
            val allRootPathObjects = allRootPaths.map(Path::of)
            val externalLabels = allRootPathObjects
                .filter { root -> primaryPath == null || !samePath(root, primaryPath) && !isUnderRoot(root, primaryPath) }
                .mapNotNull { root -> root.name.takeIf(String::isNotEmpty) }
            val externalLabelCounts = externalLabels
                .groupingBy { it }
                .eachCount()
            val primaryReservedLabels = primaryPath
                ?.let { primary ->
                    buildSet {
                        allRootPathObjects
                            .filter { root -> !samePath(root, primary) && isUnderRoot(root, primary) }
                            .mapNotNull { root -> firstSegmentUnderRoot(root, primary) }
                            .forEach(::add)
                        externalLabels
                            .filter { label -> Files.exists(primary.resolve(label)) }
                            .forEach(::add)
                    }
                }
                .orEmpty()

            val roots = allRootPaths
                .map(Path::of)
                .map { root ->
                    val isPrimary = primaryPath != null && samePath(root, primaryPath)
                    val isExternalRoot = !isPrimary && (primaryPath == null || !isUnderRoot(root, primaryPath))
                    val label = if (isExternalRoot) root.name.takeIf(String::isNotEmpty) else null
                    RootEntry(
                        path = root,
                        isPrimary = isPrimary,
                        clipboardLabel = label,
                        hasAmbiguousLabel = label != null &&
                            (externalLabelCounts[label] != 1 || primaryReservedLabels.contains(label))
                    )
                }
                .sortedByDescending { normalizeSystemPath(it.path.toString()).length }

            val primaryRoot = roots.firstOrNull { it.isPrimary } ?: roots.firstOrNull()

            return ClipboardPathResolver(roots, primaryRoot, primaryReservedLabels)
        }

        private fun normalizeSystemPath(path: String): String =
            trimTrailingSeparator(
            ClipboardRestoreParser.asciiTrim(path.replace('\\', '/').replace(DUPLICATE_SEPARATORS, "/"))
        )

        private fun trimTrailingSeparator(path: String): String =
            when {
                path == "/" -> path
                path.matches(WINDOWS_DRIVE_ROOT) -> path
                else -> path.trimEnd('/')
            }

        private fun samePath(path: Path, other: Path): Boolean =
            systemPathLookupKey(path.toString()) == systemPathLookupKey(other.toString())

        private fun isUnderRoot(path: Path, root: Path): Boolean {
            val normalizedPath = normalizeSystemPath(path.toString())
            val normalizedRoot = normalizeSystemPath(root.toString())
            val pathKey = systemPathLookupKey(normalizedPath)
            val rootKey = systemPathLookupKey(normalizedRoot)
            return pathKey != rootKey && pathKey.startsWith("$rootKey/")
        }

        private fun firstSegmentUnderRoot(path: Path, root: Path): String? {
            val normalizedPath = normalizeSystemPath(path.toString())
            val normalizedRoot = normalizeSystemPath(root.toString())
            val pathKey = systemPathLookupKey(normalizedPath)
            val rootKey = systemPathLookupKey(normalizedRoot)
            if (!pathKey.startsWith("$rootKey/")) {
                return null
            }
            val relativePath = normalizedPath.substring(normalizedRoot.length + 1)
            return relativePath.substringBefore("/").takeIf(String::isNotEmpty)
        }

        private fun systemPathLookupKey(path: String): String {
            val normalizedPath = normalizeSystemPath(path)
            return if (isWindowsStylePath(normalizedPath)) normalizedPath.lowercase() else normalizedPath
        }

        private fun isWindowsStylePath(path: String): Boolean =
            path.matches(WINDOWS_STYLE_PATH)
    }

    fun roots(): List<String> = orderedRoots.map { normalizePathString(it.path.toString()) }

    /**
     * The `// clipcode-root:` value: the basename of the base the PATHS in this payload
     * are relative to.
     *
     * Its only job is to let Paste & Restore line folder levels up, which works precisely
     * when the name and the paths describe the same base. Naming the git repository root
     * instead — while the headers stayed root-relative — broke exactly that: with the
     * project opened at `repo/src`, a payload said root `repo` and path `a.txt`, so
     * restoring into `repo` saw the name already matching and offered no adjustment,
     * landing the file at `repo/a.txt` instead of `repo/src/a.txt`.
     *
     * Multiple roots means the paths are labelled per root, so no single name describes
     * them and none is emitted. Mirror of pathResolver.ts sourceRootName.
     */
    fun singleRootName(): String? {
        val rootPaths = roots()
        if (rootPaths.size != 1) return null
        return rootPaths[0].trimEnd('/').substringAfterLast('/').ifEmpty { null }
    }

    fun toClipboardPath(absolutePath: String): String {
        val normalizedAbsolutePath = normalizePathString(absolutePath)
        primaryRoot?.let { root ->
            relativizePath(normalizedAbsolutePath, root.path)?.let { return it }
        }

        val directRelative = orderedRoots
            .asSequence()
            .filter { root -> root != primaryRoot }
            .mapNotNull { root ->
                val relativePath = relativizePath(normalizedAbsolutePath, root.path) ?: return@mapNotNull null
                if (root.clipboardLabel == null || root.hasAmbiguousLabel) {
                    normalizedAbsolutePath
                } else {
                    root.toClipboardPath(relativePath)
                }
            }
            .firstOrNull()

        return directRelative ?: normalizedAbsolutePath
    }

    /** Containment is enforced once, on the way out, so no branch can bypass it. */
    fun resolveWriteTarget(path: String): WriteResolution =
        when (val resolution = resolveWriteTargetInternal(path)) {
            is WriteResolution.Resolved ->
                if (escapesAllRoots(Path.of(resolution.target.absolutePath))) {
                    WriteResolution.Unresolved(path)
                } else {
                    resolution
                }
            else -> resolution
        }

    fun resolveDeleteTarget(path: String): DeleteResolution =
        when (val resolution = resolveDeleteTargetInternal(path)) {
            is DeleteResolution.Resolved ->
                if (escapesAllRoots(Path.of(resolution.target.absolutePath))) {
                    DeleteResolution.Unresolved(path)
                } else {
                    resolution
                }
            else -> resolution
        }

    private fun resolveWriteTargetInternal(path: String): WriteResolution {
        (absoluteRootCandidate(path) ?: crossMachineSuffixCandidate(path) ?: literalAbsoluteCandidate(path))?.let { candidate ->
            val existed = Files.exists(candidate.target) && !Files.isDirectory(candidate.target)
            return WriteResolution.Resolved(candidate.toResolvedTarget(candidate.rootRelativePath, existed))
        }

        val relativePath = toRelativeProjectPath(path) ?: return WriteResolution.Unresolved(path)
        val explicitRootCandidates = explicitRootLabelCandidates(relativePath)
        if (explicitRootCandidates.size > 1) {
            return WriteResolution.Ambiguous(relativePath, candidatePaths(explicitRootCandidates))
        }
        if (explicitRootCandidates.size == 1) {
            val candidate = explicitRootCandidates.single()
            val existed = Files.exists(candidate.target) && !Files.isDirectory(candidate.target)
            return WriteResolution.Resolved(candidate.toResolvedTarget(candidate.rootRelativePath, existed))
        }

        val targetCandidates = legacyTargetCandidates(relativePath)
        if (targetCandidates.isEmpty()) {
            return WriteResolution.Unresolved(path)
        }

        val existingCandidates = targetCandidates
            .filter { candidate -> Files.exists(candidate.target) && !Files.isDirectory(candidate.target) }

        val primaryExisting = existingCandidates.firstOrNull { it.root.isPrimary }
        val otherExistingCandidates = existingCandidates.filterNot { it.root.isPrimary }

        return when {
            primaryExisting != null && otherExistingCandidates.isNotEmpty() && !hasNestedRootPrefix(relativePath) -> {
                WriteResolution.Ambiguous(
                    relativePath,
                    candidatePaths(listOf(primaryExisting) + otherExistingCandidates)
                )
            }

            primaryExisting != null -> {
                WriteResolution.Resolved(primaryExisting.toResolvedTarget(relativePath, existed = true))
            }

            otherExistingCandidates.size > 1 -> WriteResolution.Ambiguous(
                relativePath,
                candidatePaths(otherExistingCandidates)
            )

            otherExistingCandidates.size == 1 -> {
                WriteResolution.Resolved(otherExistingCandidates.single().toResolvedTarget(relativePath, existed = true))
            }

            else -> {
                val root = primaryRoot ?: return WriteResolution.Unresolved(path)
                val targetPath = root.path.resolve(relativePath).normalize()
                WriteResolution.Resolved(
                    ResolvedTarget(
                        relativePath = relativePath,
                        absolutePath = normalizePathString(targetPath.toString()),
                        rootPath = normalizePathString(root.path.toString()),
                        existed = false
                    )
                )
            }
        }
    }

    private fun resolveDeleteTargetInternal(path: String): DeleteResolution {
        absoluteRootCandidate(path)?.let { candidate ->
            return if (Files.exists(candidate.target) && !Files.isDirectory(candidate.target)) {
                DeleteResolution.Resolved(candidate.toResolvedTarget(candidate.rootRelativePath, existed = true))
            } else {
                DeleteResolution.Missing(candidate.rootRelativePath)
            }
        }

        crossMachineSuffixCandidate(path)?.let { candidate ->
            return if (Files.exists(candidate.target) && !Files.isDirectory(candidate.target)) {
                DeleteResolution.Resolved(candidate.toResolvedTarget(candidate.rootRelativePath, existed = true))
            } else {
                DeleteResolution.Missing(candidate.rootRelativePath)
            }
        }

        val relativePath = toRelativeProjectPath(path) ?: return DeleteResolution.Unresolved(path)
        if (orderedRoots.isEmpty()) {
            return DeleteResolution.Unresolved(path)
        }

        val explicitRootCandidates = explicitRootLabelCandidates(relativePath)
        if (explicitRootCandidates.size > 1) {
            return DeleteResolution.Ambiguous(relativePath, candidatePaths(explicitRootCandidates))
        }
        if (explicitRootCandidates.size == 1) {
            val candidate = explicitRootCandidates.single()
            return if (Files.exists(candidate.target) && !Files.isDirectory(candidate.target)) {
                DeleteResolution.Resolved(candidate.toResolvedTarget(candidate.rootRelativePath, existed = true))
            } else {
                DeleteResolution.Missing(candidate.rootRelativePath)
            }
        }

        val candidates = orderedRoots
            .map { root -> root.path.resolve(relativePath).normalize() }
            .filter { Files.exists(it) && !Files.isDirectory(it) }
            .distinct()

        return when {
            candidates.isEmpty() -> DeleteResolution.Missing(relativePath)
            candidates.size > 1 -> DeleteResolution.Ambiguous(
                relativePath,
                candidates.map { normalizePathString(it.toString()) }
            )

            else -> {
                val targetPath = candidates.single()
                val matchedRoot = orderedRoots.first { targetPath.startsWith(it.path) }
                DeleteResolution.Resolved(
                    ResolvedTarget(
                        relativePath = relativePath,
                        absolutePath = normalizePathString(targetPath.toString()),
                        rootPath = normalizePathString(matchedRoot.path.toString()),
                        existed = true
                    )
                )
            }
        }
    }

    fun resolveExistingPath(path: String): String? {
        val normalizedPath = normalizePathString(path)
        if (normalizedPath.isEmpty()) {
            return null
        }
        if (isAbsolutePath(normalizedPath)) {
            return normalizedPath.takeIf { Files.exists(Path.of(it)) }
        }

        val relativePath = sanitizeRelativePath(normalizedPath)?.takeIf { it.isNotEmpty() } ?: return null
        val explicitRootCandidates = explicitRootLabelCandidates(relativePath)
            .ifEmpty { explicitRootLabelRootCandidates(relativePath) }
        val candidates = explicitRootCandidates.takeIf { it.isNotEmpty() } ?: legacyTargetCandidates(relativePath)
        val existingCandidates = candidates
            .map { it.target }
            .filter(Files::exists)
            .map { normalizePathString(it.toString()) }
            .distinct()

        return existingCandidates.singleOrNull()
    }

    private fun toRelativeProjectPath(path: String): String? {
        val normalizedPath = normalizePathString(path)
        if (normalizedPath.isEmpty()) {
            return null
        }

        if (!isAbsolutePath(normalizedPath)) {
            return sanitizeRelativePath(normalizedPath)?.takeIf { it.isNotEmpty() }
        }

        primaryRoot?.let { root ->
            relativizePath(normalizedPath, root.path)
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }
        }

        orderedRoots.filter { it != primaryRoot }.forEach { root ->
            val rootRelativePath = relativizePath(normalizedPath, root.path)
                ?.takeIf { it.isNotEmpty() }
                ?: return@forEach
            if (root.clipboardLabel != null && !root.hasAmbiguousLabel) {
                return root.toClipboardPath(rootRelativePath)
            }
            return rootRelativePath
        }

        val absoluteSegments = normalizedPath.trim('/').split('/').filter(String::isNotEmpty)
        val windowsStyleSuffix = isWindowsStylePath(normalizedPath) ||
            orderedRoots.any { root -> isWindowsStylePath(normalizePathString(root.path.toString())) }
        val suffixMatches = suffixMatchesFor(absoluteSegments, windowsStyleSuffix)

        uniqueTargetOf(suffixMatches)?.let { winner ->
            return if (!winner.root.isPrimary &&
                winner.root.clipboardLabel != null && !winner.root.hasAmbiguousLabel
            ) {
                winner.root.toClipboardPath(winner.rootRelativePath)
            } else {
                winner.rootRelativePath
            }
        }

        // Writes have a literal absolute fallback; deletes require a mapped target.
        return null
    }

    private fun relativizePath(absolutePath: String, root: Path): String? {
        val normalizedRoot = normalizePathString(root.toString())
        val absoluteKey = pathLookupKey(absolutePath)
        val rootKey = pathLookupKey(normalizedRoot)
        if (absoluteKey == rootKey) {
            return ""
        }
        if (!absoluteKey.startsWith("$rootKey/")) {
            return null
        }
        return sanitizeRelativePath(absolutePath.substring(normalizedRoot.length + 1))
            ?.takeIf { it.isNotEmpty() }
    }

    private fun normalizePathString(path: String): String =
        trimTrailingSeparator(
            ClipboardRestoreParser.asciiTrim(path.replace('\\', '/').replace(DUPLICATE_SEPARATORS, "/"))
        )

    private fun pathLookupKey(path: String): String {
        val normalizedPath = normalizePathString(path)
        return if (isWindowsStylePath(normalizedPath)) normalizedPath.lowercase() else normalizedPath
    }

    private fun sanitizeRelativePath(path: String): String? {
        // asciiTrim, not String.trim(): the two stdlibs disagree on U+001C-U+001F and
        // U+FEFF, and here that decided the FILENAME each tool wrote — `// file: a.txt\u001C`
        // restored as `a.txt` here and `a.txt\u001C` in VS Code. The parsers were aligned
        // first; without this the divergence just moved one layer down.
        val normalizedPath = ClipboardRestoreParser.asciiTrim(path)
            .replace('\\', '/').replace(DUPLICATE_SEPARATORS, "/").trimStart('/')
        val segments = normalizedPath.split('/')
            .filter { segment -> segment.isNotEmpty() && segment != "." }
        if (segments.isEmpty()) {
            return ""
        }
        // A control character (0x00-0x1F) is refused on EVERY platform, exactly like the
        // Windows-illegal <>:"|?* beside it: Windows cannot create such a name and
        // WindowsPathParser throws on it, so allowing it elsewhere made one payload restore
        // differently per platform. U+0085/U+2028/U+2029 are legal on Windows and stay.
        // TS mirror: pathResolver.ts sanitizeRelativePath.
        if (segments.any { segment -> segment == ".." || segment.any { it in "<>:\"|?*" || it.code < 0x20 } }) {
            return null
        }
        return segments.joinToString("/")
    }

    private fun isAbsolutePath(path: String): Boolean =
        path.startsWith("/") || path.matches(WINDOWS_ABSOLUTE_PATH)

    private fun isWindowsStylePath(path: String): Boolean =
        path.matches(WINDOWS_STYLE_PATH)

    private fun segmentsMatch(left: String, right: String, windowsStylePath: Boolean): Boolean =
        left.equals(right, ignoreCase = windowsStylePath)

    /** Every root/segment-index pairing whose suffix could name this file. */
    private fun suffixMatchesFor(
        absoluteSegments: List<String>,
        windowsStyleSuffix: Boolean
    ): List<TargetCandidate> =
        orderedRoots
            .flatMap { root ->
                val rootName = root.path.name.takeIf(String::isNotEmpty) ?: return@flatMap emptyList()
                absoluteSegments.indices.mapNotNull { index ->
                    if (!segmentsMatch(absoluteSegments[index], rootName, windowsStyleSuffix) || index >= absoluteSegments.lastIndex) {
                        return@mapNotNull null
                    }
                    sanitizeRelativePath(absoluteSegments.drop(index + 1).joinToString("/"))
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { relativePath ->
                            TargetCandidate(
                                root = root,
                                target = root.path.resolve(relativePath).normalize(),
                                rootRelativePath = relativePath
                            )
                        }
                }
            }
            .distinctBy { candidate ->
                "${pathLookupKey(candidate.root.path.toString())}\u0000${candidate.rootRelativePath}"
            }

    /** The single target the suffix matches agree on, or null for zero/several. */
    private fun uniqueTargetOf(matches: List<TargetCandidate>): TargetCandidate? {
        val groups = matches.groupBy { pathLookupKey(it.target.toString()) }
        if (groups.size != 1) return null
        val group = groups.values.single()
        return group.firstOrNull { it.root.isPrimary } ?: group.first()
    }

    /**
     * The suffix match already determines a UNIQUE target root. Handing the resolvers only
     * a relative path threw that away, so they resolved it against the PRIMARY root: a file
     * belonging to an external root was written over the primary repo's same-named file and
     * the real target was never created. Both resolvers take this candidate; the string form
     * stays in toRelativeProjectPath for its own label and ambiguity fallbacks.
     */
    private fun crossMachineSuffixCandidate(path: String): TargetCandidate? {
        val normalizedPath = normalizePathString(path)
        if (normalizedPath.isEmpty() || !isAbsolutePath(normalizedPath)) return null
        val absoluteSegments = normalizedPath.trim('/').split('/').filter(String::isNotEmpty)
        val windowsStyleSuffix = isWindowsStylePath(normalizedPath) ||
            orderedRoots.any { root -> isWindowsStylePath(normalizePathString(root.path.toString())) }
        return uniqueTargetOf(suffixMatchesFor(absoluteSegments, windowsStyleSuffix))
    }

    /**
     * True when the target's REAL location is no longer inside any root — i.e. a directory
     * symlink inside the project points out of it. Containment is the property that matters,
     * not "contains a symlink": a link that stays inside the project is legitimate (pnpm's
     * node_modules layout depends on it). Without this, `[DELETED] link/keep.txt` really
     * removed a file outside the project.
     */
    /**
     * null from containmentTarget means containment could not be established — refuse.
     * Failing OPEN there is how a deep path stepped over the symlink above it.
     */
    private fun escapesAllRoots(target: Path): Boolean =
        escapesRoots(orderedRoots.map { it.path.toString() }, target.toString())

    // Unmapped absolute paths retain every directory under the primary root. Only the
    // drive colon/root separator is removed; archive names and arbitrary folders stay.
    // Writes only: a foreign delete must not acquire a new target through this fallback.
    private fun literalAbsoluteCandidate(path: String): TargetCandidate? {
        val root = primaryRoot ?: return null
        val normalizedPath = normalizePathString(path)
        if (!isAbsolutePath(normalizedPath) || normalizedPath.matches(WINDOWS_DRIVE_ROOT)) return null
        val relativePath = sanitizeRelativePath(
            if (normalizedPath.matches(WINDOWS_ABSOLUTE_PATH)) normalizedPath.removeRange(1, 2) else normalizedPath
        )?.takeIf { it.isNotEmpty() } ?: return null
        return TargetCandidate(root, root.path.resolve(relativePath).normalize(), relativePath)
    }

    private fun absoluteRootCandidate(path: String): TargetCandidate? {
        val normalizedPath = normalizePathString(path)
        if (!isAbsolutePath(normalizedPath)) {
            return null
        }

        return orderedRoots
            .asSequence()
            .mapNotNull { root ->
                val rootRelativePath = relativizePath(normalizedPath, root.path) ?: return@mapNotNull null
                TargetCandidate(
                    root = root,
                    target = root.path.resolve(rootRelativePath).normalize(),
                    rootRelativePath = rootRelativePath
                )
            }
            .firstOrNull()
    }

    private fun explicitRootLabelCandidates(relativePath: String): List<TargetCandidate> {
        val firstSegment = relativePath.substringBefore("/")
        if (firstSegment == relativePath || firstSegment.isEmpty()) {
            return emptyList()
        }
        val rootRelativePath = relativePath.substringAfter("/")
            .takeIf(String::isNotEmpty)
            ?: return emptyList()

        return buildList {
            if (primaryReservedLabels.contains(firstSegment)) {
                primaryRoot?.let { root ->
                    add(
                        TargetCandidate(
                            root = root,
                            target = root.path.resolve(relativePath).normalize(),
                            rootRelativePath = relativePath
                        )
                    )
                }
            }
            addAll(
                orderedRoots
                    .filter { root -> root.clipboardLabel == firstSegment }
                    .map { root ->
                        TargetCandidate(
                            root = root,
                            target = root.path.resolve(rootRelativePath).normalize(),
                            rootRelativePath = rootRelativePath
                        )
                    }
            )
        }
            .distinctBy { pathLookupKey(it.target.toString()) }
    }

    private fun explicitRootLabelRootCandidates(relativePath: String): List<TargetCandidate> {
        if (relativePath.contains("/")) {
            return emptyList()
        }

        return buildList {
            if (primaryReservedLabels.contains(relativePath)) {
                primaryRoot?.let { root ->
                    add(
                        TargetCandidate(
                            root = root,
                            target = root.path.resolve(relativePath).normalize(),
                            rootRelativePath = relativePath
                        )
                    )
                }
            }
            addAll(
                orderedRoots
                    .filter { root -> root.clipboardLabel == relativePath }
                    .map { root ->
                        TargetCandidate(
                            root = root,
                            target = root.path,
                            rootRelativePath = ""
                        )
                    }
            )
        }
            .distinctBy { pathLookupKey(it.target.toString()) }
    }

    private fun legacyTargetCandidates(relativePath: String): List<TargetCandidate> =
        rootsPrimaryFirst()
            .map { root ->
                TargetCandidate(
                    root = root,
                    target = root.path.resolve(relativePath).normalize(),
                    rootRelativePath = relativePath
                )
            }
            .distinctBy { pathLookupKey(it.target.toString()) }

    private fun rootsPrimaryFirst(): List<RootEntry> = buildList {
        primaryRoot?.let(::add)
        orderedRoots.filter { it != primaryRoot }.forEach(::add)
    }.distinct()

    private fun hasNestedRootPrefix(relativePath: String): Boolean {
        val firstSegment = relativePath.substringBefore("/")
        if (firstSegment.isEmpty()) {
            return false
        }

        val windowsStylePath = isWindowsStylePath(relativePath) ||
            orderedRoots.any { root -> isWindowsStylePath(normalizePathString(root.path.toString())) }

        return orderedRoots
            .filter { it != primaryRoot }
            .mapNotNull { it.path.name.takeIf(String::isNotEmpty) }
            .any { rootName -> segmentsMatch(rootName, firstSegment, windowsStylePath) }
    }

    private fun candidatePaths(candidates: List<TargetCandidate>): List<String> =
        candidates
            .map { normalizePathString(it.target.toString()) }
            .distinctBy(::pathLookupKey)

    private fun TargetCandidate.toResolvedTarget(relativePath: String, existed: Boolean): ResolvedTarget =
        ResolvedTarget(
            relativePath = relativePath,
            absolutePath = normalizePathString(target.toString()),
            rootPath = normalizePathString(root.path.toString()),
            existed = existed
        )

    private fun RootEntry.toClipboardPath(relativePath: String): String =
        if (relativePath.isEmpty()) {
            clipboardLabel.orEmpty()
        } else {
            "${clipboardLabel.orEmpty()}/$relativePath"
        }
}
