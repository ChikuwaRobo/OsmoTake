package jp.hiroyuki.osmonanogc

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.*
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    private var service: ControlService? = null
    private var bound = false
    private var devices: List<NanoBleController.Device> = emptyList()
    private var updatingAuto = false

    private lateinit var url: EditText
    private lateinit var gcStatus: TextView
    private lateinit var bleStatus: TextView
    private lateinit var recordStatus: TextView
    private lateinit var deviceSpinner: Spinner
    private lateinit var autoSwitch: Switch
    private lateinit var logView: TextView

    private val observer: (ControlService.Snapshot) -> Unit = { value -> runOnUiThread { render(value) } }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: android.os.IBinder?) {
            service = (binder as ControlService.LocalBinder).service()
            bound = true
            service?.observe(observer)
        }
        override fun onServiceDisconnected(name: ComponentName?) { bound = false; service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        url.setText(getSharedPreferences("osmo", MODE_PRIVATE).getString("gc_url", ""))
        val intent = Intent(this, ControlService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 43)
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, ControlService::class.java), connection, BIND_AUTO_CREATE)
    }

    override fun onStop() {
        if (bound) { service?.removeObserver(observer); unbindService(connection); bound = false }
        super.onStop()
    }

    private fun buildUi() {
        val density = resources.displayMetrics.density
        fun Int.dp() = (this * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp(), 12.dp(), 16.dp(), 16.dp())
        }
        val title = TextView(this).apply { text = "Osmo Nano × Game Controller"; textSize = 21f }
        root.addView(title)

        url = EditText(this).apply {
            hint = "ws://GCのIP:8081/api/control"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        root.addView(url, LinearLayout.LayoutParams(-1, -2))
        root.addView(Button(this).apply {
            text = "GC URLを保存して接続"
            setOnClickListener {
                val value = url.text.toString().trim()
                if (!value.startsWith("ws://") && !value.startsWith("wss://")) {
                    Toast.makeText(this@MainActivity, "ws:// または wss:// のURLを入力してください", Toast.LENGTH_LONG).show()
                } else service?.saveAndConnectGc(value)
            }
        })

        gcStatus = statusLabel("GC: 未接続")
        bleStatus = statusLabel("BLE: 未接続")
        recordStatus = statusLabel("録画: 不明")
        root.addView(gcStatus); root.addView(bleStatus); root.addView(recordStatus)

        val scanRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        scanRow.addView(Button(this).apply {
            text = "Nano検索"
            setOnClickListener { requestScan() }
        })
        deviceSpinner = Spinner(this)
        scanRow.addView(deviceSpinner, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(scanRow)

        val connectRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        connectRow.addView(Button(this).apply {
            text = "接続"
            setOnClickListener {
                devices.getOrNull(deviceSpinner.selectedItemPosition)?.let { service?.connectNano(it.address) }
                    ?: Toast.makeText(this@MainActivity, "Nanoを検索して選択してください", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        connectRow.addView(Button(this).apply {
            text = "切断"
            setOnClickListener { service?.disconnectNano() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(connectRow)

        autoSwitch = Switch(this).apply {
            text = "GC状態と録画を自動連動"
            setOnCheckedChangeListener { _, checked -> if (!updatingAuto) service?.setAutomatic(checked) }
        }
        root.addView(autoSwitch)
        val manualRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        manualRow.addView(Button(this).apply {
            text = "● 手動録画開始"
            setOnClickListener { service?.manualStart() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        manualRow.addView(Button(this).apply {
            text = "■ 手動停止"
            setOnClickListener { service?.manualStop() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(manualRow)
        root.addView(Button(this).apply {
            text = "停止して終了"
            setOnClickListener { service?.shutdown(); finishAndRemoveTask() }
        }, LinearLayout.LayoutParams(-1, -2))

        root.addView(TextView(this).apply { text = "ログ（最新300件）"; textSize = 15f })
        logView = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
        }
        val logScroll = ScrollView(this).apply { addView(logView) }
        root.addView(logScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun statusLabel(initial: String) = TextView(this).apply {
        text = initial
        textSize = 16f
        setPadding(0, 8, 0, 2)
    }

    private fun render(value: ControlService.Snapshot) {
        gcStatus.text = "GC: ${value.gcStatus}"
        bleStatus.text = "BLE: ${value.bleStatus}"
        recordStatus.text = "録画: " + when (value.recording) {
            RecordingState.RECORDING -> "録画中（カメラ確認済み）"
            RecordingState.STOPPED -> "停止中（カメラ確認済み）"
            RecordingState.UNKNOWN -> "不明 / 確認待ち"
        }
        if (devices != value.devices) {
            val selectedAddress = devices.getOrNull(deviceSpinner.selectedItemPosition)?.address
            devices = value.devices
            deviceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                devices.map { "${it.name} (${it.rssi} dBm)" })
            val selectedIndex = devices.indexOfFirst { it.address == selectedAddress }
            if (selectedIndex >= 0) deviceSpinner.setSelection(selectedIndex)
        }
        updatingAuto = true
        autoSwitch.isChecked = value.autoEnabled
        updatingAuto = false
        logView.text = value.logs.joinToString("\n")
        (logView.parent as? ScrollView)?.post { (logView.parent as ScrollView).fullScroll(View.FOCUS_DOWN) }
    }

    private fun requestScan() {
        val permissions = if (Build.VERSION.SDK_INT >= 31) listOf(
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT
        ) else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 41)
            return
        }
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter
        if (!adapter.isEnabled) {
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 42)
            return
        }
        if (Build.VERSION.SDK_INT < 31) {
            val location = getSystemService(LOCATION_SERVICE) as LocationManager
            val locationEnabled = if (Build.VERSION.SDK_INT >= 28) location.isLocationEnabled else
                location.isProviderEnabled(LocationManager.GPS_PROVIDER) || location.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            if (!locationEnabled) {
                Toast.makeText(this, "Android 9ではBLE検索に位置情報サービスを有効にする必要があります", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                return
            }
        }
        service?.scanNano()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 41 && results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) requestScan()
        else if (requestCode == 41) Toast.makeText(this, "BLE検索の権限が必要です", Toast.LENGTH_LONG).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 42 && resultCode == RESULT_OK) requestScan()
    }
}
