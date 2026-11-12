package org.po00xo.chatbothelper

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class ProjectActivity : StartupActivity {


    override fun runActivity(project: Project) {
        try {
            val actionManager = ActionManager.getInstance()

            val logger: Logger = LoggerFactory.getLogger(ProjectActivity::class.java)


            val action = actionManager.getAction("chatbot-helper.CopyAllErrors")


            if (action != null) {
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("chatbot-helper")
                    .createNotification(
                        "chatbot-helper plugin ready",
                        NotificationType.INFORMATION
                    )
                    .notify(project)
            }
        } catch (e: Exception) {
            // Silent fail
        }    }

}