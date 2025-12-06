package com.github.audichuang.clipcode

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(
    name = "CopyFileContentSettings",
    storages = [Storage("CopyFileContentSettings.xml")]
)
class CopyFileContentSettings : PersistentStateComponent<CopyFileContentSettings.State> {
    data class State(
        var headerFormat: String = "// file: \$FILE_PATH",
        var preText: String = "",
        var postText: String = "",
        var fileCountLimit: Int = 30,
        var maxFileSizeKB: Int = 500,  // Maximum file size in KB (default 500KB)
        var filterRules: MutableList<FilterRule> = mutableListOf(),  // Combined filter rules
        var addExtraLineBetweenFiles: Boolean = true,
        var setMaxFileCount: Boolean = true,
        var showCopyNotification: Boolean = true,
        var useFilters: Boolean = false,  // Master switch for filtering
        var useIncludeFilters: Boolean = true,  // Enable include filters
        var useExcludeFilters: Boolean = true   // Enable exclude filters
    )
    
    data class FilterRule(
        var type: FilterType = FilterType.PATH,  // PATH or PATTERN
        var action: FilterAction = FilterAction.INCLUDE,  // INCLUDE or EXCLUDE
        var value: String = "",  // The path or extension
        var enabled: Boolean = true  // Individual enable/disable
    )
    
    enum class FilterType {
        PATH,
        PATTERN  // File name pattern (regex or wildcard)
    }
    
    enum class FilterAction {
        INCLUDE,
        EXCLUDE
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        fun getInstance(project: Project): CopyFileContentSettings? {
            return project.getService(CopyFileContentSettings::class.java)
        }
    }
}