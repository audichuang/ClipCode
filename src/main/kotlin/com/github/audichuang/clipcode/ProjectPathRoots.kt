package com.github.audichuang.clipcode

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile

@IdeBoundCode
object ProjectPathRoots {
    fun primaryRootPath(project: Project): String? =
        project.basePath

    fun primaryRoot(project: Project): VirtualFile? {
        primaryRootPath(project)
            ?.let { path ->
                LocalFileSystem.getInstance().findFileByPath(path)
                    ?: LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
            }
            ?.let { return it }

        return ProjectRootManager.getInstance(project).contentRoots.firstOrNull()
    }
}
