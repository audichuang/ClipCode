package com.github.audichuang.clipcode

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * "Copy non-text files?" — asked before the clipboard is written whenever the Include copy would carry
 * at least one non-text file (spec non-text-files.md, "Asking the user"). Include is the default button;
 * Esc is Cancel; the choice is not remembered.
 */
class NonTextCopyDialog(project: Project, summary: NonTextCopySummary) : DialogWrapper(project, true) {
    private val text = NonTextCopy.dialogText(summary)
    private val excludeAction = object : AbstractAction("Exclude") {
        override fun actionPerformed(e: ActionEvent?) = close(EXCLUDE_EXIT_CODE)
    }

    init {
        title = "Copy non-text files?"
        setOKButtonText("Include")
        if (text.includeUnavailable != null) {
            okAction.isEnabled = false
            okAction.putValue(DEFAULT_ACTION, null)
            excludeAction.putValue(DEFAULT_ACTION, true)
        }
        init()
    }

    override fun createActions(): Array<Action> = arrayOf(okAction, excludeAction, cancelAction)

    override fun getPreferredFocusedComponent(): JComponent? =
        getButton(if (text.includeUnavailable != null) excludeAction else okAction)

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        val c = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            gridwidth = 2
            anchor = GridBagConstraints.WEST
            insets = JBUI.insetsBottom(4)
        }
        fun line(value: String) {
            c.gridx = 0
            c.gridwidth = 2
            panel.add(JBLabel(value), c)
            c.gridy++
        }
        line(text.found)
        line(text.estimate)
        for ((path, size) in text.paths) {
            // Separate cells so a long path never pushes its size onto a new line.
            c.gridwidth = 1
            c.gridx = 0
            c.insets = JBUI.insets(0, 12, 2, 12)
            panel.add(JBLabel(path), c)
            c.gridx = 1
            c.insets = JBUI.insetsBottom(2)
            panel.add(JBLabel(size).apply { foreground = UIUtil.getContextHelpForeground() }, c)
            c.gridy++
        }
        c.insets = JBUI.insetsBottom(4)
        text.more?.let { line(it) }
        text.notes.forEach { line(it) }
        text.includeUnavailable?.let { line(it) }
        return panel
    }

    companion object {
        const val EXCLUDE_EXIT_CODE = NEXT_USER_EXIT_CODE

        /** The production chooser: shows this dialog on the EDT. */
        val CHOOSER = NonTextChooser { project, summary ->
            val dialog = NonTextCopyDialog(project, summary)
            dialog.show()
            when (dialog.exitCode) {
                OK_EXIT_CODE -> NonTextChoice.INCLUDE
                EXCLUDE_EXIT_CODE -> NonTextChoice.EXCLUDE
                else -> NonTextChoice.CANCEL
            }
        }
    }
}
