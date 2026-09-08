package com.github.audichuang.clipcode

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.impl.ContentRevisionCache
import git4idea.GitContentRevision
import git4idea.GitUtil
import git4idea.GitVcs
import git4idea.commands.GitBinaryHandler
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.commands.Git
import git4idea.util.GitTextConvMode
import com.intellij.openapi.options.advanced.AdvancedSettings
import git4idea.util.GitFileUtils
import com.intellij.openapi.vfs.VirtualFile

/** Per-copy batching; reuse the platform's bounded cache, Git executable, filters and decoding. */
internal object GitBatchContentReader {
    private val logger = Logger.getInstance(GitBatchContentReader::class.java)
    private val type = ContentRevisionCache.UniqueType.REPOSITORY_CONTENT

    fun read(project: Project, revisions: List<ContentRevision>): Map<ContentRevision, String> {
        ProgressManager.checkCanceled()
        val result = mutableMapOf<ContentRevision, String>()
        val cache = ProjectLevelVcsManager.getInstance(project).contentRevisionCache
        val candidates = revisions.distinct().filterIsInstance<GitContentRevision>().filter {
            // Specialized revisions (binary, submodule, custom providers) retain their own semantics.
            it.javaClass == GitContentRevision::class.java && !it.file.isDirectory &&
                GitUtil.isHashString(it.revisionNumber.asString()) &&
                !it.file.path.contains('\n') && !it.file.path.contains('\r')
        }
        if (candidates.size < 2) return result
        val missing = candidates.filter { revision ->
            val bytes = cache.getBytes(revision.file, revision.revisionNumber, GitVcs.getKey(), type)
            if (bytes != null) ContentRevisionCache.getAsString(bytes, revision.file, revision.charset)?.let { result[revision] = it }
            bytes == null
        }
        val groups = missing.groupBy { revision ->
            try { GitUtil.getRootForFile(project, revision.file) }
            catch (e: ProcessCanceledException) { throw e }
            catch (_: Exception) { null }
        }
        for ((root, group) in groups) {
            if (root == null || group.size < 2) continue
            // ponytail: bounded request groups; unusually large blobs use the platform's single-file reader.
            for (chunk in withoutConversions(project, root, group).chunked(32)) {
                ProgressManager.checkCanceled()
                try {
                    val paths = chunk.map { it.file.path.removePrefix(root.path + "/") }
                    val specs = chunk.mapIndexed { i, r -> "${r.revisionNumber.asString()}:${paths[i]}" }
                    val headers = run(project, root, "--batch-check", specs)
                        .toString(Charsets.UTF_8).trimEnd('\n').split('\n')
                    check(headers.size == chunk.size) { "Unexpected Git batch metadata count" }
                    val eligible = headers.mapIndexedNotNull { index, line ->
                        val fields = line.split(' ')
                        if (fields.size == 3 && fields[1] == "blob" &&
                            (fields[2].toLongOrNull() ?: Long.MAX_VALUE) <= 128 * 1024L) index else null
                    }
                    if (eligible.isEmpty()) continue
                    // Object IDs avoid ambiguity in paths containing spaces.
                    val requests = eligible.map { headers[it].substringBefore(' ') }
                    val bytes = run(project, root, "--batch", requests)
                    val contents = parse(bytes, eligible.size)
                    eligible.forEachIndexed { position, index ->
                        ProgressManager.checkCanceled()
                        val revision = chunk[index]
                        val content = contents[position] ?: return@forEachIndexed
                        ContentRevisionCache.checkContentsSize(revision.file.path, content.size.toLong())
                        val cached = ContentRevisionCache.getOrLoadAsBytes(project, revision.file,
                            revision.revisionNumber, GitVcs.getKey(), type) { content }
                        ContentRevisionCache.getAsString(cached, revision.file, revision.charset)?.let { result[revision] = it }
                    }
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Exception) {
                    // A failed batch is not an empty revision; unresolved entries use the original reader.
                    logger.warn("Git batch unavailable; using individual revision reads", e)
                }
            }
        }
        return result
    }

    private fun withoutConversions(project: Project, root: VirtualFile, revisions: List<GitContentRevision>): List<GitContentRevision> {
        ProgressManager.checkCanceled()
        val mode = AdvancedSettings.getEnum(GitFileUtils.READ_CONTENT_WITH, GitTextConvMode::class.java)
        if (mode == GitTextConvMode.NONE) return revisions
        try {
            if (mode == GitTextConvMode.FILTERS) {
                val config = GitLineHandler(project, root, GitCommand.CONFIG)
                config.setSilent(true)
                config.addParameters("--get-regexp", "^core\\.(autocrlf|eol)$")
                val result = Git.getInstance().runCommand(config)
                if (result.exitCode !in 0..1) return emptyList()
                if (result.output.any { line ->
                    val value = line.substringAfter(' ').lowercase()
                    value != "false" && value != "lf"
                }) return emptyList()
            }
            val attributes = if (mode == GitTextConvMode.TEXTCONV) listOf("diff")
                else listOf("filter", "text", "eol", "ident", "working-tree-encoding", "crlf")
            val paths = revisions.map { it.file.path.removePrefix(root.path + "/") }
            val handler = GitBinaryHandler(project, root, GitCommand.CHECK_ATTR)
            handler.setSilent(true)
            handler.addParameters(listOf("-z", "--stdin") + attributes)
            handler.setInputProcessor { output ->
                output.use { it.write((paths.joinToString("\u0000") + "\u0000").toByteArray(Charsets.UTF_8)) }
            }
            val fields = handler.run().toString(Charsets.UTF_8).split('\u0000').dropLast(1)
            check(fields.size == paths.size * attributes.size * 3)
            val converted = fields.chunked(3).filter { it[2] != "unspecified" && it[2] != "unset" }
                .mapTo(hashSetOf()) { it[0] }
            // cat-file --batch can report pre-conversion sizes; never parse transformed streams by that size.
            return revisions.filterIndexed { index, _ -> paths[index] !in converted }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Unable to establish Git conversion settings; using native revision reads", e)
            return emptyList()
        }
    }

    private fun run(project: Project, root: VirtualFile, mode: String, requests: List<String>): ByteArray {
        val handler = GitBinaryHandler(project, root, GitCommand.CAT_FILE)
        handler.setSilent(true)
        handler.addParameters(mode)
        handler.setInputProcessor { output ->
            output.use { it.write((requests.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8)) }
        }
        return handler.run()
    }

    internal fun parse(bytes: ByteArray, count: Int): List<ByteArray?> {
        var offset = 0
        val result = ArrayList<ByteArray?>(count)
        repeat(count) {
            ProgressManager.checkCanceled()
            val end = (offset until bytes.size).firstOrNull { bytes[it] == 10.toByte() }
                ?: error("Truncated Git batch header")
            val fields = String(bytes, offset, end - offset, Charsets.US_ASCII).split(' ', limit = 4)
            offset = end + 1
            if (fields.last() == "missing") {
                result.add(null)
            } else {
                check(fields.size >= 3 && fields[1] == "blob") { "Unexpected Git object type" }
                val size = fields[2].toInt()
                check(size >= 0 && size < bytes.size - offset) { "Truncated Git batch content" }
                check(bytes[offset + size] == 10.toByte()) { "Invalid Git batch delimiter" }
                result.add(bytes.copyOfRange(offset, offset + size))
                offset += size + 1
            }
        }
        check(offset == bytes.size) { "Unexpected trailing Git batch output" }
        return result
    }
}
