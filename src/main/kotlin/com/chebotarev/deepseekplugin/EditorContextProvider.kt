package com.chebotarev.deepseekplugin

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Reads the currently open editor file and the selected text so the plugin can
 * pass them as context. Selection wins over whole-file when non-empty.
 */
class EditorContextProvider(private val project: Project) {

    private val editor: Editor?
        get() = FileEditorManager.getInstance(project).selectedTextEditor

    fun currentFile(): VirtualFile? =
        FileEditorManager.getInstance(project).selectedFiles.firstOrNull()

    fun selectedText(): String? =
        editor?.selectionModel?.selectedText?.takeIf { it.isNotBlank() }

    fun fullFileText(file: VirtualFile): String? =
        runCatching { String(file.contentsToByteArray(), Charsets.UTF_8) }.getOrNull()

    /**
     * Build the message to send: user prompt + optional file/selection context.
     */
    fun buildPrompt(userPrompt: String, includeOpenFile: Boolean): String {
        if (!includeOpenFile) return userPrompt

        val file = currentFile()
        val selection = selectedText()

        val sb = StringBuilder()
        sb.append(userPrompt).append("\n\n")

        if (!selection.isNullOrBlank()) {
            sb.append("=== Selected text in ${file?.name ?: "editor"} ===\n")
            sb.append("```\n").append(selection).append("\n```\n")
        } else if (file != null) {
            val text = fullFileText(file)
            if (text != null) {
                sb.append("=== Open file: ${file.name} (${file.path}) ===\n")
                sb.append("```\n").append(text).append("\n```\n")
            }
        }
        return sb.toString()
    }
}
