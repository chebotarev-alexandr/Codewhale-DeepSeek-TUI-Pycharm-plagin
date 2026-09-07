package com.chebotarev.deepseekplugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingUtilities

/**
 * The plugin's tool-window UI: a prompt field, an optional "attach open file"
 * checkbox, a send button, and a read-only output area that streams the reply.
 */
class DeepSeekPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val contextProvider = EditorContextProvider(project)
    private val client = DeepSeekClient()

    private val output = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        margin = JBUI.insets(8)
    }

    private val promptField = JTextField().apply {
        toolTipText = "Your request. The open file / selection is attached below."
    }

    private val attachFile = JCheckBox("Attach open file / selection", true)
    private var threadId: String? = null

    init {
        val top = JPanel(BorderLayout(4, 4)).apply {
            border = JBUI.Borders.empty(8)
            add(promptField, BorderLayout.CENTER)
        }
        val controls = JPanel(BorderLayout()).apply {
            add(attachFile, BorderLayout.WEST)
            add(JButton(SendAction()), BorderLayout.EAST)
        }
        val north = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
            add(top, BorderLayout.CENTER)
            add(controls, BorderLayout.SOUTH)
        }
        add(north, BorderLayout.NORTH)
        add(JBScrollPane(output), BorderLayout.CENTER)

        promptField.addActionListener { send() }
    }

    private fun append(text: String) {
        SwingUtilities.invokeLater { output.append(text) }
    }

    private fun setBusy(busy: Boolean) {
        SwingUtilities.invokeLater { promptField.isEnabled = !busy }
    }

    private fun send() {
        val prompt = promptField.text.trim()
        if (prompt.isEmpty()) return

        // Build the context on the EDT (this method runs from a button/Enter),
        // because reading the editor selection/file requires EDT or a read action.
        val fullPrompt = contextProvider.buildPrompt(prompt, attachFile.isSelected)

        ApplicationManager.getApplication().executeOnPooledThread {
            setBusy(true)
            try {
                if (!client.isServerUp()) {
                    append("\n⚠ Codewhale server not running.\n" +
                        "Start it with:  codewhale app-server --http --insecure-no-auth\n\n")
                    return@executeOnPooledThread
                }

                if (threadId == null) {
                    threadId = client.createThread(model = null)
                }
                val tid = threadId!!

                append("\n— You —\n$prompt\n\n— DeepSeek —\n")

                client.sendTurn(tid, fullPrompt)
                client.streamEvents(
                    threadId = tid,
                    onDelta = { append(it) },
                    onEvent = { /* raw event, ignored in MVP */ },
                )
                // The stream closes when the turn completes; then send the next turn.
                append("\n")
            } catch (e: Exception) {
                append("\n⚠ Error: ${e.message}\n\n")
            } finally {
                setBusy(false)
            }
        }
    }

    private inner class SendAction : AbstractAction("Send") {
        override fun actionPerformed(e: ActionEvent?) = send()
    }
}
