package com.chebotarev.deepseekplugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.ActionEvent
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
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
    private val showRaw = JCheckBox("Show raw events", false)
    private var threadId: String? = null
    private var lastSeq: Long = 0

    // Raw SSE events are appended here (and to a log file) so the user can
    // send them back for debugging without guessing at the event schema.
    private val rawLogFile: File =
        Paths.get(System.getProperty("user.home"), ".codewhale-events.log").toFile()

    init {
        val top = JPanel(BorderLayout(4, 4)).apply {
            border = JBUI.Borders.empty(8)
            add(promptField, BorderLayout.CENTER)
        }
        val controls = JPanel(BorderLayout()).apply {
            val west = JPanel(BorderLayout())
            west.add(attachFile, BorderLayout.NORTH)
            west.add(showRaw, BorderLayout.SOUTH)
            add(west, BorderLayout.WEST)
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

    private fun logRaw(event: String) {
        runCatching {
            Files.write(
                rawLogFile.toPath(),
                (event + "\n").toByteArray(),
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND,
            )
        }
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

                // Accumulate answer vs reasoning separately.
                val answer = StringBuilder()
                val reasoning = StringBuilder()
                val raw = StringBuilder()
                val render = {
                    val reasonHtml = if (reasoning.isNotEmpty()) {
                        "<div style=\"color:#888;font-size:10pt;\">" +
                            "<b>💭 Thinking:</b><br>" + escape(reasoning.toString()) +
                            "</div><br>"
                    } else ""
                    val rawHtml = if (showRaw.isSelected && raw.isNotEmpty()) {
                        "<hr><div style=\"color:#999;font-size:9pt;\">" +
                            "<b>Raw events:</b><br>" + escape(raw.toString()) +
                            "</div>"
                    } else ""
                    setHtml(
                        "<html><body style=\"font-family:SansSerif;font-size:12pt;\">" +
                            "<b>Вы:</b> " + escape(prompt) + "<br><br>" +
                            reasonHtml +
                            "<b>DeepSeek:</b><br><br>" +
                            MarkdownRenderer.render(answer.toString()) +
                            rawHtml +
                            "</body></html>"
                    )
                }

                client.sendTurn(tid, fullPrompt)
                lastSeq = client.streamEvents(
                    threadId = tid,
                    sinceSeq = lastSeq,
                    onAnswerDelta = { delta ->
                        answer.append(delta)
                        render()
                    },
                    onReasoningDelta = { delta ->
                        reasoning.append(delta)
                        render()
                    },
                    onEvent = { event ->
                        raw.append(event).append("\n")
                        logRaw(event)
                        if (showRaw.isSelected) render()
                    },
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
