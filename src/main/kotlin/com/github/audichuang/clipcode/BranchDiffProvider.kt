package com.github.audichuang.clipcode

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import git4idea.GitUtil
import git4idea.changes.GitChangeUtils
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.fetch.GitFetchSupport
import git4idea.repo.GitRepository

/**
 * Data layer for the PR panel: candidate base refs, base..HEAD diff, and
 * ahead/behind freshness vs the tracked origin remote.
 */
class BranchDiffProvider(private val logger: Logger) {
    data class RemoteStatus(
        val ahead: Int,              // 本地領先 origin 幾個 commit
        val behind: Int,             // 本地落後 origin 幾個 commit(> 0 → 提醒 pull)
        val upstream: String?,       // 對應的 origin ref,如 "origin/main";null = 無 upstream
        val fetched: Boolean,        // fetch 是否成功(僅在 fetchAttempted 時有意義)
        val fetchAttempted: Boolean  // 這次是否嘗試 fetch(doFetch);false = 未嘗試,fetched 無意義
    )

    private fun firstRepository(project: Project): GitRepository? =
        try {
            GitUtil.getRepositoryManager(project).repositories.firstOrNull()
        } catch (e: Exception) {
            logger.warn("Failed to resolve Git repository", e)
            null
        }

    // 目前 repo 可選的 base ref(remote 分支優先),供下拉用
    fun candidateBaseRefs(project: Project): List<String> {
        val repository = firstRepository(project) ?: return emptyList()
        return try {
            val upstream = repository.currentBranch
                ?.findTrackedBranch(repository)
                ?.name

            val remoteBranchNames = repository.branches.remoteBranches
                .map { it.name }

            val ordered = linkedSetOf<String>()
            upstream?.let { ordered.add(it) }
            ordered.addAll(remoteBranchNames)
            ordered.toList()
        } catch (e: Exception) {
            logger.warn("Failed to list candidate base refs", e)
            emptyList()
        }
    }

    // base..HEAD 的變更;base 為 ref 字串(如 "origin/main")
    fun diffChanges(project: Project, baseRef: String): List<Change> {
        val repository = firstRepository(project) ?: return emptyList()
        return try {
            GitChangeUtils.getDiff(repository, baseRef, "HEAD", true)?.toList() ?: emptyList()
        } catch (e: Exception) {
            logger.warn("Failed to compute diff for $baseRef..HEAD", e)
            emptyList()
        }
    }

    // 相對 origin 的新鮮度;doFetch = true 時先背景 fetch
    fun remoteStatus(project: Project, doFetch: Boolean): RemoteStatus {
        val repository = firstRepository(project) ?: return RemoteStatus(0, 0, null, false, doFetch)

        val upstream = try {
            repository.currentBranch?.findTrackedBranch(repository)?.name
        } catch (e: Exception) {
            logger.warn("Failed to resolve tracked branch", e)
            null
        }

        if (upstream == null) {
            return RemoteStatus(0, 0, null, false, doFetch)
        }

        var fetched = false
        if (doFetch) {
            fetched = try {
                // 靜默降級:throwExceptionIfFailed() 只讀 isFailed 拋例外,不彈 IDE 通知;
                // 失敗改由 panel banner 自己顯示,fetched=false 後仍用本地快取算 ahead/behind
                GitFetchSupport.fetchSupport(project)
                    .fetchAllRemotes(listOf(repository))
                    .throwExceptionIfFailed()
                true
            } catch (e: Exception) {
                logger.warn("Failed to fetch remote", e)
                false
            }
        }

        val (ahead, behind) = try {
            val handler = GitLineHandler(project, repository.root, GitCommand.REV_LIST)
            handler.addParameters("--count", "--left-right", "$upstream...HEAD")
            val result = Git.getInstance().runCommand(handler)
            if (result.success()) {
                parseAheadBehind(result.output.firstOrNull().orEmpty()) ?: (0 to 0)
            } else {
                0 to 0
            }
        } catch (e: Exception) {
            logger.warn("Failed to compute ahead/behind vs $upstream", e)
            0 to 0
        }

        return RemoteStatus(ahead = ahead, behind = behind, upstream = upstream, fetched = fetched, fetchAttempted = doFetch)
    }

    companion object {
        // 輸入為 rev-list --count --left-right 的一行 "<behind>\t<ahead>";解析失敗回 null
        fun parseAheadBehind(revListLine: String): Pair<Int, Int>? {
            val parts = revListLine.trim().split("\t")
            if (parts.size != 2) {
                return null
            }
            val behind = parts[0].trim().toIntOrNull() ?: return null
            val ahead = parts[1].trim().toIntOrNull() ?: return null
            return ahead to behind
        }
    }
}
