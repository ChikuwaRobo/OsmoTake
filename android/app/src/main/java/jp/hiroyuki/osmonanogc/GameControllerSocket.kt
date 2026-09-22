package jp.hiroyuki.osmonanogc

import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class GameControllerSocket(
    private val serial: ScheduledExecutorService,
    private val listener: Listener,
) {
    interface Listener {
        fun onGcConnected()
        fun onGcDisconnected()
        fun onFreshMatchState(running: Boolean)
        fun onGcLog(message: String)
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
    private var socket: WebSocket? = null
    private var generation = 0L
    private var desired = false
    private var url = ""
    private var retrySeconds = 1L
    private var disconnectedGeneration = -1L
    private var connected = false

    fun connect(newUrl: String) {
        val request = try { Request.Builder().url(newUrl).build() } catch (error: Exception) {
            listener.onGcLog("GC URLエラー: ${error.message}")
            return
        }
        if (connected) {
            connected = false
            listener.onGcDisconnected()
        }
        desired = true
        url = newUrl
        generation++
        disconnectedGeneration = -1
        socket?.cancel()
        val current = generation
        listener.onGcLog("GCへ接続中: $newUrl")
        socket = client.newWebSocket(request, callbacks(current))
    }

    fun disconnect() {
        desired = false
        generation++
        socket?.close(1000, "app disconnect")
        socket = null
        notifyDisconnected(generation)
    }

    private fun callbacks(current: Long) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = serial.execute {
            if (current != generation || webSocket !== socket) { webSocket.close(1000, "stale"); return@execute }
            retrySeconds = 1
            disconnectedGeneration = -1
            connected = true
            listener.onGcLog("GC WebSocket接続済み（新しいmatchState待ち）")
            listener.onGcConnected()
        }

        override fun onMessage(webSocket: WebSocket, text: String) = serial.execute {
            if (current != generation || webSocket !== socket) return@execute
            try {
                val running = GcMessageParser.runningOrNull(text) ?: return@execute
                listener.onGcLog("GC matchState: ${if (running) "RUNNING" else "非RUNNING"}")
                listener.onFreshMatchState(running)
            } catch (error: Exception) {
                listener.onGcLog("GC JSONを無視: ${error.message}")
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) =
            onMessage(webSocket, bytes.utf8())

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = serial.execute {
            lost(current, "GC WebSocket終了: $code $reason")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = serial.execute {
            lost(current, "GC WebSocket切断: ${t.message}")
        }
    }

    private fun lost(current: Long, message: String) {
        if (current != generation || disconnectedGeneration == current) return
        disconnectedGeneration = current
        connected = false
        listener.onGcLog(message)
        listener.onGcDisconnected()
        if (!desired) return
        val delay = retrySeconds
        retrySeconds = (retrySeconds * 2).coerceAtMost(15)
        serial.schedule({
            if (desired && current == generation) connect(url)
        }, delay, TimeUnit.SECONDS)
    }

    private fun notifyDisconnected(current: Long) {
        if (disconnectedGeneration == current) return
        disconnectedGeneration = current
        listener.onGcDisconnected()
    }

    fun closeNow() {
        desired = false
        connected = false
        socket?.cancel()
        socket = null
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
