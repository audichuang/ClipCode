package com.github.audichuang.clipcode

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

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
        private val DETACHED_ABSOLUTE_PATH_ANCHORS = setOf("node_modules")

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
                .filter(String::isNotBlank)
                .distinctBy(::systemPathLookupKey)

            val normalizedPrimary = primaryRootPath
                ?.let(::normalizeSystemPath)
                ?.takeIf(String::isNotBlank)
                ?: normalizedRootPaths.firstOrNull()

            val allRootPaths = buildList {
                normalizedPrimary?.let(::add)
                addAll(normalizedRootPaths)
            }
                .filter(String::isNotBlank)
                .distinctBy(::systemPathLookupKey)

            val primaryPath = normalizedPrimary?.let(Path::of)
            val allRootPathObjects = allRootPaths.map(Path::of)
            val externalLabels = allRootPathObjects
                .filter { root -> primaryPath == null || !samePath(root, primaryPath) && !isUnderRoot(root, primaryPath) }
                .mapNotNull { root -> root.name.takeIf(String::isNotBlank) }
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
                    val label = if (isExternalRoot) root.name.takeIf(String::isNotBlank) else null
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
            trimTrailingSeparator(path.replace('\\', '/').replace(Regex("/+"), "/").trim())

        private fun trimTrailingSeparator(path: String): String =
            when {
                path == "/" -> path
                path.matches(Regex("^[A-Za-z]:/$")) -> path
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
            return relativePath.substringBefore("/").takeIf(String::isNotBlank)
        }

        private fun systemPathLookupKey(path: String): String {
            val normalizedPath = normalizeSystemPath(path)
            return if (isWindowsStylePath(normalizedPath)) normalizedPath.lowercase() else normalizedPath
        }

        private fun isWindowsStylePath(path: String): Boolean =
            path.matches(Regex("^[A-Za-z]:($|/.*)"))
    }

    fun roots(): List<String> = orderedRoots.map { normalizePathString(it.path.toString()) }

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

    fun resolveWriteTarget(path: String): WriteResolution {
        absoluteRootCandidate(path)?.let { candidate ->
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

    fun resolveDeleteTarget(path: String): DeleteResolution {
        absoluteRootCandidate(path)?.let { candidate ->
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
        if (normalizedPath.isBlank()) {
            return null
        }
        if (isAbsolutePath(normalizedPath)) {
            return normalizedPath.takeIf { Files.exists(Path.of(it)) }
        }

        val relativePath = sanitizeRelativePath(normalizedPath)?.takeIf { it.isNotBlank() } ?: return null
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
        if (normalizedPath.isBlank()) {
            return null
        }

        if (!isAbsolutePath(normalizedPath)) {
            return sanitizeRelativePath(normalizedPath)?.takeIf { it.isNotBlank() }
        }

        primaryRoot?.let { root ->
            relativizePath(normalizedPath, root.path)
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }

        orderedRoots.filter { it != primaryRoot }.forEach { root ->
            val rootRelativePath = relativizePath(normalizedPath, root.path)
                ?.takeIf { it.isNotBlank() }
                ?: return@forEach
            if (root.clipboardLabel != null && !root.hasAmbiguousLabel) {
                return root.toClipboardPath(rootRelativePath)
            }
            return rootRelativePath
        }

        val absoluteSegments = normalizedPath.trim('/').split('/').filter(String::isNotBlank)
        val windowsStyleSuffix = isWindowsStylePath(normalizedPath) ||
            orderedRoots.any { root -> isWindowsStylePath(normalizePathString(root.path.toString())) }
        val suffixMatches = orderedRoots
            .flatMap { root ->
                val rootName = root.path.name.takeIf(String::isNotBlank) ?: return@flatMap emptyList()
                absoluteSegments.indices.mapNotNull { index ->
                    if (!segmentsMatch(absoluteSegments[index], rootName, windowsStyleSuffix) || index >= absoluteSegments.lastIndex) {
                        return@mapNotNull null
                    }
                    sanitizeRelativePath(absoluteSegments.drop(index + 1).joinToString("/"))
                        ?.takeIf { it.isNotBlank() }
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

        val targetGroups = suffixMatches.groupBy { candidate ->
            pathLookupKey(candidate.target.toString())
        }
        when (targetGroups.size) {
            1 -> return targetGroups.values.single()
                .firstOrNull { it.root.isPrimary }
                ?.rootRelativePath
                ?: targetGroups.values.single().first().rootRelativePath
        }

        if (suffixMatches.isNotEmpty()) {
            return null
        }

        return detachedPrimaryChildFallback(absoluteSegments, windowsStyleSuffix)
            ?: detachedAbsolutePathFallback(absoluteSegments, windowsStyleSuffix)
    }

    private fun detachedPrimaryChildFallback(absoluteSegments: List<String>, windowsStylePath: Boolean): String? {
        val root = primaryRoot ?: return null
        if (!Files.isDirectory(root.path)) {
            return null
        }

        val childNames = Files.list(root.path).use { children ->
            children
                .filter(Files::isDirectory)
                .map { child -> child.name }
                .filter { childName -> childName.isNotBlank() }
                .toList()
        }
        if (childNames.isEmpty()) {
            return null
        }

        val anchorIndex = absoluteSegments.indexOfFirst { segment ->
            DETACHED_ABSOLUTE_PATH_ANCHORS.any { anchor -> segmentsMatch(segment, anchor, windowsStylePath) }
        }
        val childMatches = absoluteSegments.indices
            .filter { index ->
                childNames.any { childName -> segmentsMatch(absoluteSegments[index], childName, windowsStylePath) }
            }
            .filter { index -> index < absoluteSegments.lastIndex }

        val preferredMatch = if (anchorIndex > 0) {
            childMatches
                .filter { index -> index < anchorIndex }
                .filterNot { index ->
                    DETACHED_ABSOLUTE_PATH_ANCHORS.any { anchor ->
                        segmentsMatch(absoluteSegments[index], anchor, windowsStylePath)
                    }
                }
                .maxOrNull()
        } else {
            childMatches
                .filterNot { index ->
                    DETACHED_ABSOLUTE_PATH_ANCHORS.any { anchor ->
                        segmentsMatch(absoluteSegments[index], anchor, windowsStylePath)
                    }
                }
                .singleOrNull()
        } ?: return null

        return sanitizeRelativePath(absoluteSegments.drop(preferredMatch).joinToString("/"))
            ?.takeIf { it.isNotBlank() }
    }

    private fun detachedAbsolutePathFallback(absoluteSegments: List<String>, windowsStylePath: Boolean): String? {
        val anchorIndex = absoluteSegments.indexOfFirst { segment ->
            DETACHED_ABSOLUTE_PATH_ANCHORS.any { anchor -> segmentsMatch(segment, anchor, windowsStylePath) }
        }
        if (anchorIndex < 0 || anchorIndex >= absoluteSegments.lastIndex) {
            return null
        }

        return sanitizeRelativePath(absoluteSegments.drop(anchorIndex).joinToString("/"))
            ?.takeIf { it.isNotBlank() }
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
            ?.takeIf { it.isNotBlank() }
    }

    private fun normalizePathString(path: String): String =
        trimTrailingSeparator(path.replace('\\', '/').replace(Regex("/+"), "/").trim())

    private fun pathLookupKey(path: String): String {
        val normalizedPath = normalizePathString(path)
        return if (isWindowsStylePath(normalizedPath)) normalizedPath.lowercase() else normalizedPath
    }

    private fun sanitizeRelativePath(path: String): String? {
        val normalizedPath = path.trim().replace('\\', '/').replace(Regex("/+"), "/").trimStart('/')
        val segments = normalizedPath.split('/')
            .filter { segment -> segment.isNotEmpty() && segment != "." }
        if (segments.isEmpty()) {
            return ""
        }
        if (segments.any { segment -> segment == ".." || segment.contains(Regex("[<>:\"|?*]")) }) {
            return null
        }
        return segments.joinToString("/")
    }

    private fun isAbsolutePath(path: String): Boolean =
        path.startsWith("/") || path.matches(Regex("^[A-Za-z]:/.*"))

    private fun isWindowsStylePath(path: String): Boolean =
        path.matches(Regex("^[A-Za-z]:($|/.*)"))

    private fun segmentsMatch(left: String, right: String, windowsStylePath: Boolean): Boolean =
        left.equals(right, ignoreCase = windowsStylePath)

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
        if (firstSegment == relativePath || firstSegment.isBlank()) {
            return emptyList()
        }
        val rootRelativePath = relativePath.substringAfter("/")
            .takeIf(String::isNotBlank)
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
        if (firstSegment.isBlank()) {
            return false
        }

        val windowsStylePath = isWindowsStylePath(relativePath) ||
            orderedRoots.any { root -> isWindowsStylePath(normalizePathString(root.path.toString())) }

        return orderedRoots
            .filter { it != primaryRoot }
            .mapNotNull { it.path.name.takeIf(String::isNotBlank) }
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
        if (relativePath.isBlank()) {
            clipboardLabel.orEmpty()
        } else {
            "${clipboardLabel.orEmpty()}/$relativePath"
        }
}
