package org.po00xo.chatbothelper.toolwindow

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic

@Service(Service.Level.PROJECT)
class ReferenceFinderState(private val project: Project) {

    fun interface Listener {
        fun onReferencesCopied(text: String)
    }

    @Volatile
    var lastText: String = ""
        private set

    @Volatile
    var hasCopied: Boolean = false
        private set

    /**
     * What the tool window should display right now.
     * Before the first keybind press this is the hint.
     * After the first press it is whatever was copied.
     */
    val displayText: String
        get() = if (hasCopied) lastText else KEYBIND_HINT

    /** Called by the action after it copies to clipboard. */
    fun publish(text: String) {
        lastText = text
        hasCopied = true
        project.messageBus
            .syncPublisher(REFERENCES_COPIED)
            .onReferencesCopied(text)
    }

    companion object {
        val REFERENCES_COPIED: Topic<Listener> =
            Topic.create("REFERENCE_FINDER_REFERENCES_COPIED", Listener::class.java)

        const val KEYBIND_HINT =
            "press ctrl alt q on an unresolved red symbol\n" +
                    "the exact text copied to your clipboard shows here"

        fun getInstance(project: Project): ReferenceFinderState = project.service()
    }
}