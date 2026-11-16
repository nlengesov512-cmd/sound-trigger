package com.example.soundtrigger

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import kotlin.math.log10
import kotlin.math.sqrt

class RecorderService : Service() {
    companion object {
        const val EXTRA_THRESHOLD = "threshold"
        const val EXTRA_SILENCE_SECONDS = "silence_seconds"
        const val EXTRA_PAUSE_SECONDS = "pause_seconds"
        const val EXTRA_EPISODE_SECONDS = "episode_seconds"
        const val EXTRA_ALARM_HOUR = "alarm_hour"
        const val EXTRA_ALARM_MINUTE = "alarm_minute"
        const val ACTION_STOP = "com.example.soundtrigger.STOP"
        const val ACTION_STATUS = "com.example.soundtrigger.STATUS"
        private const val TAG = "SoundTrigger"
        private const val MONITOR_CHANNEL = "soundtrigger_monitor"
        private const val MONITOR_NOTIFICATION_ID = 2001
        private const val ALARM_REQUEST_CODE = 3001
        private const val SAMPLE_RATE = 16000
    }

    @Volatile private var running = false
    @Volatile private var isRecording = false
    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var thresholdDbfs = -40.0
    private var silenceLimitMs = 13_000L
    private var allowedPauseMs = 8_000L
    private var episodeTriggerMs = 14_000L
    private var alarmHour = 8
    private var alarmMinute = 0
    private var silenceDurationMs = 0L
    private var episodeSoundMs = 0L
    private var episodeQuietMs = 0L
    private var alarmStartedForThisSession = false
    private var lastStatusAtMs = 0L
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
            running = false
            try { audioRecord?.stop() } catch (_: Exception) {}
            stopSelf()
            return START_NOT_STICKY
        }

        thresholdDbfs = (intent?.getIntExtra(EXTRA_THRESHOLD, -40) ?: -40).toDouble()
        silenceLimitMs = ((intent?.getIntExtra(EXTRA_SILENCE_SECONDS, 13) ?: 13).coerceIn(1, 3600) * 1000L)
        allowedPauseMs = ((intent?.getIntExtra(EXTRA_PAUSE_SECONDS, 8) ?: 8).coerceIn(1, 600) * 1000L)
        episodeTriggerMs = ((intent?.getIntExtra(EXTRA_EPISODE_SECONDS, 14) ?: 14).coerceIn(1, 3600) * 1000L)
        alarmHour = (intent?.getIntExtra(EXTRA_ALARM_HOUR, 8) ?: 8).coerceIn(0, 23)
        alarmMinute = (intent?.getIntExtra(EXTRA_ALARM_MINUTE, 0) ?: 0).coerceIn(0, 59)

        val notification = Notification.Builder(this, MONITOR_CHANNEL)
            .setContentTitle("Sound Trigger работает")
            .setContentText("Ожидание звука • порог " + thresholdDbfs.toInt() + " dBFS")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        startForeground(MONITOR_NOTIFICATION_ID, notification)

        if (!running) startAudioMonitor()
        return START_NOT_STICKY
    }

    private fun startAudioMonitor() {
        running = true
        isRecording = false
        silenceDurationMs = 0L
        episodeSoundMs = 0L
        episodeQuietMs = 0L
        alarmStartedForThisSession = false
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:AudioMonitor")
        wakeLock?.acquire(12 * 60 * 60 * 1000L)
        sendStatus(-120.0)

        worker = Thread({ monitorLoop() }, "SoundTrigger-Audio").also { it.start() }
    }

    private fun monitorLoop() {
        val minBufferBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferBytes <= 0) {
            Log.e(TAG, "Invalid AudioRecord buffer size: " + minBufferBytes)
            running = false
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

                updateSoundEpisode(levelDbfs, chunkDurationMs)

                if (!isRecording && levelDbfs >= thresholdDbfs) beginRecording()

                if (isRecording) {
                    writePcm(samples, count)
                    if (levelDbfs < thresholdDbfs - 5.0) {
                        silenceDurationMs += chunkDurationMs
                    } else {
                        silenceDurationMs = 0L
                    }
                    if (silenceDurationMs >= silenceLimitMs) finishRecording()
                }

                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastStatusAtMs >= 250L) {
                    sendStatus(levelDbfs)
                    lastStatusAtMs = now
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Microphone monitor failed", e)
            running = false
            stopSelf()
        } finally {
            try { audioRecord?.stop() } catch (_: Exception) {}
            audioRecord?.release()
            audioRecord = null
            if (isRecording) finishRecording()
            sendStatus(-120.0)
        }
    }

    private fun updateSoundEpisode(levelDbfs: Double, chunkDurationMs: Long) {
        if (levelDbfs >= thresholdDbfs) {
            episodeSoundMs += chunkDurationMs
            episodeQuietMs = 0L
        } else if (episodeSoundMs > 0L) {
            episodeQuietMs += chunkDurationMs
            if (episodeQuietMs > allowedPauseMs) {
                episodeSoundMs = 0L
                episodeQuietMs = 0L
            }
        }

        if (!alarmStartedForThisSession && episodeSoundMs >= episodeTriggerMs) {
            alarmStartedForThisSession = true
            scheduleClockAlarm()
            sendStatus(levelDbfs)
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
            val folder = File(base, "SoundTriggerTemp")
            if (!folder.exists() && !folder.mkdirs()) throw IllegalStateException("Cannot create temporary folder")
            val file = File(folder, "recording_" + System.currentTimeMillis() + ".wav")
            output = RandomAccessFile(file, "rw")
            output?.setLength(0L)
            output?.write(ByteArray(44))
            outputFile = file
            audioDataBytes = 0L
            silenceDurationMs = 0L
            isRecording = true
            Log.i(TAG, "Recording started: " + file.absolutePath)
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
        val completedFile = outputFile
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

        if (completedFile != null && completedFile.exists()) {
            try {
                val savedUri = publishToDownloads(completedFile)
                getSharedPreferences("recordings", MODE_PRIVATE).edit()
                    .putString("last_uri", savedUri.toString()).apply()
                completedFile.delete()
                Log.i(TAG, "Recording saved: " + savedUri)
            } catch (e: Exception) {
                Log.e(TAG, "Could not save recording to Downloads", e)
            }
        }
        sendStatus(-120.0)
    }

    private fun publishToDownloads(file: File): Uri {
        if (Build.VERSION.SDK_INT < 29) throw IllegalStateException("Android 10 or newer is required")
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SoundTrigger/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create file in Downloads")
        try {
            val destination = contentResolver.openOutputStream(uri, "w")
                ?: throw IllegalStateException("Could not open file in Downloads")
            destination.use { outputStream ->
                file.inputStream().use { input -> input.copyTo(outputStream) }
            }
            val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            contentResolver.update(uri, ready, null, null)
            return uri
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            throw e
        }
    }

    private fun closeOutputWithoutHeader() {
        try { output?.close() } catch (_: Exception) {}
        output = null
        outputFile?.delete()
        outputFile = null
    }

    private fun scheduleClockAlarm() {
        val now = Calendar.getInstance()
        val trigger = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, alarmHour)
            set(Calendar.MINUTE, alarmMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now.timeInMillis) add(Calendar.DAY_OF_YEAR, 1)
        }

        val alarmIntent = Intent(this, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this, ALARM_REQUEST_CODE, alarmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            if (Build.VERSION.SDK_INT >= 31 && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger.timeInMillis, pendingIntent)
                Log.w(TAG, "Exact alarm permission missing; scheduled an inexact alarm")
            } else if (Build.VERSION.SDK_INT >= 23) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger.timeInMillis, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, trigger.timeInMillis, pendingIntent)
            }
            Log.i(TAG, "Clock alarm scheduled for " + trigger.time)
        } catch (e: SecurityException) {
            Log.e(TAG, "Exact alarm permission missing", e)
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger.timeInMillis, pendingIntent)
        }
    }

    private fun cancelPendingAlarm() {
        val intent = Intent(this, AlarmReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            this, ALARM_REQUEST_CODE, intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pending != null) {
            val manager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            manager.cancel(pending)
            pending.cancel()
        }
    }

    private fun sendStatus(levelDbfs: Double) {
        val intent = Intent(ACTION_STATUS).setPackage(packageName).apply {
            putExtra("level", levelDbfs)
            putExtra("recording", isRecording)
            putExtra("running", running)
            putExtra("episode_ms", episodeSoundMs)
            putExtra("target_ms", episodeTriggerMs)
            putExtra("alarm_scheduled", alarmStartedForThisSession)
        }
        sendBroadcast(intent)
    }

    private fun createMonitorChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    MONITOR_CHANNEL, "Sound Trigger monitoring", NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    override fun onDestroy() {
        running = false
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { worker?.join(700) } catch (_: InterruptedException) {}
        if (isRecording) finishRecording()
        try { wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        sendStatus(-120.0)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
