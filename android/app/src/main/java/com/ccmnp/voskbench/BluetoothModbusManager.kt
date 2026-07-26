package com.ccmnp.voskbench

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

class BluetoothModbusManager(private val context: Context) {

    companion object {
        private const val TAG = "BluetoothModbus"
        private val MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var isConnected = false

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

    fun sendCommand(gesture: String): Boolean {
        if (!isConnected) {
            Log.e(TAG, "❌ Not connected to Bluetooth device")
            return false
        }

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

        val command = gestureToModbus[gesture.lowercase()]
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
}