package com.example.soundtrigger

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

class RecorderService : Service() {
    companion object {
        const val EXTRA_THRESHOLD = "threshold"
        const val ACTION_STOP = "com.example.soundtrigger.STOP"
        private const val TAG = "SoundTrigger"
        private const val MONITOR_CHANNEL = "soundtrigger_monitor"
        private const val MONITOR_NOTIFICATION_ID = 2001
        private const val ALARM_REQUEST_CODE = 3001
        private const val SAMPLE_RATE = 16000
        private const val SILENCE_LIMIT_MS = 13_000L
        private const val TIMER_START_AFTER_MS = 30_000L
        private const val TIMER_LENGTH_MS = 10 * 60_000L
    }

    @Volatile private var running = false
    @Volatile private var isRecording = false
    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var thresholdDbfs = -40.0
    private var silenceDurationMs = 0L
    private var recordingStartedAtMs = 0L
    private var timerStartedForThisMonitoringSession = false
    private var output: RandomAccessFile? = null
    private var outputFile: File? = null
    private var audioDataBytes = 0L

    override fun onCreate() {
        super.onCreate()
        createMonitorChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            cancelPendingAlarm()
            stopSelf()
            return START_NOT_STICKY
        }
        thresholdDbfs = (intent?.getIntExtra(EXTRA_THRESHOLD, -40) ?: -40).toDouble()
        val notification = Notification.Builder(this, MONITOR_CHANNEL)
            .setContentTitle("Sound Trigger работает")
            .setContentText("Ожидание звука • порог ${thresholdDbfs.toInt()} dBFS")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        startForeground(MONITOR_NOTIFICATION_ID, notification)

        if (!running) startAudioMonitor()
        return START_NOT_STICKY
    }

    private fun startAudioMonitor() {
        running = true
        timerStartedForThisMonitoringSession = false
        silenceDurationMs = 0L
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:AudioMonitor")
        wakeLock?.acquire(12 * 60 * 60 * 1000L)

        worker = Thread({ monitorLoop() }, "SoundTrigger-Audio").also { it.start() }
    }

    private fun monitorLoop() {
        val minBufferBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferBytes <= 0) {
            Log.e(TAG, "Invalid AudioRecord buffer size: $minBufferBytes")
            stopSelf()
            return
        }

        val sampleCount = maxOf(1024, minBufferBytes / 2)
        val samples = ShortArray(sampleCount)
        try {
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                sampleCount * 2
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                throw IllegalStateException("AudioRecord did not initialize")
            }
            audioRecord = recorder
            recorder.startRecording()

            while (running) {
                val count = recorder.read(samples, 0, samples.size)
                if (count <= 0) continue
                val levelDbfs = calculateDbfs(samples, count)
                val chunkDurationMs = count * 1000L / SAMPLE_RATE

                if (!isRecording && levelDbfs >= thresholdDbfs) {
                    beginRecording()
                }

                if (isRecording) {
                    writePcm(samples, count)
                    if (levelDbfs < thresholdDbfs - 5.0) {
                        silenceDurationMs += chunkDurationMs
                    } else {
                        silenceDurationMs = 0L
                    }

                    // The ten-minute timer starts only if this same recording segment
                    // has remained active for at least 30 seconds. A segment ending
                    // earlier due to 13 seconds of silence never starts the timer.
                    if (!timerStartedForThisMonitoringSession &&
                        android.os.SystemClock.elapsedRealtime() - recordingStartedAtMs >= TIMER_START_AFTER_MS) {
                        timerStartedForThisMonitoringSession = true
                        scheduleTenMinuteAlarm()
                    }

                    if (silenceDurationMs >= SILENCE_LIMIT_MS) {
                        finishRecording()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Microphone monitor failed", e)
            stopSelf()
        } finally {
            try { audioRecord?.stop() } catch (_: Exception) {}
            audioRecord?.release()
            audioRecord = null
        }
    }

    private fun calculateDbfs(samples: ShortArray, count: Int): Double {
        var sumSquares = 0.0
        for (i in 0 until count) {
            val value = samples[i].toDouble()
            sumSquares += value * value
        }
        val rms = sqrt(sumSquares / count) / 32768.0
        return if (rms <= 0.000001) -120.0 else 20.0 * log10(rms)
    }

    private fun beginRecording() {
        try {
            val base = getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: filesDir
            val folder = File(base, "SoundTrigger")
            if (!folder.exists() && !folder.mkdirs()) throw IllegalStateException("Cannot create recordings folder")
            val file = File(folder, "recording_${System.currentTimeMillis()}.wav")
            output = RandomAccessFile(file, "rw")
            output?.setLength(0L)
            output?.write(ByteArray(44)) // WAV header is filled when the clip is closed.
            outputFile = file
            audioDataBytes = 0L
            silenceDurationMs = 0L
            recordingStartedAtMs = android.os.SystemClock.elapsedRealtime()
            isRecording = true
            getSharedPreferences("recordings", MODE_PRIVATE).edit()
                .putString("last_path", file.absolutePath).apply()
            Log.i(TAG, "Recording started: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Could not create recording", e)
            isRecording = false
            closeOutputWithoutHeader()
        }
    }

    private fun writePcm(samples: ShortArray, count: Int) {
        val buffer = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) buffer.putShort(samples[i])
        val bytes = buffer.array()
        output?.write(bytes)
        audioDataBytes += bytes.size
    }

    private fun finishRecording() {
        if (!isRecording) return
        isRecording = false
        try {
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt((36L + audioDataBytes).toInt())
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16)
            header.putShort(1)
            header.putShort(1)
            header.putInt(SAMPLE_RATE)
            header.putInt(SAMPLE_RATE * 2)
            header.putShort(2)
            header.putShort(16)
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(audioDataBytes.toInt())
            output?.seek(0L)
            output?.write(header.array())
            output?.fd?.sync()
        } catch (e: Exception) {
            Log.e(TAG, "Could not finalize WAV file", e)
        } finally {
            try { output?.close() } catch (_: Exception) {}
            output = null
            outputFile = null
            silenceDurationMs = 0L
        }
    }

    private fun closeOutputWithoutHeader() {
        try { output?.close() } catch (_: Exception) {}
        output = null
        outputFile = null
    }

    private fun scheduleTenMinuteAlarm() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val alarmIntent = Intent(this, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            ALARM_REQUEST_CODE,
            alarmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAt = System.currentTimeMillis() + TIMER_LENGTH_MS
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                if (Build.VERSION.SDK_INT >= 31 && !alarmManager.canScheduleExactAlarms()) {
                    // Fallback is less exact; request the special access in the app UI.
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                } else {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                }
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
            Log.i(TAG, "Ten-minute alarm scheduled")
        } catch (e: SecurityException) {
            Log.e(TAG, "Exact alarm permission missing", e)
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    private fun cancelPendingAlarm() {
        val intent = Intent(this, AlarmReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            this,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pending != null) {
            val manager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            manager.cancel(pending)
            pending.cancel()
        }
    }

    private fun createMonitorChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(MONITOR_CHANNEL, "Sound Trigger monitoring", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        running = false
        if (isRecording) finishRecording()
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
