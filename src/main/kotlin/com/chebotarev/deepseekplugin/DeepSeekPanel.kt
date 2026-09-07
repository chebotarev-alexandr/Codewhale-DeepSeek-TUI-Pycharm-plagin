package com.chebotarev.deepseekplugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
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
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JTextPane
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.View
import javax.swing.text.html.HTMLEditorKit

/**
 * Chat-style tool window: full conversation history as rounded bubbles (user
 * right / blue, agent left / dark), input pinned to the bottom with slash
 * commands, file attachment, and approval-gated tool execution.
 */
class DeepSeekPanel(private val project: Project) : JPanel(BorderLayout()) {

    private class ChatMessage(
        val role: String, // "user" or "assistant"
        val content: StringBuilder = StringBuilder(),
        val reasoning: StringBuilder = StringBuilder(),
        val attached: Boolean = false,
        val attachments: List<String> = emptyList(),
        var pane: JTextPane? = null,
        var bubble: JPanel? = null,
        var row: JPanel? = null,
    )

    private class SlashCommand(val name: String, val desc: String, val action: () -> Unit)

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
        toolTipText = "Твой запрос. Введи / для списка команд."
    }

    private val attachFile = JCheckBox("Attach open file", true)
    private val autoApprove = JCheckBox("Auto-approve", true)
    private val showThinking = JCheckBox("Show thinking", false)
    private val attachmentLabel = JLabel(" ").apply {
        foreground = Color(154, 164, 178)
        isVisible = false
    }

    private val messages = mutableListOf<ChatMessage>()
    private val pendingAttachments = mutableListOf<Pair<String, String>>() // name to content
    private var threadId: String? = null
    private var lastSeq: Long = 0
    private var slashPopup: JPopupMenu? = null

    private val rawLogFile: File =
        Paths.get(System.getProperty("user.home"), ".codewhale-events.log").toFile()

    private val slashCommands = listOf(
        SlashCommand("/clear", "Новый диалог") { clearConversation() },
        SlashCommand("/compact", "Сжать контекст") { compactConversation() },
        SlashCommand("/undo", "Откатить последний ход") { undoLast() },
        SlashCommand("/help", "Список команд") { showHelp() },
    )

    init {
        val attachButton = JButton("📎 файл").apply {
            toolTipText = "Прикрепить файл с диска (содержимое попадёт в контекст)"
            addActionListener { pickFile() }
        }

        val options = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            add(attachFile)
            add(autoApprove)
            add(showThinking)
            add(attachButton)
        }
        val promptRow = JPanel(BorderLayout(4, 0)).apply {
            isOpaque = false
            add(promptField, BorderLayout.CENTER)
            add(JButton(SendAction()), BorderLayout.EAST)
        }
        val input = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(8)
            add(options)
            add(attachmentLabel)
            add(promptRow)
        }

        background = panelBg
        add(scrollPane, BorderLayout.CENTER)
        add(input, BorderLayout.SOUTH)

        promptField.addActionListener { send() }
        promptField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = updateSlashPopup()
            override fun removeUpdate(e: DocumentEvent?) = updateSlashPopup()
            override fun changedUpdate(e: DocumentEvent?) = updateSlashPopup()
        })

        autoApprove.addActionListener {
            val tid = threadId ?: return@addActionListener
            ApplicationManager.getApplication().executeOnPooledThread {
                runCatching { client.patchAutoApprove(tid, autoApprove.isSelected) }
            }
        }

        scrollPane.viewport.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) = reflowAll()
        })
    }

    // ---- sizing ----

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
        if (msg.role == "user") {
            val sb = StringBuilder()
            sb.append("<b>Вы</b>")
            if (msg.attached) sb.append(" <span style=\"font-size:9pt;color:#cfe0ff;\">📎</span>")
            sb.append("<br>").append(escape(msg.content.toString()).replace("\n", "<br>"))
            if (msg.attachments.isNotEmpty()) {
                sb.append("<br><span style=\"font-size:9pt;color:#cfe0ff;\">📎 ")
                    .append(msg.attachments.joinToString(", ") { escape(it) })
                    .append("</span>")
            }
            return sb.toString()
        }
        val sb = StringBuilder()
        sb.append("<b style=\"color:#9aa4b2;\">DeepSeek</b><br>")
        if (showThinking.isSelected && msg.reasoning.isNotEmpty()) {
            sb.append("<div style=\"color:#888888;font-size:10pt;\">💭 ")
                .append(escape(msg.reasoning.toString()))
                .append("</div><br>")
        }
        sb.append(MarkdownRenderer.render(msg.content.toString()))
        return sb.toString()
    }

    private fun applyPaneContent(msg: ChatMessage) {
        val pane = msg.pane ?: return
        val textColor = if (msg.role == "user") "#ffffff" else "#e8e8e8"
        pane.text = "<html><body style=\"font-family:SansSerif;font-size:12pt;color:$textColor;\">" +
            messageBodyHtml(msg) + "</body></html>"

        val ins = pane.insets
        val root = pane.ui.getRootView(pane)
        root.setSize(100000f, 100000f)
        val naturalW = root.getPreferredSpan(View.X_AXIS).toInt().coerceAtLeast(1)
        val maxContentW = (bubbleMaxWidth() - ins.left - ins.right).coerceAtLeast(60)
        val contentW = minOf(naturalW, maxContentW)
        root.setSize(contentW.toFloat(), 100000f)
        val contentH = root.getPreferredSpan(View.Y_AXIS).toInt().coerceAtLeast(1)

        val totalW = contentW + ins.left + ins.right
        val totalH = contentH + ins.top + ins.bottom
        pane.preferredSize = Dimension(totalW, totalH)
        pane.maximumSize = Dimension(totalW, Int.MAX_VALUE)

        msg.bubble?.let {
            it.maximumSize = Dimension(it.preferredSize.width, Int.MAX_VALUE)
            it.revalidate()
        }
        msg.row?.let {
            it.maximumSize = Dimension(Int.MAX_VALUE, it.preferredSize.height)
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

        return object : JPanel(BorderLayout()) {
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
            maximumSize = Dimension(preferredSize.width, Int.MAX_VALUE)
        }
    }

    private fun addMessageRow(msg: ChatMessage) {
        val bubble = buildBubble(msg)
        msg.bubble = bubble
        val row = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(bubble, if (msg.role == "user") BorderLayout.EAST else BorderLayout.WEST)
        }
        msg.row = row
        row.maximumSize = Dimension(Int.MAX_VALUE, row.preferredSize.height)
        chat.add(row)
        chat.add(Box.createVerticalStrut(6))
        chat.revalidate()
        chat.repaint()
    }

    private fun addSystemNote(text: String) {
        val label = JLabel("<html><span style=\"color:#888888;\">$text</span></html>")
        label.alignmentX = Component.LEFT_ALIGNMENT
        chat.add(label)
        chat.add(Box.createVerticalStrut(6))
        chat.revalidate()
        chat.repaint()
    }

    private fun rebuildChat() {
        chat.removeAll()
        for (msg in messages) addMessageRow(msg)
        chat.revalidate()
        chat.repaint()
    }

    private fun reflowAll() {
        for (msg in messages) applyPaneContent(msg)
        chat.revalidate()
        chat.repaint()
    }

    private fun refreshMessage(msg: ChatMessage) {
        SwingUtilities.invokeLater {
            applyPaneContent(msg)
            chat.revalidate()
            chat.repaint()
            SwingUtilities.invokeLater {
                val bar = scrollPane.verticalScrollBar
                bar.value = bar.maximum
            }
        }
    }

    // ---- slash commands ----

    private fun updateSlashPopup() {
        val text = promptField.text
        val show = text.startsWith("/") && !text.contains(" ") && !text.contains("\n")
        if (!show) {
            slashPopup?.isVisible = false
            return
        }
        val query = text.substring(1)
        val matches = slashCommands.filter { query.isEmpty() || it.name.startsWith("/" + query) }
        if (matches.isEmpty()) {
            slashPopup?.isVisible = false
            return
        }
        val popup = JPopupMenu()
        for (cmd in matches) {
            val item = JMenuItem(cmd.name + "  —  " + cmd.desc)
            item.addActionListener {
                promptField.text = cmd.name + " "
                slashPopup?.isVisible = false
            }
            popup.add(item)
        }
        slashPopup = popup
        popup.show(promptField, 0, promptField.height)
    }

    private fun clearConversation() {
        threadId = null
        lastSeq = 0
        messages.clear()
        rebuildChat()
    }

    private fun compactConversation() {
        val tid = threadId ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { client.compactThread(tid) }
            SwingUtilities.invokeLater { addSystemNote("Контекст сжат.") }
        }
    }

    private fun undoLast() {
        val tid = threadId ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching {
                val newId = client.undoThread(tid)
                SwingUtilities.invokeLater {
                    if (newId != null) threadId = newId
                    while (messages.isNotEmpty() && messages.last().role == "assistant") {
                        messages.removeAt(messages.size - 1)
                    }
                    if (messages.isNotEmpty() && messages.last().role == "user") {
                        messages.removeAt(messages.size - 1)
                    }
                    rebuildChat()
                    addSystemNote("Откатил последний ход.")
                }
            }
        }
    }

    private fun showHelp() {
        addSystemNote(
            "Команды: " + slashCommands.joinToString("  ") { it.name } +
                "  <br>📎 — прикрепить файл. Auto-approve вкл/выкл — подтверждение правок."
        )
    }

    // ---- file attachment ----

    private fun pickFile() {
        val chooser = JFileChooser()
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            val file = chooser.selectedFile
            runCatching {
                val content = file.readText()
                pendingAttachments.add(file.name to content)
                updateAttachmentLabel()
            }.onFailure {
                addSystemNote("Не удалось прочитать файл: ${it.message}")
            }
        }
    }

    private fun updateAttachmentLabel() {
        if (pendingAttachments.isEmpty()) {
            attachmentLabel.isVisible = false
        } else {
            attachmentLabel.text = "📎 " + pendingAttachments.joinToString(", ") { it.first }
            attachmentLabel.isVisible = true
        }
    }

    // ---- approvals ----

    private fun addApprovalRow(approvalId: String, desc: String) {
        val label = JLabel("<html><span style=\"color:#e6a23c;\">🔒 Разрешить?</span><br>" +
            "<span style=\"color:#e8e8e8;\">" + escape(desc) + "</span></html>")
        val allow = JButton("Разрешить")
        val allowAll = JButton("Разрешить всё")
        val deny = JButton("Запретить")

        val bar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
            isOpaque = true
            background = Color(44, 40, 28)
        }
        bar.add(allow)
        bar.add(allowAll)
        bar.add(deny)

        val row = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }
        row.add(label)
        row.add(bar)
        row.maximumSize = Dimension(Int.MAX_VALUE, row.preferredSize.height)

        fun resolve(decision: String, remember: Boolean) {
            allow.isEnabled = false
            allowAll.isEnabled = false
            deny.isEnabled = false
            label.text = "<html><span style=\"color:#9aa4b2;\">⏳ Отправляю решение…</span></html>"
            ApplicationManager.getApplication().executeOnPooledThread {
                val result = runCatching { client.resolveApproval(approvalId, decision, remember) }
                SwingUtilities.invokeLater {
                    result.onSuccess {
                        label.text = if (decision == "allow") {
                            "<html><span style=\"color:#6ccb6c;\">✓ Разрешено</span></html>"
                        } else {
                            "<html><span style=\"color:#ff7b7b;\">✗ Запрещено</span></html>"
                        }
                    }.onFailure { err ->
                        label.text = "<html><span style=\"color:#ff7b7b;\">⚠ Ошибка: " +
                            escape(err.message ?: "неизвестно") + "</span></html>"
                    }
                }
            }
        }
        allow.addActionListener { resolve("allow", false) }
        allowAll.addActionListener { resolve("allow", true) }
        deny.addActionListener { resolve("deny", false) }

        chat.add(row)
        chat.add(Box.createVerticalStrut(6))
        chat.revalidate()
        chat.repaint()
        SwingUtilities.invokeLater {
            val barV = scrollPane.verticalScrollBar
            barV.value = barV.maximum
        }
    }

    // ---- send ----

    private fun send() {
        val prompt = promptField.text.trim()
        if (prompt.isEmpty()) return

        val cmd = slashCommands.find { it.name == prompt }
        if (cmd != null) {
            cmd.action()
            promptField.text = ""
            return
        }

        val attachedNames = pendingAttachments.map { it.first }
        val userText = buildUserPrompt(prompt)
        val fullPrompt = contextProvider.buildPrompt(userText, attachFile.isSelected)
        val workspace = project.basePath
        pendingAttachments.clear()
        updateAttachmentLabel()

        val userMsg = ChatMessage(
            "user", StringBuilder(prompt),
            attached = attachFile.isSelected,
            attachments = attachedNames,
        )
        messages.add(userMsg)
        addMessageRow(userMsg)

        val assistantMsg = ChatMessage("assistant")
        messages.add(assistantMsg)
        addMessageRow(assistantMsg)

        promptField.text = ""
        SwingUtilities.invokeLater {
            val bar = scrollPane.verticalScrollBar
            bar.value = bar.maximum
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            setBusy(true)
            try {
                if (!client.isServerUp()) {
                    assistantMsg.content.append(
                        "⚠ Codewhale server not running. Start: `codewhale app-server --http --insecure-no-auth`"
                    )
                    refreshMessage(assistantMsg)
                    return@executeOnPooledThread
                }
                if (threadId == null) {
                    threadId = client.createThread(
                        model = null,
                        workspace = workspace,
                        autoApprove = autoApprove.isSelected,
                    )
                }
                val tid = threadId!!
                client.sendTurn(tid, fullPrompt)
                lastSeq = client.streamEvents(
                    threadId = tid,
                    sinceSeq = lastSeq,
                    onAnswerDelta = { delta ->
                        assistantMsg.content.append(delta)
                        refreshMessage(assistantMsg)
                    },
                    onReasoningDelta = { delta ->
                        assistantMsg.reasoning.append(delta)
                        if (showThinking.isSelected) refreshMessage(assistantMsg)
                    },
                    onApprovalRequired = { id, desc ->
                        SwingUtilities.invokeLater { addApprovalRow(id, desc) }
                    },
                    onEvent = { event -> logRaw(event) },
                )
                refreshMessage(assistantMsg)
            } catch (e: Exception) {
                assistantMsg.content.append("\n\n⚠ Error: " + (e.message ?: ""))
                refreshMessage(assistantMsg)
            } finally {
                setBusy(false)
            }
        }
    }

    private fun buildUserPrompt(prompt: String): String {
        if (pendingAttachments.isEmpty()) return prompt
        val sb = StringBuilder(prompt)
        for ((name, content) in pendingAttachments) {
            sb.append("\n\n=== Attached file: ").append(name).append(" ===\n```\n")
                .append(content).append("\n```")
        }
        return sb.toString()
    }

    private inner class SendAction : AbstractAction("SEND") {
        override fun actionPerformed(e: ActionEvent?) = send()
    }
}
