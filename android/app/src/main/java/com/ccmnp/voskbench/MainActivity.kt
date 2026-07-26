package com.ccmnp.voskbench

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
        private const val RESET_TIMEOUT = 3000L
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

    private var buttonTimerStart = 0L
    private var isTimerRunning = false
    private var lastSpeechTime = 0L
    private var resetRunnable: Runnable? = null

    private var isServiceRunning = false

    private lateinit var bluetoothManager: BluetoothModbusManager
    private var isBluetoothConnected = false

    private lateinit var stateLabel: TextView
    private lateinit var partialLabel: TextView
    private lateinit var responseLabel: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var timerButton: Button
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
        "щипок", "щепок", "щепать",
        "указательный", "пистолет",
        "флексия", "сгибание",
        "экстензия", "разгибание"
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
        timerButton = findViewById(R.id.timerButton)
        serviceToggleButton = findViewById(R.id.serviceToggleButton)
        btConnectButton = findViewById(R.id.btConnectButton)

        bluetoothManager = BluetoothModbusManager(this)

        timerButton.setOnClickListener {
            startTimer()
        }

        serviceToggleButton.setOnClickListener {
            toggleVoiceService()
        }

        btConnectButton.setOnClickListener {
            checkBluetoothPermissionsAndConnect()
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

        val devices = bluetoothManager.getPairedDevices()
        if (devices.isEmpty()) {
            Toast.makeText(
                this,
                "Нет сопряженных устройств. Сопрягите устройство в настройках Bluetooth.",
                Toast.LENGTH_LONG
            ).show()
            log("[BLUETOOTH] Нет сопряженных устройств")
            return
        }

        val deviceNames = devices.map { "${it.name} (${it.address})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Выберите Bluetooth устройство")
            .setItems(deviceNames) { _, which ->
                val device = devices[which]
                connectBluetoothDevice(device.address)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun connectBluetoothDevice(deviceAddress: String) {
        log("[BLUETOOTH] Подключение к $deviceAddress...")
        Toast.makeText(this, "Подключение к устройству...", Toast.LENGTH_SHORT).show()

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
        if (!isBluetoothConnected) {
            val savedAddress = getSavedDeviceAddress()
            if (savedAddress != null) {
                log("[BLUETOOTH] Попытка подключения к сохраненному устройству...")
                if (bluetoothManager.connect(savedAddress)) {
                    isBluetoothConnected = true
                    btConnectButton.text = "Bluetooth: Подключено"
                    btConnectButton.setBackgroundColor(0xFF4CAF50.toInt())
                    log("[BLUETOOTH] ✅ Подключено к сохраненному устройству")
                } else {
                    log("[BLUETOOTH] ❌ Не удалось подключиться к сохраненному устройству")
                    Toast.makeText(this, "Сначала подключите Bluetooth устройство", Toast.LENGTH_SHORT).show()
                    return
                }
            } else {
                log("[BLUETOOTH] ❌ Нет сохраненного Bluetooth устройства")
                Toast.makeText(this, "Сначала подключите Bluetooth устройство", Toast.LENGTH_SHORT).show()
                return
            }
        }

        val result = bluetoothManager.sendCommand(gesture)
        if (result) {
            log("[BLUETOOTH] ✅ Команда '$gesture' отправлена")
            btConnectButton.text = "✅ $gesture отправлено"
            handler.postDelayed({
                btConnectButton.text = "Bluetooth: Подключено"
            }, 1500)
        } else {
            log("[BLUETOOTH] ❌ Ошибка отправки команды '$gesture'")
            isBluetoothConnected = false
            btConnectButton.text = "Bluetooth: Ошибка"
            btConnectButton.setBackgroundColor(0xFFFF4444.toInt())
            handler.postDelayed({
                btConnectButton.text = "Bluetooth: Подключено"
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
        resetTimer()
        bluetoothManager.disconnect()
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
        serviceToggleButton.setBackgroundColor(0xFF2196F3.toInt())
        log("[СЕРВИС] Фоновый режим остановлен")
    }

    private fun startTimer() {
        resetTimer()
        buttonTimerStart = System.currentTimeMillis()
        isTimerRunning = true
        lastSpeechTime = System.currentTimeMillis()

        timerButton.text = "Таймер запущен..."
        timerButton.setBackgroundColor(0xFF4CAF50.toInt())

        log("[ТАЙМЕР] Запущен")
        scheduleReset()
    }

    private fun resetTimer() {
        isTimerRunning = false
        buttonTimerStart = 0L
        lastSpeechTime = 0L

        resetRunnable?.let { handler.removeCallbacks(it) }
        resetRunnable = null

        timerButton.text = "Старт таймера"
        timerButton.setBackgroundColor(0xFF2196F3.toInt())

        log("[ТАЙМЕР] Сброшен")
    }

    private fun scheduleReset() {
        resetRunnable?.let { handler.removeCallbacks(it) }
        resetRunnable = Runnable {
            if (isTimerRunning && System.currentTimeMillis() - lastSpeechTime >= RESET_TIMEOUT) {
                log("[ТАЙМЕР] Сброс по таймауту (${RESET_TIMEOUT}ms без речи)")
                resetTimer()
            }
        }
        handler.postDelayed(resetRunnable!!, RESET_TIMEOUT)
    }

    private fun getDuration(): Long {
        return if (isTimerRunning && buttonTimerStart > 0) {
            System.currentTimeMillis() - buttonTimerStart
        } else {
            0L
        }
    }

    override fun onPartialResult(hypothesis: String?) {
        hypothesis ?: return
        val text = extractText(hypothesis)
        if (text.isBlank() || text == lastPartial) return
        lastPartial = text
        lastPartialTime = System.currentTimeMillis()

        if (isTimerRunning) {
            lastSpeechTime = System.currentTimeMillis()
            scheduleReset()
        }

        when (state) {
            State.LISTENING -> {
                if (isTimerRunning) {
                    val duration = getDuration()
                    log("[СЛУШАЮ] $text (${duration}ms)")
                    if (text.contains(KEYWORD, ignoreCase = true)) {
                        log("*** КЛЮЧЕВОЕ СЛОВО: '$KEYWORD' *** (${duration}ms)")
                        resetTimer()
                        onWakeWordDetected()
                    }
                } else {
                    log("[СЛУШАЮ] $text")
                    if (text.contains(KEYWORD, ignoreCase = true)) {
                        log("*** КЛЮЧЕВОЕ СЛОВО: '$KEYWORD' ***")
                        onWakeWordDetected()
                    }
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
                if (isTimerRunning) {
                    val duration = getDuration()
                    log("[ФИНАЛ] $text (${duration}ms)")
                    if (text.contains(KEYWORD, ignoreCase = true)) {
                        log("*** КЛЮЧЕВОЕ СЛОВО: '$KEYWORD' *** (${duration}ms)")
                        resetTimer()
                        onWakeWordDetected()
                    }
                } else {
                    if (text.contains(KEYWORD, ignoreCase = true)) {
                        onWakeWordDetected()
                    }
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
            val result = execute(parsed)
            log("[ОТВЕТ] $result")
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

    private fun parsePacket(text: String): Map<String, String>? {
        val words = text.lowercase()
            .replace(Regex("[^\\w\\s]"), "")
            .split("\\s+".toRegex())
            .filter { it.isNotBlank() }

        val idx = words.indexOf(KEYWORD)
        if (idx < 0) return null

        val body = words.drop(idx + 1)
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

    private fun execute(parsed: Map<String, String>): String {
        val cmd = parsed["command"] ?: ""
        val payload = parsed["payload"] ?: ""

        return when (cmd) {
            "жест", "gesture" -> {
                val gesture = gestures.find { it in payload }
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