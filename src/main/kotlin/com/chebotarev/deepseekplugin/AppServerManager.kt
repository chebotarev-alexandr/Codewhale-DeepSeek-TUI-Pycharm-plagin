package com.chebotarev.deepseekplugin

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.openapi.application.ApplicationManager
import java.io.File

/**
 * Manages the lifecycle of the local Codewhale `app-server` process.
 *
 * The plugin never talks to a remote model itself; it needs the
 * `codewhale app-server --http` process on 127.0.0.1:7878. This class finds
 * the binary, starts it as a child process, tracks its state, and stops it —
 * but only when *we* started it. A server that was already running before the
 * plugin came up is treated as EXTERNAL and left untouched.
 */
class AppServerManager(private val client: DeepSeekClient) {

    enum class State { STOPPED, STARTING, RUNNING, EXTERNAL }

    @Volatile
    var state: State = State.STOPPED
        private set

    /** Human-readable reason the server is stopped (e.g. binary not found). */
    @Volatile
    var errorMessage: String? = null
        private set

    /** Invoked on the EDT whenever [state] changes. */
    var onStateChanged: (() -> Unit)? = null

    private var processHandler: OSProcessHandler? = null

    fun isRunning(): Boolean = state == State.RUNNING || state == State.EXTERNAL

    private fun setState(next: State) {
        if (state == next) return
        state = next
        ApplicationManager.getApplication().invokeLater { onStateChanged?.invoke() }
    }

    /** Detect the current state without starting anything (called at init). */
    fun refresh() {
        ApplicationManager.getApplication().executeOnPooledThread {
            if (processHandler?.isProcessTerminated == false) {
                setState(State.RUNNING)
            } else if (client.isServerUp()) {
                setState(State.EXTERNAL)
            } else {
                setState(State.STOPPED)
            }
        }
    }

    /** Start the server (async, from the UI). */
    fun start() {
        if (isRunning() || state == State.STARTING) return
        ApplicationManager.getApplication().executeOnPooledThread { ensureRunning() }
    }

    /**
     * Synchronously make sure a server is listening. Starts one if needed and
     * blocks until it is healthy or [timeoutMs] elapses. Returns true when the
     * server is reachable. Safe to call from a pooled thread (it does network
     * I/O and process launch); never call it on the EDT.
     */
    fun ensureRunning(timeoutMs: Long = 20_000): Boolean {
        if (client.isServerUp()) {
            setState(
                if (processHandler?.isProcessTerminated == false) State.RUNNING
                else State.EXTERNAL
            )
            return true
        }
        if (state == State.STARTING) return waitUntilHealthy(processHandler, timeoutMs)

        errorMessage = null
        val bin = resolveBinary()
        if (bin == null) {
            errorMessage =
                "Не найден бинарь codewhale. Установи: curl -fsSL https://codewhale.net/install.sh | sh"
            setState(State.STOPPED)
            return false
        }

        setState(State.STARTING)
        return runCatching {
            val cmd = GeneralCommandLine(
                bin.absolutePath, "app-server", "--http", "--insecure-no-auth",
            )
            val handler = OSProcessHandler(cmd)
            processHandler = handler
            handler.addProcessListener(object : ProcessAdapter() {
                override fun processTerminated(event: ProcessEvent) {
                    processHandler = null
                    setState(State.STOPPED)
                }
            })
            handler.startNotify()
            if (waitUntilHealthy(handler, timeoutMs)) {
                setState(State.RUNNING)
                true
            } else {
                errorMessage = "Сервер не поднялся (health-check не прошёл за ${timeoutMs / 1000}с)."
                handler.destroyProcess()
                processHandler = null
                setState(State.STOPPED)
                false
            }
        }.getOrElse {
            processHandler = null
            errorMessage = "Не удалось запустить сервер: " + (it.message ?: "неизвестно")
            setState(State.STOPPED)
            false
        }
    }

    /** Stop the server — only the one we started ourselves. */
    fun stop() {
        val h = processHandler ?: return
        if (state != State.RUNNING && state != State.STARTING) return
        h.destroyProcess()
        processHandler = null
        setState(State.STOPPED)
    }

    private fun waitUntilHealthy(handler: OSProcessHandler?, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (handler?.isProcessTerminated == true) return false
            if (client.isServerUp()) return true
            Thread.sleep(300)
        }
        return false
    }

    private fun resolveBinary(): File? {
        System.getenv("CODEWHALE_BIN")?.takeIf { it.isNotBlank() }?.let {
            val f = File(it)
            if (f.isFile && f.canExecute()) return f
        }
        val home = System.getProperty("user.home")
        val candidates = listOf(
            "codewhale",
            "codew",
            "$home/.local/bin/codewhale",
            "$home/.local/bin/codew",
            "/opt/homebrew/bin/codewhale",
            "/usr/local/bin/codewhale",
            "/usr/local/bin/codew",
        )
        for (c in candidates) {
            val f = findExecutable(c)
            if (f != null) return f
        }
        return null
    }

    private fun findExecutable(nameOrPath: String): File? {
        val direct = File(nameOrPath)
        if (direct.isFile && direct.canExecute()) return direct
        val path = System.getenv("PATH") ?: return null
        for (dir in path.split(File.pathSeparator)) {
            if (dir.isBlank()) continue
            val candidate = File(dir, nameOrPath)
            if (candidate.isFile && candidate.canExecute()) return candidate
        }
        return null
    }
}
