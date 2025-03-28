package com.daichongweb.gitplus

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

class AddToIgnoreFileAction : AnAction() {
    companion object {
        private const val NOTIFICATION_GROUP_ID = "GitIgnore.Notification"
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: run {
            showError(event, "No project found")
            return
        }

        val virtualFile = event.getData(CommonDataKeys.VIRTUAL_FILE) ?: run {
            showError(event, "No file selected")
            return
        }

        try {
            if (!isGitRepository(project)) {
                showError(event, "This project is not a Git repository. Please initialize Git first.")
                return
            }

            val projectBasePath = project.basePath ?: run {
                showError(event, "Project base path not found")
                return
            }

            val ignoreFile = findOrCreateIgnoreFile(Paths.get(projectBasePath), virtualFile)
            val relativePath = getRelativePath(Paths.get(projectBasePath), virtualFile)

            appendToIgnoreFile(ignoreFile, relativePath)
            refreshFileSystem(ignoreFile)

            // 使用正确的通知方式
            Notification(
                NOTIFICATION_GROUP_ID,
                "Added to ignore file",
                "Added: $relativePath",
                NotificationType.INFORMATION
            ).notify(project)
        } catch (e: Exception) {
            Messages.showErrorDialog(project, "Failed to add to ignore file: ${e.message}", "Error")
        }
    }

    override fun update(event: AnActionEvent) {
        // 这个方法现在会在BGT线程执行
        val project = event.project
        val virtualFile = event.getData(CommonDataKeys.VIRTUAL_FILE)

        // 只在Git仓库中且选中了文件时才显示
        event.presentation.isEnabledAndVisible = project != null &&
                virtualFile != null &&
                isGitRepository(project)
    }

    private fun isGitRepository(project: Project): Boolean {
        val projectRoot = project.basePath ?: return false
        val gitDir = Paths.get(projectRoot, ".git")
        return Files.exists(gitDir) && Files.isDirectory(gitDir)
    }

    private fun findOrCreateIgnoreFile(projectRoot: Path, selectedFile: VirtualFile): Path {
        // 优先在项目根目录查找或创建.gitignore
        val rootIgnoreFile = projectRoot.resolve(".gitignore")
        if (Files.exists(rootIgnoreFile) || !Files.exists(projectRoot.resolve(".git"))) {
            if (!Files.exists(rootIgnoreFile)) {
                Files.createFile(rootIgnoreFile)
            }
            return rootIgnoreFile
        }

        // 如果不是在项目根目录，向上查找.gitignore
        var currentDir = selectedFile.parent?.path?.let { Paths.get(it) } ?: projectRoot

        while (Files.exists(currentDir) && currentDir.startsWith(projectRoot)) {
            val potentialIgnoreFile = currentDir.resolve(".gitignore")
            if (Files.exists(potentialIgnoreFile)) {
                return potentialIgnoreFile
            }
            currentDir = currentDir.parent
        }

        return rootIgnoreFile
    }

    private fun getRelativePath(projectRoot: Path, file: VirtualFile): String {
        val filePath = Paths.get(file.path)
        return projectRoot.relativize(filePath).toString().let {
            if (file.isDirectory) "$it/" else it
        }
    }

    private fun appendToIgnoreFile(ignoreFile: Path, entry: String) {
        val content = if (Files.exists(ignoreFile)) {
            Files.readAllLines(ignoreFile)
        } else {
            emptyList()
        }

        if (isAlreadyIgnored(content, entry)) return

        val entryToWrite = when {
            content.isEmpty() -> entry
            content.last().isBlank() -> entry
            else -> "\n$entry"
        }

        Files.write(
            ignoreFile, entryToWrite.toByteArray(),
            StandardOpenOption.CREATE, StandardOpenOption.APPEND
        )
    }

    private fun isAlreadyIgnored(existingRules: List<String>, newEntry: String): Boolean {
        if (existingRules.any { it.trim() == newEntry }) return true

        if (newEntry.contains("/")) {
            val parts = newEntry.split("/")
            var currentPath = ""
            for (part in parts.dropLast(1)) {
                currentPath += "$part/"
                if (existingRules.any { it.trim() == currentPath }) {
                    return true
                }
            }
        }

        return false
    }

    private fun refreshFileSystem(ignoreFile: Path) {
        try {
            com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                .refreshAndFindFileByPath(ignoreFile.toString())?.refresh(false, false)
        } catch (e: Exception) {
            // 静默处理刷新失败
        }
    }

    private fun showError(event: AnActionEvent, message: String) {
        Messages.showErrorDialog(event.project, message, "Error")
    }
}