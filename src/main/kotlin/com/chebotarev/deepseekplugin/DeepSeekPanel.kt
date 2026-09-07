package com.chebotarev.deepseekplugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
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
 * Chat-style tool window: the full conversation history is rendered as bubbles
 * (user on the right, agent on the left), with the input field pinned to the
 * bottom like a messenger.
 */
class DeepSeekPanel(private val project: Project) : JPanel(BorderLayout()) {

    private class ChatMessage(
        val role: String, // "user" or "assistant"
        val content: StringBuilder = StringBuilder(),
        val reasoning: StringBuilder = StringBuilder(),
        val attached: Boolean = false,
    )

    private val contextProvider = EditorContextProvider(project)
    private val client = DeepSeekClient()

    private val output = JTextPane().apply {
        isEditable = false
        editorKit = HTMLEditorKit()
        margin = JBUI.insets(8)
        // Fixed dark palette so text is visible regardless of the IDE theme.
        background = Color(30, 30, 30)
        foreground = Color(232, 232, 232)
        caretColor = Color(232, 232, 232)
    }

    private val promptField = JTextField().apply {
        toolTipText = "Твой запрос. Открытый файл / выделение прикрепляется как контекст."
    }

    private val attachFile = JCheckBox("Attach open file", true)
    private val showThinking = JCheckBox("Show thinking", false)

    private val messages = mutableListOf<ChatMessage>()
    private var threadId: String? = null
    private var lastSeq: Long = 0

    // Raw SSE events are appended here for debugging if the panel output ever
    // needs diagnosis again.
    private val rawLogFile: File =
        Paths.get(System.getProperty("user.home"), ".codewhale-events.log").toFile()

    init {
        val options = JPanel(BorderLayout()).apply {
            val west = JPanel()
            west.add(attachFile)
            west.add(showThinking)
            add(west, BorderLayout.WEST)
        }
        val input = JPanel(BorderLayout(4, 4)).apply {
            border = JBUI.Borders.empty(8)
            add(options, BorderLayout.NORTH)
            add(promptField, BorderLayout.CENTER)
            add(JButton(SendAction()), BorderLayout.EAST)
        }
        add(JBScrollPane(output), BorderLayout.CENTER)
        add(input, BorderLayout.SOUTH)

        promptField.addActionListener { send() }
        renderAll()
    }

    private fun setHtml(text: String) {
        SwingUtilities.invokeLater {
            output.text = text
            // Scroll to the latest message.
            output.caretPosition = output.document.length
        }
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

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun renderAll() {
        val sb = StringBuilder()
        sb.append("<html><body style=\"font-family:SansSerif;font-size:12pt;color:#e8e8e8;margin:4px;\">")
        if (messages.isEmpty()) {
            sb.append("<div style=\"color:#888888;\">Введи запрос в поле ниже — ")
            sb.append("открытый файл прикрепится как контекст.</div>")
        }
        for (msg in messages) {
            if (msg.role == "user") {
                sb.append("<div style=\"background-color:#2563eb;color:#ffffff;padding:8px;margin:8px 0 8px 70px;\">")
                sb.append("<b>Вы</b>")
                if (msg.attached) sb.append(" <span style=\"font-size:9pt;color:#cfe0ff;\">📎</span>")
                sb.append("<br>").append(escape(msg.content.toString()))
                sb.append("</div>")
            } else {
                sb.append("<div style=\"background-color:#2b2b2b;color:#e8e8e8;padding:8px;margin:8px 70px 8px 0;\">")
                sb.append("<b>DeepSeek</b><br>")
                if (showThinking.isSelected && msg.reasoning.isNotEmpty()) {
                    sb.append("<div style=\"color:#888888;font-size:10pt;\">💭 ")
                    sb.append(escape(msg.reasoning.toString()))
                    sb.append("</div><br>")
                }
                sb.append(MarkdownRenderer.render(msg.content.toString()))
                sb.append("</div>")
            }
        }
        sb.append("</body></html>")
        setHtml(sb.toString())
    }

    private fun send() {
        val prompt = promptField.text.trim()
        if (prompt.isEmpty()) return

        val fullPrompt = contextProvider.buildPrompt(prompt, attachFile.isSelected)
        val workspace = project.basePath

        messages.add(
            ChatMessage("user", StringBuilder(prompt), attached = attachFile.isSelected)
        )
        val assistantMsg = ChatMessage("assistant")
        messages.add(assistantMsg)
        promptField.text = ""
        renderAll()

        ApplicationManager.getApplication().executeOnPooledThread {
            setBusy(true)
            try {
                if (!client.isServerUp()) {
                    assistantMsg.content.append(
                        "⚠ Codewhale server not running. Start: `codewhale app-server --http --insecure-no-auth`"
                    )
                    renderAll()
                    return@executeOnPooledThread
                }

                if (threadId == null) {
                    threadId = client.createThread(model = null, workspace = workspace)
                }
                val tid = threadId!!

                client.sendTurn(tid, fullPrompt)
                lastSeq = client.streamEvents(
                    threadId = tid,
                    sinceSeq = lastSeq,
                    onAnswerDelta = { delta ->
                        assistantMsg.content.append(delta)
                        renderAll()
                    },
                    onReasoningDelta = { delta ->
                        assistantMsg.reasoning.append(delta)
                        if (showThinking.isSelected) renderAll()
                    },
                    onEvent = { event -> logRaw(event) },
                )
                renderAll()
            } catch (e: Exception) {
                assistantMsg.content.append("\n\n⚠ Error: " + (e.message ?: ""))
                renderAll()
            } finally {
                setBusy(false)
            }
        }
    }

    private inner class SendAction : AbstractAction("Send") {
        override fun actionPerformed(e: ActionEvent?) = send()
    }
}
