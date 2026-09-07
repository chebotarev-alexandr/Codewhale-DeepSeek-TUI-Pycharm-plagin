package com.chebotarev.deepseekplugin

import java.util.regex.Pattern

/**
 * Turns the agent's markdown output into simple HTML for display in a JTextPane,
 * with lightweight syntax highlighting for fenced code blocks.
 *
 * Strategy: extract code blocks into placeholders first, HTML-escape + format
 * the remaining prose, then substitute the placeholders with pre-highlighted
 * code HTML (already escaped). This avoids double-escaping the generated tags.
 */
object MarkdownRenderer {

    private val fence = Pattern.compile("```(\\w*)\\n?(.*?)```", Pattern.DOTALL)

    fun render(markdown: String): String {
        // 1. Pull out code blocks, keep a list of their HTML.
        val codeHtml = mutableListOf<String>()
        val sb = StringBuilder()
        val m = fence.matcher(markdown)
        var last = 0
        while (m.find()) {
            sb.append(markdown, last, m.start())
            val lang = m.group(1).lowercase()
            val code = m.group(2)
            codeHtml.add(
                "<div style=\"background-color:#1e1e1e;color:#e8e8e8;padding:8px;margin:0;\">" +
                    highlight(code, lang).replace("\n", "<br>") + "</div>"
            )
            sb.append("\u0000CODEBLOCK\u0000") // placeholder
            last = m.end()
        }
        sb.append(markdown.substring(last))

        // 2. HTML-escape the prose (placeholders contain no & < > so they survive).
        var html = sb.toString()
        html = html.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        // 3. Inline formatting on escaped prose. Explicit colors everywhere so
        // text stays visible on the fixed dark panel regardless of IDE theme.
        html = html.replace(Regex("`([^`]+)`"), "<code style=\"color:#e6a23c;\">$1</code>")
        html = html.replace(Regex("\\*\\*([^*]+)\\*\\*"), "<b>$1</b>")
        html = html.replace(Regex("(?m)^### (.+)$"), "<h4 style=\"color:#e8e8e8;\">$1</h4>")
        html = html.replace(Regex("(?m)^## (.+)$"), "<h3 style=\"color:#e8e8e8;\">$1</h3>")
        html = html.replace(Regex("(?m)^# (.+)$"), "<h2 style=\"color:#e8e8e8;\">$1</h2>")
        html = html.replace("\n", "<br>")

        // 4. Substitute code blocks back in.
        var i = 0
        html = Regex("\u0000CODEBLOCK\u0000").replace(html) { _ ->
            if (i < codeHtml.size) codeHtml[i++] else ""
        }

        // Return a bare HTML fragment — the caller wraps it in <html><body>.
        return html
    }

    /**
     * Renders a standalone diff/patch (e.g. a `file_change` item) with the same
     * line coloring as fenced ```diff blocks. Returns a bare HTML fragment.
     */
    fun renderDiff(code: String): String =
        "<div style=\"background-color:#1e1e1e;color:#e8e8e8;padding:8px;margin:0;\">" +
            highlightDiff(code).replace("\n", "<br>") + "</div>"

    /** Minimal keyword/string/comment/number highlighting for common languages. */
    private fun highlight(code: String, lang: String): String {
        if (lang == "diff" || lang == "patch") return highlightDiff(code)

        var escaped = code
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        val keywords = when (lang) {
            "python", "py" -> setOf(
                "def", "class", "return", "if", "elif", "else", "for", "while",
                "import", "from", "as", "try", "except", "finally", "with",
                "lambda", "True", "False", "None", "self", "not", "in", "is",
                "and", "or", "pass", "raise", "yield", "async", "await",
            )
            "javascript", "js", "typescript", "ts" -> setOf(
                "const", "let", "var", "function", "return", "if", "else",
                "for", "while", "import", "export", "from", "async", "await",
                "new", "class", "this", "true", "false", "null", "undefined",
            )
            else -> setOf(
                "def", "class", "return", "if", "else", "for", "while",
                "import", "from", "const", "let", "var", "function", "async",
                "await", "try", "except", "finally", "with", "new", "this",
            )
        }

        // Strings (double then single quotes)
        escaped = escaped.replace(Regex("(\")([^\"\\n]*?)(\")"), "<span style=\"color:#6a9955;\">\"$2\"</span>")
        escaped = escaped.replace(Regex("(')([^'\\n]*?)(')"), "<span style=\"color:#6a9955;\">'$2'</span>")
        // Comments (# python, // js)
        escaped = escaped.replace(Regex("(#)([^\\n]*)"), "<span style=\"color:#808080;font-style:italic;\">#$2</span>")
        escaped = escaped.replace(Regex("(//)([^\\n]*)"), "<span style=\"color:#808080;font-style:italic;\">//$2</span>")
        // Numbers
        escaped = escaped.replace(Regex("\\b(\\d+(?:\\.\\d+)?)\\b"), "<span style=\"color:#b5cea8;\">$1</span>")
        // Keywords
        for (kw in keywords) {
            escaped = escaped.replace(Regex("\\b${Regex.escape(kw)}\\b"), "<span style=\"color:#569cd6;\">$kw</span>")
        }

        return escaped
    }

    /** Line-oriented highlighting for git diff / patch blocks. */
    private fun highlightDiff(code: String): String {
        val lines = code.split("\n")
        val sb = StringBuilder()
        for ((idx, line) in lines.withIndex()) {
            val esc = line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            val color = when {
                line.startsWith("+") && !line.startsWith("+++") -> "#6ccb6c"
                line.startsWith("-") && !line.startsWith("---") -> "#ff7b7b"
                line.startsWith("@@") -> "#569cd6"
                else -> "#e8e8e8"
            }
            sb.append("<span style=\"color:").append(color).append(";\">")
                .append(esc).append("</span>")
            if (idx < lines.size - 1) sb.append("\n")
        }
        return sb.toString()
    }
}
