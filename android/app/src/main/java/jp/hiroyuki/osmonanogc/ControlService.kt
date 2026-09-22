@file:Suppress("MissingPermission", "DEPRECATION")

package jp.hiroyuki.osmonanogc

import android.app.*
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.net.wifi.WifiManager
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class ControlService : Service(), NanoBleController.Listener, GameControllerSocket.Listener {
    data class Snapshot(
        val gcStatus: String = "未接続",
        val bleStatus: String = "未接続",
        val recording: RecordingState = RecordingState.UNKNOWN,
        val autoEnabled: Boolean = false,
        val devices: List<NanoBleController.Device> = emptyList(),
        val logs: List<String> = emptyList(),
    )

    inner class LocalBinder : Binder() {
        fun service(): ControlService = this@ControlService
    }

    private val binder = LocalBinder()
    private val serial = ScheduledThreadPoolExecutor(1) { runnable -> Thread(runnable, "control-serial") }.apply {
        setRemoveOnCancelPolicy(true)
        rejectedExecutionHandler = java.util.concurrent.RejectedExecutionHandler { _, _ -> }
    }
    private val listeners = CopyOnWriteArrayList<(Snapshot) -> Unit>()
    private val coordinator = AutoCoordinator()
    private lateinit var nano: NanoBleController
    private lateinit var gc: GameControllerSocket
    private var snapshot = Snapshot()
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var disconnectGrace = 0L
    private var graceScheduled = false
    private var confirmationGeneration = 0L
    private var awaitingConfirmation: AutoCoordinator.Command? = null
    private var shuttingDown = false

    override fun onCreate() {
        super.onCreate()
        nano = NanoBleController(this, serial, this)
        gc = GameControllerSocket(serial, this)
        createNotificationChannel()
        startForeground(7, notification("GC: 未接続 / Nano: 未接続"))
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OsmoNanoGC:Control").apply {
                setReferenceCounted(false)
                acquire()
            }
        wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "OsmoNanoGC:Wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        val prefs = getSharedPreferences("osmo", MODE_PRIVATE)
        val savedUrl = prefs.getString("gc_url", "") ?: ""
        if (savedUrl.isNotBlank()) serial.execute { gc.connect(savedUrl) }
    }

    override fun onBind(intent: Intent?): IBinder = binder
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    fun observe(listener: (Snapshot) -> Unit) {
        listeners += listener
        listener(snapshot)
    }
    fun removeObserver(listener: (Snapshot) -> Unit) { listeners -= listener }

    fun saveAndConnectGc(url: String) = serial.execute {
        getSharedPreferences("osmo", MODE_PRIVATE).edit().putString("gc_url", url).apply()
        update(snapshot.copy(gcStatus = "接続中…"))
        gc.connect(url)
    }

    fun scanNano() = serial.execute { nano.scan() }
    fun connectNano(address: String) = serial.execute { nano.connect(address) }
    fun disconnectNano() = serial.execute { nano.disconnect() }

    fun setAutomatic(enabled: Boolean) = serial.execute {
        if (!enabled) nano.cancelPendingRecordStarts()
        execute(coordinator.setAuto(enabled))
        update(snapshot.copy(autoEnabled = coordinator.autoEnabled))
        log(if (enabled) "自動連動を有効化" else "自動連動を解除")
    }

    fun manualStop() = serial.execute {
        execute(coordinator.manualStop())
        update(snapshot.copy(autoEnabled = false))
        log("手動停止: 自動連動を解除")
    }

    fun manualStart() = serial.execute {
        nano.cancelPendingRecordStarts()
        val command = coordinator.manualStart()
        update(snapshot.copy(autoEnabled = false))
        if (command == null) {
            log("手動録画開始: Nanoへ接続して制御準備完了を待ってください")
            update(snapshot.copy(bleStatus = "手動録画開始にはNano接続が必要です"))
        } else {
            execute(command)
            log("手動録画開始: 自動連動を解除")
        }
    }

    fun shutdown() = serial.execute {
        if (shuttingDown) return@execute
        shuttingDown = true
        log("終了処理: 録画停止を試行")
        coordinator.manualStop()
        nano.stopThenDisconnect()
        gc.disconnect()
        serial.schedule({ stopSelf() }, 700, TimeUnit.MILLISECONDS)
    }

    override fun onDevices(devices: List<NanoBleController.Device>) = update(snapshot.copy(devices = devices))
    override fun onBleStatus(status: String) = update(snapshot.copy(bleStatus = status))
    override fun onReady() { execute(coordinator.onBleReady()); publish() }
    override fun onLost() {
        confirmationGeneration++
        awaitingConfirmation = null
        coordinator.onBleLost()
        update(snapshot.copy(recording = RecordingState.UNKNOWN))
    }
    override fun onRecordingState(state: RecordingState) {
        update(snapshot.copy(recording = state))
        val expected = awaitingConfirmation
        if ((expected == AutoCoordinator.Command.START && state == RecordingState.RECORDING) ||
            (expected == AutoCoordinator.Command.STOP && state == RecordingState.STOPPED)) {
            confirmationGeneration++
            awaitingConfirmation = null
        }
        execute(coordinator.onConfirmed(state))
    }
    override fun onLog(message: String) = log("BLE $message")

    override fun onGcConnected() {
        coordinator.onGcConnected()
        update(snapshot.copy(gcStatus = "接続済み（matchState待ち）"))
    }

    override fun onGcDisconnected() {
        val wasAutomatic = coordinator.autoEnabled
        coordinator.onGcDisconnected()
        if (wasAutomatic) nano.cancelPendingRecordStarts()
        update(snapshot.copy(gcStatus = "切断・再接続中"))
        if (graceScheduled) return
        graceScheduled = true
        val grace = ++disconnectGrace
        serial.schedule({
            if (grace == disconnectGrace) {
                graceScheduled = false
                execute(coordinator.onDisconnectGraceExpired())
                log("GC切断猶予5秒経過: 停止を要求")
            }
        }, 5, TimeUnit.SECONDS)
    }

    override fun onFreshMatchState(running: Boolean) {
        disconnectGrace++ // invalidate a pending disconnect grace task
        graceScheduled = false
        update(snapshot.copy(gcStatus = if (running) "接続済み / RUNNING" else "接続済み / 非RUNNING"))
        execute(coordinator.onMatchState(running))
    }
    override fun onGcLog(message: String) = log("GC $message")

    private fun execute(command: AutoCoordinator.Command?) {
        when (command) {
            AutoCoordinator.Command.START -> sendAndAwait(command, true)
            AutoCoordinator.Command.STOP -> sendAndAwait(command, false)
            null -> Unit
        }
    }

    private fun sendAndAwait(command: AutoCoordinator.Command, start: Boolean) {
        if (!nano.isReady) {
            log("${if (start) "録画開始" else "録画停止"}はNano再接続後に実行します")
            return
        }
        nano.sendRecord(start)
        awaitingConfirmation = command
        val check = ++confirmationGeneration
        update(snapshot.copy(recording = RecordingState.UNKNOWN))
        serial.schedule({
            if (check == confirmationGeneration && awaitingConfirmation == command) {
                awaitingConfirmation = null
                coordinator.onCommandTimeout(command)
                log("${if (start) "録画開始" else "録画停止"}は送信済みですが15秒以内に確認できませんでした")
            }
        }, 15, TimeUnit.SECONDS)
    }

    private fun log(message: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.JAPAN).format(java.util.Date())
        val logs = (snapshot.logs + "$time $message").takeLast(300)
        update(snapshot.copy(logs = logs))
    }

    private fun update(value: Snapshot) { snapshot = value; publish() }
    private fun publish() {
        val value = snapshot.copy(autoEnabled = coordinator.autoEnabled)
        snapshot = value
        listeners.forEach { listener -> try { listener(value) } catch (_: Exception) { } }
        val text = "GC: ${value.gcStatus} / Nano: ${value.bleStatus}"
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(7, notification(text))
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel("control", "Osmo Nano制御", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(text: String): Notification {
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "control")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Osmo Nano GC連動")
            .setContentText(text.take(120))
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) { shutdown(); super.onTaskRemoved(rootIntent) }
    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        serial.execute {
            try { nano.closeNow() } catch (_: Exception) { }
            try { gc.closeNow() } catch (_: Exception) { }
            serial.shutdownNow()
        }
        super.onDestroy()
    }
}
