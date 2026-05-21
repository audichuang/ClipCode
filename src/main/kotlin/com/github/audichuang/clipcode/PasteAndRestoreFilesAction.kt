package com.github.audichuang.clipcode

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor

@IdeBoundCode
class PasteAndRestoreFilesAction : AnAction() {
    private val logger = Logger.getInstance(PasteAndRestoreFilesAction::class.java)

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    @IdeBoundCode
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: run {
            CopyFileContentAction.showNotification(
                "No project found. Action cannot proceed.",
                NotificationType.ERROR,
                null
            )
            return
        }

        val clipboardContent = getClipboardContent()
        if (clipboardContent.isNullOrBlank()) {
            CopyFileContentAction.showNotification(
                "Clipboard is empty or doesn't contain text.",
                NotificationType.WARNING,
                project
            )
            return
        }

        val settings = CopyFileContentSettings.getInstance(project) ?: run {
            CopyFileContentAction.showNotification(
                "Failed to load settings.",
                NotificationType.ERROR,
                project
            )
            return
        }

        val headerFormat = settings.state.headerFormat
        val pathResolver = ClipboardPathResolver.fromProject(project)

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Preparing Restore Plan", true) {
            private var parsedEntries: List<ClipboardRestoreParser.ParsedClipboardEntry> = emptyList()
            private var plan: RestorePlan? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                parsedEntries = ClipboardRestoreParser().parse(clipboardContent, headerFormat)
                indicator.checkCanceled()
                plan = if (parsedEntries.isNotEmpty()) {
                    RestorePlanBuilder(pathResolver).build(parsedEntries)
                } else {
                    null
                }
            }

            override fun onSuccess() {
                if (project.isDisposed) return
                continueOnEdt(project, parsedEntries, plan)
            }
        })
    }

    @IdeBoundCode
    private fun continueOnEdt(
        project: Project,
        parsedEntries: List<ClipboardRestoreParser.ParsedClipboardEntry>,
        plan: RestorePlan?
    ) {
        if (parsedEntries.isEmpty()) {
            CopyFileContentAction.showNotification(
                "No files found in clipboard content. Make sure the content was copied using ClipCode plugin.",
                NotificationType.WARNING,
                project
            )
            return
        }

        if (plan == null) {
            CopyFileContentAction.showNotification(
                "Failed to prepare restore plan.",
                NotificationType.ERROR,
                project
            )
            return
        }

        if (plan.createOperations.isEmpty() && plan.deleteOperations.isEmpty()) {
            CopyFileContentAction.showNotification(
                buildNoActionMessage(plan.skippedOperations),
                NotificationType.WARNING,
                project
            )
            return
        }

        val confirmationResult = Messages.showYesNoDialog(
            project,
            buildConfirmationMessage(plan),
            buildDialogTitle(plan),
            "Proceed",
            "Cancel",
            Messages.getQuestionIcon()
        )
        if (confirmationResult != Messages.YES) {
            return
        }

        val executor = RestoreExecutor(project)
        val existingFiles = executor.collectExistingCreatePaths(plan)
        var overwriteAll = false
        var skipExisting = false

        if (existingFiles.isNotEmpty()) {
            when (showOverwriteDialog(project, existingFiles)) {
                OverwriteChoice.OVERWRITE_ALL -> overwriteAll = true
                OverwriteChoice.SKIP_EXISTING -> skipExisting = true
                OverwriteChoice.CANCEL -> return
            }
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Restoring Files from Clipboard", true) {
            private var executionResult: RestoreExecutor.ExecutionResult? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.checkCanceled()
                executionResult = executor.execute(plan, overwriteAll, skipExisting)
            }

            override fun onSuccess() {
                if (project.isDisposed) return
                val result = executionResult ?: return
                showExecutionNotifications(project, result, plan.skippedOperations)
            }
        })
    }

    @IdeBoundCode
    private fun getClipboardContent(): String? {
        return try {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                clipboard.getData(DataFlavor.stringFlavor) as String
            } else {
                null
            }
        } catch (e: Exception) {
            logger.error("Failed to get clipboard content", e)
            null
        }
    }

    private fun buildDialogTitle(plan: RestorePlan): String = when {
        plan.createOperations.isNotEmpty() && plan.deleteOperations.isNotEmpty() -> "Restore and Delete Files"
        plan.deleteOperations.isNotEmpty() -> "Delete Files"
        else -> "Restore Files from Clipboard"
    }

    private fun buildConfirmationMessage(plan: RestorePlan): String {
        val sections = mutableListOf<String>()
        val (willOverwrite, willCreate) = plan.createOperations.partition { it.existed }

        if (willCreate.isNotEmpty()) {
            sections.add(
                "Files to CREATE (new) (${willCreate.size}):\n" +
                    previewPathsWithTargets(
                        willCreate.map { it.relativePath to it.absolutePath },
                        "+"
                    )
            )
        }

        if (willOverwrite.isNotEmpty()) {
            sections.add(
                "Files to OVERWRITE (existing) (${willOverwrite.size}):\n" +
                    previewPathsWithTargets(
                        willOverwrite.map { it.relativePath to it.absolutePath },
                        "~"
                    )
            )
        }

        if (plan.deleteOperations.isNotEmpty()) {
            sections.add(
                "Files to DELETE (${plan.deleteOperations.size}):\n" +
                    previewPathsWithTargets(
                        plan.deleteOperations.map { it.relativePath to it.absolutePath },
                        "-"
                    )
            )
        }

        if (plan.skippedOperations.isNotEmpty()) {
            sections.add("Will be SKIPPED:\n${buildSkippedSummary(plan.skippedOperations)}")
        }

        return sections.joinToString("\n\n") + "\n\nDo you want to proceed?"
    }

    private fun previewPathsWithTargets(paths: List<Pair<String, String>>, prefix: String): String {
        val preview = paths.take(15).joinToString("\n") { (relativePath, absolutePath) ->
            "  $prefix $relativePath\n    target: $absolutePath"
        }
        val more = if (paths.size > 15) "\n  ... and ${paths.size - 15} more" else ""
        return preview + more
    }

    private fun buildSkippedSummary(skippedOperations: List<RestorePlan.SkippedOperation>): String {
        val grouped = skippedOperations.groupBy { it.reason }
        val summaryLines = mutableListOf<String>()

        grouped[RestorePlan.SkipReason.ALREADY_ABSENT]?.let { items ->
            summaryLines.add("  - already absent: ${items.size}")
        }
        grouped[RestorePlan.SkipReason.UNRESOLVED_PATH]?.let { items ->
            summaryLines.add("  - path unresolved: ${items.size}")
        }
        grouped[RestorePlan.SkipReason.AMBIGUOUS_TARGET]?.let { items ->
            summaryLines.add("  - ambiguous target: ${items.size}")
        }

        return summaryLines.joinToString("\n")
    }

    private fun buildNoActionMessage(skippedOperations: List<RestorePlan.SkippedOperation>): String {
        if (skippedOperations.isEmpty()) {
            return "No actionable files found in clipboard content."
        }

        return buildString {
            append("No actionable files found.\n")
            append(buildSkippedSummary(skippedOperations))
        }
    }

    @IdeBoundCode
    private fun showOverwriteDialog(
        project: Project,
        existingFiles: List<String>
    ): OverwriteChoice {
        val existingList = existingFiles.take(10).joinToString("\n") { "• $it" }
        val moreNote = if (existingFiles.size > 10) "\n... and ${existingFiles.size - 10} more" else ""
        val dialogMessage = "The following ${existingFiles.size} file(s) already exist:\n\n$existingList$moreNote\n\nWhat would you like to do?"

        return when (
            Messages.showDialog(
                project,
                dialogMessage,
                "Files Already Exist",
                arrayOf("Overwrite All", "Skip Existing", "Cancel"),
                0,
                Messages.getWarningIcon()
            )
        ) {
            0 -> OverwriteChoice.OVERWRITE_ALL
            1 -> OverwriteChoice.SKIP_EXISTING
            else -> OverwriteChoice.CANCEL
        }
    }

    @IdeBoundCode
    private fun showExecutionNotifications(
        project: Project,
        executionResult: RestoreExecutor.ExecutionResult,
        skippedOperations: List<RestorePlan.SkippedOperation>
    ) {
        val resultParts = mutableListOf<String>()
        if (executionResult.createdCount > 0) {
            resultParts.add("Created ${executionResult.createdCount} file(s)")
        }
        if (executionResult.overwrittenCount > 0) {
            resultParts.add("Overwritten ${executionResult.overwrittenCount} file(s)")
        }
        if (executionResult.skippedExistingCount > 0) {
            resultParts.add("Skipped ${executionResult.skippedExistingCount} existing file(s)")
        }
        if (executionResult.deletedCount > 0) {
            resultParts.add("Deleted ${executionResult.deletedCount} file(s)")
        }

        if (resultParts.isNotEmpty()) {
            CopyFileContentAction.showNotification(
                "<html><b>${resultParts.joinToString(", ")}</b></html>",
                NotificationType.INFORMATION,
                project
            )
        }

        if (skippedOperations.isNotEmpty()) {
            val warningDetails = skippedOperations.take(5).joinToString("<br>") { skipped ->
                val path = skipped.relativePath ?: skipped.rawPath
                when (skipped.reason) {
                    RestorePlan.SkipReason.ALREADY_ABSENT ->
                        "$path: already absent in target project"
                    RestorePlan.SkipReason.UNRESOLVED_PATH ->
                        "$path: path unresolved"
                    RestorePlan.SkipReason.AMBIGUOUS_TARGET ->
                        "$path: ambiguous target, skipped for safety"
                }
            }
            val more = if (skippedOperations.size > 5) "<br>..." else ""
            CopyFileContentAction.showNotification(
                "<html><b>Skipped ${skippedOperations.size} operation(s):</b><br>$warningDetails$more</html>",
                NotificationType.WARNING,
                project
            )
        }

        if (executionResult.errors.isNotEmpty()) {
            val errorDetails = executionResult.errors.take(5).joinToString("<br>")
            val more = if (executionResult.errors.size > 5) "<br>..." else ""
            CopyFileContentAction.showNotification(
                "<html><b>Failed ${executionResult.errors.size} operation(s):</b><br>$errorDetails$more</html>",
                NotificationType.ERROR,
                project
            )
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null
    }

    private enum class OverwriteChoice {
        OVERWRITE_ALL,
        SKIP_EXISTING,
        CANCEL
    }
}
