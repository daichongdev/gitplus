package com.daichongweb.gitplus

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import git4idea.GitUtil
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepository
import java.nio.file.Path
import java.nio.file.Paths

class GitRemoveFromCacheAction : AnAction() {
    companion object {
        private const val NOTIFICATION_GROUP_ID = "GitRemoveFromCache.Notification"
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return showError("未找到项目", event)
        val virtualFile = event.getData(CommonDataKeys.VIRTUAL_FILE) ?: return showError("未选择文件", event)
    
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                // Get repository and path in read action
                val (repository, relativePath) = ApplicationManager.getApplication().runReadAction<Pair<GitRepository, String>> {
                    val repo = findGitRepository(project, virtualFile)
                        ?: return@runReadAction null
                    val path = getRelativePath(Paths.get(repo.root.path), virtualFile)
                    Pair(repo, path)
                } ?: return@executeOnPooledThread showError("不是Git仓库", event, project)
    
                // Execute git command outside read action
                executeGitRemove(project, repository, relativePath)
            } catch (e: Exception) {
                showError("从Git缓存移除失败: ${e.message}", event, project)
            }
        }
    }

    private fun showError(message: String, event: AnActionEvent? = null, project: Project? = event?.project) {
        ApplicationManager.getApplication().invokeLater {
            project?.let {
                NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP_ID)
                    .createNotification(message, NotificationType.ERROR)
                    .notify(it)
            }
        }
    }

    private fun showInfo(message: String, project: Project) {
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID)
                .createNotification(message, NotificationType.INFORMATION)
                .notify(project)
        }
    }

    override fun update(event: AnActionEvent) {
        val project = event.project
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE)

        // 使用 ActionUpdateThread.BGT 来处理后台线程操作
        event.presentation.isEnabledAndVisible = project != null && file != null
    }

    private fun executeGitRemove(project: Project, repository: GitRepository, path: String) {
        val handler = GitLineHandler(project, repository.root, GitCommand.RM).apply {
            addParameters("-r", "--cached", path)
            setSilent(false)
            setStdoutSuppressed(false)
        }

        val result = Git.getInstance().runCommand(handler)

        if (result.success()) {
            repository.update()
            showInfo("已从Git缓存移除: $path", project)
        } else {
            showError("Git命令执行失败: ${result.errorOutputAsJoinedString}", project = project)
        }
    }

    private fun findGitRepository(project: Project, file: VirtualFile?): GitRepository? {
        return ApplicationManager.getApplication().runReadAction<GitRepository?> {
            file?.let { GitUtil.getRepositoryManager(project).getRepositoryForFile(it) }
        }
    }

    private fun getRelativePath(repoRoot: Path, file: VirtualFile): String {
        return repoRoot.relativize(Paths.get(file.path)).toString()
    }
}