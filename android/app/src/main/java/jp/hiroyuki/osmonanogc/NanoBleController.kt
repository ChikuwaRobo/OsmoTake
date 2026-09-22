@file:Suppress("DEPRECATION", "MissingPermission")

package jp.hiroyuki.osmonanogc

import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import java.util.UUID
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class NanoBleController(
    private val context: Context,
    private val serial: ScheduledExecutorService,
    private val listener: Listener,
) {
    data class Device(val name: String, val address: String, val rssi: Int)
    interface Listener {
        fun onDevices(devices: List<Device>)
        fun onBleStatus(status: String)
        fun onReady()
        fun onLost()
        fun onRecordingState(state: RecordingState)
        fun onLog(message: String)
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        val NOTIFY_UUID: UUID = UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb")
        val WRITE_UUID: UUID = UUID.fromString("0000fff5-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val TOKEN = "OSMOCTRL"
    }

    private data class Write(
        val bytes: ByteArray,
        val label: String,
        val recordRevision: Long? = null,
        val recordStart: Boolean? = null,
    )
    private val adapter get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val parser = DumlProtocol.FrameParser()
    private val writes = ArrayDeque<Write>()
    private val found = linkedMapOf<String, Device>()
    private var scanCallback: ScanCallback? = null
    private var scanGeneration = 0L
    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var generation = 0L
    private var sequence = 0
    private var mtu = 23
    private var writing = false
    private var ready = false
    private var desiredConnected = false
    private var address: String? = null
    private var keepAlive: ScheduledFuture<*>? = null
    private var statusPoll: ScheduledFuture<*>? = null
    private var pairingTimeout: ScheduledFuture<*>? = null
    private var connectTimeout: ScheduledFuture<*>? = null
    private var recordRevision = 0L

    val isReady get() = ready

    fun scan() {
        stopScan()
        val currentScan = ++scanGeneration
        found.clear()
        listener.onDevices(emptyList())
        listener.onBleStatus("Nanoを検索中…")
        val scanner = adapter.bluetoothLeScanner ?: run {
            listener.onBleStatus("Bluetoothを有効にしてください")
            return
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = serial.execute {
                if (currentScan == scanGeneration) accept(result)
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>) = serial.execute {
                if (currentScan == scanGeneration) results.forEach(::accept)
            }
            override fun onScanFailed(errorCode: Int) = serial.execute {
                if (currentScan == scanGeneration) listener.onBleStatus("BLE検索エラー: $errorCode")
            }
        }
        scanCallback = callback
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        serial.schedule({
            if (currentScan != scanGeneration) return@schedule
            stopScan()
            listener.onBleStatus(if (found.isEmpty()) "Osmo Nanoが見つかりません" else "${found.size}台見つかりました")
        }, 6, TimeUnit.SECONDS)
    }

    private fun accept(result: ScanResult) {
        val detectedName = result.device.name ?: result.scanRecord?.deviceName ?: ""
        val name = detectedName.ifBlank { "Osmo Nano" }
        val lower = detectedName.lowercase()
        val manufacturer = result.scanRecord?.manufacturerSpecificData
        var modelNano = false
        if (manufacturer != null) for (index in 0 until manufacturer.size()) {
            val company = manufacturer.keyAt(index)
            val value = manufacturer.valueAt(index)
            if ((company == 0x08AA && value.isNotEmpty() && value[0].toInt() and 0xFF == 0x19) ||
                (value.size >= 3 && value[0] == 0xAA.toByte() && value[1] == 0x08.toByte() && value[2] == 0x19.toByte())) {
                modelNano = true
            }
        }
        if (!(modelNano || ("nano" in lower && ("osmo" in lower || "dji" in lower)))) return
        found[result.device.address] = Device(name, result.device.address, result.rssi)
        listener.onDevices(found.values.sortedByDescending { it.rssi })
    }

    fun stopScan() {
        val callback = scanCallback ?: return
        scanGeneration++
        scanCallback = null
        try { adapter.bluetoothLeScanner?.stopScan(callback) } catch (_: Exception) { }
    }

    fun connect(deviceAddress: String) {
        stopScan()
        desiredConnected = true
        address = deviceAddress
        generation++
        val current = generation
        resetSession()
        closeGatt()
        listener.onLost()
        listener.onBleStatus("Nanoへ接続中…")
        val device = adapter.getRemoteDevice(deviceAddress)
        gatt = device.connectGatt(context, false, callback(current), BluetoothDevice.TRANSPORT_LE)
        connectTimeout = serial.schedule({
            if (current == generation && !ready) fail("BLE接続準備が20秒以内に完了しませんでした")
        }, 20, TimeUnit.SECONDS)
    }

    private fun callback(callbackGeneration: Long) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) = serial.execute {
            if (callbackGeneration != generation || g !== gatt) { g.close(); return@execute }
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                listener.onBleStatus("BLE接続済み。サービス確認中…")
                if (!g.discoverServices()) fail("サービス検索を開始できません")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                handleLost("BLE接続が切れました")
                closeGatt()
                if (desiredConnected) serial.schedule({
                    if (desiredConnected && callbackGeneration == generation) address?.let(::connect)
                }, 2, TimeUnit.SECONDS)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = serial.execute {
            if (!current(callbackGeneration, g)) return@execute
            val service = g.getService(SERVICE_UUID)
            writeCharacteristic = service?.getCharacteristic(WRITE_UUID)
            val notify = service?.getCharacteristic(NOTIFY_UUID)
            if (status != BluetoothGatt.GATT_SUCCESS || writeCharacteristic == null || notify == null) {
                fail("Nano制御用FFF4/FFF5が見つかりません"); return@execute
            }
            if (!g.requestMtu(247)) enableNotifications(g, notify)
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) = serial.execute {
            if (!current(callbackGeneration, g)) return@execute
            mtu = if (status == BluetoothGatt.GATT_SUCCESS) newMtu else 23
            val notify = g.getService(SERVICE_UUID)?.getCharacteristic(NOTIFY_UUID)
            if (notify != null) enableNotifications(g, notify) else fail("FFF4が見つかりません")
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = serial.execute {
            if (!current(callbackGeneration, g) || descriptor.uuid != CCCD_UUID) return@execute
            if (status != BluetoothGatt.GATT_SUCCESS) { fail("通知を有効にできません: $status"); return@execute }
            val pairingSize = 13 + DumlProtocol.pairingPayload(pairingId()).size
            if (mtu - 3 < pairingSize) { fail("BLE書込上限${mtu - 3} byteではペアリングできません"); return@execute }
            startSession(callbackGeneration)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val bytes = characteristic.value?.clone() ?: return
            serial.execute { if (current(callbackGeneration, g)) receive(bytes, callbackGeneration) }
        }
    }

    private fun current(value: Long, candidate: BluetoothGatt) = value == generation && candidate === gatt

    private fun enableNotifications(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (!g.setCharacteristicNotification(characteristic, true)) { fail("通知を設定できません"); return }
        val descriptor = characteristic.getDescriptor(CCCD_UUID) ?: run { fail("CCCDが見つかりません"); return }
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (!g.writeDescriptor(descriptor)) fail("CCCDを書き込めません")
    }

    private fun startSession(current: Long) {
        connectTimeout?.cancel(false)
        connectTimeout = null
        enqueue(command(DumlProtocol.SESSION, 0, 0x2B, byteArrayOf(4, 0)), "セッション開始")
        enqueue(command(DumlProtocol.WIFI, 7, 0x45, DumlProtocol.pairingPayload(pairingId())), "アプリ内ペアリング")
        enqueue(command(DumlProtocol.SESSION, 0, 0x2B, byteArrayOf(1, 1)), "keepalive")
        keepAlive?.cancel(false)
        keepAlive = serial.scheduleWithFixedDelay({
            if (current == generation && writes.none { it.label == "keepalive" })
                enqueue(command(DumlProtocol.SESSION, 0, 0x2B, byteArrayOf(1, 1)), "keepalive")
        }, 1, 1, TimeUnit.SECONDS)
        pairingTimeout = serial.schedule({
            if (current == generation && !ready) fail("アプリ内ペアリングが30秒以内に完了しませんでした")
        }, 30, TimeUnit.SECONDS)
        listener.onBleStatus("初回はNano画面で $TOKEN を承認してください")
    }

    private fun pairingId(): String {
        val prefs = context.getSharedPreferences("osmo", Context.MODE_PRIVATE)
        return prefs.getString("pairing_id", null) ?: UUID.randomUUID().toString().replace("-", "").also {
            prefs.edit().putString("pairing_id", it).apply()
        }
    }

    private fun command(destination: Int, set: Int, id: Int, payload: ByteArray = byteArrayOf()): Pair<Int, ByteArray> {
        val seq = sequence and 0xFFFF
        sequence = (sequence + 1) and 0xFFFF
        return seq to DumlProtocol.frame(seq, destination, set, id, payload)
    }

    private fun enqueue(
        command: Pair<Int, ByteArray>, label: String, revision: Long? = null,
        priority: Boolean = false, recordStart: Boolean? = null,
    ) {
        val write = Write(command.second, label, revision, recordStart)
        if (priority) writes.addFirst(write) else writes.addLast(write)
        drain()
    }

    private fun drain() {
        if (writing || writes.isEmpty()) return
        val characteristic = writeCharacteristic ?: return
        val activeGatt = gatt ?: return
        val write = writes.removeFirst()
        val current = generation
        if (write.recordRevision != null && write.recordRevision != recordRevision) { drain(); return }
        writing = true
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        characteristic.value = write.bytes
        val accepted = try { activeGatt.writeCharacteristic(characteristic) } catch (_: Exception) { false }
        if (!accepted) { writing = false; fail("BLE書込を開始できません"); return }
        listener.onLog("TX ${write.label}: ${write.bytes.toHex()}")
        serial.schedule({ if (current == generation) { writing = false; drain() } }, 120, TimeUnit.MILLISECONDS)
    }

    fun sendRecord(start: Boolean) {
        if (!ready) { listener.onLog("録画命令を保留: Nano未準備"); return }
        recordRevision++
        val revision = recordRevision
        writes.removeAll { it.recordRevision != null }
        val action = if (start) "録画開始" else "録画停止"
        enqueue(command(DumlProtocol.CAMERA, 1, if (start) 0x21 else 0x22), action, revision, recordStart = start)
        enqueue(command(DumlProtocol.CAMERA, 2, 0x02, byteArrayOf(if (start) 1 else 0)), action, revision, recordStart = start)
        if (!start) enqueue(command(DumlProtocol.CAMERA, 2, 0x7C, byteArrayOf(0)), action, revision, recordStart = false)
        listener.onBleStatus("$action コマンド送信中（カメラ確認待ち）")
    }

    fun cancelPendingRecordStarts() {
        writes.removeAll { it.recordStart == true }
        listener.onLog("未送信の録画開始キューを取消")
    }

    private fun receive(bytes: ByteArray, current: Long) {
        listener.onLog("RX ${bytes.toHex()}")
        val previousErrors = parser.errors.size
        parser.feed(bytes).forEach { packet -> handle(packet, current) }
        parser.errors.drop(previousErrors).forEach { listener.onLog("破損DUMLを破棄: $it") }
        if (parser.errors.isNotEmpty()) parser.errors.clear()
    }

    private fun handle(packet: DumlProtocol.Packet, current: Long) {
        if (!packet.isResponse && packet.flags and 0x40 != 0) {
            val payload = if (packet.commandSet == 0 && packet.commandId == 0x81) APP_DEVICE_INFO else packet.payload
            val response = DumlProtocol.frame(packet.sequence, packet.source, packet.commandSet, packet.commandId,
                payload, source = packet.destination, flags = 0xC0)
            enqueue(packet.sequence to response, "ACK %02x/%02x".format(packet.commandSet, packet.commandId), priority = true)
        }
        if (packet.commandSet == 7 && packet.commandId == 0x45 && packet.isResponse) {
            if (packet.payload.size >= 2 && packet.payload[0].toInt() == 0 && packet.payload[1].toInt() == 1) markReady(current)
            else if (packet.payload.size >= 2 && packet.payload[0].toInt() == 0 && packet.payload[1].toInt() == 2)
                listener.onBleStatus("Nano画面で $TOKEN を承認してください")
            else listener.onBleStatus("ペアリングが拒否されました")
        } else if (packet.commandSet == 7 && packet.commandId == 0x46 && !packet.isResponse && packet.payload.firstOrNull()?.toInt() == 1) {
            markReady(current)
        }
        DumlProtocol.recordingState(packet)?.let(listener::onRecordingState)
    }

    private fun markReady(current: Long) {
        if (current != generation || ready) return
        ready = true
        connectTimeout?.cancel(false)
        pairingTimeout?.cancel(false)
        listener.onBleStatus("Nano制御準備完了")
        enqueue(command(DumlProtocol.SESSION_INFO, 0, 0x32, byteArrayOf(0x31, 0x31, 0, 0, 0)), "セッション情報")
        enqueue(command(DumlProtocol.CAMERA, 2, 0x70), "状態取得")
        statusPoll = serial.scheduleWithFixedDelay({
            if (current == generation && ready && writes.none { it.label == "状態取得" })
                enqueue(command(DumlProtocol.CAMERA, 2, 0x70), "状態取得")
        }, 2500, 2500, TimeUnit.MILLISECONDS)
        listener.onReady()
    }

    fun disconnect() {
        desiredConnected = false
        generation++
        stopScan()
        handleLost("切断しました")
        closeGatt()
    }

    fun stopThenDisconnect() {
        desiredConnected = false
        if (ready) sendRecord(false)
        serial.schedule(::disconnect, 500, TimeUnit.MILLISECONDS)
    }

    fun closeNow() {
        desiredConnected = false
        generation++
        try { stopScan() } catch (_: Exception) { }
        resetSession()
        closeGatt()
    }

    private fun handleLost(message: String) {
        resetSession()
        listener.onRecordingState(RecordingState.UNKNOWN)
        listener.onBleStatus(message)
        listener.onLost()
    }

    private fun closeGatt() {
        try { gatt?.disconnect() } catch (_: Exception) { }
        try { gatt?.close() } catch (_: Exception) { }
        gatt = null
        writeCharacteristic = null
    }

    private fun fail(message: String) {
        listener.onLog(message)
        disconnect()
        listener.onBleStatus(message)
    }

    private fun resetSession() {
        ready = false
        mtu = 23
        writes.clear()
        writing = false
        parser.clear()
        keepAlive?.cancel(false); statusPoll?.cancel(false); pairingTimeout?.cancel(false); connectTimeout?.cancel(false)
        keepAlive = null; statusPoll = null; pairingTimeout = null; connectTimeout = null
        listener.onRecordingState(RecordingState.UNKNOWN)
    }

    private fun ByteArray.toHex() = joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }

    private val APP_DEVICE_INFO = byteArrayOf(
        0x00, 0x41, 0x50, 0x50, *ByteArray(37), 0x02, *ByteArray(8), 0x02, 0x08, *ByteArray(10)
    )
}
