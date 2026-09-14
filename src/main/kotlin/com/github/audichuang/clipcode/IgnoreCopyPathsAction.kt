package com.github.audichuang.clipcode

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

/** Add the selection to the existing project-level ClipCode exclude rules. */
class IgnoreCopyPathsAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && selectedFiles(e).isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val files = selectedFiles(e).distinctBy { it.path }
        if (files.isEmpty()) return
        val state = CopyFileContentSettings.getInstance(project)?.state ?: return
        val resolver = ClipboardPathResolver.fromProject(project)
        val rules = state.filterRules.map { it.copy() }.toMutableList()
        for (file in files) {
            val path = CopyPathFormatter.relativeFilterPath(resolver, file.path)?.takeIf { it.isNotBlank() }
                ?: file.path
            val existing = rules.firstOrNull {
                it.type == CopyFileContentSettings.FilterType.PATH &&
                    it.action == CopyFileContentSettings.FilterAction.EXCLUDE && it.value == path
            }
            if (existing != null) existing.enabled = true
            else rules.add(CopyFileContentSettings.FilterRule(
                type = CopyFileContentSettings.FilterType.PATH,
                action = CopyFileContentSettings.FilterAction.EXCLUDE, value = path
            ))
        }
        // Replace the list so an in-progress copy can finish reading its previous snapshot.
        state.filterRules = rules
        // Enabling excludes must not also activate previously inactive include restrictions.
        if (!state.useFilters) state.useIncludeFilters = false
        state.useFilters = true
        state.useExcludeFilters = true
        CopyFileContentAction.showNotification(
            "Ignored ${files.size} selected path(s) when copying with ClipCode.",
            NotificationType.INFORMATION, project
        )
    }

    private fun selectedFiles(e: AnActionEvent) =
        e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.takeIf { it.isNotEmpty() }
            ?: e.getData(CommonDataKeys.VIRTUAL_FILE)?.let { arrayOf(it) } ?: emptyArray()
}
