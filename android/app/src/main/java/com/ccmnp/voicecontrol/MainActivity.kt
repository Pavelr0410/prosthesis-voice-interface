package com.ccmnp.voicecontrol

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max

/* ================================================================
 * ГолосЖест — голосовое управление бионическим протезом
 * Android-стенд для портирования VOSK real-time UI
 *
 * Архитектура:
 *   - VOSK small-ru-0.22, свободное распознавание + fuzzy match
 *   - Состояния: LOADING → LISTENING → CAPTURING → PROCESSING → LISTENING
 *   - Wake word: "протез"
 *   - Команды: жест <payload> выполняй | статус выполняй
 * ================================================================ */

class MainActivity : AppCompatActivity(), RecognitionListener {

    companion object {
        private const val TAG = "VoiceControl"
        private const val SAMPLE_RATE = 16000f
        private const val PERMISSION_RECORD = 1001
        private const val PACKET_TIMEOUT = 5000L      // макс. длительность захвата
        private const val MATCH_THRESHOLD = 0.85      // порог fuzzy match
        private const val KEYWORD = "протез"
    }

    // ── Состояние ────────────────────────────────────────────
    private enum class State { LOADING, LISTENING, CAPTURING, PROCESSING }

    private var state = State.LOADING
    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var captureStart = 0L
    private val capturedText = StringBuilder()
    private val grammarList = mutableListOf<String>()

    // ── UI ────────────────────────────────────────────────────
    private lateinit var stateLabel: TextView
    private lateinit var partialLabel: TextView
    private lateinit var responseLabel: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView

    private val logLines = mutableListOf<String>()
    private val handler = Handler(Looper.getMainLooper())

    // ── End-word synonyms ─────────────────────────────────────
    private val endSynonyms = setOf(
        "выполнять", "выполняй", "выполняйте", "выполни",
        "поехали", "давай", "старт"
    )

    // ── Gestures ──────────────────────────────────────────────
    private val gestures = setOf(
        "нейтральный", "нейтраль", "большой палец",
        "сжатие пальцев", "сжатие", "кулак",
        "разжатие пальцев", "разжатие", "открытая ладонь", "открыть",
        "щипок", "щепок", "щепать",
        "указательный", "пистолет",
        "флексия", "сгибание",
        "экстензия", "разгибание"
    )

    // ═══════════════════════════════════════════════════════════
    // Жизненный цикл
    // ═══════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        stateLabel = findViewById(R.id.stateLabel)
        partialLabel = findViewById(R.id.partialLabel)
        responseLabel = findViewById(R.id.responseLabel)
        logText = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)

        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_RECORD)
    }

    // ── Разрешения ────────────────────────────────────────────
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_RECORD && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            initModel()
        } else {
            Toast.makeText(this, "Нужен доступ к микрофону", Toast.LENGTH_LONG).show()
        }
    }

    // ── Модель: скачивание + загрузка ─────────────────────────
    private fun initModel() {
        state = State.LOADING
        updateStateUI()

        StorageService.unpack(this, "model-ru", "model",
            { model ->
                this.model = model
                loadGrammar()
                log("[INFO] VOSK загружен")
                startListening()
            },
            { e -> log("[ERROR] Model load failed: $e") }
        )
    }

    private fun loadGrammar() {
        try {
            val json = assets.open("grammar.json").bufferedReader().readText()
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                grammarList.add(arr.getString(i).lowercase())
            }
            log("[INFO] Грамматика: ${grammarList.size} фраз")
        } catch (e: IOException) {
            log("[WARN] grammar.json not found: $e")
        }
    }

    // ═══════════════════════════════════════════════════════════
    // VOSK RecognitionListener
    // ═══════════════════════════════════════════════════════════

    override fun onPartialResult(hypothesis: String?) {
        hypothesis ?: return
        val text = extractText(hypothesis)
        if (text.isBlank()) return

        when (state) {
            State.LISTENING -> {
                log("[СЛУШАЮ] $text")
                if (text.contains(KEYWORD, ignoreCase = true)) {
                    onWakeWordDetected()
                }
            }
            State.CAPTURING -> {
                partialLabel.text = text
            }
            else -> {}
        }
    }

    override fun onResult(hypothesis: String?) {
        // Final result — not used in our architecture (we use partial results)
    }

    override fun onFinalResult(hypothesis: String?) {
        hypothesis ?: return
        val text = extractText(hypothesis)
        if (text.isBlank()) return

        when (state) {
            State.LISTENING -> {
                if (text.contains(KEYWORD, ignoreCase = true)) {
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
        log("[ERROR] VOSK: $e")
    }

    override fun onTimeout() {
        if (state == State.CAPTURING) {
            processCommand()
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Логика автомата
    // ═══════════════════════════════════════════════════════════

    private fun startListening() {
        state = State.LISTENING
        updateStateUI()

        val recognizer = Recognizer(model, SAMPLE_RATE)
        speechService = SpeechService(recognizer, SAMPLE_RATE)
        speechService?.startListening(this)
    }

    private fun onWakeWordDetected() {
        log("*** КЛЮЧЕВОЕ СЛОВО: '$KEYWORD' ***")
        state = State.CAPTURING
        captureStart = System.currentTimeMillis()
        capturedText.clear()
        updateStateUI()

        // Таймаут: если пользователь молчит > PACKET_TIMEOUT → process
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

        // Fuzzy match против grammar.json
        val (matched, score) = fuzzyMatch(raw)

        val displayText = if (matched != null) {
            log("[MATCH] ${"%.1f".format(score * 100)}% → '$matched'")
            matched
        } else {
            log("[MATCH] ${"%.1f".format(score * 100)}% — below threshold")
            raw
        }

        log("[ПАКЕТ] $displayText")
        partialLabel.text = ""

        // Парсинг
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

    // ═══════════════════════════════════════════════════════════
    // Fuzzy match
    // ═══════════════════════════════════════════════════════════

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

    /** Simplified SequenceMatcher.ratio() */
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

    // ═══════════════════════════════════════════════════════════
    // Парсинг
    // ═══════════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════════
    // Исполнение
    // ═══════════════════════════════════════════════════════════

    private fun execute(parsed: Map<String, String>): String {
        val cmd = parsed["command"] ?: ""
        val payload = parsed["payload"] ?: ""

        return when (cmd) {
            "жест", "gesture" -> {
                val gesture = gestures.find { it in payload }
                if (gesture != null) "Жест: $gesture"
                else "Не распознан"
            }
            "статус", "status" -> "Батарея 66%"
            else -> "Неизвестная команда: '$cmd'"
        }
    }

    // ═══════════════════════════════════════════════════════════
    // UI helpers
    // ═══════════════════════════════════════════════════════════

    private fun updateStateUI() {
        stateLabel.text = when (state) {
            State.LOADING -> getString(R.string.state_loading)
            State.LISTENING -> getString(R.string.state_listening)
            State.CAPTURING -> {
                val elapsed = (System.currentTimeMillis() - captureStart) / 1000.0
                getString(R.string.state_capturing, elapsed, PACKET_TIMEOUT / 1000.0)
            }
            State.PROCESSING -> getString(R.string.state_processing)
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

    // ═══════════════════════════════════════════════════════════
    // Завершение
    // ═══════════════════════════════════════════════════════════

    override fun onDestroy() {
        speechService?.stop()
        speechService?.shutdown()
        model?.close()
        super.onDestroy()
    }
}
