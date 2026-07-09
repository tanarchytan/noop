package com.noop.bp

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * EXPERIMENTAL (blood-pressure RE): a tiny line-oriented command server over a LOCAL (unix abstract)
 * socket, so a PC can drive the bonded strap through adb without rebuilding/reinstalling per experiment:
 *
 *   adb forward tcp:8899 localabstract:noop_bp_cmd
 *   printf 'status\n' | nc 127.0.0.1 8899
 *
 * It is NOT a network socket (no INTERNET exposure) — only reachable over USB/adb. Each line is one
 * command; the reply is text, terminated by a line "<<<END". Commands are handled by [onCommand] (wired
 * to the BLE client). Read-only-ish: the client side blocks known-destructive opcodes.
 */
class BpCommandServer(private val onCommand: (String) -> String) {
    companion object {
        const val NAME = "noop_bp_cmd"
        private const val TAG = "BpCmdServer"
    }

    @Volatile private var running = false
    private var serverSocket: LocalServerSocket? = null
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "bp-cmd-server").apply { isDaemon = true; start() }
        Log.i(TAG, "started on localabstract:$NAME")
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun loop() {
        while (running) {
            try {
                val ss = LocalServerSocket(NAME).also { serverSocket = it }
                while (running) {
                    val client = ss.accept()
                    handle(client)
                }
            } catch (t: Throwable) {
                if (running) { Log.w(TAG, "server loop error: ${t.message}"); Thread.sleep(500) }
            }
        }
    }

    private fun handle(client: LocalSocket) {
        client.use { sock ->
            val reader = BufferedReader(InputStreamReader(sock.inputStream))
            val out = sock.outputStream
            while (running) {
                val line = reader.readLine() ?: break
                val reply = runCatching { onCommand(line) }.getOrElse { "ERROR: ${it.message}" }
                out.write(reply.toByteArray())
                out.write("\n<<<END\n".toByteArray())
                out.flush()
            }
        }
    }
}
