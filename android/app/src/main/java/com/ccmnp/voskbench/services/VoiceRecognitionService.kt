package com.ccmnp.voskbench.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File

class VoiceRecognitionService : Service(), RecognitionListener {

    companion object {
        private const val TAG = "VoiceService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "voice_control_channel"
        private const val KEYWORD = "протез"
        private const val KEYWORD_MATCH_THRESHOLD = 0.6
    }

    private var speechService: SpeechService? = null
    private var model: Model? = null
    private var isListening = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "========== SERVICE CREATED ==========")
        initModel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "========== SERVICE STARTED ==========")

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        // Если модель уже загружена - запускаем прослушивание
        if (model != null) {
            startListening()
        } else {
            Log.e(TAG, "Model is null, cannot start listening")
            // Пробуем загрузить модель еще раз
            initModel()
            if (model != null) {
                startListening()
            } else {
                Log.e(TAG, "Still cannot load model, stopping service")
                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun initModel() {
        try {
            // Показываем путь к модели
            val modelDir = File(filesDir, "model-ru")
            Log.d(TAG, "Model path: ${modelDir.absolutePath}")
            Log.d(TAG, "Model exists: ${modelDir.exists()}")

            if (!modelDir.exists()) {
                Log.e(TAG, "❌ Model directory not found at: ${modelDir.absolutePath}")
                // Пробуем скопировать модель из assets
                copyModelFromAssets()
                return
            }

            // Проверяем, что в папке есть файлы модели
            val files = modelDir.listFiles()
            if (files == null || files.isEmpty()) {
                Log.e(TAG, "❌ Model directory is empty")
                copyModelFromAssets()
                return
            }

            Log.d(TAG, "Model files: ${files.map { it.name }}")
            model = Model(modelDir.absolutePath)
            Log.d(TAG, "✅ Model loaded successfully from: ${modelDir.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to load model: ${e.message}")
            Log.e(TAG, "Stack trace:", e)
            stopSelf()
        }
    }

    private fun copyModelFromAssets() {
        try {
            Log.d(TAG, "Attempting to copy model from assets...")
            val modelDir = File(filesDir, "model-ru")
            modelDir.mkdirs()

            // Проверяем, есть ли модель в assets
            val assetFiles = assets.list("")
            if (assetFiles != null && assetFiles.contains("model-ru.zip")) {
                Log.d(TAG, "Found model-ru.zip in assets")
                // Здесь нужно распаковать zip, но это сложно сделать в сервисе
                // Поэтому просто логируем ошибку
                Log.e(TAG, "Model needs to be extracted from assets. Please run MainActivity first to extract the model.")
            } else {
                Log.e(TAG, "Model not found in assets")
            }

            // Пробуем использовать модель из папки приложения (если есть)
            val altModelDir = File("/data/data/${packageName}/files/model-ru")
            if (altModelDir.exists()) {
                Log.d(TAG, "Found model in alternative path: ${altModelDir.absolutePath}")
                model = Model(altModelDir.absolutePath)
                Log.d(TAG, "✅ Model loaded from alternative path")
                return
            }

            Log.e(TAG, "❌ Model not found in any location")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to copy model: ${e.message}")
        }
    }

    private fun startListening() {
        if (model == null) {
            Log.e(TAG, "❌ Model is null, cannot start listening")
            return
        }

        try {
            Log.d(TAG, "Creating Recognizer...")
            val recognizer = Recognizer(model, 16000f)
            Log.d(TAG, "✅ Recognizer created")

            Log.d(TAG, "Creating SpeechService...")
            speechService = SpeechService(recognizer, 16000f)
            Log.d(TAG, "✅ SpeechService created")

            Log.d(TAG, "Starting SpeechService...")
            speechService?.startListening(this)
            isListening = true
            Log.d(TAG, "✅✅✅ Listening started successfully! ✅✅✅")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start listening: ${e.message}")
            Log.e(TAG, "Stack trace:", e)
        }
    }

    private fun createNotificationChannel() {
        try {
            Log.d(TAG, "Creating notification channel...")
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Голосовое управление",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Слушает микрофон для голосового управления"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
            Log.d(TAG, "✅ Notification channel created")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to create notification channel: ${e.message}")
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ГолосЖест")
            .setContentText("🔴 Слушаю микрофон...")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onPartialResult(hypothesis: String?) {
        hypothesis ?: return
        val text = extractText(hypothesis)
        if (text.isBlank()) return

        Log.d(TAG, "[СЛУШАЮ] $text")

        if (matchesKeyword(text)) {
            Log.d(TAG, "*** КЛЮЧЕВОЕ СЛОВО: '$KEYWORD' ***")
            sendKeywordBroadcast()
        }
    }

    override fun onResult(hypothesis: String?) {
        // Можно игнорировать
    }

    override fun onFinalResult(hypothesis: String?) {
        // Можно игнорировать
    }

    override fun onError(e: Exception?) {
        Log.e(TAG, "❌ VOSK Error: ${e?.message}")
        if (e?.message?.contains("no speech") == true) {
            // Это не критичная ошибка
            Log.d(TAG, "No speech detected, continuing...")
        } else {
            restartListening()
        }
    }

    override fun onTimeout() {
        Log.d(TAG, "VOSK Timeout")
        // Не перезапускаем при таймауте, просто ждем следующую речь
    }

    private fun restartListening() {
        try {
            Log.d(TAG, "Restarting listening...")
            speechService?.stop()
            speechService?.shutdown()
            Thread.sleep(100)
            startListening()
            Log.d(TAG, "✅ Listening restarted")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to restart: ${e.message}")
        }
    }

    private fun sendKeywordBroadcast() {
        try {
            val intent = Intent("KEYWORD_DETECTED")
            intent.putExtra("keyword", KEYWORD)
            sendBroadcast(intent)
            Log.d(TAG, "✅ Broadcast sent: KEYWORD_DETECTED")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to send broadcast: ${e.message}")
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

    // ── Фаззи-детекция ключевого слова (порог 60%) ─────────────
    private fun matchesKeyword(text: String): Boolean {
        val words = text.lowercase()
            .replace(Regex("[^\\w\\s]"), "")
            .split("\\s+".toRegex())
            .filter { it.isNotBlank() }
        for (w in words) {
            if (w.length >= 4 && similarity(w, KEYWORD) >= KEYWORD_MATCH_THRESHOLD) {
                return true
            }
        }
        return false
    }

    private fun similarity(a: String, b: String): Double {
        val m = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in a.indices) {
            for (j in b.indices) {
                m[i + 1][j + 1] = if (a[i] == b[j]) {
                    m[i][j] + 1
                } else {
                    maxOf(m[i + 1][j], m[i][j + 1])
                }
            }
        }
        val lcs = m[a.length][b.length]
        val total = a.length + b.length
        return if (total == 0) 1.0 else (2.0 * lcs) / total
    }

    override fun onDestroy() {
        Log.d(TAG, "========== SERVICE DESTROYED ==========")
        try {
            speechService?.stop()
            speechService?.shutdown()
            model?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup: ${e.message}")
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}