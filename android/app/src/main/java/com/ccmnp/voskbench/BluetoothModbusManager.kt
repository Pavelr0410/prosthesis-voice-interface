package com.ccmnp.voskbench

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.ActivityCompat
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

class BluetoothModbusManager(private val context: Context) {

    companion object {
        private const val TAG = "BluetoothModbus"
        private val MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        // FEST BLE: MOVE_ALL_FINGERS — 6 байт позиций пальцев (0-100)
        // порядок: pinky, ring, middle, index, thumb, thumb_rot
        private val MOVE_ALL_FINGERS_UUID =
            UUID.fromString("4368000a-4d74-1001-726b-526f64696f6e")
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var isConnected = false
    private var discoveryReceiver: BroadcastReceiver? = null

    // BLE (GATT)
    private var bleScanner: BluetoothLeScanner? = null
    private var bleScanCallback: ScanCallback? = null
    private var bleGatt: BluetoothGatt? = null
    private var bleWriteChar: BluetoothGattCharacteristic? = null
    private var bleWriteReady = false

    /** Колбэк для передачи причин ошибок в UI (лог приложения). */
    var onError: ((String) -> Unit)? = null

    private fun reportError(msg: String) {
        Log.e(TAG, msg)
        onError?.invoke(msg)
    }

    val isBleConnected: Boolean get() = bleWriteReady && isConnected
    val isClassicConnected: Boolean get() = isConnected

    init {
        if (hasBluetoothPermissions()) {
            bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        } else {
            Log.e(TAG, "Bluetooth permissions not granted")
        }
    }

    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasBluetoothScanPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun connect(deviceAddress: String): Boolean {
        if (!hasBluetoothPermissions()) {
            Log.e(TAG, "❌ Bluetooth permissions not granted")
            return false
        }

        try {
            val device = bluetoothAdapter?.getRemoteDevice(deviceAddress)
                ?: return false

            // Отменяем Discovery, чтобы не мешать соединению
            try {
                bluetoothAdapter?.cancelDiscovery()
            } catch (e: SecurityException) {
                Log.e(TAG, "Failed to cancel discovery: ${e.message}")
            }

            socket = device.createRfcommSocketToServiceRecord(MY_UUID)
            socket?.connect()
            outputStream = socket?.outputStream
            isConnected = true

            Log.d(TAG, "✅ Connected to $deviceAddress")
            return true
        } catch (e: IOException) {
            Log.e(TAG, "❌ Connection failed: ${e.message}")
            isConnected = false
            return false
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ Security exception: ${e.message}")
            isConnected = false
            return false
        }
    }

    fun commandBytes(gesture: String): ByteArray? {
        val gestureToModbus = mapOf(
            "нейтральный" to byteArrayOf(0x01, 0x00),
            "нейтраль" to byteArrayOf(0x01, 0x00),
            "большой палец" to byteArrayOf(0x01, 0x01),
            "кулак" to byteArrayOf(0x01, 0x02),
            "сжатие" to byteArrayOf(0x01, 0x02),
            "открытая ладонь" to byteArrayOf(0x01, 0x03),
            "открыть" to byteArrayOf(0x01, 0x03),
            "щипок" to byteArrayOf(0x01, 0x04),
            "щепок" to byteArrayOf(0x01, 0x04),
            "указательный" to byteArrayOf(0x01, 0x05),
            "пистолет" to byteArrayOf(0x01, 0x05),
            "сгибание" to byteArrayOf(0x01, 0x06),
            "флексия" to byteArrayOf(0x01, 0x06),
            "разгибание" to byteArrayOf(0x01, 0x07),
            "экстензия" to byteArrayOf(0x01, 0x07),
            "status" to byteArrayOf(0x02, 0x00)
        )
        return gestureToModbus[gesture.lowercase()]
    }

    fun sendCommand(gesture: String): Boolean {
        if (!isConnected) {
            Log.e(TAG, "❌ Not connected to Bluetooth device")
            return false
        }

        val command = commandBytes(gesture)
        if (command == null) {
            Log.e(TAG, "❌ Unknown gesture: $gesture")
            return false
        }

        return try {
            outputStream?.write(command)
            outputStream?.flush()
            Log.d(TAG, "✅ Sent Modbus command: $gesture → ${command.joinToString(" ")}")
            true
        } catch (e: IOException) {
            Log.e(TAG, "❌ Send failed: ${e.message}")
            isConnected = false
            false
        }
    }

    fun disconnect() {
        try {
            outputStream?.close()
            socket?.close()
            isConnected = false
            Log.d(TAG, "Disconnected")
        } catch (e: IOException) {
            Log.e(TAG, "Disconnect error: ${e.message}")
        }
    }

    fun getPairedDevices(): List<BluetoothDevice> {
        // Проверяем разрешения для Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.e(TAG, "❌ BLUETOOTH_CONNECT permission not granted")
                return emptyList()
            }
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.e(TAG, "❌ BLUETOOTH_SCAN permission not granted")
                return emptyList()
            }
        } else {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.e(TAG, "❌ BLUETOOTH permission not granted")
                return emptyList()
            }
        }

        // Проверяем, что адаптер не null
        if (bluetoothAdapter == null) {
            Log.e(TAG, "❌ Bluetooth adapter is null")
            return emptyList()
        }

        return try {
            bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ Security exception while getting bonded devices: ${e.message}")
            emptyList()
        }
    }

    // ── Поиск новых (несопряжённых) устройств ──────────────────
    fun startDiscovery(onDeviceFound: (BluetoothDevice) -> Unit) {
        if (!hasBluetoothScanPermissions()) {
            Log.e(TAG, "❌ Bluetooth scan permissions not granted")
            return
        }
        if (bluetoothAdapter == null) {
            Log.e(TAG, "❌ Bluetooth adapter is null")
            return
        }

        stopDiscovery()

        discoveryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device: BluetoothDevice? =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(
                                    BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java
                                )
                            } else {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                            }
                        if (device != null) {
                            onDeviceFound(device)
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        Log.d(TAG, "Discovery finished")
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(discoveryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(discoveryReceiver, filter)
            }
            bluetoothAdapter?.startDiscovery()
            Log.d(TAG, "✅ Discovery started")
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ Failed to start discovery: ${e.message}")
        }
    }

    fun stopDiscovery() {
        try {
            bluetoothAdapter?.cancelDiscovery()
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to cancel discovery: ${e.message}")
        }
        discoveryReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: Exception) {}
        }
        discoveryReceiver = null
    }

    fun getDevice(address: String): BluetoothDevice? {
        return try {
            bluetoothAdapter?.getRemoteDevice(address)
        } catch (e: Exception) {
            Log.e(TAG, "getRemoteDevice error: ${e.message}")
            null
        }
    }

    fun isLeDevice(device: BluetoothDevice?): Boolean {
        val type = device?.type ?: return false
        return type == BluetoothDevice.DEVICE_TYPE_LE ||
            type == BluetoothDevice.DEVICE_TYPE_DUAL ||
            type == BluetoothDevice.DEVICE_TYPE_UNKNOWN
    }

    // ── BLE: сканирование ───────────────────────────────────────
    fun startBleScan(onDeviceFound: (BluetoothDevice, String?, List<ParcelUuid>?) -> Unit) {
        if (bluetoothAdapter == null || !hasBluetoothScanPermissions()) {
            reportError("Cannot start BLE scan: Bluetooth off or permissions not granted")
            return
        }
        stopBleScan()

        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bleScanner = manager.adapter?.bluetoothLeScanner ?: run {
            reportError("BluetoothLeScanner is null")
            return
        }

        bleScanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val advName = result.scanRecord?.deviceName
                onDeviceFound(device, advName, result.scanRecord?.serviceUuids)
            }

            override fun onScanFailed(errorCode: Int) {
                reportError("BLE scan failed, errorCode=$errorCode")
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            bleScanner?.startScan(null, settings, bleScanCallback)
            Log.d(TAG, "✅ BLE scan started")
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ BLE scan failed: ${e.message}")
        }
    }

    fun stopBleScan() {
        try {
            bleScanner?.stopScan(bleScanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "stopBleScan error: ${e.message}")
        }
        bleScanCallback = null
    }

    // ── BLE: подключение через GATT ─────────────────────────────
    fun connectBle(address: String, onReady: (Boolean) -> Unit) {
        if (bluetoothAdapter == null || !hasBluetoothPermissions()) {
            reportError("Cannot connect BLE: Bluetooth off or permissions not granted")
            onReady(false)
            return
        }
        val device = getDevice(address) ?: run {
            reportError("Device not found: $address")
            onReady(false)
            return
        }

        try {
            bleGatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            Log.d(TAG, "✅ BLE connected, discovering services...")
                            gatt?.discoverServices()
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            bleWriteReady = false
                            isConnected = false
                            reportError("BLE disconnected (status=$status)")
                        }
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        reportError("Service discovery failed (status=$status)")
                        onReady(false)
                        return
                    }

                    // Ищем MOVE_ALL_FINGERS в любом сервисе
                    var moveChar: BluetoothGattCharacteristic? = null
                    gatt?.services?.forEach { s ->
                        s.characteristics.forEach { c ->
                            if (c.uuid == MOVE_ALL_FINGERS_UUID) {
                                moveChar = c
                            }
                        }
                    }

                    if (moveChar != null) {
                        bleWriteChar = moveChar
                        bleWriteReady = true
                        isConnected = true
                        Log.d(TAG, "✅ MOVE_ALL_FINGERS characteristic found")
                        onReady(true)
                    } else {
                        reportError("MOVE_ALL_FINGERS (4368000a) characteristic not found on the device")
                        gatt?.services?.forEach { s ->
                            Log.d(TAG, "  Service: ${s.uuid}")
                            s.characteristics.forEach { c ->
                                Log.d(TAG, "    Char: ${c.uuid} (${c.properties})")
                            }
                        }
                        onReady(false)
                    }
                }
            })
        } catch (e: SecurityException) {
            reportError("connectGatt failed: ${e.message}")
            onReady(false)
        }
    }

    fun gesturePositions(gesture: String): ByteArray? {
        val pos = mapOf(
            "нейтральный" to byteArrayOf(25, 25, 25, 25, 25, 25),
            "нейтраль" to byteArrayOf(25, 25, 25, 25, 25, 25),
            "большой палец" to byteArrayOf(0, 0, 0, 0, 100, 0),
            "кулак" to byteArrayOf(100, 100, 100, 100, 100, 100),
            "сжатие" to byteArrayOf(100, 100, 100, 100, 100, 100),
            "открытая ладонь" to byteArrayOf(0, 0, 0, 0, 0, 0),
            "открыть" to byteArrayOf(0, 0, 0, 0, 0, 0),
            "разжатие" to byteArrayOf(0, 0, 0, 0, 0, 0),
            "разжатие пальцев" to byteArrayOf(0, 0, 0, 0, 0, 0),
            "щипок" to byteArrayOf(0, 0, 0, 50, 50, 100),
            "щепок" to byteArrayOf(0, 0, 0, 50, 50, 100),
            "указательный" to byteArrayOf(100, 100, 100, 0, 0, 0),
            "пистолет" to byteArrayOf(100, 100, 100, 0, 0, 0),
            "сгибание" to byteArrayOf(100, 100, 100, 100, 100, 100),
            "флексия" to byteArrayOf(100, 100, 100, 100, 100, 100),
            "закрыть" to byteArrayOf(100, 100, 100, 100, 100, 100),
            "закрытие" to byteArrayOf(100, 100, 100, 100, 100, 100),
            "сжатие кисти" to byteArrayOf(100, 100, 100, 100, 100, 100),
            "разгибание" to byteArrayOf(0, 0, 0, 0, 0, 0),
            "экстензия" to byteArrayOf(0, 0, 0, 0, 0, 0),
            "коза" to byteArrayOf(0, 100, 100, 0, 100, 0)
        )
        return pos[gesture.lowercase()]
    }

    fun sendCommandBle(gesture: String): Boolean {
        val positions = gesturePositions(gesture)
            ?: return false.also { reportError("Unknown gesture: $gesture") }

        val gatt = bleGatt
        val char = bleWriteChar
        if (gatt == null || char == null || !bleWriteReady) {
            reportError("BLE not ready to write (not connected)")
            return false
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(char, positions, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                @Suppress("DEPRECATION")
                char.value = positions
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(char)
            }
            Log.d(TAG, "✅ BLE command sent: $gesture → ${positions.joinToString(" ")}")
            true
        } catch (e: Exception) {
            reportError("BLE write failed: ${e.message}")
            false
        }
    }

    fun disconnectBle() {
        try {
            bleGatt?.disconnect()
            bleGatt?.close()
        } catch (e: Exception) {
            Log.e(TAG, "disconnectBle error: ${e.message}")
        }
        bleGatt = null
        bleWriteChar = null
        bleWriteReady = false
        isConnected = false
    }
}