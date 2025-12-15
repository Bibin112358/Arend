package org.arend.module.config

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.arend.util.Version


class EmptyLibraryConfig(override val name: String, project: Project) : LibraryConfig(project) {
    override val root: VirtualFile?
        get() = null

    override fun getLibraryVersion(): Version? = null
}