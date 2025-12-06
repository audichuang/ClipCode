package com.github.audichuang.clipcode

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.ex.FileChooserDialogImpl
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.ide.util.TreeFileChooser
import com.intellij.ide.util.TreeFileChooserFactory
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiDirectory
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.*
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*
import javax.swing.table.DefaultTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer
import javax.swing.table.TableCellEditor
import com.intellij.ui.RoundedLineBorder
import com.intellij.icons.AllIcons
import java.io.File

class CopyFileContentConfigurable(private val project: Project) : Configurable {
    private var settings: CopyFileContentSettings? = null
    private val headerFormatArea = JBTextArea(4, 20).apply {
        border = JBUI.Borders.merge(
            JBUI.Borders.empty(5),
            RoundedLineBorder(JBColor.LIGHT_GRAY, 4, 1),
            true
        )
    }

    private val preTextArea = JBTextArea(4, 20).apply {
        border = JBUI.Borders.merge(
            JBUI.Borders.empty(5),
            RoundedLineBorder(JBColor.LIGHT_GRAY, 4, 1),
            true
        )
    }

    private val postTextArea = JBTextArea(4, 20).apply {
        border = JBUI.Borders.merge(
            JBUI.Borders.empty(5),
            RoundedLineBorder(JBColor.LIGHT_GRAY, 4, 1),
            true
        )
    }
    private val extraLineCheckBox = JBCheckBox("Add an extra line between files")
    private val setMaxFilesCheckBox = JBCheckBox("Set maximum number of files to have their content copied")
    private val maxFilesField = JBTextField(10)
    private val warningLabel = com.intellij.ui.components.JBLabel().apply {
        icon = com.intellij.icons.AllIcons.General.WarningDialog
        text = "Not setting a maximum number of files may cause high memory usage"
        foreground = JBUI.CurrentTheme.Label.foreground()
        border = JBUI.Borders.emptyLeft(5)
        isVisible = false
    }
    private val infoLabel = JLabel("<html><b>Info:</b> Please add file extensions to the table above.</html>").apply {
        foreground = JBColor(0x31708F, 0x31708F)
        background = JBColor(0xD9EDF7, 0xD9EDF7)
        border = JBUI.Borders.compound(
            JBUI.Borders.empty(5),
            BorderFactory.createLineBorder(JBColor(0xBCE8F1, 0xBCE8F1))
        )
        isOpaque = true
        isVisible = false
    }
    private val showNotificationCheckBox = JBCheckBox("Show notification after copying")
    private val useFiltersCheckBox = JBCheckBox("Enable filtering")
    private val useIncludeFiltersCheckBox = JBCheckBox("Enable include rules")
    private val useExcludeFiltersCheckBox = JBCheckBox("Enable exclude rules")
    
    private val filterTableModel = object : DefaultTableModel() {
        override fun isCellEditable(row: Int, column: Int): Boolean = column == 0  // Only checkbox column is editable
        override fun getColumnClass(columnIndex: Int): Class<*> {
            return when (columnIndex) {
                0 -> java.lang.Boolean::class.java  // Enabled checkbox
                else -> super.getColumnClass(columnIndex)
            }
        }
    }
    private val filterTable = JBTable(filterTableModel)
    private val addIncludePathButton = JButton("Include Path/File")
    private val addExcludePathButton = JButton("Exclude Path/File") 
    private val addIncludePatternButton = JButton("Include Pattern")
    private val addExcludePatternButton = JButton("Exclude Pattern")
    private val removeButton = JButton("Remove")
    private val filtersPanel = createFiltersPanel()

    init {
        filterTableModel.addColumn("Enabled")  // Checkbox
        filterTableModel.addColumn("Type")  // Icon
        filterTableModel.addColumn("Action")  // Include/Exclude
        filterTableModel.addColumn("Filter")  // Path or Extension
        
        setupFilterTable()
        setupFilterTableRenderers()
        
        // Disable remove button initially
        removeButton.isEnabled = false
        
        // Enable/disable remove button based on selection
        filterTable.selectionModel.addListSelectionListener {
            removeButton.isEnabled = filterTable.selectedRowCount > 0
        }

        setMaxFilesCheckBox.addActionListener {
            val maxFilesSelected = setMaxFilesCheckBox.isSelected
            maxFilesField.isVisible = maxFilesSelected
            warningLabel.isVisible = !maxFilesSelected
        }

        useFiltersCheckBox.addActionListener {
            val enabled = useFiltersCheckBox.isSelected
            useIncludeFiltersCheckBox.isVisible = enabled
            useExcludeFiltersCheckBox.isVisible = enabled
            filtersPanel.isVisible = enabled
            if (!enabled) {
                useIncludeFiltersCheckBox.isSelected = true
                useExcludeFiltersCheckBox.isSelected = true
            }
        }
        
        useIncludeFiltersCheckBox.addActionListener {
            updateFilterTableState()
        }
        
        useExcludeFiltersCheckBox.addActionListener {
            updateFilterTableState()
        }
    }

    private fun updateFilterTableState() {
        // Update filter table based on checkbox states
        filterTable.repaint()
    }

    private fun setupFilterTable() {
        // Load existing filter rules
        settings?.state?.filterRules?.forEach { rule ->
            val icon = when (rule.type) {
                CopyFileContentSettings.FilterType.PATH -> getPathIcon(rule.value)
                CopyFileContentSettings.FilterType.PATTERN -> AllIcons.FileTypes.Any_type
            }
            val actionText = when (rule.action) {
                CopyFileContentSettings.FilterAction.INCLUDE -> "Include"
                CopyFileContentSettings.FilterAction.EXCLUDE -> "Exclude"
            }
            filterTableModel.addRow(arrayOf(rule.enabled, icon, actionText, rule.value))
        }

        // Setup button listeners
        addIncludePathButton.addActionListener {
            showPathChooser(CopyFileContentSettings.FilterAction.INCLUDE)
        }
        
        addExcludePathButton.addActionListener {
            showPathChooser(CopyFileContentSettings.FilterAction.EXCLUDE)
        }
        
        addIncludePatternButton.addActionListener {
            showPatternDialog(CopyFileContentSettings.FilterAction.INCLUDE)
        }
        
        addExcludePatternButton.addActionListener {
            showPatternDialog(CopyFileContentSettings.FilterAction.EXCLUDE)
        }

        removeButton.addActionListener {
            val selectedRows = filterTable.selectedRows
            if (selectedRows.isNotEmpty()) {
                // Remove rows from bottom to top to maintain correct indices
                selectedRows.sortedDescending().forEach { row ->
                    filterTableModel.removeRow(row)
                }
            }
        }
    }
    
    private fun showPathChooser(action: CopyFileContentSettings.FilterAction) {
        val projectRoot = com.intellij.openapi.roots.ProjectRootManager.getInstance(project).contentRoots.firstOrNull()
        
        // Use FileChooserDescriptor with project scope
        val descriptor = FileChooserDescriptor(true, true, false, false, false, true)
        descriptor.title = "Select Files or Folders to ${if (action == CopyFileContentSettings.FilterAction.INCLUDE) "Include" else "Exclude"}"
        descriptor.description = "Choose files or folders from the project"
        projectRoot?.let { descriptor.roots = listOf(it) }
        
        // Use FileChooserDialog with project scope
        val dialog = com.intellij.openapi.fileChooser.ex.FileChooserDialogImpl(descriptor, project)
        val selectedFiles = dialog.choose(project, projectRoot)
        
        selectedFiles.forEach { virtualFile ->
            val relativePath = projectRoot?.let { VfsUtil.getRelativePath(virtualFile, it, '/') }
            val pathToAdd = relativePath ?: virtualFile.path
            
            addFilterRule(action, pathToAdd, virtualFile.isDirectory)
        }
    }
    
    private fun addFilterRule(action: CopyFileContentSettings.FilterAction, value: String, isDirectory: Boolean) {
        // Check if not already in table
        var exists = false
        for (i in 0 until filterTableModel.rowCount) {
            if (filterTableModel.getValueAt(i, 3) == value && 
                filterTableModel.getValueAt(i, 2) == (if (action == CopyFileContentSettings.FilterAction.INCLUDE) "Include" else "Exclude")) {
                exists = true
                break
            }
        }
        if (!exists) {
            val icon = if (isDirectory) AllIcons.Nodes.Folder else AllIcons.FileTypes.Any_type
            val actionText = if (action == CopyFileContentSettings.FilterAction.INCLUDE) "Include" else "Exclude"
            filterTableModel.addRow(arrayOf(true, icon, actionText, value))
        }
    }
    
    private fun showPatternDialog(action: CopyFileContentSettings.FilterAction) {
        val pattern = Messages.showInputDialog(
            "Enter file name pattern (e.g., *.java, Test*.kt, .*\\.xml):\nSupports wildcards (* and ?) and regex", 
            "Add ${if (action == CopyFileContentSettings.FilterAction.INCLUDE) "Include" else "Exclude"} Pattern", 
            null
        )
        if (!pattern.isNullOrBlank()) {
            // Check if not already in table
            var exists = false
            for (i in 0 until filterTableModel.rowCount) {
                if (filterTableModel.getValueAt(i, 3) == pattern && 
                    filterTableModel.getValueAt(i, 2) == (if (action == CopyFileContentSettings.FilterAction.INCLUDE) "Include" else "Exclude")) {
                    exists = true
                    break
                }
            }
            if (!exists) {
                val icon = AllIcons.FileTypes.Any_type
                val actionText = if (action == CopyFileContentSettings.FilterAction.INCLUDE) "Include" else "Exclude"
                filterTableModel.addRow(arrayOf(true, icon, actionText, pattern))
            }
        }
    }
    
    
    private fun getPathIcon(path: String): Icon {
        // Check if path exists and is directory or file
        val projectRoot = com.intellij.openapi.roots.ProjectRootManager.getInstance(project).contentRoots.firstOrNull()
        val fullPath = if (path.startsWith("/")) {
            File(path)
        } else {
            projectRoot?.let { File(it.path, path) } ?: File(path)
        }
        
        return when {
            !fullPath.exists() -> AllIcons.FileTypes.Unknown
            fullPath.isDirectory -> AllIcons.Nodes.Folder
            else -> AllIcons.FileTypes.Any_type
        }
    }
    
    private fun setupFilterTableRenderers() {
        // Checkbox column
        filterTable.columnModel.getColumn(0).apply {
            preferredWidth = 60
            maxWidth = 60
        }
        
        // Icon/Type column  
        filterTable.columnModel.getColumn(1).apply {
            preferredWidth = 30
            maxWidth = 30
            cellRenderer = object : DefaultTableCellRenderer() {
                override fun getTableCellRendererComponent(
                    table: JTable?, value: Any?, isSelected: Boolean,
                    hasFocus: Boolean, row: Int, column: Int
                ): Component {
                    val label = super.getTableCellRendererComponent(
                        table, "", isSelected, hasFocus, row, column
                    ) as JLabel
                    label.icon = value as? Icon
                    label.horizontalAlignment = JLabel.CENTER
                    return label
                }
            }
        }
        
        // Action column
        filterTable.columnModel.getColumn(2).apply {
            preferredWidth = 80
            maxWidth = 80
        }
        
        // Filter value column takes remaining space
        filterTable.columnModel.getColumn(3).preferredWidth = 320
    }

    override fun createComponent(): JComponent {
        settings = CopyFileContentSettings.getInstance(project)

        maxFilesField.isVisible = setMaxFilesCheckBox.isSelected
        warningLabel.isVisible = !setMaxFilesCheckBox.isSelected
        filtersPanel.isVisible = useFiltersCheckBox.isSelected
        useIncludeFiltersCheckBox.isVisible = useFiltersCheckBox.isSelected
        useExcludeFiltersCheckBox.isVisible = useFiltersCheckBox.isSelected
        if (!useFiltersCheckBox.isSelected) {
            useIncludeFiltersCheckBox.isSelected = true
            useExcludeFiltersCheckBox.isSelected = true
        }

        return FormBuilder.createFormBuilder()
            .addComponentFillVertically(createSection("Text structure of what's going to the clipboard") {
                it.add(createLabeledPanel("Pre Text:", preTextArea), BorderLayout.NORTH)
                it.add(createLabeledPanel("File Header Format:", headerFormatArea), BorderLayout.CENTER)
                it.add(createLabeledPanel("Post Text:", postTextArea), BorderLayout.SOUTH)
                it.add(createLabeledPanel("", extraLineCheckBox))
            }, 0)
            .addComponentFillVertically(createSection("Constraints for copying") {
                val maxFilesPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
                maxFilesPanel.add(setMaxFilesCheckBox)
                maxFilesPanel.add(Box.createHorizontalStrut(10))
                maxFilesPanel.add(maxFilesField)
                it.add(maxFilesPanel)
                
                val warningPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
                warningPanel.add(Box.createHorizontalStrut(25))
                warningPanel.add(warningLabel)
                it.add(warningPanel)
                it.add(Box.createVerticalStrut(10))
                
                // Filtering section
                val filteringPanel = JPanel(BorderLayout())
                val filterHeader = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
                filterHeader.add(useFiltersCheckBox)
                filterHeader.add(Box.createHorizontalStrut(20))
                filterHeader.add(useIncludeFiltersCheckBox)
                filterHeader.add(Box.createHorizontalStrut(10))
                filterHeader.add(useExcludeFiltersCheckBox)
                filteringPanel.add(filterHeader, BorderLayout.NORTH)
                filteringPanel.add(filtersPanel, BorderLayout.CENTER)
                
                it.add(filteringPanel)
            }, 0)
            .addComponentFillVertically(createSection("Information on what has been copied") {
                it.add(showNotificationCheckBox)
            }, 0)
            .panel
    }

    private fun createSectionDivider(title: String = ""): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = IdeBorderFactory.createTitledBorder(title, false, JBUI.insetsTop(20))

        return panel
    }

    private fun createInlinePanel(leftComponent: JComponent, rightComponent: JComponent, spacing: Int = 10): JPanel {
        val panel = JPanel(BorderLayout())

        val leftWrapper = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        leftWrapper.add(leftComponent)
        leftWrapper.border = JBUI.Borders.emptyRight(spacing)

        val rightWrapper = JPanel(BorderLayout())
        rightWrapper.add(rightComponent, BorderLayout.CENTER)

        panel.add(leftWrapper, BorderLayout.WEST)
        panel.add(rightWrapper, BorderLayout.CENTER)

        return panel
    }

    private fun createWrappedCheckBoxPanel(checkBox: JBCheckBox, paddingTop: Int = 4): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.emptyTop(paddingTop)
        panel.add(checkBox)
        return panel
    }

    private fun createCollapsibleSection(title: String = "", content: (JPanel) -> Unit): JPanel {
        val collapsiblePanel = JPanel(BorderLayout())
        content(collapsiblePanel)

        val panel = JPanel(BorderLayout())
        val titleBorder = IdeBorderFactory.createTitledBorder(title, false, JBUI.insetsTop(8))
        panel.border = titleBorder
        panel.add(CollapsiblePanel(title, collapsiblePanel), BorderLayout.CENTER)

        return panel
    }

    private fun createSection(title: String = "", content: (JPanel) -> Unit): JPanel {
        val panel = JPanel(BorderLayout())
        val sectionContent = Box.createVerticalBox()

        // Panel to contain the inner content
        val contentPanel = JPanel()
        contentPanel.layout = BoxLayout(contentPanel, BoxLayout.Y_AXIS)
        contentPanel.border = JBUI.Borders.emptyRight(10)
        content(contentPanel)

        // Add margin to each component inside the content panel
        for (component in contentPanel.components) {
            if (component is JComponent) {
                component.border = JBUI.Borders.emptyBottom(10)
            }
        }

        sectionContent.add(contentPanel)

        val divider = createSectionDivider(title)
        panel.add(divider, BorderLayout.NORTH)
        panel.add(sectionContent, BorderLayout.CENTER)

        return panel
    }

    private fun createCollapsibleTextInputsPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        val titleBorder = IdeBorderFactory.createTitledBorder("Text Options", false, JBUI.insetsTop(8))
        panel.border = titleBorder

        val collapsiblePanel = JPanel(BorderLayout())
        collapsiblePanel.add(createLabeledPanel("Pre Text:", preTextArea), BorderLayout.NORTH)
        collapsiblePanel.add(createLabeledPanel("File Header Format:", headerFormatArea), BorderLayout.CENTER)
        collapsiblePanel.add(createLabeledPanel("Post Text:", postTextArea), BorderLayout.SOUTH)

        panel.add(CollapsiblePanel("Text Options", collapsiblePanel), BorderLayout.CENTER)

        return panel
    }

    class CollapsiblePanel(private val title: String, content: JPanel) : JPanel(BorderLayout()) {
        private val toggleButton: JButton = JButton(title)

        init {
            toggleButton.isContentAreaFilled = false
            toggleButton.isOpaque = false
            toggleButton.border = BorderFactory.createEmptyBorder()
            toggleButton.margin = JBUI.emptyInsets()
            toggleButton.horizontalAlignment = SwingConstants.LEFT
            toggleButton.preferredSize = Dimension(0, 24)

            toggleButton.addActionListener {
                content.isVisible = !content.isVisible
                updateToggleButtonText(content.isVisible)
            }
            updateToggleButtonText(content.isVisible)

            val headerPanel = JPanel(BorderLayout())
            headerPanel.add(toggleButton, BorderLayout.WEST)
            headerPanel.border = JBUI.Borders.empty(4, 0)

            add(headerPanel, BorderLayout.NORTH)
            add(content, BorderLayout.CENTER)
        }

        private fun updateToggleButtonText(expanded: Boolean) {
            toggleButton.text = if (expanded) "▼ $title" else "▶ $title"
        }
    }

    private fun createFiltersPanel(): JPanel {
        val panel = JPanel(BorderLayout())

        val scrollPane = JBScrollPane(filterTable)
        scrollPane.preferredSize = Dimension(600, 200)
        panel.add(scrollPane, BorderLayout.CENTER)

        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        buttonPanel.add(addIncludePathButton)
        buttonPanel.add(addExcludePathButton)
        buttonPanel.add(addIncludePatternButton)
        buttonPanel.add(addExcludePatternButton)
        buttonPanel.add(removeButton)
        panel.add(buttonPanel, BorderLayout.SOUTH)

        return panel
    }

    private fun createLabeledPanel(title: String, component: JComponent): JPanel {
        val label = JLabel(title)
        label.border = JBUI.Borders.emptyBottom(4)

        val panel = JPanel(BorderLayout())
        panel.add(label, BorderLayout.NORTH)
        panel.add(component, BorderLayout.CENTER)

        return panel
    }

    override fun isModified(): Boolean {
        return settings?.let {
            val currentRules = mutableListOf<CopyFileContentSettings.FilterRule>()
            
            for (i in 0 until filterTableModel.rowCount) {
                val enabled = filterTableModel.getValueAt(i, 0) as Boolean
                val actionText = filterTableModel.getValueAt(i, 2) as String
                val value = filterTableModel.getValueAt(i, 3) as String
                
                val action = if (actionText == "Include") 
                    CopyFileContentSettings.FilterAction.INCLUDE 
                else 
                    CopyFileContentSettings.FilterAction.EXCLUDE
                    
                val type = if (value.contains("*") || value.contains("?") || value.startsWith(".")) 
                    CopyFileContentSettings.FilterType.PATTERN 
                else 
                    CopyFileContentSettings.FilterType.PATH
                    
                currentRules.add(CopyFileContentSettings.FilterRule(type, action, value, enabled))
            }
            
            it.state.filterRules != currentRules ||
                    headerFormatArea.text != it.state.headerFormat ||
                    preTextArea.text != it.state.preText ||
                    postTextArea.text != it.state.postText ||
                    extraLineCheckBox.isSelected != it.state.addExtraLineBetweenFiles ||
                    setMaxFilesCheckBox.isSelected != it.state.setMaxFileCount ||
                    (setMaxFilesCheckBox.isSelected && maxFilesField.text.toIntOrNull() != it.state.fileCountLimit) ||
                    showNotificationCheckBox.isSelected != it.state.showCopyNotification ||
                    useFiltersCheckBox.isSelected != it.state.useFilters ||
                    useIncludeFiltersCheckBox.isSelected != it.state.useIncludeFilters ||
                    useExcludeFiltersCheckBox.isSelected != it.state.useExcludeFilters
        } ?: false
    }

    override fun apply() {
        settings?.let {
            val rules = mutableListOf<CopyFileContentSettings.FilterRule>()
            
            for (i in 0 until filterTableModel.rowCount) {
                val enabled = filterTableModel.getValueAt(i, 0) as Boolean
                val actionText = filterTableModel.getValueAt(i, 2) as String
                val value = filterTableModel.getValueAt(i, 3) as String
                
                val action = if (actionText == "Include") 
                    CopyFileContentSettings.FilterAction.INCLUDE 
                else 
                    CopyFileContentSettings.FilterAction.EXCLUDE
                    
                val type = if (value.contains("*") || value.contains("?") || value.startsWith(".")) 
                    CopyFileContentSettings.FilterType.PATTERN 
                else 
                    CopyFileContentSettings.FilterType.PATH
                    
                rules.add(CopyFileContentSettings.FilterRule(type, action, value, enabled))
            }
            
            it.state.filterRules.clear()
            it.state.filterRules.addAll(rules)
            it.state.headerFormat = headerFormatArea.text
            it.state.preText = preTextArea.text
            it.state.postText = postTextArea.text
            it.state.addExtraLineBetweenFiles = extraLineCheckBox.isSelected
            it.state.setMaxFileCount = setMaxFilesCheckBox.isSelected
            it.state.fileCountLimit = maxFilesField.text.toIntOrNull() ?: 50
            it.state.showCopyNotification = showNotificationCheckBox.isSelected
            it.state.useFilters = useFiltersCheckBox.isSelected
            it.state.useIncludeFilters = useIncludeFiltersCheckBox.isSelected
            it.state.useExcludeFilters = useExcludeFiltersCheckBox.isSelected
        }
    }

    override fun getDisplayName(): String = "ClipCode Settings"

    override fun reset() {
        settings?.let {
            headerFormatArea.text = it.state.headerFormat
            preTextArea.text = it.state.preText
            postTextArea.text = it.state.postText
            extraLineCheckBox.isSelected = it.state.addExtraLineBetweenFiles
            setMaxFilesCheckBox.isSelected = it.state.setMaxFileCount
            maxFilesField.text = it.state.fileCountLimit.toString()
            showNotificationCheckBox.isSelected = it.state.showCopyNotification
            useFiltersCheckBox.isSelected = it.state.useFilters
            useIncludeFiltersCheckBox.isSelected = it.state.useIncludeFilters
            useExcludeFiltersCheckBox.isSelected = it.state.useExcludeFilters
            
            filterTableModel.setRowCount(0)
            it.state.filterRules.forEach { rule ->
                val icon = when (rule.type) {
                    CopyFileContentSettings.FilterType.PATH -> getPathIcon(rule.value)
                    CopyFileContentSettings.FilterType.PATTERN -> AllIcons.FileTypes.Any_type
                }
                val actionText = when (rule.action) {
                    CopyFileContentSettings.FilterAction.INCLUDE -> "Include"
                    CopyFileContentSettings.FilterAction.EXCLUDE -> "Exclude"
                }
                filterTableModel.addRow(arrayOf(rule.enabled, icon, actionText, rule.value))
            }
            
            maxFilesField.isVisible = it.state.setMaxFileCount
            warningLabel.isVisible = !it.state.setMaxFileCount
            filtersPanel.isVisible = it.state.useFilters
            useIncludeFiltersCheckBox.isEnabled = it.state.useFilters
            useExcludeFiltersCheckBox.isEnabled = it.state.useFilters
        }
    }
}
