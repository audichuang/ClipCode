package com.github.audichuang.clipcode

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.util.Locale

/**
 * [nonTextChooser] decides Include / Exclude / Cancel when a copy would carry non-text files; the
 * default shows [NonTextCopyDialog]. All-default parameters keep the no-arg constructor plugin.xml needs.
 */
class CopyFileContentAction(
    private val nonTextChooser: NonTextChooser = NonTextCopyDialog.CHOOSER
) : AnAction() {
    private val logger = Logger.getInstance(CopyFileContentAction::class.java)

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    @IdeBoundCode
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: run {
            showNotification("No project found. Action cannot proceed.", NotificationType.ERROR, null)
            return
        }
        
        // 嘗試多種方式取得選中的檔案（特別是 External Libraries）
        val selectedFiles = getSelectedVirtualFiles(e)
        
        if (selectedFiles.isEmpty()) {
            showNotification("No files selected.", NotificationType.ERROR, project)
            return
        }

        performCopyFilesContent(project, selectedFiles)
    }

    /**
     * 從多種來源嘗試取得選中的 VirtualFiles。
     * External Libraries 裡的檔案通常透過 PSI_FILE 或 NAVIGATABLE 提供，而非 VIRTUAL_FILE_ARRAY。
     * 資料夾不在這裡展開：交給 BGT 上的 processDirectory 遞迴，
     * 那邊才有 include/exclude 過濾與檔數上限的提前中斷，也不會凍結 EDT。
     */
    @IdeBoundCode
    private fun getSelectedVirtualFiles(e: AnActionEvent): Array<VirtualFile> {
        // 1. 首先嘗試標準的 VIRTUAL_FILE_ARRAY
        e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.let {
            if (it.isNotEmpty()) {
                logger.info("getSelectedVirtualFiles: Found ${it.size} items from VIRTUAL_FILE_ARRAY")
                return it.distinct().toTypedArray()
            }
        }

        // 2. 嘗試單一檔案/資料夾 VIRTUAL_FILE
        e.getData(CommonDataKeys.VIRTUAL_FILE)?.let {
            logger.info("getSelectedVirtualFiles: Found item from VIRTUAL_FILE: ${it.path} (isDirectory=${it.isDirectory})")
            return arrayOf(it)
        }

        // 3. 嘗試從 PSI_FILE 取得（External Libraries 的單一檔案常用此方式）
        e.getData(CommonDataKeys.PSI_FILE)?.let { psiFile ->
            psiFile.virtualFile?.let { virtualFile ->
                logger.info("getSelectedVirtualFiles: Found file from PSI_FILE: ${virtualFile.path}")
                return arrayOf(virtualFile)
            }
        }

        // 4. 嘗試從 PSI_ELEMENT 取得（支援 PsiDirectory 和其他 PSI 元素）
        e.getData(CommonDataKeys.PSI_ELEMENT)?.let { psiElement ->
            when (psiElement) {
                is com.intellij.psi.PsiDirectory -> {
                    logger.info("getSelectedVirtualFiles: Found PsiDirectory: ${psiElement.virtualFile.path}")
                    return arrayOf(psiElement.virtualFile)
                }
                is com.intellij.psi.PsiDirectoryContainer -> {
                    // 選取的是 Package 節點（PsiDirectoryContainer 是 PsiPackage 的基類）
                    logger.info("getSelectedVirtualFiles: Found PsiDirectoryContainer: ${psiElement.javaClass.simpleName}")
                    val directories = psiElement.directories.map { it.virtualFile }
                    if (directories.isNotEmpty()) {
                        return directories.distinct().toTypedArray()
                    }
                }
                else -> {
                    // 其他 PSI 元素，嘗試取得其所屬檔案
                    psiElement.containingFile?.virtualFile?.let { virtualFile ->
                        logger.info("getSelectedVirtualFiles: Found file from PSI_ELEMENT: ${virtualFile.path}")
                        return arrayOf(virtualFile)
                    }
                }
            }
        }

        // 5. 嘗試從 NAVIGATABLE_ARRAY 取得（支援多選）
        e.getData(CommonDataKeys.NAVIGATABLE_ARRAY)?.let { navigatables ->
            val collectedFiles = mutableListOf<VirtualFile>()
            for (nav in navigatables) {
                when (nav) {
                    is com.intellij.psi.PsiDirectory -> {
                        collectedFiles.add(nav.virtualFile)
                    }
                    is com.intellij.psi.PsiElement -> {
                        nav.containingFile?.virtualFile?.let { collectedFiles.add(it) }
                    }
                    is com.intellij.openapi.fileEditor.OpenFileDescriptor -> {
                        collectedFiles.add(nav.file)
                    }
                }
            }
            if (collectedFiles.isNotEmpty()) {
                logger.info("getSelectedVirtualFiles: Found ${collectedFiles.size} files from NAVIGATABLE_ARRAY")
                return collectedFiles.distinct().toTypedArray()
            }
        }

        logger.info("getSelectedVirtualFiles: No files found from any source")
        return emptyArray()
    }

    fun performCopyFilesContent(
        project: Project,
        filesToCopy: Array<VirtualFile>,
        customHeaderGenerator: ((VirtualFile, String) -> String)? = null
    ) {
        val settings = CopyFileContentSettings.getInstance(project) ?: run {
            showNotification("Failed to load settings.", NotificationType.ERROR, project)
            return
        }

        // The Include result is built first: whether to ask, and every number the dialog shows, come
        // from it (spec non-text-files.md, "Asking the user").
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Copying file content…", true) {
            private var payload: CopyPayload? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                payload = buildCopyPayload(project, filesToCopy, settings, customHeaderGenerator, indicator, includeNonText = true)
            }

            override fun onSuccess() {
                if (project.isDisposed) return
                val include = payload ?: return
                if (include.nonTextCarried.isEmpty()) {
                    // No dialog, and today's build always runs instead of reusing the Include payload:
                    // the two can still differ (a non-text file over its own limit is a size placeholder
                    // in Include and silently skipped today), and Exclude must stay byte-identical to
                    // today. Only the left-out count is taken from the Include walk: no non-text file took
                    // a file-count slot, so it stopped at the same place, and today's walk does not count
                    // what the limit left out. Unreadable items come from the walk whose payload is copied.
                    copyWithoutNonText(project, filesToCopy, settings, customHeaderGenerator, include.limitLeftOut, include.limitLeftOutCapped)
                    return
                }
                when (nonTextChooser.choose(project, include.toSummary(settings))) {
                    NonTextChoice.INCLUDE -> {
                        copyToClipboard(include.text)
                        showCopyNotifications(project, settings, include)
                    }
                    NonTextChoice.EXCLUDE ->
                        copyWithoutNonText(project, filesToCopy, settings, customHeaderGenerator, null, false)
                    NonTextChoice.CANCEL -> Unit
                }
            }
        })
    }

    /** Today's copy: non-text files are skipped. [knownLimitLeftOut] only feeds the notice. */
    private fun copyWithoutNonText(
        project: Project,
        filesToCopy: Array<VirtualFile>,
        settings: CopyFileContentSettings,
        customHeaderGenerator: ((VirtualFile, String) -> String)?,
        knownLimitLeftOut: Int?,
        knownLimitLeftOutCapped: Boolean
    ) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Copying file content…", true) {
            private var payload: CopyPayload? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                payload = buildCopyPayload(project, filesToCopy, settings, customHeaderGenerator, indicator, includeNonText = false)
            }

            override fun onSuccess() {
                if (project.isDisposed) return
                val result = payload ?: return
                // X3: nothing was copied (every item skipped, or only size placeholders): the user's
                // clipboard is not overwritten with an empty wrapper. Include always carries at least one
                // file, so this is the only place it can happen, with or without the dialog.
                if (result.fileCount == 0) {
                    showNothingToCopy(project, result)
                    return
                }
                copyToClipboard(result.text)
                showCopyNotifications(project, settings, result.copy(limitLeftOut = knownLimitLeftOut, limitLeftOutCapped = knownLimitLeftOutCapped))
            }
        })
    }

    /** Today's build (non-text files skipped); NodeModulesCopyTest / CopyResponsivenessTest call it by reflection. */
    private fun buildCopyPayload(
        project: Project,
        filesToCopy: Array<VirtualFile>,
        settings: CopyFileContentSettings,
        customHeaderGenerator: ((VirtualFile, String) -> String)?,
        indicator: ProgressIndicator
    ): CopyPayload = buildCopyPayload(project, filesToCopy, settings, customHeaderGenerator, indicator, includeNonText = false)

    private fun buildCopyPayload(
        project: Project,
        filesToCopy: Array<VirtualFile>,
        settings: CopyFileContentSettings,
        customHeaderGenerator: ((VirtualFile, String) -> String)?,
        indicator: ProgressIndicator,
        includeNonText: Boolean
    ): CopyPayload {
        // 在 BGT 跑；VFS 存取需要 read lock（2024.3+ strict mode），但改為「逐檔」取
        // ReadAction（見 processFile / processDirectory），不用單一長 read action 包住
        // 整批複製 — 否則 EDT 上排隊的 write action（使用者打字）要等整段複製完。

        // Filter config is snapshotted once per copy instead of re-read per file/directory
        val enabledRules = settings.state.filterRules.filter { it.enabled }
        val session = ApplicationManager.getApplication().runReadAction<CopySession> {
            CopySession(
                indicator = indicator,
                externalLibraryHandler = ExternalLibraryHandler(project),
                pathResolver = ClipboardPathResolver.fromProject(project),
                useFilters = settings.state.useFilters,
                useIncludeFilters = settings.state.useIncludeFilters,
                useExcludeFilters = settings.state.useExcludeFilters,
                includeRules = enabledRules.filter { it.action == CopyFileContentSettings.FilterAction.INCLUDE },
                excludeRules = enabledRules.filter { it.action == CopyFileContentSettings.FilterAction.EXCLUDE },
                includeNonText = includeNonText,
                maxNonTextBytes = settings.state.maxNonTextFileSizeKB * 1024L
            )
        }

        val fileContents = mutableListOf<String>().apply {
            // Metadata line first (before any header) so parsers drop it as pre-header
            // text; restore uses it to align folder levels. Mirrors the VS Code
            // buildPayloadInternal, including the phantom-header suppression.
            session.pathResolver.singleRootName()?.let { sourceRoot ->
                val metaLine = ClipboardRestoreParser.SOURCE_ROOT_MARKER + sourceRoot
                if (!ClipboardRestoreParser.wouldParseAsHeader(metaLine, settings.state.headerFormat)) {
                    add(metaLine)
                }
            }
            add(ClipboardRestoreParser.escapeContent(settings.state.preText, settings.state.headerFormat))
        }

        for ((index, file) in filesToCopy.withIndex()) {
            indicator.checkCanceled()
            if (stopForFileLimit(session, settings)) {
                countLimitLeftOut(filesToCopy.asList().subList(index, filesToCopy.size), session, topLevel = true)
                break
            }

            val (isDirectory, isSymlinkedFolder) = ApplicationManager.getApplication().runReadAction<Pair<Boolean, Boolean>> {
                file.isDirectory to (file.isDirectory && file.`is`(com.intellij.openapi.vfs.VFileProperty.SYMLINK))
            }
            // Decision D2 / spec case 22: a symlinked folder is never followed, even when it IS the
            // selection (alone or among others). A project root reached through its own spelling is
            // not such a link. A dangling link is not a directory: processFile names it Unreadable.
            if (isSymlinkedFolder && session.pathResolver.rawRelativePath(file.path) != "") {
                session.skipNotes.note(SkipCategory.SYMLINKED_FOLDER, CopyPathFormatter.displayPath(session.pathResolver, file.path))
                continue
            }
            if (isDirectory) {
                processDirectory(file, fileContents, session, settings, settings.state.addExtraLineBetweenFiles, customHeaderGenerator, topLevel = true)
            } else {
                processFile(file, fileContents, session, settings, settings.state.addExtraLineBetweenFiles, customHeaderGenerator, topLevel = true)
            }
        }

        indicator.checkCanceled()
        // Byte-mirror of ClipboardPayloadFormatter: the terminator goes in only when there
        // is a footer. This path does NOT go through the formatter (see AGENTS.md), so
        // every wire rule has to be repeated here — and NOTHING pins the two together:
        // buildCopyPayload needs the IDE runtime, so the formatter's own tests do not
        // cover it. Change one, change the other by hand.
        if (settings.state.postText.isNotEmpty() &&
            !ClipboardRestoreParser.wouldParseAsHeader(
                ClipboardRestoreParser.POST_TEXT_MARKER, settings.state.headerFormat
            )
        ) {
            fileContents.add(ClipboardRestoreParser.POST_TEXT_MARKER)
        }
        fileContents.add(ClipboardRestoreParser.escapeContent(settings.state.postText, settings.state.headerFormat))

        val text = fileContents.joinToString(separator = "\n")

        return CopyPayload(
            text = text,
            fileCount = session.fileCount,
            skippedFileSizeCount = session.skippedFileSizeCount,
            skipNotes = session.skipNotes,
            fileLimitReached = session.fileLimitReached,
            nonTextCarried = session.nonTextCarried.toList(),
            nonTextOverLimit = session.nonTextOverLimit,
            unreadOverLimit = session.unreadOverLimit,
            limitLeftOut = if (session.includeNonText && session.fileLimitReached) session.limitLeftOut else null,
            limitLeftOutCapped = session.includeNonText && session.fileLimitReached && session.limitLeftOutCapped,
            utf8Bytes = if (session.nonTextCarried.isNotEmpty()) NonTextCopy.utf8Length(text) else 0L
        )
    }

    /**
     * Today's file-count check, shared by the top-level loop and [processDirectory]: once the limit is
     * filled the walk stops (true) in both modes. Spec non-text-files.md lines 84-92: a folder walk is
     * never continued only to count what the limit left out; see [countLimitLeftOut].
     */
    private fun stopForFileLimit(session: CopySession, settings: CopyFileContentSettings): Boolean {
        if (settings.state.setMaxFileCount && session.fileCount >= settings.state.fileCountLimit) {
            session.fileLimitReached = true
            return true
        }
        return false
    }

    /**
     * Include only: the files the limit left out, counted from what is already known when the walk stops
     * — the rest of a list already enumerated ([remaining], the selection or one folder's children). Each
     * level of the walk adds its own remainder as it unwinds. Mirror of OpenIDEA `plan_export_with`
     * (`items[cut..]` that pass the filters) with `limit_left_out_lower_bound`: a remaining folder is never
     * listed, so it only marks the number as a lower bound ("+"). A file is counted only when the copy
     * would have taken it: it passes the filters, its name is portable and it was not copied already.
     * Symlinked folders and unportable or filtered-out folders are not left out. Counting stops at
     * [NonTextCopy.LEFT_OUT_COUNT_CAP]; one more makes it "1000+".
     */
    private fun countLimitLeftOut(remaining: List<VirtualFile>, session: CopySession, topLevel: Boolean = false) {
        if (!session.includeNonText) return
        ApplicationManager.getApplication().runReadAction {
            for (item in remaining) {
                if (session.limitLeftOutCapped && session.limitLeftOut >= NonTextCopy.LEFT_OUT_COUNT_CAP) return@runReadAction
                if (item.isDirectory) {
                    val linked = item.`is`(com.intellij.openapi.vfs.VFileProperty.SYMLINK) &&
                        session.pathResolver.rawRelativePath(item.path) != ""
                    if (!linked && (!item.isInLocalFileSystem || PortableName.isPortableFolder(session.pathResolver, item.path)) &&
                        directoryPassesFilters(item, session)
                    ) {
                        session.limitLeftOutCapped = true
                    }
                    continue
                }
                if (item.path in session.copiedFilePaths || item.path in session.limitLeftOutPaths) continue
                if (session.useFilters && !passesFileFilters(item, session)) continue
                if (isUnportable(item, session)) continue
                if (linksOutOfEveryRoot(item, session, topLevel)) continue
                if (session.limitLeftOut >= NonTextCopy.LEFT_OUT_COUNT_CAP) {
                    session.limitLeftOutCapped = true
                } else {
                    session.limitLeftOutPaths.add(item.path)
                    session.limitLeftOut++
                }
            }
        }
    }

    /** The per-file filter decision, from [CopyFilterMatcher] (see AGENTS.md). Caller holds the read lock. */
    private fun passesFileFilters(file: VirtualFile, session: CopySession): Boolean =
        CopyFilterMatcher.passes(
            fileName = file.name,
            relativePath = CopyPathFormatter.relativeFilterPath(session.pathResolver, file.path),
            absolutePath = file.path,
            useIncludeFilters = session.useIncludeFilters,
            useExcludeFilters = session.useExcludeFilters,
            includeRules = session.includeRules,
            excludeRules = session.excludeRules,
            cache = session.patternCache
        )

    /**
     * Decision D2 for one file. Every file with a real name on disk is checked, library files included: a
     * project-local folder registered as a library (node_modules) is still a folder of real files whose
     * header is built from the on-disk name. Only entries of a jar / jrt / decompiled view, which have no
     * such name, keep today's path.
     */
    private fun isUnportable(file: VirtualFile, session: CopySession): Boolean =
        file.isInLocalFileSystem && !PortableName.isPortableFile(session.pathResolver, file.path)

    /**
     * X7: reading [file] would carry content from outside every copy root (the resolver's roots, each
     * resolved too, so a project opened through a symlinked path is not an escape from itself); see
     * [ClipboardPathResolver.readsOutsideEveryRoot]. A symlink is always asked: a target inside a root is
     * followed. A [topLevel] item (selected directly, or an open tab) is asked too, since it may be reached
     * through a symlinked folder (`vendor -> ~/private`, then `vendor/secrets.env`). Below a top-level folder
     * that is inside a root only links can lead out, as symlinked subfolders are never walked, so the walk
     * pays no real-path lookup per file. A regular file outside every root keeps today's absolute-path
     * header. Caller holds the read lock.
     */
    private fun linksOutOfEveryRoot(file: VirtualFile, session: CopySession, topLevel: Boolean = false): Boolean {
        if (!file.isInLocalFileSystem) return false
        val isSymlink = file.`is`(com.intellij.openapi.vfs.VFileProperty.SYMLINK)
        return (isSymlink || topLevel) && session.pathResolver.readsOutsideEveryRoot(file.path, isSymlink)
    }

    @IdeBoundCode
    private fun showCopyNotifications(
        project: Project,
        settings: CopyFileContentSettings,
        result: CopyPayload
    ) {
        if (result.fileLimitReached) {
            val leftOut = result.limitLeftOut
                ?.takeIf { NonTextCopy.hasLimitLeftOut(it, result.limitLeftOutCapped) }
                ?.let { " ${NonTextCopy.limitLeftOutSentence(it, result.limitLeftOutCapped)}." } ?: ""
            val fileLimitWarningMessage = """
                <html>
                <b>File Limit Reached:</b> The file limit of ${settings.state.fileCountLimit} files was reached.$leftOut
                </html>
            """.trimIndent()
            showNotificationWithSettingsAction(fileLimitWarningMessage, NotificationType.WARNING, project)
        }

        if (!settings.state.showCopyNotification) return

        val fileCountMessage = when {
            result.skippedFileSizeCount > 0 && result.fileCount == 1 ->
                "1 file copied (${result.skippedFileSizeCount} skipped: size exceeded)."
            result.skippedFileSizeCount > 0 ->
                "${result.fileCount} files copied (${result.skippedFileSizeCount} skipped: size exceeded)."
            result.fileCount == 1 -> "1 file copied."
            else -> "${result.fileCount} files copied."
        }
        // Reported, never silent — the VS Code half says the same sentence. After Include the
        // non-text files are carried, so what is skipped is called "Unreadable" instead. Otherwise
        // today's sentence carries the unreadable count and its paths follow, one per line. Either
        // way the symlinked folders and unportable names follow as their own blocks (decision D2).
        val notes = result.skipNotes
        val unreadable = notes.count(SkipCategory.UNREADABLE)
        val unreadableMessage = when {
            result.nonTextCarried.isNotEmpty() ->
                noticeLinesHtml(NonTextCopy.includeNoticeLines(result.nonTextCarried.size, notes))
            else ->
                (if (unreadable > 0) " $unreadable skipped: not UTF-8 text or unreadable." else "") +
                    noticeLinesHtml(NonTextCopy.excludeNoticeLines(notes))
        }

        showPayloadNotification("", result.text, project)
        showNotification("<html><b>$fileCountMessage</b>$unreadableMessage</html>", NotificationType.INFORMATION, project)
    }

    // No statistics fields on purpose: chars/lines/words/tokens are all a function of
    // [text] alone (headers, pre/post text and the clipcode-root line included — that is
    // what gets pasted, and what VS Code counts), so they are derived once in
    // [showPayloadNotification]. Carrying them here is what let them drift into per-file
    // sums the two tools never agreed on.
    private data class CopyPayload(
        val text: String,
        val fileCount: Int,
        val skippedFileSizeCount: Int,
        /** Unreadable items, symlinked folders and unportable names: counted and named in the notice. */
        val skipNotes: SkipNotes,
        val fileLimitReached: Boolean,
        /** Include only: carried non-text files in payload order. */
        val nonTextCarried: List<NonTextCopySummary.Item> = emptyList(),
        val nonTextOverLimit: Int = 0,
        val unreadOverLimit: Int = 0,
        /** Files the file-count limit left out; null when not counted (today's build, or the limit not reached). */
        val limitLeftOut: Int? = null,
        /** True when [limitLeftOut] is a lower bound: the count cap, or a folder past the limit never listed. */
        val limitLeftOutCapped: Boolean = false,
        /** UTF-8 length of [text]; computed on the background thread, only when non-text files were carried. */
        val utf8Bytes: Long = 0
    ) {
        fun toSummary(settings: CopyFileContentSettings) = NonTextCopySummary(
            carried = nonTextCarried,
            estimatedPayloadBytes = utf8Bytes,
            nonTextOverLimit = nonTextOverLimit,
            unreadOverLimit = unreadOverLimit,
            limitLeftOut = limitLeftOut ?: 0,
            limitLeftOutCapped = limitLeftOutCapped,
            nonTextLimitBytes = settings.state.maxNonTextFileSizeKB * 1024L,
            headerFormat = settings.state.headerFormat
        )
    }

    /** X3: shown even when the copy notification is off, since the clipboard was left as it was. */
    @IdeBoundCode
    private fun showNothingToCopy(project: Project, result: CopyPayload) {
        val lines = NonTextCopy.nothingToCopyLines(result.skippedFileSizeCount, result.skipNotes)
        showNotification("<html><b>Nothing to copy</b>${noticeLinesHtml(lines)}</html>", NotificationType.WARNING, project)
    }

    /** Plain notice lines → HTML, one category per line. */
    private fun noticeLinesHtml(lines: List<String>): String =
        lines.joinToString("") { "<br>" + com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(it) }

    // 每個檔案各取一次 read lock，讓 EDT 的 write action 可以在檔案之間插隊
    private fun processFile(
        file: VirtualFile,
        fileContents: MutableList<String>,
        session: CopySession,
        settings: CopyFileContentSettings,
        addExtraLine: Boolean,
        customHeaderGenerator: ((VirtualFile, String) -> String)? = null,
        topLevel: Boolean = false
    ) {
        session.indicator.checkCanceled()
        ApplicationManager.getApplication().runReadAction<String> {
            processFileUnderReadLock(file, fileContents, session, settings, addExtraLine, customHeaderGenerator, topLevel)
        }
    }

    private fun processFileUnderReadLock(
        file: VirtualFile,
        fileContents: MutableList<String>,
        session: CopySession,
        settings: CopyFileContentSettings,
        addExtraLine: Boolean,
        customHeaderGenerator: ((VirtualFile, String) -> String)? = null,
        topLevel: Boolean = false
    ): String {
        val handler = session.externalLibraryHandler
        val isExternalLibrary = handler.isFromExternalLibrary(file)
        
        // Determine the file path to display
        val fileRelativePath = when {
            // Project files can be marked as library files when they live under excluded/library roots
            // such as node_modules. Keep those headers project-relative so Paste and Restore can map
            // them back into the current project.
            isExternalLibrary -> {
                CopyPathFormatter.displayPathOrFallback(
                    session.pathResolver,
                    file.path,
                    handler.getCleanPath(file)
                )
            }
            // Then check if it's from project
            !file.isDirectory -> {
                CopyPathFormatter.displayPath(session.pathResolver, file.path)
            }
            // Fallback to presentable URL
            else -> file.presentableUrl
        }

        // Skip already copied files (使用絕對路徑作為去重 key，避免路徑碰撞)
        val dedupeKey = file.path
        if (dedupeKey in session.copiedFilePaths) {
            logger.info("Skipping already copied file: $fileRelativePath")
            return ""
        }

        session.copiedFilePaths.add(dedupeKey)

        var content = ""
        
        // Check filters if enabled. The decision itself lives in CopyFilterMatcher so the
        // git payload builder applies exactly the same rules — it used to apply none.
        if (session.useFilters) {
            if (!passesFileFilters(file, session)) {
                logger.info("Skipping file: ${file.name} - filtered out")
                return ""
            }
        }

        // Decision D2: a name a header cannot carry unchanged is skipped and named, in every mode, before
        // anything is read. A newline in a name would otherwise put a second, forged header line on the
        // clipboard. Library files on disk too (see isUnportable).
        if (isUnportable(file, session)) {
            session.noteUnportable(file.path)
            logger.info("Skipping file: ${file.name} - name not portable")
            return ""
        }
        // X7: a symlink to a file outside every copy root is never read, in every mode, and is named as
        // Unreadable (OpenIDEA folder_file_rel / escapes_all_roots). A clipboard often goes to an LLM, so
        // `CLAUDE.md -> ~/.agents/...` must not carry what lies outside the project.
        if (linksOutOfEveryRoot(file, session, topLevel)) {
            session.noteUnreadable(fileRelativePath)
            logger.info("Skipping file: ${file.name} - reads a file outside every copy root")
            return ""
        }
        // External-library files keep today's behaviour in both modes.
        if (session.includeNonText && !isExternalLibrary) {
            processFileIncludingNonText(file, fileRelativePath, fileContents, session, settings, addExtraLine, customHeaderGenerator)
            return ""
        }

        val maxFileSizeBytes = settings.state.maxFileSizeKB * 1024L
        
        // ✅ 修復 OOM 風險：先檢查檔案大小（file.length 只讀 metadata，不載入內容）
        // 這樣可以避免讀取 2GB log 檔等超大檔案導致 IDE 崩潰
        if (file.length > maxFileSizeBytes) {
            logger.info("Skipping file: ${file.name} - File size (${file.length} bytes) exceeds limit ($maxFileSizeBytes bytes)")
            session.skippedFileSizeCount++
            // 回傳提示字串，讓使用者知道哪些檔案被跳過（與 Git 模式保持一致的 UX）
            val header = customHeaderGenerator?.invoke(file, fileRelativePath)
                ?: settings.state.headerFormat.replace("\$FILE_PATH", fileRelativePath)
            fileContents.add(header)
            // Escape like real content so a permissive headerFormat can't parse the
            // skip line as a header (VS Code escapes these lines too)
            fileContents.add(
                ClipboardRestoreParser.escapeContent("// File skipped: size exceeds limit (${file.length} bytes)", settings.state.headerFormat)
            )
            if (addExtraLine) {
                fileContents.add("")
            }
            return ""
        }

        // Handle external library files differently
        if (isExternalLibrary) {
            // Check if file should be processed
            if (!handler.shouldProcessFile(file)) {
                // Counted like every other drop. VS Code has no extension blocklist, so the
                // two tools still disagree on WHICH library files they copy — but a drop the
                // user is never told about is the part that is simply a bug.
                session.noteUnreadable(fileRelativePath)
                logger.info("Skipping external library file: ${file.name}")
                return ""
            }
            
            // Try to read content from external library (size already checked above)
            content = handler.readContent(file) ?: run {
                session.noteUnreadable(fileRelativePath)
                return ""
            }
            
            if (content.isNotEmpty() || file.length == 0L) {
                val header = customHeaderGenerator?.invoke(file, fileRelativePath)
                    ?: settings.state.headerFormat.replace("\$FILE_PATH", fileRelativePath)
                fileContents.add(header)
                fileContents.add(ClipboardRestoreParser.escapeTextBody(content, settings.state.headerFormat))
                session.fileCount++
                if (addExtraLine) {
                    fileContents.add("")
                }
            } else {
                session.noteUnreadable(fileRelativePath)
                logger.info("Skipping file: ${file.name} - Could not read content from external library")
            }
        } else {
            // Regular project files (size already checked above). The bytes alone decide (X16): an IDE
            // binary file type holding strict UTF-8 is text; non-UTF-8 bytes are counted and named,
            // as VS Code's decodeUtf8OrSkip does.
            content = readFileContents(file) ?: run {
                session.noteUnreadable(fileRelativePath)
                return ""
            }

            if (content.isNotEmpty() || file.length == 0L) {
                val header = customHeaderGenerator?.invoke(file, fileRelativePath)
                    ?: settings.state.headerFormat.replace("\$FILE_PATH", fileRelativePath)
                fileContents.add(header)
                fileContents.add(ClipboardRestoreParser.escapeTextBody(content, settings.state.headerFormat))
                session.fileCount++
                if (addExtraLine) {
                    fileContents.add("")
                }
            } else {
                // Same class of silent drop as the external-library twin above. Strict
                // decoding makes this branch near-unreachable (non-empty bytes decode to
                // a non-empty string), but "near" is not "never" and an uncounted drop
                // is exactly what this counter exists to stop.
                session.noteUnreadable(fileRelativePath)
                logger.info("Skipping file: ${file.name} - Could not read content")
            }
        }
        // Returned only so processFile can count it once the read lock is released.
        return content
    }

    /**
     * Include mode, one project file: classified by [NonTextCopy.classify] and held to the limit of its
     * kind. A carried non-text file takes a file-count slot exactly like a text file; size placeholders
     * and unreadable items do not, as today. Caller holds the read lock.
     */
    private fun processFileIncludingNonText(
        file: VirtualFile,
        fileRelativePath: String,
        fileContents: MutableList<String>,
        session: CopySession,
        settings: CopyFileContentSettings,
        addExtraLine: Boolean,
        customHeaderGenerator: ((VirtualFile, String) -> String)?
    ) {
        val headerFormat = settings.state.headerFormat
        fun addEntry(body: String) {
            fileContents.add(customHeaderGenerator?.invoke(file, fileRelativePath) ?: headerFormat.replace("\$FILE_PATH", fileRelativePath))
            fileContents.add(body)
            if (addExtraLine) fileContents.add("")
        }
        fun unreadable(reason: String) {
            session.noteUnreadable(fileRelativePath)
            logger.info("Skipping file: ${file.name} - $reason")
        }

        val outcome = NonTextCopy.classify(
            length = file.length,
            textLimitBytes = settings.state.maxFileSizeKB * 1024L,
            nonTextLimitBytes = session.maxNonTextBytes
        ) { readBytesOrNull(file) }
        when (outcome) {
            is NonTextCopy.Outcome.SizePlaceholder -> {
                session.skippedFileSizeCount++
                if (outcome.unread) session.unreadOverLimit++
                if (outcome.nonTextOverLimit) session.nonTextOverLimit++
                addEntry(ClipboardRestoreParser.escapeContent(NonTextCopy.sizePlaceholderLine(outcome.size), headerFormat))
            }
            is NonTextCopy.Outcome.Text -> {
                // Same guard as today's branch: strict decoding of non-empty bytes is never empty.
                if (outcome.content.isEmpty() && file.length != 0L) {
                    unreadable("Could not read content")
                } else {
                    addEntry(ClipboardRestoreParser.escapeTextBody(outcome.content, headerFormat))
                    session.fileCount++
                }
            }
            is NonTextCopy.Outcome.Carried -> {
                addEntry(ClipboardPayloadFormatter.nonTextBody(outcome.bytes, headerFormat))
                session.fileCount++
                session.nonTextCarried.add(NonTextCopySummary.Item(fileRelativePath, outcome.bytes.size.toLong()))
            }
            NonTextCopy.Outcome.Unreadable -> unreadable("unreadable")
        }
    }

    /** Raw bytes, or null when the file cannot be read (permission denied, dangling symlink). */
    private fun readBytesOrNull(file: VirtualFile): ByteArray? {
        if (file.`is`(com.intellij.openapi.vfs.VFileProperty.SYMLINK) && file.canonicalPath == null) return null
        return try {
            file.contentsToByteArray()
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            // Expected (chmod 000, dangling symlink): counted and named in the notice, not an IDE error.
            logger.warn("Failed to read ${file.path}: ${e.message}")
            null
        }
    }

    private fun processDirectory(
        directory: VirtualFile,
        fileContents: MutableList<String>,
        session: CopySession,
        settings: CopyFileContentSettings,
        addExtraLine: Boolean,
        customHeaderGenerator: ((VirtualFile, String) -> String)? = null,
        topLevel: Boolean = false
    ) {
        session.indicator.checkCanceled()
        // 目錄的 filter 判斷（讀 directory.path/name）與 children + isDirectory 快照
        // 一次取鎖；遞迴的子項各自取各自的短鎖
        val snapshot = ApplicationManager.getApplication().runReadAction<Pair<String, List<Pair<VirtualFile, Boolean>>>?> {
            if (!directoryPassesFilters(directory, session)) null
            else (directory.canonicalPath ?: directory.path) to directory.children.map { it to it.isDirectory }
        } ?: return
        // Decision D2: a folder whose name is not portable is named once and not walked, so none of its
        // files is copied (the spec allows this instead of naming each file). A jar / jrt folder has no
        // on-disk name and keeps today's path, as its files do.
        if (directory.isInLocalFileSystem && !PortableName.isPortableFolder(session.pathResolver, directory.path)) {
            session.noteUnportable(directory.path)
            logger.info("Skipping directory: ${directory.name} - name not portable")
            return
        }
        // X7: a folder selected directly may be reached through a symlinked folder (`vendor -> ~/private`,
        // then `vendor/sub`); it is then outside every copy root and is not walked, named like the link it
        // is reached through. Below it only links could lead out again, and those are checked per child.
        if (topLevel && directory.isInLocalFileSystem && session.pathResolver.readsOutsideEveryRoot(directory.path, false)) {
            session.skipNotes.note(SkipCategory.SYMLINKED_FOLDER, CopyPathFormatter.displayPath(session.pathResolver, directory.path))
            logger.info("Skipping directory: ${directory.name} - reached through a link to outside every copy root")
            return
        }
        // A folder that cannot be listed shows as empty in VFS; name it in either mode. Only the
        // notice changes — nothing is added to the payload.
        if (snapshot.second.isEmpty()) {
            noteIfUnlistable(directory, session)
        }
        // Track only current ancestors: normal package aliases keep their selected paths.
        if (!session.directoryAncestors.add(snapshot.first)) return
        try {
            for ((index, child) in snapshot.second.withIndex()) {
                val (childFile, childIsDirectory) = child
                if (stopForFileLimit(session, settings)) {
                    countLimitLeftOut(snapshot.second.subList(index, snapshot.second.size).map { it.first }, session)
                    break
                }
                if (childIsDirectory) {
                    // Never through a link (decision D2): it is counted and named as a symlinked
                    // folder. Following links also multiplied a pnpm/Bazel tree into aliases of the
                    // same few files; the real directories are reached on their own.
                    if (childFile.`is`(com.intellij.openapi.vfs.VFileProperty.SYMLINK)) {
                        session.skipNotes.note(SkipCategory.SYMLINKED_FOLDER, CopyPathFormatter.displayPath(session.pathResolver, childFile.path))
                        continue
                    }
                    processDirectory(childFile, fileContents, session, settings, addExtraLine, customHeaderGenerator)
                } else {
                    processFile(childFile, fileContents, session, settings, addExtraLine, customHeaderGenerator)
                }
            }
        } finally {
            session.directoryAncestors.remove(snapshot.first)
        }
    }

    private fun noteIfUnlistable(directory: VirtualFile, session: CopySession) {
        val nioPath = ApplicationManager.getApplication().runReadAction<java.nio.file.Path?> {
            directory.fileSystem.getNioPath(directory)
        } ?: return
        if (java.nio.file.Files.isReadable(nioPath)) return
        session.noteUnreadable(CopyPathFormatter.displayPath(session.pathResolver, directory.path))
        logger.info("Skipping directory: ${directory.name} - cannot be listed")
    }

    /** 目錄層級的 include/exclude PATH 過濾。呼叫端需持有 read lock（會讀 directory.path/name）。 */
    private fun directoryPassesFilters(
        directory: VirtualFile,
        session: CopySession
    ): Boolean {
        if (!session.useFilters) return true

        val dirRelativePath = CopyPathFormatter.relativeFilterPath(session.pathResolver, directory.path)
        val dirAbsolutePath = directory.path

        val includePathRules = session.includePathRules
        val excludePathRules = session.excludePathRules

        // Check excludes first
        if (session.useExcludeFilters && excludePathRules.isNotEmpty()) {
            val isExcluded = excludePathRules.any { rule ->
                if (PathRuleMatcher.isAbsolutePath(rule.value)) {
                    PathRuleMatcher.matchesPath(dirAbsolutePath, rule.value)
                } else {
                    dirRelativePath != null &&
                        PathRuleMatcher.matchesPath(dirRelativePath, rule.value)
                }
            }
            if (isExcluded) {
                logger.info("Skipping directory: ${directory.name} - Directory is excluded")
                return false
            }
        }

        // Check includes if specified.
        // Only when the INCLUDE set is PATH-only. A PATTERN include such as `*.md` says
        // nothing about which directories may contain a match, so pruning on the PATH
        // rules alone threw away directories whose files the pattern would have included —
        // VS Code evaluates every include rule per file as an OR and copied them. Pruning
        // is an optimisation; it may never drop a file the per-file filter would keep.
        val includeIsPathOnly = session.includeRules.size == includePathRules.size
        if (session.useIncludeFilters && includePathRules.isNotEmpty() && includeIsPathOnly) {
            val shouldProcess = includePathRules.any { rule ->
                if (PathRuleMatcher.isAbsolutePath(rule.value)) {
                    PathRuleMatcher.overlapsDirectory(dirAbsolutePath, rule.value)
                } else {
                    dirRelativePath != null &&
                        PathRuleMatcher.overlapsDirectory(dirRelativePath, rule.value)
                }
            }
            if (!shouldProcess) {
                logger.info("Skipping directory: ${directory.name} - Directory does not match any include path")
                return false
            }
        }

        return true
    }

    @IdeBoundCode
    private fun copyToClipboard(text: String) {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val data = StringSelection(text)
        clipboard.setContents(data, null)
    }

    @IdeBoundCode
    /** Files dropped because their bytes are not UTF-8 — reported, never silent. */
    private fun readFileContents(file: VirtualFile): String? {
        return try {
            // Strict UTF-8 or skip — NOT VfsUtilCore.loadText. loadText decodes with the
            // file's charset, which reads a Big5/Shift_JIS/UTF-16 file correctly here and
            // then cannot round-trip: the wire carries no encoding, so VS Code writes it
            // back as UTF-8 and the original bytes are lost. See Utf8Text.
            Utf8Text.decodeOrNull(file.contentsToByteArray())
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to read file contents: ${e.message}")
            null
        }
    }

    /** Uncached entry point; kept because `CopyFileContentInternalTest` calls it by reflection. */
    private fun matchesPattern(fileName: String, pattern: String): Boolean =
        CopyFilterMatcher.matchesPattern(fileName, pattern, null)

    companion object {
        /**
         * Copy notification carrying the payload's token estimate, coloured by size.
         * IntelliJ has no notification colour API, so the type IS the colour
         * (grey / yellow / red). Thresholds and wording mirror
         * `ClipCodeVSCode/src/notify.ts` — same payload, same number, same colour in
         * both tools. EVERY copy path that puts a payload on the clipboard should go
         * through here, otherwise an oversized copy is red in one tool and silent in
         * the other.
         *
         * [bodyHtml] is the path-specific part; the four statistics lines are appended to it.
         */
        @IdeBoundCode
        fun showPayloadNotification(bodyHtml: String, payloadText: String, project: Project?) {
            val app = ApplicationManager.getApplication()
            if (project?.isDisposed == true || app.isDisposed) return
            if (app.isDispatchThread) {
                app.executeOnPooledThread { showPayloadNotification(bodyHtml, payloadText, project) }
                return
            }
            val stats = TokenEstimator.stats(payloadText)
            val (type, warning) = when {
                stats.tokens >= TokenEstimator.DANGER_THRESHOLD ->
                    NotificationType.ERROR to "<br><b>Over ${grouped(TokenEstimator.DANGER_THRESHOLD)} tokens.</b>"
                stats.tokens >= TokenEstimator.WARN_THRESHOLD ->
                    NotificationType.WARNING to "<br><b>Over ${grouped(TokenEstimator.WARN_THRESHOLD)} tokens.</b>"
                else -> NotificationType.INFORMATION to ""
            }
            // All four derived from [payloadText] here, so EVERY copy path reports the
            // same four numbers — Git and PR copies used to show the token line alone.
            val statistics = "Total characters: ${grouped(stats.chars)}<br>" +
                "Total lines: ${grouped(stats.lines)}<br>" +
                "Total words: ${grouped(stats.words)}<br>" +
                "Estimated tokens: ${grouped(stats.tokens)}"
            showNotification("<html>$bodyHtml$statistics$warning</html>", type, project)
        }

        /**
         * Thousands separators pinned to [Locale.ROOT], not the IDE locale: the VS Code
         * mirror renders the same count and the two must not disagree on `1,234` vs `1.234`.
         */
        private fun grouped(value: Int): String = String.format(Locale.ROOT, "%,d", value)

        @IdeBoundCode
        fun showNotification(
            message: String,
            notificationType: NotificationType,
            project: Project?
        ): com.intellij.notification.Notification {
            val notificationGroup = NotificationGroupManager.getInstance().getNotificationGroup("ClipCode")
            val notification = notificationGroup.createNotification(message, notificationType).setImportant(true)
            notification.notify(project)
            return notification
        }
    }

    private data class CopySession(
        val indicator: ProgressIndicator,
        val copiedFilePaths: MutableSet<String> = mutableSetOf(),
        val directoryAncestors: MutableSet<String> = mutableSetOf(),
        val externalLibraryHandler: ExternalLibraryHandler,
        val pathResolver: ClipboardPathResolver,
        // The whole filter configuration is snapshotted at copy start — both the
        // toggles and the rules. Reading the toggles live while the rules were frozen
        // would be incoherent: applying "enable filters + exclude *.pem" mid-copy would
        // turn filtering on against an empty rule set and copy the very files it excludes.
        val useFilters: Boolean = false,
        val useIncludeFilters: Boolean = false,
        val useExcludeFilters: Boolean = false,
        // Enabled rules, partitioned once per copy; the PATH-only subsets are what the
        // directory filter needs. Compiled patterns are memoised by raw pattern string.
        val includeRules: List<CopyFileContentSettings.FilterRule> = emptyList(),
        val excludeRules: List<CopyFileContentSettings.FilterRule> = emptyList(),
        val includePathRules: List<CopyFileContentSettings.FilterRule> =
            includeRules.filter { it.type == CopyFileContentSettings.FilterType.PATH },
        val excludePathRules: List<CopyFileContentSettings.FilterRule> =
            excludeRules.filter { it.type == CopyFileContentSettings.FilterType.PATH },
        val patternCache: MutableMap<String, Regex> = mutableMapOf(),
        var fileCount: Int = 0,
        var skippedFileSizeCount: Int = 0,
        // Files whose bytes are not UTF-8 (or could not be read at all), symlinked folders and
        // unportable names. They are dropped on purpose, but a drop the user is never told about
        // is indistinguishable from the file not being there. Mirror of copy.ts
        // CopyState.skippedUnreadableCount.
        val skipNotes: SkipNotes = SkipNotes(),
        var fileLimitReached: Boolean = false,
        // Include mode (spec non-text-files.md): non-text files are carried as Base64. False is
        // today's copy, byte for byte.
        val includeNonText: Boolean = false,
        val maxNonTextBytes: Long = 2048L * 1024,
        /** Include only: files the limit left out, counted when the walk stops (see countLimitLeftOut). */
        var limitLeftOut: Int = 0,
        var limitLeftOutCapped: Boolean = false,
        /** The files counted into [limitLeftOut]: one also listed by an enclosing level is counted once. */
        val limitLeftOutPaths: MutableSet<String> = mutableSetOf(),
        val nonTextCarried: MutableList<NonTextCopySummary.Item> = mutableListOf(),
        var nonTextOverLimit: Int = 0,
        var unreadOverLimit: Int = 0
    ) {
        fun noteUnreadable(path: String) = skipNotes.note(SkipCategory.UNREADABLE, path)

        /** Named with its real path under the root (backslash, tab and all), or the absolute path outside every root. */
        fun noteUnportable(absolutePath: String) =
            skipNotes.note(SkipCategory.NAME_NOT_PORTABLE, pathResolver.rawRelativePath(absolutePath) ?: absolutePath)
    }

    @IdeBoundCode
    private fun showNotificationWithSettingsAction(message: String, notificationType: NotificationType, project: Project?) {
        val notificationGroup = NotificationGroupManager.getInstance().getNotificationGroup("ClipCode")
        val notification = notificationGroup.createNotification(message, notificationType).setImportant(true)
        notification.addAction(NotificationAction.createSimple("Go to Settings") {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, "ClipCode Settings")
        })
        notification.notify(project)
    }
}
