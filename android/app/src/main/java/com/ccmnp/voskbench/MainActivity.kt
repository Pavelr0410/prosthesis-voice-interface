package com.ccmnp.voskbench

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ccmnp.voskbench.services.VoiceRecognitionService
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipInputStream
import kotlin.math.max

class MainActivity : AppCompatActivity(), RecognitionListener {

    companion object {
        private const val TAG = "VoiceControl"
        private const val SAMPLE_RATE = 16000f
        private const val PERMISSION_RECORD = 1001
        private const val PERMISSION_BLUETOOTH = 1002
        private const val PACKET_TIMEOUT = 5000L
        private const val MATCH_THRESHOLD = 0.85
        private const val KEYWORD = "протез"
        private const val KEYWORD_MATCH_THRESHOLD = 0.6
        private const val SCAN_ATTEMPTS = 3
        private const val SCAN_DURATION_MS = 12000L
    }

    private enum class State { LOADING, LISTENING, CAPTURING, PROCESSING }

    private var state = State.LOADING
    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var captureStart = 0L
    private val capturedText = StringBuilder()
    private var lastPartial = ""
    private val grammarList = mutableListOf<String>()
    private val whitelistWords = mutableSetOf<String>()

    private var isServiceRunning = false

    private lateinit var bluetoothManager: BluetoothModbusManager
    private var isBluetoothConnected = false
    private val discoveredDevices = mutableListOf<BluetoothDevice>()
    private var isAutoConnecting = false
    private var scanAttempt = 0

    private lateinit var stateLabel: TextView
    private lateinit var partialLabel: TextView
    private lateinit var responseLabel: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var serviceToggleButton: Button
    private lateinit var btConnectButton: Button

    private val logLines = mutableListOf<String>()
    private val handler = Handler(Looper.getMainLooper())
    private var lastPartialTime = System.currentTimeMillis()

    private val endSynonyms = setOf(
        "выполнять", "выполняй", "выполняйте", "выполни",
        "поехали", "давай", "старт"
    )

    private val gestures = setOf(
        "нейтральный", "нейтраль", "большой палец",
        "сжатие пальцев", "сжатие", "кулак",
        "разжатие пальцев", "разжатие", "открытая ладонь", "открыть",
        "закрыть", "закрытие", "сжатие кисти",
        "щипок", "щепок", "щепать",
        "указательный", "пистолет",
        "флексия", "сгибание",
        "экстензия", "разгибание",
        "коза"
    )

    private val keywordReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "KEYWORD_DETECTED") {
                val keyword = intent.getStringExtra("keyword") ?: return
                log("*** КЛЮЧЕВОЕ СЛОВО ИЗ СЕРВИСА: '$keyword' ***")
                onWakeWordDetected()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        stateLabel = findViewById(R.id.stateLabel)
        partialLabel = findViewById(R.id.partialLabel)
        responseLabel = findViewById(R.id.responseLabel)
        logText = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)
        serviceToggleButton = findViewById(R.id.serviceToggleButton)
        btConnectButton = findViewById(R.id.btConnectButton)

        bluetoothManager = BluetoothModbusManager(this)
        bluetoothManager.onError = { msg ->
            runOnUiThread { log("[BLUETOOTH] ❌ $msg") }
        }

        serviceToggleButton.setOnClickListener {
            toggleVoiceService()
        }

        btConnectButton.setOnClickListener {
            toggleBluetoothConnection()
        }

        val filter = IntentFilter("KEYWORD_DETECTED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(keywordReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(keywordReceiver, filter)
        }

        // 🔥 ЗАПРАШИВАЕМ РАЗРЕШЕНИЯ
        requestPermissions()

        // 🔥 ПРИНУДИТЕЛЬНО ЗАПУСКАЕМ VOSK через 2 секунды
        handler.postDelayed({
            log("⏰ Принудительный запуск VOSK через 2 секунды...")
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                log("✅ Разрешение на микрофон есть, запускаем VOSK")
                initModel()
            } else {
                log("❌ Нет разрешения на микрофон, запрашиваем...")
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_RECORD)
            }
        }, 2000)

        // Watchdog
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (state != State.LOADING && System.currentTimeMillis() - lastPartialTime > 10000) {
                    log("[WATCHDOG] No partials for 10s, restarting SpeechService")
                    try { speechService?.stop() } catch (_: Exception) {}
                    if (model != null) startListening()
                    lastPartialTime = System.currentTimeMillis()
                }
                handler.postDelayed(this, 5000)
            }
        }, 10000)
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()
        permissions.add(Manifest.permission.RECORD_AUDIO)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            log("📢 Запрашиваем разрешения: ${missingPermissions.joinToString()}")
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                PERMISSION_BLUETOOTH
            )
        } else {
            log("✅ Все разрешения уже есть")
        }
    }

    private fun toggleBluetoothConnection() {
        if (isBluetoothConnected) {
            // Отключение
            log("[BLUETOOTH] Отключаюсь от протеза...")
            bluetoothManager.stopDiscovery()
            bluetoothManager.stopBleScan()
            bluetoothManager.disconnect()
            bluetoothManager.disconnectBle()
            isBluetoothConnected = false
            isAutoConnecting = false
            btConnectButton.text = "Подключить Bluetooth"
            btConnectButton.setBackgroundColor(0xFF9C27B0.toInt())  // как в XML
            log("[BLUETOOTH] ✅ Отключено")
            Toast.makeText(this, "Bluetooth отключён", Toast.LENGTH_SHORT).show()
        } else {
            checkBluetoothPermissionsAndConnect()
        }
    }

    private fun checkBluetoothPermissionsAndConnect() {
        val hasBluetoothPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
        }

        if (!hasBluetoothPermission) {
            Toast.makeText(this, "Нет разрешения на Bluetooth", Toast.LENGTH_SHORT).show()
            requestPermissions()
            return
        }

        showBluetoothDevicesDialog()
    }

    private fun showBluetoothDevicesDialog() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Нет разрешения на Bluetooth", Toast.LENGTH_SHORT).show()
                requestPermissions()
                return
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Нет разрешения на сканирование Bluetooth", Toast.LENGTH_SHORT).show()
                requestPermissions()
                return
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Нет разрешения на Bluetooth", Toast.LENGTH_SHORT).show()
                requestPermissions()
                return
            }
        }

        // Сначала — прямое подключение к сохранённому адресу протеза (как кнопка «По адресу»)
        val saved = getSavedDeviceAddress()
        if (!saved.isNullOrBlank()) {
            log("[BLUETOOTH] Автоподключение напрямую к сохранённому адресу: $saved")
            bluetoothManager.connectBle(saved) { ok ->
                runOnUiThread {
                    if (ok) {
                        onBleConnected(saved)
                    } else {
                        log("[BLUETOOTH] Прямое подключение не удалось — запускаю сканирование...")
                        startScanFlow()
                    }
                }
            }
            return
        }

        startScanFlow()
    }

    private fun startScanFlow() {
        // Android требует включённой геолокации для BLE-сканирования
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        val locationOn = try {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            true
        }
        if (!locationOn) {
            log("[BLUETOOTH] ❌ Геолокация выключена — BLE-скан не возвращает устройства")
            Toast.makeText(
                this,
                "Включите геолокацию (требуется для BLE-сканирования)",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val bonded = bluetoothManager.getPairedDevices()
        discoveredDevices.clear()
        isAutoConnecting = false

        if (bonded.isNotEmpty()) {
            log("[BLUETOOTH] Сопряжённые устройства: ${bonded.joinToString { "${it.name} (${it.address})" }}")
        }

        // Если FEST уже сопряжён — подключаемся сразу, без ожидания сканирования
        val bondedFest = bonded.firstOrNull {
            it.name?.startsWith("FEST", ignoreCase = true) == true
        }
        if (bondedFest != null) {
            log("[BLUETOOTH] Найден сопряжённый протез: ${bondedFest.name} (${bondedFest.address})")
            connectBluetoothDevice(bondedFest.address)
            return
        }

        // Сканируем с повторами и автоматически подключаемся к протезу (имя/сервис FEST)
        scanAttempt = 0
        runScanAttempt()
    }

    private fun runScanAttempt() {
        scanAttempt += 1
        if (scanAttempt > SCAN_ATTEMPTS) {
            val foundNames = if (discoveredDevices.isEmpty()) "ничего" else
                discoveredDevices.joinToString { "${it.name} (${it.address})" }
            log("[BLUETOOTH] ❌ Протез FEST не найден после $SCAN_ATTEMPTS попыток. Найдено: $foundNames")
            log("[BLUETOOTH] Совет: протез рекламируется ~1 мин после включения — перезапустите питание протеза и держите рядом; проверьте Bluetooth и геолокацию на телефоне")
            Toast.makeText(
                this,
                "Протез FEST не найден. Перезапустите питание протеза и попробуйте снова.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        discoveredDevices.clear()
        log("[BLUETOOTH] Сканирование (попытка $scanAttempt из $SCAN_ATTEMPTS), автоподключение к FEST...")
        Toast.makeText(
            this,
            "Сканирование... попытка $scanAttempt из $SCAN_ATTEMPTS",
            Toast.LENGTH_SHORT
        ).show()

        bluetoothManager.startDiscovery { device ->
            runOnUiThread {
                if (isAutoConnecting) return@runOnUiThread
                if (!device.name.isNullOrBlank() && device.name.startsWith("FEST", ignoreCase = true)) {
                    stopScanAndConnect(device)
                }
                if (!device.name.isNullOrBlank() &&
                    discoveredDevices.none { it.address == device.address }
                ) {
                    discoveredDevices.add(device)
                    log("[BLUETOOTH] Найдено (classic): ${device.name} (${device.address})")
                }
            }
        }

        bluetoothManager.startBleScan { device, advName, serviceUuids ->
            runOnUiThread {
                val name = advName ?: device.name
                if (isAutoConnecting) return@runOnUiThread

                // Детект FEST по имени ИЛИ по Motorica-сервису (4368...)
                val isFestName = !name.isNullOrBlank() && name.startsWith("FEST", ignoreCase = true)
                val isFestService = serviceUuids?.any {
                    it.toString().uppercase().startsWith("4368")
                } == true

                if (isFestName || isFestService) {
                    stopScanAndConnect(device)
                }

                // Диагностический лог: имя + сервисы + адрес
                val svc = serviceUuids?.joinToString(",") ?: "-"
                if (discoveredDevices.none { it.address == device.address }) {
                    discoveredDevices.add(device)
                    log("[BLUETOOTH] BLE adv: '$name' (${device.address}) services=$svc")
                }
            }
        }

        handler.postDelayed({
            bluetoothManager.stopDiscovery()
            bluetoothManager.stopBleScan()
            if (isAutoConnecting) return@postDelayed
            log("[BLUETOOTH] Попытка $scanAttempt: найдено ${discoveredDevices.size} устройств, FEST не обнаружен")
            runScanAttempt()
        }, SCAN_DURATION_MS)
    }

    private fun stopScanAndConnect(device: BluetoothDevice) {
        if (isAutoConnecting) return
        isAutoConnecting = true
        bluetoothManager.stopDiscovery()
        bluetoothManager.stopBleScan()
        log("[BLUETOOTH] Автоподключение к протезу: ${device.name} (${device.address})")
        connectBluetoothDevice(device.address)
    }

    private fun showDeviceSelectionDialog(
        bonded: List<BluetoothDevice>,
        discovered: List<BluetoothDevice>
    ) {
        val allDevices = linkedMapOf<String, BluetoothDevice>()
        for (d in bonded) allDevices[d.address] = d
        for (d in discovered) allDevices.putIfAbsent(d.address, d)

        if (allDevices.isEmpty()) {
            Toast.makeText(
                this,
                "Устройства не найдены. Включите видимость протеза и попробуйте ещё раз.",
                Toast.LENGTH_LONG
            ).show()
            log("[BLUETOOTH] Устройства не найдены")
            return
        }

        val deviceNames = allDevices.values.map { "${it.name} (${it.address})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Выберите Bluetooth устройство")
            .setItems(deviceNames) { _, which ->
                connectBluetoothDevice(allDevices.values.elementAt(which).address)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun connectBluetoothDevice(deviceAddress: String) {
        log("[BLUETOOTH] Подключение к $deviceAddress...")
        Toast.makeText(this, "Подключение к устройству...", Toast.LENGTH_SHORT).show()

        val device = bluetoothManager.getDevice(deviceAddress)
        if (bluetoothManager.isLeDevice(device)) {
            connectBleDevice(deviceAddress)
        } else {
            connectClassicDevice(deviceAddress)
        }
    }

    private fun onBleConnected(deviceAddress: String) {
        isBluetoothConnected = true
        saveDeviceAddress(deviceAddress)
        log("[BLUETOOTH] ✅ BLE подключено к $deviceAddress")
        btConnectButton.text = "Bluetooth: Подключено (BLE)"
        btConnectButton.setBackgroundColor(0xFF4CAF50.toInt())
        Toast.makeText(this, "✅ BLE подключено!", Toast.LENGTH_SHORT).show()
        // Принудительно раскрываем кисть (как в Python-версии)
        val opened = bluetoothManager.sendCommandBle("открыть")
        log(
            if (opened) "[BLUETOOTH] ✅ Кисть принудительно открыта"
            else "[BLUETOOTH] ❌ Не удалось раскрыть кисть"
        )
    }

    private fun connectBleDevice(deviceAddress: String) {
        log("[BLUETOOTH] Подключение через BLE (GATT)...")
        bluetoothManager.connectBle(deviceAddress) { ok ->
            runOnUiThread {
                if (ok) {
                    onBleConnected(deviceAddress)
                } else {
                    log("[BLUETOOTH] ❌ BLE не удалось, пробуем classic (RFCOMM)...")
                    connectClassicDevice(deviceAddress)
                }
            }
        }
    }

    private fun connectClassicDevice(deviceAddress: String) {
        if (bluetoothManager.connect(deviceAddress)) {
            isBluetoothConnected = true
            saveDeviceAddress(deviceAddress)
            log("[BLUETOOTH] ✅ Подключено к $deviceAddress")
            btConnectButton.text = "Bluetooth: Подключено"
            btConnectButton.setBackgroundColor(0xFF4CAF50.toInt())
            Toast.makeText(this, "✅ Подключено!", Toast.LENGTH_SHORT).show()
        } else {
            isBluetoothConnected = false
            log("[BLUETOOTH] ❌ Ошибка подключения к $deviceAddress")
            Toast.makeText(this, "❌ Ошибка подключения", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveDeviceAddress(address: String) {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        prefs.edit().putString("bt_device_address", address).apply()
    }

    private fun getSavedDeviceAddress(): String? {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        return prefs.getString("bt_device_address", null)
    }

    private fun sendModbusCommand(gesture: String) {
        // 1. BLE уже подключено
        if (bluetoothManager.isBleConnected) {
            val ok = bluetoothManager.sendCommandBle(gesture)
            handleSendResult(gesture, ok)
            return
        }

        // 2. Classic (RFCOMM) уже подключено
        if (bluetoothManager.isClassicConnected) {
            val ok = bluetoothManager.sendCommand(gesture)
            handleSendResult(gesture, ok)
            return
        }

        // 3. Автоподключение к сохранённому устройству
        val savedAddress = getSavedDeviceAddress()
        if (savedAddress == null) {
            log("[BLUETOOTH] ❌ Нет сохраненного Bluetooth устройства")
            Toast.makeText(this, "Сначала подключите Bluetooth устройство", Toast.LENGTH_SHORT).show()
            return
        }

        log("[BLUETOOTH] Попытка подключения к сохраненному устройству...")
        val device = bluetoothManager.getDevice(savedAddress)
        if (bluetoothManager.isLeDevice(device)) {
            bluetoothManager.connectBle(savedAddress) { ok ->
                runOnUiThread {
                    if (ok) {
                        isBluetoothConnected = true
                        log("[BLUETOOTH] ✅ BLE подключено к сохраненному устройству")
                        btConnectButton.text = "Bluetooth: Подключено (BLE)"
                        btConnectButton.setBackgroundColor(0xFF4CAF50.toInt())
                        val sent = bluetoothManager.sendCommandBle(gesture)
                        handleSendResult(gesture, sent)
                    } else {
                        log("[BLUETOOTH] ❌ BLE reconnect failed")
                        Toast.makeText(this, "Не удалось подключиться", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
            if (bluetoothManager.connect(savedAddress)) {
                isBluetoothConnected = true
                btConnectButton.text = "Bluetooth: Подключено"
                btConnectButton.setBackgroundColor(0xFF4CAF50.toInt())
                log("[BLUETOOTH] ✅ Подключено к сохраненному устройству")
                handleSendResult(gesture, bluetoothManager.sendCommand(gesture))
            } else {
                log("[BLUETOOTH] ❌ Не удалось подключиться к сохраненному устройству")
                Toast.makeText(this, "Сначала подключите Bluetooth устройство", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleSendResult(gesture: String, ok: Boolean) {
        if (ok) {
            log("[BLUETOOTH] ✅ Команда '$gesture' отправлена")
            btConnectButton.text = "✅ $gesture отправлено"
            handler.postDelayed({
                btConnectButton.text = if (bluetoothManager.isBleConnected) {
                    "Bluetooth: Подключено (BLE)"
                } else {
                    "Bluetooth: Подключено"
                }
            }, 1500)
        } else {
            log("[BLUETOOTH] ❌ Ошибка отправки команды '$gesture'")
            isBluetoothConnected = false
            btConnectButton.text = "Bluetooth: Ошибка"
            btConnectButton.setBackgroundColor(0xFFFF4444.toInt())
            handler.postDelayed({
                btConnectButton.text = if (bluetoothManager.isBleConnected) {
                    "Bluetooth: Подключено (BLE)"
                } else {
                    "Bluetooth: Подключено"
                }
                btConnectButton.setBackgroundColor(0xFF4CAF50.toInt())
            }, 2000)
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(keywordReceiver)
        } catch (_: Exception) {}
        speechService?.stop()
        speechService?.shutdown()
        model?.close()
        bluetoothManager.stopDiscovery()
        bluetoothManager.stopBleScan()
        bluetoothManager.disconnect()
        bluetoothManager.disconnectBle()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        log("📢 onRequestPermissionsResult: requestCode=$requestCode")

        when (requestCode) {
            PERMISSION_RECORD -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    log("✅ Разрешение на микрофон ПОЛУЧЕНО")
                    initModel()
                } else {
                    log("❌ Разрешение на микрофон ОТКЛОНЕНО!")
                    Toast.makeText(this, "Нужен доступ к микрофону", Toast.LENGTH_LONG).show()
                }
            }
            PERMISSION_BLUETOOTH -> {
                val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                if (allGranted) {
                    log("✅ Bluetooth разрешения получены")
                    Toast.makeText(this, "Bluetooth разрешения получены", Toast.LENGTH_SHORT).show()
                    showBluetoothDevicesDialog()
                } else {
                    log("❌ Bluetooth разрешения ОТКЛОНЕНЫ")
                    Toast.makeText(this, "Нужны разрешения для Bluetooth", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun initModel() {
        log("========== initModel() ВЫЗВАН ==========")
        state = State.LOADING
        updateStateUI()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val modelDir = File(filesDir, "model-ru")
                val confFile = File(modelDir, "conf/model.conf")

                log("📁 Путь к модели: ${modelDir.absolutePath}")
                log("📁 conf/model.conf существует: ${confFile.exists()}")

                if (!confFile.exists()) {
                    log("[INFO] Extracting model-ru.zip...")
                    modelDir.deleteRecursively()
                    modelDir.mkdirs()

                    val zipBytes = assets.open("model-ru.zip").use { it.readBytes() }
                    ZipInputStream(zipBytes.inputStream()).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            var entryName = entry.name
                            if (entryName.startsWith("model-ru/")) {
                                entryName = entryName.substringAfter("model-ru/")
                            }
                            val outFile = File(modelDir, entryName)
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                outFile.outputStream().use { zis.copyTo(it) }
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                    val fileCount = modelDir.walkTopDown().count { it.isFile }
                    log("[INFO] Model extracted: $fileCount files")
                }

                log("🔄 Загружаем модель VOSK...")
                val m = Model(modelDir.absolutePath)
                model = m
                log("✅ МОДЕЛЬ ЗАГРУЖЕНА УСПЕШНО!")

                loadGrammar()
                log("[INFO] VOSK загружен")

                withContext(Dispatchers.Main) {
                    log("📢 Запускаем startListening()...")
                    startListening()
                    log("✅ startListening() вызван в initModel()")
                }
            } catch (e: Exception) {
                log("[ERROR] Model load failed: ${e.message}")
                log("Stack trace: ${e.stackTraceToString()}")
                Log.e(TAG, "Model load", e)
            }
        }
    }

    private fun loadGrammar() {
        try {
            val json = assets.open("grammar.json").bufferedReader().readText()
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                grammarList.add(arr.getString(i).lowercase())
            }
            whitelistWords.addAll(WhitelistCorrector.buildWhitelist(grammarList))
            log("[INFO] Грамматика: ${grammarList.size} фраз, ${whitelistWords.size} слов")
        } catch (e: IOException) {
            log("[WARN] grammar.json not found: $e")
        }
    }

    private fun toggleVoiceService() {
        if (isServiceRunning) {
            stopVoiceService()
        } else {
            startVoiceService()
        }
    }

    private fun startVoiceService() {
        val intent = Intent(this, VoiceRecognitionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        isServiceRunning = true
        serviceToggleButton.text = "Остановить фоновый режим"
        serviceToggleButton.setBackgroundColor(0xFFFF4444.toInt())
        log("[СЕРВИС] Запущен фоновый режим")
    }

    private fun stopVoiceService() {
        val intent = Intent(this, VoiceRecognitionService::class.java)
        stopService(intent)
        isServiceRunning = false
        serviceToggleButton.text = "Запустить фоновый режим"
        serviceToggleButton.setBackgroundColor(0xFFFF9800.toInt())  // жёлтый, как в XML
        log("[СЕРВИС] Фоновый режим остановлен")
    }

    override fun onPartialResult(hypothesis: String?) {
        hypothesis ?: return
        val text = extractText(hypothesis)
        if (text.isBlank() || text == lastPartial) return
        lastPartial = text
        lastPartialTime = System.currentTimeMillis()

        when (state) {
            State.LISTENING -> {
                log("[СЛУШАЮ] $text")
                if (matchesKeyword(text)) {
                    log("*** КЛЮЧЕВОЕ СЛОВО: '$KEYWORD' ***")
                    onWakeWordDetected()
                }
            }
            State.CAPTURING -> {
                partialLabel.text = text
                capturedText.clear()
                capturedText.append(text)
            }
            else -> {}
        }
    }

    override fun onResult(hypothesis: String?) {
        hypothesis ?: return
        val text = extractText(hypothesis)
        if (text.isBlank()) return
        if (state == State.CAPTURING) {
            capturedText.clear()
            capturedText.append(text)
        }
    }

    override fun onFinalResult(hypothesis: String?) {
        hypothesis ?: return
        val text = extractText(hypothesis)
        if (text.isBlank()) return

        when (state) {
            State.LISTENING -> {
                if (matchesKeyword(text)) {
                    onWakeWordDetected()
                }
            }
            State.CAPTURING -> {
                capturedText.append(" ").append(text)
            }
            else -> {}
        }
    }

    override fun onError(e: Exception?) {
        log("[ERROR] VOSK: ${e?.message}")
        if (state == State.LISTENING || state == State.CAPTURING) {
            handler.postDelayed({
                try {
                    speechService?.stop()
                    startListening()
                    log("[INFO] SpeechService restarted after error")
                } catch (ex: Exception) {
                    log("[ERROR] Restart failed: ${ex.message}")
                }
            }, 1000)
        }
    }

    override fun onTimeout() {
        if (state == State.CAPTURING && capturedText.isNotBlank()) {
            state = State.PROCESSING
            updateStateUI()
            processCommand()
        }
    }

    private fun startListening() {
        //log("========== startListening() ВЫЗВАН ==========")
        state = State.LISTENING
        updateStateUI()
        log("[INFO] SpeechService started (mic listening)")

        try {
            if (model == null) {
                log("❌ model == null! Не могу запустить SpeechService")
                return
            }

            //log("🔄 Создаем Recognizer...")
            val recognizer = Recognizer(model, SAMPLE_RATE)
            //log("✅ Recognizer создан")

            //log("🔄 Создаем SpeechService...")
            speechService = SpeechService(recognizer, SAMPLE_RATE)
            //log("✅ SpeechService создан")

            //log("🔄 Запускаем SpeechService...")
            speechService?.startListening(this)
            //log("✅ SpeechService успешно запущен")

            // Принудительно обновляем UI
            handler.post {
                stateLabel.text = "[СЛУШАЮ] Ожидание ключевого слова..."
                stateLabel.setTextColor(0xFF8099CC.toInt())
            }
        } catch (e: Exception) {
            log("❌ Ошибка запуска SpeechService: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun onWakeWordDetected() {
        log("*** КЛЮЧЕВОЕ СЛОВО: '$KEYWORD' ***")
        state = State.CAPTURING
        captureStart = System.currentTimeMillis()
        capturedText.clear()
        lastPartial = ""
        responseLabel.text = "Говорите..."
        responseLabel.setTextColor(0xFFFFAA33.toInt())
        updateStateUI()

        handler.postDelayed({
            if (state == State.CAPTURING) {
                state = State.PROCESSING
                updateStateUI()
                processCommand()
            }
        }, PACKET_TIMEOUT)
    }

    private fun processCommand() {
        // Старт задержки — момент последней частичной транскрипции
        // (пользователь закончил говорить); замер = конец речи → выполнение жеста.
        val tStart = lastPartialTime
        val raw = capturedText.toString().trim()
        state = State.PROCESSING
        updateStateUI()

        log("[RAW] $raw")

        if (raw.isBlank()) {
            log("[!] Пустой захват")
            partialLabel.text = ""
            state = State.LISTENING
            updateStateUI()
            return
        }

        val textForMatch = if (WhitelistCorrector.shouldCorrect(raw, whitelistWords)) {
            val corrected = WhitelistCorrector.correctPhrase(raw, whitelistWords)
            if (corrected != raw.lowercase()) {
                log("[CORR] '$raw' → '$corrected'")
            }
            corrected
        } else {
            raw.lowercase()
        }

        val (matched, score) = fuzzyMatch(textForMatch)

        val displayText = if (matched != null) {
            log("[MATCH] ${"%.1f".format(score * 100)}% → '$matched'")
            matched
        } else {
            log("[MATCH] ${"%.1f".format(score * 100)}% — below threshold")
            raw
        }

        log("[ПАКЕТ] $displayText")
        partialLabel.text = ""

        val parsed = parsePacket(displayText)
        if (parsed == null) {
            log("[!] KEYWORD not found in packet")
            responseLabel.text = "Ошибка: пакет не распознан"
            responseLabel.setTextColor(0xFFFF4444.toInt())
        } else {
            val result = execute(parsed, raw)
            val elapsedMs = System.currentTimeMillis() - tStart
            log("[ОТВЕТ] $result")
            log("[ТАЙМЕР] Обработка команды: $elapsedMs мс")
            responseLabel.text = result
            responseLabel.setTextColor(
                if (result.contains("Не распознан") || result.contains("Неизвестная"))
                    0xFFFF8833.toInt()
                else
                    0xFF44FF44.toInt()
            )
        }

        state = State.LISTENING
        updateStateUI()
    }

    private fun fuzzyMatch(raw: String): Pair<String?, Double> {
        val rawLower = raw.lowercase().trim()
        var bestMatch: String? = null
        var bestScore = 0.0

        for (phrase in grammarList) {
            val score = similarity(rawLower, phrase)
            if (score > bestScore) {
                bestScore = score
                bestMatch = phrase
            }
        }

        return if (bestScore >= MATCH_THRESHOLD) {
            Pair(bestMatch, bestScore)
        } else {
            Pair(null, bestScore)
        }
    }

    private fun similarity(a: String, b: String): Double {
        val m = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in a.indices) {
            for (j in b.indices) {
                m[i + 1][j + 1] = if (a[i] == b[j]) {
                    m[i][j] + 1
                } else {
                    max(m[i + 1][j], m[i][j + 1])
                }
            }
        }
        val lcs = m[a.length][b.length]
        val total = a.length + b.length
        return if (total == 0) 1.0 else (2.0 * lcs) / total
    }

    // ── Фаззи-детекция ключевого слова (порог 60%) ─────────────
    private fun keywordIndex(words: List<String>): Int {
        for (i in words.indices) {
            val w = words[i]
            if (w.length >= 4 && similarity(w, KEYWORD) >= KEYWORD_MATCH_THRESHOLD) {
                return i
            }
        }
        return -1
    }

    private fun matchesKeyword(text: String): Boolean {
        val words = text.lowercase()
            .replace(Regex("[^\\w\\s]"), "")
            .split("\\s+".toRegex())
            .filter { it.isNotBlank() }
        return keywordIndex(words) >= 0
    }

    private fun parsePacket(text: String): Map<String, String>? {
        val words = text.lowercase()
            .replace(Regex("[^\\w\\s]"), "")
            .split("\\s+".toRegex())
            .filter { it.isNotBlank() }

        // Ключевое слово могло быть "съедено" wake word-детекцией ("протез" уже сказан).
        // Если его нет в пакете — берём весь текст как тело команды.
        val idx = keywordIndex(words)
        val body = if (idx >= 0) words.drop(idx + 1) else words

        val endIdx = body.indexOfFirst { it in endSynonyms }

        val command: String
        val payload: String

        if (endIdx < 0) {
            command = body.getOrElse(0) { "" }
            payload = body.drop(1).joinToString(" ")
        } else if (endIdx == 0) {
            command = ""
            payload = ""
        } else {
            command = body[0]
            payload = body.subList(1, endIdx).joinToString(" ")
        }

        return mapOf("command" to command, "payload" to payload)
    }

    // ── Распознавание жеста с фаззи-порогом (устойчиво к ошибкам Vosk) ──
    private fun findGesture(text: String): String? {
        if (text.isBlank()) return null

        // 1) Точное вхождение (подстрока)
        gestures.find { it in text }?.let { return it }

        // 2) Фаззи: слово в тексте похоже на жест (сниженный порог 0.6)
        val words = text.lowercase()
            .replace(Regex("[^\\w\\s]"), "")
            .split("\\s+".toRegex())
            .filter { it.isNotBlank() }

        var best: String? = null
        var bestScore = 0.0
        for (g in gestures) {
            for (w in words) {
                val score = similarity(g, w)
                if (score > bestScore) {
                    bestScore = score
                    best = g
                }
            }
        }
        return if (bestScore >= 0.6) best else null
    }

    private fun execute(parsed: Map<String, String>, raw: String = ""): String {
        val cmd = parsed["command"] ?: ""
        val payload = parsed["payload"] ?: ""

        return when (cmd) {
            "жест", "gesture" -> {
                // Ищем жест в payload, затем в исходной транскрипции raw
                val gesture = findGesture(payload) ?: if (raw.isNotBlank()) findGesture(raw) else null
                if (gesture != null) {
                    sendModbusCommand(gesture)
                    "Жест: $gesture"
                } else {
                    "Не распознан"
                }
            }
            "статус", "status" -> {
                sendModbusCommand("status")
                "Батарея 66%"
            }
            else -> "Неизвестная команда: '$cmd'"
        }
    }

    private fun updateStateUI() {
        runOnUiThread {
            stateLabel.text = when (state) {
                State.LOADING -> "[ЗАГРУЗКА VOSK...]"
                State.LISTENING -> "[СЛУШАЮ] Ожидание ключевого слова..."
                State.CAPTURING -> {
                    val elapsed = (System.currentTimeMillis() - captureStart) / 1000.0
                    "⏺ [ЗАПИСЬ КОМАНДЫ] %.1fs / %.0fs".format(elapsed, PACKET_TIMEOUT / 1000.0)
                }
                State.PROCESSING -> "[ОБРАБОТКА...]"
            }

            stateLabel.setTextColor(when (state) {
                State.LOADING -> 0xFFCCCCFF.toInt()
                State.LISTENING -> 0xFF8099CC.toInt()
                State.CAPTURING -> 0xFFFFAA33.toInt()
                State.PROCESSING -> 0xFFFF6644.toInt()
            })
        }
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        handler.post {
            val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            logLines.add("[$ts] $msg")
            if (logLines.size > 200) {
                logLines.removeAt(0)
            }
            logText.text = logLines.joinToString("\n")
            logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun extractText(hypothesis: String): String {
        return try {
            JSONObject(hypothesis).optString("partial",
                JSONObject(hypothesis).optString("text", "")
            ).trim()
        } catch (e: Exception) {
            ""
        }
    }

    override fun onResume() {
        super.onResume()
        if (model != null && (speechService == null || state != State.LOADING)) {
            handler.postDelayed({
                if (state == State.LISTENING || state == State.CAPTURING) {
                    try {
                        speechService?.stop()
                    } catch (_: Exception) {}
                    startListening()
                    log("[INFO] SpeechService resumed")
                }
            }, 500)
        }
    }
}