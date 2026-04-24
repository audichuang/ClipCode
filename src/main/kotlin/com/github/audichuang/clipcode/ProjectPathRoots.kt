package com.github.audichuang.clipcode

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile

object ProjectPathRoots {
    @Suppress("DEPRECATION")
    fun primaryRootPath(project: Project): String? =
        project.baseDir?.path
            ?: project.basePath

    @Suppress("DEPRECATION")
    fun primaryRoot(project: Project): VirtualFile? {
        primaryRootPath(project)
            ?.let { path ->
                LocalFileSystem.getInstance().findFileByPath(path)
                    ?: LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
                    ?: project.baseDir?.takeIf { it.path == path }
            }
            ?.let { return it }

        return ProjectRootManager.getInstance(project).contentRoots.firstOrNull()
    }
}
