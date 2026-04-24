package com.github.audichuang.clipcode

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

class ClipboardPathResolver private constructor(
    private val orderedRoots: List<Path>,
    private val primaryRoot: Path?
) {
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
        fun fromProject(project: Project): ClipboardPathResolver {
            val projectBasePath = project.basePath
            val rootPaths = buildList {
                if (!projectBasePath.isNullOrBlank()) {
                    add(projectBasePath)
                }
                addAll(ProjectRootManager.getInstance(project).contentRoots.map { it.path })
            }
            return fromRootPaths(rootPaths, projectBasePath)
        }

        fun fromRootPaths(rootPaths: List<String>, primaryRootPath: String? = rootPaths.firstOrNull()): ClipboardPathResolver {
            val normalizedRoots = rootPaths
                .map(::normalizeSystemPath)
                .filter(String::isNotBlank)
                .distinct()
                .sortedByDescending(String::length)
                .map(Path::of)

            val normalizedPrimary = primaryRootPath
                ?.let(::normalizeSystemPath)
                ?.takeIf(String::isNotBlank)
                ?.let(Path::of)
                ?: normalizedRoots.firstOrNull()

            return ClipboardPathResolver(normalizedRoots, normalizedPrimary)
        }

        private fun normalizeSystemPath(path: String): String =
            trimTrailingSeparator(path.replace('\\', '/').replace(Regex("/+"), "/").trim())

        private fun trimTrailingSeparator(path: String): String =
            when {
                path == "/" -> path
                path.matches(Regex("^[A-Za-z]:/$")) -> path
                else -> path.trimEnd('/')
            }
    }

    fun roots(): List<String> = orderedRoots.map { normalizePathString(it.toString()) }

    fun toClipboardPath(absolutePath: String): String {
        val normalizedAbsolutePath = normalizePathString(absolutePath)
        val directRelative = orderedRoots
            .asSequence()
            .mapNotNull { root -> relativizePath(normalizedAbsolutePath, root) }
            .firstOrNull()

        return directRelative ?: normalizedAbsolutePath
    }

    fun resolveWriteTarget(path: String): WriteResolution {
        val relativePath = toRelativeProjectPath(path) ?: return WriteResolution.Unresolved(path)
        val existingCandidates = orderedRoots
            .map { root -> root.resolve(relativePath).normalize() }
            .filter { Files.exists(it) && !Files.isDirectory(it) }
            .distinct()

        return when {
            existingCandidates.size > 1 -> WriteResolution.Ambiguous(
                relativePath,
                existingCandidates.map { normalizePathString(it.toString()) }
            )

            existingCandidates.size == 1 -> {
                val targetPath = existingCandidates.single()
                val matchedRoot = orderedRoots.first { targetPath.startsWith(it) }
                WriteResolution.Resolved(
                    ResolvedTarget(
                        relativePath = relativePath,
                        absolutePath = normalizePathString(targetPath.toString()),
                        rootPath = normalizePathString(matchedRoot.toString()),
                        existed = true
                    )
                )
            }

            else -> {
                val root = primaryRoot ?: return WriteResolution.Unresolved(path)
                val targetPath = root.resolve(relativePath).normalize()
                WriteResolution.Resolved(
                    ResolvedTarget(
                        relativePath = relativePath,
                        absolutePath = normalizePathString(targetPath.toString()),
                        rootPath = normalizePathString(root.toString()),
                        existed = false
                    )
                )
            }
        }
    }

    fun resolveDeleteTarget(path: String): DeleteResolution {
        val relativePath = toRelativeProjectPath(path) ?: return DeleteResolution.Unresolved(path)
        if (orderedRoots.isEmpty()) {
            return DeleteResolution.Unresolved(path)
        }

        val candidates = orderedRoots
            .map { root -> root.resolve(relativePath).normalize() }
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
                val matchedRoot = orderedRoots.first { targetPath.startsWith(it) }
                DeleteResolution.Resolved(
                    ResolvedTarget(
                        relativePath = relativePath,
                        absolutePath = normalizePathString(targetPath.toString()),
                        rootPath = normalizePathString(matchedRoot.toString()),
                        existed = true
                    )
                )
            }
        }
    }

    private fun toRelativeProjectPath(path: String): String? {
        val normalizedPath = normalizePathString(path)
        if (normalizedPath.isBlank()) {
            return null
        }

        if (!isAbsolutePath(normalizedPath)) {
            return sanitizeRelativePath(normalizedPath)?.takeIf { it.isNotBlank() }
        }

        orderedRoots.forEach { root ->
            relativizePath(normalizedPath, root)
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }

        val absoluteSegments = normalizedPath.trim('/').split('/').filter(String::isNotBlank)
        val suffixMatches = orderedRoots
            .flatMap { root ->
                val rootName = root.name.takeIf(String::isNotBlank) ?: return@flatMap emptyList()
                absoluteSegments.indices.mapNotNull { index ->
                    if (absoluteSegments[index] != rootName || index >= absoluteSegments.lastIndex) {
                        return@mapNotNull null
                    }
                    sanitizeRelativePath(absoluteSegments.drop(index + 1).joinToString("/"))
                        ?.takeIf { it.isNotBlank() }
                }
            }
            .distinct()

        return when (suffixMatches.size) {
            1 -> suffixMatches.single()
            else -> null
        }
    }

    private fun relativizePath(absolutePath: String, root: Path): String? {
        val normalizedRoot = normalizePathString(root.toString())
        if (absolutePath == normalizedRoot) {
            return ""
        }
        val prefix = "$normalizedRoot/"
        if (!absolutePath.startsWith(prefix)) {
            return null
        }
        return sanitizeRelativePath(absolutePath.removePrefix(prefix))
            ?.takeIf { it.isNotBlank() }
    }

    private fun normalizePathString(path: String): String =
        trimTrailingSeparator(path.replace('\\', '/').replace(Regex("/+"), "/").trim())

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
}
