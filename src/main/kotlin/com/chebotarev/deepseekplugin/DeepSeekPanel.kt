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
import javax.swing.JTextPane
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.text.html.HTMLEditorKit

/**
 * The plugin's tool-window UI: a prompt field, an optional "attach open file"
 * checkbox, a send button, and a read-only output pane that renders the
 * streaming reply (markdown + syntax-highlighted code).
 */
class DeepSeekPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val contextProvider = EditorContextProvider(project)
    private val client = DeepSeekClient()

    private val output = JTextPane().apply {
        isEditable = false
        editorKit = HTMLEditorKit()
        margin = JBUI.insets(8)
    }

    private val promptField = JTextField().apply {
        toolTipText = "Your request. The open file / selection is attached below."
    }

    private val attachFile = JCheckBox("Attach open file / selection", true)
    private var threadId: String? = null
    private var lastSeq: Long = 0

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

    private fun setHtml(text: String) {
        SwingUtilities.invokeLater { output.text = text }
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
        val workspace = project.basePath

        ApplicationManager.getApplication().executeOnPooledThread {
            setBusy(true)
            try {
                if (!client.isServerUp()) {
                    setHtml("<html><body>⚠ <b>Codewhale server not running.</b><br>" +
                        "Start it with: <code>codewhale app-server --http --insecure-no-auth</code>" +
                        "</body></html>")
                    return@executeOnPooledThread
                }

                if (threadId == null) {
                    threadId = client.createThread(model = null, workspace = workspace)
                }
                val tid = threadId!!

                // Accumulate the raw markdown of this turn, render on the fly.
                val markdown = StringBuilder()
                val render = {
                    setHtml(
                        "<html><body style=\"font-family:SansSerif;font-size:12pt;\">" +
                            "<b>Вы:</b> " + escape(prompt) + "<br><br>" +
                            "<b>DeepSeek:</b><br><br>" +
                            MarkdownRenderer.render(markdown.toString()) +
                            "</body></html>"
                    )
                }

                client.sendTurn(tid, fullPrompt)
                lastSeq = client.streamEvents(
                    threadId = tid,
                    sinceSeq = lastSeq,
                    onDelta = { delta ->
                        markdown.append(delta)
                        render()
                    },
                    onEvent = { /* raw event, ignored in MVP */ },
                )
                render()
            } catch (e: Exception) {
                setHtml("<html><body>⚠ <b>Error:</b> ${escape(e.message ?: "")}</body></html>")
            } finally {
                setBusy(false)
            }
        }
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private inner class SendAction : AbstractAction("Send") {
        override fun actionPerformed(e: ActionEvent?) = send()
    }
}
