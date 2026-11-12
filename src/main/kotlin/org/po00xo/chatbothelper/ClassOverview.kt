package org.po00xo.chatbothelper

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class ClassOverview : StartupActivity {

    private val logger: Logger = LoggerFactory.getLogger(ClassOverview::class.java)

    override fun runActivity(project: Project) {
        logger.info("Chatbot Helper plugin starting up for project: {}", project.name)

        try {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Chatbot Helper")
                .createNotification(
                    "Chatbot Helper - press Ctrl+Alt+Q to copy class overview",
                    NotificationType.INFORMATION
                )
                .notify(project)

            logger.info("Chatbot Helper startup notification displayed successfully")
        } catch (ex: Exception) {
            logger.error("Chatbot Helper startup notification failed", ex)
        }
    }
}