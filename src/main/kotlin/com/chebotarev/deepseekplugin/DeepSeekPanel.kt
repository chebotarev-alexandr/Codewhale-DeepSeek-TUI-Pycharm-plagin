package com.chebotarev.deepseekplugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.ActionEvent
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import javax.swing.AbstractAction
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextPane
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.text.html.HTMLEditorKit

/**
 * Chat-style tool window: full conversation history as rounded bubbles (user
 * right / blue, agent left / dark), input pinned to the bottom, autoscroll.
 */
class DeepSeekPanel(private val project: Project) : JPanel(BorderLayout()) {

    private class ChatMessage(
        val role: String, // "user" or "assistant"
        val content: StringBuilder = StringBuilder(),
        val reasoning: StringBuilder = StringBuilder(),
        val attached: Boolean = false,
        var pane: JTextPane? = null,
        var bubble: JPanel? = null,
    )

    private val contextProvider = EditorContextProvider(project)
    private val client = DeepSeekClient()

    private val panelBg = Color(30, 30, 30)
    private val userBg = Color(37, 99, 235)
    private val agentBg = Color(44, 44, 44)

    private val chat = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = true
        background = panelBg
    }

    private val scrollPane = JBScrollPane(chat).apply {
        border = null
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        viewport.background = panelBg
    }

    private val promptField = JTextField().apply {
        toolTipText = "Твой запрос. Открытый файл / выделение прикрепляется как контекст."
    }

    private val attachFile = JCheckBox("Attach open file", true)
    private val showThinking = JCheckBox("Show thinking", false)

    private val messages = mutableListOf<ChatMessage>()
    private var threadId: String? = null
    private var lastSeq: Long = 0

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
        background = panelBg
        add(scrollPane, BorderLayout.CENTER)
        add(input, BorderLayout.SOUTH)

        promptField.addActionListener { send() }

        scrollPane.viewport.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) = reflowAll()
        })
    }

    private fun bubbleMaxWidth(): Int {
        val vw = scrollPane.viewport.width
        return if (vw > 120) (vw * 0.78).toInt().coerceIn(260, 720) else 480
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

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

    private fun setBusy(busy: Boolean) {
        SwingUtilities.invokeLater { promptField.isEnabled = !busy }
    }

    private fun messageBodyHtml(msg: ChatMessage): String {
        return if (msg.role == "user") {
            "<b>Вы</b><br>" + escape(msg.content.toString()).replace("\n", "<br>")
        } else {
            val sb = StringBuilder()
            sb.append("<b style=\"color:#9aa4b2;\">DeepSeek</b><br>")
            if (showThinking.isSelected && msg.reasoning.isNotEmpty()) {
                sb.append("<div style=\"color:#888888;font-size:10pt;\">💭 ")
                    .append(escape(msg.reasoning.toString()))
                    .append("</div><br>")
            }
            sb.append(MarkdownRenderer.render(msg.content.toString()))
            sb.toString()
        }
    }

    /** Re-measure a message pane so the bubble wraps at max width (and is
     *  narrow for short text), then repaint. */
    private fun applyPaneContent(msg: ChatMessage) {
        val pane = msg.pane ?: return
        val textColor = if (msg.role == "user") "#ffffff" else "#e8e8e8"
        pane.text = "<html><body style=\"font-family:SansSerif;font-size:12pt;color:$textColor;\">" +
            messageBodyHtml(msg) + "</body></html>"

        // Natural (unwrapped) width first.
        pane.setSize(100000, Int.MAX_VALUE)
        val naturalW = pane.preferredSize.width.coerceAtLeast(1)
        val w = minOf(naturalW, bubbleMaxWidth())
        // Now wrap at w to get the real height.
        pane.setSize(w, Int.MAX_VALUE)
        val h = pane.preferredSize.height
        pane.preferredSize = Dimension(w, h)
        pane.maximumSize = Dimension(w, h)

        msg.bubble?.let {
            it.maximumSize = it.preferredSize
            it.revalidate()
        }
    }

    private fun buildBubble(msg: ChatMessage): JPanel {
        val role = msg.role
        val pane = JTextPane().apply {
            editorKit = HTMLEditorKit()
            isEditable = false
            isOpaque = false
            margin = JBUI.insets(2)
        }
        msg.pane = pane
        applyPaneContent(msg)

        val bubble = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics?) {
                val g2 = g!!.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = if (role == "user") userBg else agentBg
                g2.fillRoundRect(0, 0, width, height, 18, 18)
                g2.dispose()
            }
        }.apply {
            isOpaque = false
            border = JBUI.Borders.empty(8, 12, 8, 12)
            add(pane, BorderLayout.CENTER)
            maximumSize = preferredSize
        }
        return bubble
    }

    private fun addMessageRow(msg: ChatMessage) {
        val bubble = buildBubble(msg)
        msg.bubble = bubble

        val row = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            if (msg.role == "user") {
                add(Box.createHorizontalGlue())
                add(bubble)
            } else {
                add(bubble)
                add(Box.createHorizontalGlue())
            }
        }
        row.maximumSize = Dimension(Int.MAX_VALUE, row.preferredSize.height)
        row.alignmentX = Component.LEFT_ALIGNMENT

        chat.add(row)
        chat.add(Box.createVerticalStrut(6))
        chat.revalidate()
        chat.repaint()
    }

    private fun reflowAll() {
        for (msg in messages) applyPaneContent(msg)
        chat.revalidate()
        chat.repaint()
    }

    private fun scrollToBottom() {
        SwingUtilities.invokeLater {
            val bar = scrollPane.verticalScrollBar
            bar.value = bar.maximum
        }
    }

    private fun send() {
        val prompt = promptField.text.trim()
        if (prompt.isEmpty()) return

        val fullPrompt = contextProvider.buildPrompt(prompt, attachFile.isSelected)
        val workspace = project.basePath

        val userMsg = ChatMessage("user", StringBuilder(prompt), attached = attachFile.isSelected)
        messages.add(userMsg)
        addMessageRow(userMsg)

        val assistantMsg = ChatMessage("assistant")
        messages.add(assistantMsg)
        addMessageRow(assistantMsg)

        promptField.text = ""
        scrollToBottom()

        ApplicationManager.getApplication().executeOnPooledThread {
            setBusy(true)
            try {
                if (!client.isServerUp()) {
                    assistantMsg.content.append(
                        "⚠ Codewhale server not running. Start: `codewhale app-server --http --insecure-no-auth`"
                    )
                    applyPaneContent(assistantMsg)
                    chat.revalidate()
                    chat.repaint()
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
                        applyPaneContent(assistantMsg)
                        chat.revalidate()
                        chat.repaint()
                        scrollToBottom()
                    },
                    onReasoningDelta = { delta ->
                        assistantMsg.reasoning.append(delta)
                        if (showThinking.isSelected) {
                            applyPaneContent(assistantMsg)
                            chat.revalidate()
                            chat.repaint()
                            scrollToBottom()
                        }
                    },
                    onEvent = { event -> logRaw(event) },
                )
                applyPaneContent(assistantMsg)
                chat.revalidate()
                chat.repaint()
            } catch (e: Exception) {
                assistantMsg.content.append("\n\n⚠ Error: " + (e.message ?: ""))
                applyPaneContent(assistantMsg)
                chat.revalidate()
                chat.repaint()
            } finally {
                setBusy(false)
                scrollToBottom()
            }
        }
    }

    private inner class SendAction : AbstractAction("Send") {
        override fun actionPerformed(e: ActionEvent?) = send()
    }
}
