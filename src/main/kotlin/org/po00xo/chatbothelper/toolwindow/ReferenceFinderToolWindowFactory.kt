package org.po00xo.chatbothelper.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import org.po00xo.chatbothelper.toolwindow.ReferenceFinderState
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingUtilities

class ReferenceFinderToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {

        val preview = JTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = Font(Font.MONOSPACED, Font.PLAIN, 13)
            border = BorderFactory.createEmptyBorder(6, 6, 6, 6)
        }

        val status = JLabel(" ").apply {
            foreground = JBColor.GRAY
            border = BorderFactory.createEmptyBorder(2, 6, 2, 6)
        }

        val panel = JPanel(BorderLayout()).apply {
            add(status, BorderLayout.NORTH)
            add(JBScrollPane(preview), BorderLayout.CENTER)
        }

        val state = ReferenceFinderState.getInstance(project)

        // Initial render: replay whatever the service already holds.
        // (survives tool-window close/reopen).
        if (state.hasCopied) {
            applyText(preview, status, state.lastText)
        } else {
            showPlaceholder(preview, status)
        }

        val content = ContentFactory.getInstance()
            .createContent(panel, "Available References", false)
        toolWindow.contentManager.addContent(content)

        // Live updates: every Ctrl+Alt+Q press replaces the preview.
        //
        // NOTE: ToolWindow itself is no longer Disposable in current
        // IntelliJ Platform builds. Content however owns a Disposable
        // whose lifetime matches the tool window's open/close cycle,
        // which is exactly what we want here — one subscription per
        // time the tool window is shown.
        val parentDisposable = content.disposer ?: project
        project.messageBus
            .connect(parentDisposable)
            .subscribe(
                ReferenceFinderState.REFERENCES_COPIED,
                ReferenceFinderState.Listener { text ->
                    SwingUtilities.invokeLater { applyText(preview, status, text) }
                }
            )
    }

    private fun applyText(area: JTextArea, status: JLabel, text: String) {
        if (text.isEmpty()) {
            showPlaceholder(area, status)
            return
        }
        area.text = text
        area.caretPosition = 0                 // jump to top so user sees the start
        status.text = "Copied to clipboard — ${text.length} characters"
    }

    private fun showPlaceholder(area: JTextArea, status: JLabel) {
        area.text = PLACEHOLDER
        area.caretPosition = 0
        status.text = "Waiting for Ctrl+Alt+Q…"
    }

    private companion object {
        const val PLACEHOLDER =
            "Nothing copied yet.\n\n" +
                    "Press Ctrl+Alt+Q.\n" +
                    "The overview that goes to your clipboard will appear here."
    }
}