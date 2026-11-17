package com.chebotarev.deepseekplugin

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.spellchecker.SpellCheckerManager
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.event.ActionEvent
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import javax.swing.AbstractAction
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JTextPane
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.Highlighter
import javax.swing.text.JTextComponent
import javax.swing.text.DefaultEditorKit
import javax.swing.text.View
import javax.swing.text.html.HTMLEditorKit
import javax.swing.plaf.TextUI

/**
 * Chat-style tool window: full conversation history as rounded bubbles (user
 * right / blue, agent left / dark), input pinned to the bottom with slash
 * commands, file attachment, and approval-gated tool execution.
 */
class DeepSeekPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private class ChatMessage(
        val role: String, // "user" or "assistant"
        val content: StringBuilder = StringBuilder(),
        val reasoning: StringBuilder = StringBuilder(),
        val attached: Boolean = false,
        val attachments: List<String> = emptyList(),
        var pane: JTextPane? = null,
        var bubble: JPanel? = null,
        var row: JPanel? = null,
        var refreshPending: Boolean = false,
    )

    private class SlashCommand(val name: String, val desc: String, val action: () -> Unit)

    /**
     * Multiline, auto-growing chat input. Pasted code wraps and expands the
     * field (up to [maxRows], then the surrounding scroll pane scrolls), and a
     * basic spell check underlines misspelled words. Enter sends, Shift+Enter
     * inserts a line break.
     */
    private class ChatInputField(
        private val project: Project,
        private val hint: String,
    ) : JBTextArea(1, 40) {

        var onSend: (() -> Unit)? = null

        private val maxRows = 4
        private val rowHeight: Int = getFontMetrics(font).height + 2
        private val spellHighlights = mutableListOf<Any>()

        private val spellPainter = object : Highlighter.HighlightPainter {
            override fun paint(g: Graphics, p0: Int, p1: Int, shape: Shape, c: JTextComponent) {
                val rect = shape.bounds
                if (rect.height <= 0 || rect.width <= 0) return
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = Color(255, 100, 100)
                val bottom = rect.y + rect.height - 1
                var x = rect.x
                val step = 2
                var up = true
                while (x < rect.x + rect.width) {
                    val y1 = if (up) bottom else bottom - 1
                    val y2 = if (up) bottom - 1 else bottom
                    g2.drawLine(x, y1, x + step, y2)
                    x += step
                    up = !up
                }
                g2.dispose()
            }
        }

        init {
            isOpaque = false
            background = Color(0, 0, 0, 0)
            border = null
            lineWrap = true
            wrapStyleWord = true
            foreground = Color(232, 232, 232)
            caretColor = Color(232, 232, 232)
            selectionColor = Color(64, 128, 200)
            selectedTextColor = Color(255, 255, 255)
            margin = Insets(4, 6, 4, 0)

            // Enter sends; Shift+Enter inserts a line break. The default
            // "insert-break" binding is keyed to plain Enter, so rebind both.
            inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "SEND_MESSAGE")
            inputMap.put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK),
                DefaultEditorKit.insertBreakAction,
            )
            actionMap.put("SEND_MESSAGE", object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) { onSend?.invoke() }
            })

            document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent?) = updateHeight()
                override fun removeUpdate(e: DocumentEvent?) = updateHeight()
                override fun changedUpdate(e: DocumentEvent?) = updateHeight()
            })
        }

        override fun paintComponent(g: Graphics?) {
            super.paintComponent(g)
            if (text.isEmpty() && !isFocusOwner) {
                val g2 = g!!.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = Color(120, 124, 132)
                g2.drawString(hint, insets.left + 2, insets.top + g2.fontMetrics.ascent)
                g2.dispose()
            }
        }

        /** Grow/shrink to the wrapped content, capped at [maxRows]. */
        private fun updateHeight() {
            SwingUtilities.invokeLater {
                val target = if (text.isEmpty()) {
                    rowHeight + insets.top + insets.bottom
                } else {
                    val root = (getUI() as TextUI).getRootView(this)
                    root.setSize(width.coerceAtLeast(1).toFloat(), 100000f)
                    val needed = root.getPreferredSpan(View.Y_AXIS).toInt().coerceAtLeast(rowHeight)
                    val rows = ((needed + rowHeight - 1) / rowHeight).coerceIn(1, maxRows)
                    rows * rowHeight + insets.top + insets.bottom
                }
                if (preferredSize.height != target) {
                    preferredSize = Dimension(preferredSize.width, target)
                    revalidate()
                }
                runSpellCheck()
            }
        }

        private fun runSpellCheck() {
            val highlighter = highlighter
            spellHighlights.forEach { highlighter.removeHighlight(it) }
            spellHighlights.clear()
            val manager = runCatching { SpellCheckerManager.getInstance(project) }.getOrNull() ?: return
            val t = text
            if (t.isEmpty()) return
            for (m in Regex("\\p{L}+").findAll(t)) {
                val word = m.value
                if (!isCheckable(word)) continue
                if (manager.hasProblem(word)) {
                    runCatching {
                        spellHighlights.add(
                            highlighter.addHighlight(m.range.first, m.range.last + 1, spellPainter)
                        )
                    }
                }
            }
        }

        private fun isCheckable(word: String): Boolean {
            if (word.length <= 2) return false
            if (word.all { it.isUpperCase() }) return false
            // Skip mixed-case identifiers / proper nouns (PyCharm, DeepSeek, camelCase).
            if (word.drop(1).any { it.isUpperCase() }) return false
            return true
        }
    }

    private val contextProvider = EditorContextProvider(project)
    private val client = DeepSeekClient()

    private val server = AppServerManager(client)

    private val serverDot = object : JPanel() {
        init {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            preferredSize = Dimension(16, 20)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent?) {
                    if (server.state == AppServerManager.State.EXTERNAL) return
                    if (server.isRunning()) server.stop() else server.start()
                }
            })
        }

        override fun paintComponent(g: Graphics?) {
            super.paintComponent(g)
            val g2 = g!!.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = when (server.state) {
                AppServerManager.State.RUNNING, AppServerManager.State.EXTERNAL -> Color(108, 203, 108)
                AppServerManager.State.STARTING -> Color(230, 162, 60)
                AppServerManager.State.STOPPED ->
                    if (server.errorMessage != null) Color(255, 123, 123) else Color(120, 124, 132)
            }
            val d = 10
            g2.fillOval((width - d) / 2, (height - d) / 2, d, d)
            g2.dispose()
        }
    }

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

    private val promptField = ChatInputField(project, "Спроси агента или введи / …").apply {
        toolTipText = "Enter — отправить, Shift+Enter — новая строка. Введи / для команд."
    }

    private var attachFileEnabled = true
    private var autoApproveEnabled = true
    private var showThinkingEnabled = false
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
        val attachButton = JButton("📎").apply {
            toolTipText = "Прикрепить файл с диска (содержимое попадёт в контекст)"
            isOpaque = false
            setContentAreaFilled(false)
            border = null
            foreground = Color(154, 164, 178)
            isFocusable = false
            addActionListener { pickFile() }
        }

        val settingsButton = JButton("⚙").apply {
            toolTipText = "Настройки"
            isOpaque = false
            setContentAreaFilled(false)
            border = null
            foreground = Color(154, 164, 178)
            isFocusable = false
            font = font.deriveFont(18f)
        }
        settingsButton.addActionListener { showSettingsPopup(settingsButton) }

        val inputBox = object : JPanel(BorderLayout(0, 0)) {
            override fun paintComponent(g: Graphics?) {
                super.paintComponent(g)
                val g2 = g!!.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = panelBg
                g2.fillRoundRect(0, 0, width, height, 20, 20)
                g2.color = Color(60, 60, 68)
                g2.stroke = BasicStroke(1f)
                g2.drawRoundRect(0, 0, width - 1, height - 1, 20, 20)
                g2.dispose()
            }
        }.apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 10, 2, 10)
            add(attachButton, BorderLayout.WEST)
            add(promptField, BorderLayout.CENTER)
        }

        val sendButton = object : JButton(SendAction()) {
            override fun paintComponent(g: Graphics?) {
                val g2 = g!!.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = userBg
                g2.fillRoundRect(0, 0, width, height, 16, 16)
                g2.dispose()
                super.paintComponent(g)
            }
        }.apply {
            isOpaque = false
            setContentAreaFilled(false)
            border = JBUI.Borders.empty(6, 14, 6, 14)
            foreground = Color(255, 255, 255)
            isFocusable = false
        }

        val rightButtons = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            isOpaque = false
            add(settingsButton)
            add(sendButton)
        }
        val right = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(rightButtons, BorderLayout.SOUTH)
        }

        val promptRow = JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            add(serverDot, BorderLayout.WEST)
            add(inputBox, BorderLayout.CENTER)
            add(right, BorderLayout.EAST)
        }
        val input = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(8)
            add(promptRow)
            add(attachmentLabel)
        }

        background = panelBg
        add(scrollPane, BorderLayout.CENTER)
        add(input, BorderLayout.SOUTH)

        promptField.onSend = { send() }
        promptField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = updateSlashPopup()
            override fun removeUpdate(e: DocumentEvent?) = updateSlashPopup()
            override fun changedUpdate(e: DocumentEvent?) = updateSlashPopup()
        })

        server.onStateChanged = { updateServerUI() }
        server.refresh()

        scrollPane.viewport.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) = reflowAll()
        })
    }

    // ---- sizing ----

    private fun bubbleMaxWidth(): Int {
        val vw = scrollPane.viewport.width
        // Nearly the full viewport width (minus a small margin) so medium
        // messages fit on one line instead of wrapping their last word.
        return if (vw > 120) (vw - 12).coerceIn(200, 760) else 480
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

    private fun showSettingsPopup(anchor: JComponent) {
        val labels = listOf(
            (if (attachFileEnabled) "✓  " else "     ") + "Attach open file",
            (if (autoApproveEnabled) "✓  " else "     ") + "Auto-approve",
            (if (showThinkingEnabled) "✓  " else "     ") + "Show thinking",
        )
        val popup = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(labels)
            .setItemChosenCallback { chosen ->
                when (labels.indexOf(chosen)) {
                    0 -> attachFileEnabled = !attachFileEnabled
                    1 -> {
                        autoApproveEnabled = !autoApproveEnabled
                        onAutoApproveChanged()
                    }
                    2 -> {
                        showThinkingEnabled = !showThinkingEnabled
                        reflowAll()
                    }
                }
            }
            .createPopup()
        popup.showUnderneathOf(anchor)
    }

    private fun onAutoApproveChanged() {
        val tid = threadId ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { client.patchAutoApprove(tid, autoApproveEnabled) }
        }
    }

    private fun updateServerUI() {
        val err = server.errorMessage
        serverDot.toolTipText = when (server.state) {
            AppServerManager.State.STOPPED ->
                err ?: "Сервер не запущен — нажми, чтобы запустить"
            AppServerManager.State.STARTING -> "Запуск сервера…"
            AppServerManager.State.RUNNING ->
                "Сервер запущен (127.0.0.1:7878) — нажми, чтобы остановить"
            AppServerManager.State.EXTERNAL -> "Сервер запущен вне плагина"
        }
        serverDot.repaint()
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
        if (showThinkingEnabled && msg.reasoning.isNotEmpty()) {
            sb.append(reasoningHtml(msg))
        }
        sb.append(MarkdownRenderer.render(msg.content.toString()))
        return sb.toString()
    }

    /**
     * Renders the reasoning buffer as HTML, capped to a trailing window.
     * Reasoning models stream tens of thousands of tokens of "thinking": the
     * buffer can grow to hundreds of KB. Re-rendering the whole thing on every
     * delta makes a single frame cost seconds and saturates the EDT, which is
     * the freeze seen when "Show thinking" is on. Capping to a tail keeps each
     * frame cheap while still showing the latest thinking.
     */
    private fun reasoningHtml(msg: ChatMessage): String {
        val text = msg.reasoning.toString()
        if (text.isEmpty()) return ""
        val maxShown = 8000
        val shown = if (text.length > maxShown) {
            "… (скрыто " + (text.length - maxShown) + " симв., показан хвост)\n" +
                text.substring(text.length - maxShown)
        } else {
            text
        }
        return "<div style=\"color:#888888;font-size:10pt;\">💭 " +
            escape(shown).replace("\n", "<br>") + "</div><br>"
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
        val contentW = minOf(naturalW + 8, maxContentW)
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
        // Re-measure once the pane is in the live tree: the first measurement
        // (before layout) under-sizes the bubble and wraps text too early.
        SwingUtilities.invokeLater {
            applyPaneContent(msg)
            chat.revalidate()
            chat.repaint()
        }
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
        // Coalesce stream deltas: only one pending repaint per message. The
        // stream callback fires per token (thousands per turn); without this
        // every reasoning delta queues a full HTML re-render on the EDT, the
        // queue grows faster than the EDT drains it, and the IDE freezes.
        if (msg.refreshPending) return
        msg.refreshPending = true
        SwingUtilities.invokeLater {
            msg.refreshPending = false
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

    // ---- file changes ----

    private fun addFileChangeRow(path: String?, detail: String?, summary: String?) {
        SwingUtilities.invokeLater {
            val title = path ?: (summary ?: "изменение файла")
            val body = detail ?: ""
            val html = "<b style=\"color:#e6a23c;\">✎ " + escape(title) + "</b><br>" +
                (if (body.isNotEmpty()) MarkdownRenderer.renderDiff(body) else "")

            val pane = JTextPane().apply {
                editorKit = HTMLEditorKit()
                isEditable = false
                isOpaque = false
                margin = JBUI.insets(2)
            }
            pane.text = "<html><body style=\"font-family:SansSerif;font-size:11pt;color:#e8e8e8;\">" +
                html + "</body></html>"

            val ins = pane.insets
            val root = pane.ui.getRootView(pane)
            root.setSize(100000f, 100000f)
            val naturalW = root.getPreferredSpan(View.X_AXIS).toInt().coerceAtLeast(1)
            val maxContentW = (bubbleMaxWidth() - ins.left - ins.right).coerceAtLeast(60)
            val contentW = minOf(naturalW + 8, maxContentW)
            root.setSize(contentW.toFloat(), 100000f)
            val contentH = root.getPreferredSpan(View.Y_AXIS).toInt().coerceAtLeast(1)
            pane.preferredSize = Dimension(contentW + ins.left + ins.right, contentH + ins.top + ins.bottom)
            pane.maximumSize = Dimension(pane.preferredSize.width, Int.MAX_VALUE)

            val bar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { isOpaque = false }
            if (path != null) {
                val open = JButton("Открыть файл")
                open.addActionListener { openFile(path) }
                bar.add(open)
            }

            val bubble = object : JPanel(BorderLayout()) {
                override fun paintComponent(g: Graphics?) {
                    val g2 = g!!.create() as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = Color(40, 44, 54)
                    g2.fillRoundRect(0, 0, width, height, 16, 16)
                    g2.dispose()
                }
            }.apply {
                isOpaque = false
                border = JBUI.Borders.empty(8, 12, 8, 12)
                add(pane, BorderLayout.CENTER)
                add(bar, BorderLayout.SOUTH)
                maximumSize = Dimension(preferredSize.width, Int.MAX_VALUE)
            }

            val row = JPanel(BorderLayout()).apply {
                isOpaque = false
                add(bubble, BorderLayout.WEST)
            }
            row.maximumSize = Dimension(Int.MAX_VALUE, row.preferredSize.height)

            chat.add(row)
            chat.add(Box.createVerticalStrut(6))
            chat.revalidate()
            chat.repaint()
            SwingUtilities.invokeLater {
                val barV = scrollPane.verticalScrollBar
                barV.value = barV.maximum
            }
        }
    }

    private fun openFile(path: String) {
        val base = project.basePath
        val file: File? = when {
            File(path).isAbsolute -> File(path)
            base != null -> File(base, path)
            else -> null
        }
        if (file == null || !file.exists()) {
            addSystemNote("Файл не найден: " + path)
            return
        }
        ApplicationManager.getApplication().invokeLater {
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
            if (vf != null) FileEditorManager.getInstance(project).openFile(vf, true)
            else addSystemNote("Не удалось открыть: " + path)
        }
    }

    // ---- approvals ----

    private fun addApprovalRow(approvalId: String, desc: String) {
        val html = "<b style=\"color:#e6a23c;\">🔒 Разрешить?</b><br>" +
            "<span style=\"color:#e8e8e8;\">" + escape(desc) + "</span>"

        // Wrapping text pane, measured like the message bubbles so the
        // description wraps at the bubble width instead of overflowing.
        val pane = JTextPane().apply {
            editorKit = HTMLEditorKit()
            isEditable = false
            isOpaque = false
            margin = JBUI.insets(2)
        }
        pane.text = "<html><body style=\"font-family:SansSerif;font-size:12pt;color:#e8e8e8;\">" +
            html + "</body></html>"

        val ins = pane.insets
        val root = pane.ui.getRootView(pane)
        root.setSize(100000f, 100000f)
        val naturalW = root.getPreferredSpan(View.X_AXIS).toInt().coerceAtLeast(1)
        val maxContentW = (bubbleMaxWidth() - ins.left - ins.right).coerceAtLeast(60)
        val contentW = minOf(naturalW + 8, maxContentW)
        root.setSize(contentW.toFloat(), 100000f)
        val contentH = root.getPreferredSpan(View.Y_AXIS).toInt().coerceAtLeast(1)
        pane.preferredSize = Dimension(contentW + ins.left + ins.right, contentH + ins.top + ins.bottom)
        pane.maximumSize = Dimension(pane.preferredSize.width, Int.MAX_VALUE)

        val allow = JButton("Разрешить")
        val allowAll = JButton("Разрешить всё")
        val deny = JButton("Запретить")

        val bar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            add(allow)
            add(allowAll)
            add(deny)
        }

        val bubble = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics?) {
                val g2 = g!!.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = Color(44, 40, 28)
                g2.fillRoundRect(0, 0, width, height, 16, 16)
                g2.dispose()
            }
        }.apply {
            isOpaque = false
            border = JBUI.Borders.empty(8, 12, 8, 12)
            add(pane, BorderLayout.CENTER)
            add(bar, BorderLayout.SOUTH)
            maximumSize = Dimension(preferredSize.width, Int.MAX_VALUE)
        }

        val row = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(bubble, BorderLayout.WEST)
        }
        row.maximumSize = Dimension(Int.MAX_VALUE, row.preferredSize.height)

        fun resolve(decision: String, remember: Boolean) {
            allow.isEnabled = false
            allowAll.isEnabled = false
            deny.isEnabled = false
            val color = if (decision == "allow") "#6ccb6c" else "#ff7b7b"
            val mark = if (decision == "allow") "✓ Разрешено" else "✗ Запрещено"
            pane.text = "<html><body style=\"font-family:SansSerif;font-size:12pt;color:$color;\">" +
                escape(mark) + "</body></html>"
            ApplicationManager.getApplication().executeOnPooledThread {
                val result = runCatching { client.resolveApproval(approvalId, decision, remember) }
                SwingUtilities.invokeLater {
                    result.onFailure { err ->
                        pane.text = "<html><body style=\"font-family:SansSerif;font-size:12pt;color:#ff7b7b;\">" +
                            escape("⚠ Ошибка: " + (err.message ?: "неизвестно")) + "</body></html>"
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

    // ---- user input dialogs ----

    /**
     * Renders a `request_user_input` prompt as an answerable bubble instead of
     * raw JSON: each question shows its options as clickable buttons, plus a
     * free-text field and a skip button. Answers are POSTed to
     * /v1/user-input/{thread}/{input_id} and resume the paused turn.
     */
    private fun addUserInputRow(inputId: String, request: JsonObject) {
        SwingUtilities.invokeLater {
            val questions = runCatching { request.getAsJsonArray("questions") }.getOrNull()
            if (questions == null || questions.isEmpty()) {
                addSystemNote("⚠ Диалог с нестандартным форматом: " + request.toString().take(400))
                return@invokeLater
            }
            val n = questions.size()
            val qids = Array(n) { i -> questions.get(i).asJsonObject.get("id")?.asString ?: "q$i" }
            val multi = BooleanArray(n) { i ->
                questions.get(i).asJsonObject.get("multi_select")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
            }
            val selected = Array(n) { mutableListOf<String>() }
            val freeText = arrayOfNulls<String>(n)
            val skipped = BooleanArray(n)

            fun answerJson(kind: String, qid: String, label: String, value: String): JsonObject {
                val o = JsonObject()
                o.addProperty("kind", kind)
                o.addProperty("id", qid)
                o.addProperty("label", label)
                o.addProperty("value", value)
                return o
            }

            val statusPane = JTextPane().apply {
                editorKit = HTMLEditorKit()
                isEditable = false
                isOpaque = false
                margin = JBUI.insets(2)
            }
            lateinit var controls: JPanel

            var submitted = false
            fun submit() {
                if (submitted) return
                submitted = true
                val arr = JsonArray()
                for (i in 0 until n) {
                    when {
                        skipped[i] -> arr.add(answerJson("skipped", qids[i], "", ""))
                        freeText[i] != null -> arr.add(answerJson("custom", qids[i], "", freeText[i]!!))
                        selected[i].isNotEmpty() -> selected[i].forEach {
                            arr.add(answerJson("choice", qids[i], it, it))
                        }
                        else -> arr.add(answerJson("skipped", qids[i], "", ""))
                    }
                }
                val tid = threadId
                if (tid == null) {
                    statusPane.text = "<html><body style=\"color:#ff7b7b;\">⚠ Нет активного диалога.</body></html>"
                    return
                }
                controls.isEnabled = false
                statusPane.text = "<html><body style=\"color:#e6a23c;\">Отправка…</body></html>"
                ApplicationManager.getApplication().executeOnPooledThread {
                    val result = runCatching { client.resolveUserInput(tid, inputId, arr) }
                    SwingUtilities.invokeLater {
                        statusPane.text = if (result.isSuccess) {
                            "<html><body style=\"color:#6ccb6c;\">✓ Отвечено</body></html>"
                        } else {
                            "<html><body style=\"color:#ff7b7b;\">⚠ Ошибка: " +
                                (result.exceptionOrNull()?.message ?: "неизвестно") + "</body></html>"
                        }
                    }
                }
            }

            val body = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
            }

            for (i in 0 until n) {
                val q = questions.get(i).asJsonObject
                val header = q.get("header")?.takeIf { it.isJsonPrimitive }?.asString
                val questionText = q.get("question")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
                val detail = q.get("detail")?.takeIf { it.isJsonPrimitive }?.asString
                val options = q.getAsJsonArray("options")

                val html = StringBuilder()
                if (!header.isNullOrBlank()) html.append("<b style=\"color:#9aa4b2;\">").append(escape(header)).append("</b><br>")
                html.append("<span style=\"color:#e8e8e8;\">").append(escape(questionText)).append("</span>")
                if (!detail.isNullOrBlank()) {
                    html.append("<br><span style=\"color:#9aa4b2;font-size:10pt;\">").append(escape(detail)).append("</span>")
                }
                val qPane = JTextPane().apply {
                    editorKit = HTMLEditorKit()
                    isEditable = false
                    isOpaque = false
                    margin = JBUI.insets(2)
                    text = "<html><body style=\"font-family:SansSerif;font-size:12pt;\">$html</body></html>"
                }
                body.add(qPane)

                val optionBar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
                    isOpaque = false
                    alignmentX = Component.LEFT_ALIGNMENT
                }
                if (options != null) {
                    for (j in 0 until options.size()) {
                        val opt = options.get(j).asJsonObject
                        val label = opt.get("label")?.asString ?: continue
                        val desc = opt.get("description")?.takeIf { it.isJsonPrimitive }?.asString
                        val btn = JButton(label).apply {
                            toolTipText = desc
                            isFocusable = false
                        }
                        btn.addActionListener {
                            if (multi[i]) {
                                if (label in selected[i]) selected[i].remove(label) else selected[i].add(label)
                                btn.text = if (label in selected[i]) "✓ $label" else label
                            } else {
                                selected[i].clear()
                                selected[i].add(label)
                                if (n == 1) submit()
                            }
                        }
                        optionBar.add(btn)
                    }
                }
                body.add(optionBar)

                val freeRow = JPanel(BorderLayout(6, 0)).apply {
                    isOpaque = false
                    alignmentX = Component.LEFT_ALIGNMENT
                }
                val freeField = JTextField(24).apply { isOpaque = false }
                val freeBtn = JButton("Ответить").apply { isFocusable = false }
                val skipBtn = JButton("Пропустить").apply { isFocusable = false }
                freeBtn.addActionListener {
                    val v = freeField.text.trim()
                    if (v.isEmpty()) return@addActionListener
                    freeText[i] = v
                    if (n == 1) submit()
                }
                skipBtn.addActionListener {
                    skipped[i] = true
                    if (n == 1) submit()
                }
                freeRow.add(freeField, BorderLayout.CENTER)
                val right = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { isOpaque = false }
                right.add(freeBtn)
                right.add(skipBtn)
                freeRow.add(right, BorderLayout.EAST)
                body.add(freeRow)

                body.add(Box.createVerticalStrut(8))
            }

            controls = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(body)
                if (n > 1) {
                    val confirm = JButton("Отправить ответ").apply { alignmentX = Component.LEFT_ALIGNMENT; isFocusable = false }
                    confirm.addActionListener { submit() }
                    add(confirm)
                }
                add(statusPane)
            }

            val bubble = object : JPanel(BorderLayout()) {
                override fun paintComponent(g: Graphics?) {
                    val g2 = g!!.create() as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = Color(44, 44, 52)
                    g2.fillRoundRect(0, 0, width, height, 16, 16)
                    g2.dispose()
                }
            }.apply {
                isOpaque = false
                border = JBUI.Borders.empty(8, 12, 8, 12)
                add(controls, BorderLayout.CENTER)
            }

            val row = JPanel(BorderLayout()).apply {
                isOpaque = false
                add(bubble, BorderLayout.WEST)
            }
            row.maximumSize = Dimension(Int.MAX_VALUE, row.preferredSize.height)

            chat.add(row)
            chat.add(Box.createVerticalStrut(6))
            chat.revalidate()
            chat.repaint()
            SwingUtilities.invokeLater {
                val bar = scrollPane.verticalScrollBar
                bar.value = bar.maximum
            }
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
        val fullPrompt = contextProvider.buildPrompt(userText, attachFileEnabled)
        val workspace = project.basePath
        pendingAttachments.clear()
        updateAttachmentLabel()

        val userMsg = ChatMessage(
            "user", StringBuilder(prompt),
            attached = attachFileEnabled,
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
                if (!server.ensureRunning()) {
                    assistantMsg.content.append("⚠ Не удалось запустить Codewhale server.")
                    server.errorMessage?.let { assistantMsg.content.append("\n").append(it) }
                    refreshMessage(assistantMsg)
                    return@executeOnPooledThread
                }
                if (threadId == null) {
                    threadId = client.createThread(
                        model = null,
                        workspace = workspace,
                        autoApprove = autoApproveEnabled,
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
                        if (showThinkingEnabled) refreshMessage(assistantMsg)
                    },
                    onApprovalRequired = { id, desc ->
                        SwingUtilities.invokeLater { addApprovalRow(id, desc) }
                    },
                    onFileChange = { path, detail, summary ->
                        addFileChangeRow(path, detail, summary)
                    },
                    onUserInputRequired = { inputId, request ->
                        addUserInputRow(inputId, request)
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

    override fun dispose() {
        server.stop()
    }
}
