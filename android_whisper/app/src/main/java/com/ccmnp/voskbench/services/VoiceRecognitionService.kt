package com.ccmnp.voskbench.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.whispercpp.java.whisper.WhisperContext
import kotlin.math.max

class VoiceRecognitionService : Service() {

    companion object {
        private const val TAG = "VoiceService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "voice_control_channel"
        private const val KEYWORD = "протез"
        private const val KEYWORD_MATCH_THRESHOLD = 0.6
        private const val SAMPLE_RATE = 16000
        private const val WHISPER_MODEL_ASSET = "ggml-tiny-q5_1.bin"
        private const val SPEECH_THRESHOLD = 400f
        private const val SILENCE_MS = 900L
        private const val PACKET_TIMEOUT = 5000L
    }

    private var whisper: WhisperContext? = null
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    @Volatile private var isRecording = false
    private var captureStart = 0L
    private var lastSpeechTs = 0L
    private val capturedSamples = mutableListOf<Float>()
    private val audioLock = Any()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "========== SERVICE CREATED ==========")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "========== SERVICE STARTED ==========")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        if (whisper != null) {
            startListening()
        } else {
            Thread {
                initModel()
                if (whisper != null) {
                    startListening()
                } else {
                    Log.e(TAG, "Still cannot load model, stopping")
                    stopSelf()
                }
            }.start()
        }
        return START_STICKY
    }

    private fun initModel() {
        try {
            Log.d(TAG, "Loading Whisper-tiny from assets...")
            val w = WhisperContext.createContextFromAsset(assets, WHISPER_MODEL_ASSET)
            whisper = w
            Log.d(TAG, "✅ Whisper loaded")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to load model: ${e.message}")
            Log.e(TAG, "Stack trace:", e)
            stopSelf()
        }
    }

    private fun startListening() {
        if (whisper == null) {
            Log.e(TAG, "❌ Whisper is null")
            return
        }
        if (audioThread == null || !audioThread!!.isAlive) {
            isRecording = true
            audioThread = Thread { audioLoop() }
            audioThread!!.start()
        }
    }

    private fun audioLoop() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf)
        audioRecord = rec
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized")
            return
        }
        rec.startRecording()
        val buf = ShortArray(1024)
        while (isRecording) {
            val n = rec.read(buf, 0, buf.size)
            if (n > 0) {
                val rms = computeRms(buf, n)
                handleFrame(buf.copyOf(n), rms)
            }
        }
        try { rec.stop() } catch (_: Exception) {}
        rec.release()
        audioRecord = null
    }

    private fun computeRms(samples: ShortArray, n: Int): Float {
        var sum = 0.0
        for (i in 0 until n) sum += samples[i].toDouble() * samples[i].toDouble()
        return Math.sqrt(sum / n).toFloat()
    }

    private fun handleFrame(samples: ShortArray, rms: Float) {
        when {
            capturedSamples.isEmpty() -> {
                if (rms > SPEECH_THRESHOLD) {
                    synchronized(audioLock) {
                        for (s in samples) capturedSamples.add(s / 32768f)
                    }
                    lastSpeechTs = System.currentTimeMillis()
                    captureStart = System.currentTimeMillis()
                }
            }
            else -> {
                synchronized(audioLock) {
                    for (s in samples) capturedSamples.add(s / 32768f)
                }
                if (rms > SPEECH_THRESHOLD) {
                    lastSpeechTs = System.currentTimeMillis()
                }
                val elapsed = System.currentTimeMillis() - captureStart
                val silence = System.currentTimeMillis() - lastSpeechTs
                if ((elapsed >= 500 && silence >= SILENCE_MS) || elapsed >= PACKET_TIMEOUT) {
                    val samplesToSend = synchronized(audioLock) { capturedSamples.toFloatArray() }
                    capturedSamples.clear()
                    if (samplesToSend.size > SAMPLE_RATE / 4) {
                        processSamples(samplesToSend)
                    }
                }
            }
        }
    }

    private fun processSamples(samples: FloatArray) {
        Thread {
            try {
                val w = whisper ?: return@Thread
                val segments = w.transcribeDataWithTime(samples)
                val text = segments.joinToString(" ") { it.sentence }
                Log.d(TAG, "[СЛУШАЮ] $text")
                if (matchesKeyword(text)) {
                    Log.d(TAG, "*** КЛЮЧЕВОЕ СЛОВО: '$KEYWORD' ***")
                    sendKeywordBroadcast()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Whisper error: ${e.message}")
            }
        }.start()
    }

    private fun createNotificationChannel() {
        try {
            val channel = NotificationChannel(
                CHANNEL_ID, "Голосовое управление", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Слушает микрофон для голосового управления"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
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
        isRecording = false
        try { audioThread?.join(500) } catch (_: Exception) {}
        audioThread = null
        try { audioRecord?.stop() } catch (_: Exception) {}
        audioRecord?.release()
        audioRecord = null
        try { whisper?.release() } catch (_: Exception) {}
        whisper = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
