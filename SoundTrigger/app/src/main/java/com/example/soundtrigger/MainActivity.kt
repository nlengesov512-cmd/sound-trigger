package com.example.soundtrigger

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private lateinit var levelLabel: TextView
    private lateinit var stateLabel: TextView
    private lateinit var levelBar: ProgressBar
    private lateinit var alarmTimeLabel: TextView
    private lateinit var recordingsContainer: LinearLayout
    private var receiverRegistered = false
    private var player: MediaPlayer? = null

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != RecorderService.ACTION_STATUS) return
            val level = intent.getDoubleExtra("level", -120.0)
            val recording = intent.getBooleanExtra("recording", false)
            val running = intent.getBooleanExtra("running", false)
            val episode = intent.getLongExtra("episode_ms", 0L)
            val target = intent.getLongExtra("target_ms", 14_000L)
            val alarmSet = intent.getBooleanExtra("alarm_scheduled", false)

            levelLabel.text = "Уровень микрофона: " +
                if (level <= -119.0) "—" else String.format(Locale.getDefault(), "%.1f dBFS", level)
            levelBar.progress = ((level + 90.0).coerceIn(0.0, 90.0) * 100.0 / 90.0).toInt()
            stateLabel.text = (when {
                !running -> "Мониторинг остановлен"
                recording -> "Идёт запись"
                else -> "Микрофон активен — ожидание звука"
            }) + "\nЗвуковой эпизод: " + (episode / 1000L) + " / " +
                (target / 1000L) + " сек" +
                if (alarmSet) "\nБудильник уже запланирован" else ""
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(28, 28, 28, 28)
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "SOUND TRIGGER"
            textSize = 25f
            setPadding(0, 0, 0, 8)
        })
        root.addView(TextView(this).apply {
            text = "Запись по уровню звука и будильник по времени суток"
            textSize = 15f
            setPadding(0, 0, 0, 18)
        })

        levelLabel = TextView(this).apply {
            text = "Уровень микрофона: —"
            textSize = 17f
        }
        root.addView(levelLabel)
        levelBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        root.addView(levelBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 8 })
        stateLabel = TextView(this).apply {
            text = "Мониторинг остановлен"
            textSize = 14f
            setPadding(0, 0, 0, 16)
        }
        root.addView(stateLabel)

        addSeekSetting(root, "Порог запуска", "threshold_progress", 45, 25) { value ->
            thresholdText.text = "Порог запуска: " + (-65 + value) + " dBFS"
        }
        addSeekSetting(root, "Тишина до остановки записи", "silence_seconds", 59, 12) { value ->
            silenceText.text = "Остановка после тишины: " + (value + 1) + " сек"
        }
        addSeekSetting(root, "Допустимая пауза внутри эпизода", "pause_seconds", 29, 7) { value ->
            pauseText.text = "Сброс эпизода после паузы: " + (value + 1) + " сек"
        }
        addSeekSetting(root, "Длительность звукового эпизода", "episode_seconds", 119, 13) { value ->
            episodeText.text = "Для запуска будильника нужно звука: " + (value + 1) + " сек"
        }

        alarmTimeLabel = TextView(this).apply {
            textSize = 17f
            setPadding(0, 18, 0, 6)
        }
        root.addView(alarmTimeLabel)
        updateAlarmTimeLabel()
        root.addView(Button(this).apply {
            text = "ВЫБРАТЬ ВРЕМЯ БУДИЛЬНИКА"
            setOnClickListener {
                TimePickerDialog(
                    this@MainActivity,
                    { _, hour, minute ->
                        prefs.edit().putInt("alarm_hour", hour).putInt("alarm_minute", minute).apply()
                        updateAlarmTimeLabel()
                    },
                    prefs.getInt("alarm_hour", 8),
                    prefs.getInt("alarm_minute", 0),
                    true
                ).show()
            }
        })

        root.addView(Button(this).apply {
            text = "РАЗРЕШИТЬ ТОЧНЫЙ БУДИЛЬНИК"
            setOnClickListener { requestExactAlarmAccess() }
        })
        root.addView(Button(this).apply {
            text = "НАЧАТЬ МОНИТОРИНГ"
            setOnClickListener { requestPermissionsThenStart() }
        })
        root.addView(Button(this).apply {
            text = "ОСТАНОВИТЬ МОНИТОРИНГ И ОТМЕНИТЬ БУДИЛЬНИК"
            setOnClickListener {
                startService(Intent(this@MainActivity, RecorderService::class.java)
                    .setAction(RecorderService.ACTION_STOP))
                stateLabel.text = "Мониторинг остановлен"
            }
        })

        root.addView(TextView(this).apply {
            text = "ЗАПИСИ"
            textSize = 20f
            setPadding(0, 24, 0, 6)
        })
        root.addView(Button(this).apply {
            text = "ОБНОВИТЬ СПИСОК ЗАПИСЕЙ"
            setOnClickListener { refreshRecordings() }
        })
        recordingsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(recordingsContainer)

        root.addView(TextView(this).apply {
            text = "Записи сохраняются в Загрузки/SoundTrigger. Порог dBFS относительный и зависит от микрофона телефона; это не измерение акустических дБ SPL."
            textSize = 13f
            setPadding(0, 18, 0, 0)
        })

        setContentView(scroll)
    }

    private val thresholdText by lazy { makeSettingLabel() }
    private val silenceText by lazy { makeSettingLabel() }
    private val pauseText by lazy { makeSettingLabel() }
    private val episodeText by lazy { makeSettingLabel() }

    private fun makeSettingLabel(): TextView = TextView(this).apply { textSize = 15f }

    private fun addSeekSetting(
        root: LinearLayout,
        title: String,
        key: String,
        max: Int,
        defaultProgress: Int,
        update: (Int) -> Unit
    ) {
        val label = when (key) {
            "threshold_progress" -> thresholdText
            "silence_seconds" -> silenceText
            "pause_seconds" -> pauseText
            else -> episodeText
        }
        label.setPadding(0, 14, 0, 2)
        root.addView(label)
        root.addView(TextView(this).apply {
            text = title
            textSize = 12f
        })
        val bar = SeekBar(this).apply {
            this.max = max
            progress = prefs.getInt(key, defaultProgress).coerceIn(0, max)
        }
        root.addView(bar)
        update(bar.progress)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                update(progress)
                if (fromUser) prefs.edit().putInt(key, progress).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun updateAlarmTimeLabel() {
        val h = prefs.getInt("alarm_hour", 8).toString().padStart(2, '0')
        val m = prefs.getInt("alarm_minute", 0).toString().padStart(2, '0')
        alarmTimeLabel.text = "Время будильника: $h:$m"
    }

    private fun requestPermissionsThenStart() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 51) else startMonitoring()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 51) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED) {
                startMonitoring()
            } else {
                Toast.makeText(this, "Для работы нужен доступ к микрофону", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startMonitoring() {
        val intent = Intent(this, RecorderService::class.java)
            .putExtra(RecorderService.EXTRA_THRESHOLD, -65 + prefs.getInt("threshold_progress", 25))
            .putExtra(RecorderService.EXTRA_SILENCE_SECONDS, prefs.getInt("silence_seconds", 12) + 1)
            .putExtra(RecorderService.EXTRA_PAUSE_SECONDS, prefs.getInt("pause_seconds", 7) + 1)
            .putExtra(RecorderService.EXTRA_EPISODE_SECONDS, prefs.getInt("episode_seconds", 13) + 1)
            .putExtra(RecorderService.EXTRA_ALARM_HOUR, prefs.getInt("alarm_hour", 8))
            .putExtra(RecorderService.EXTRA_ALARM_MINUTE, prefs.getInt("alarm_minute", 0))
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, "Мониторинг запущен", Toast.LENGTH_SHORT).show()
    }

    private fun requestExactAlarmAccess() {
        if (Build.VERSION.SDK_INT >= 31) {
            val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
            if (alarmManager.canScheduleExactAlarms()) {
                Toast.makeText(this, "Разрешение на точные будильники уже есть", Toast.LENGTH_SHORT).show()
                return
            }
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                Toast.makeText(this, "Разрешите точные будильники в специальных настройках Android", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, "Точные будильники поддерживаются системой", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(RecorderService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(statusReceiver, filter)
        }
        receiverRegistered = true
        refreshRecordings()
    }

    override fun onPause() {
        if (receiverRegistered) {
            unregisterReceiver(statusReceiver)
            receiverRegistered = false
        }
        super.onPause()
    }

    private fun refreshRecordings() {
        recordingsContainer.removeAllViews()
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.RELATIVE_PATH
        )
        val selection = MediaStore.MediaColumns.RELATIVE_PATH + "=?"
        val args = arrayOf(Environment.DIRECTORY_DOWNLOADS + "/SoundTrigger/")
        val sort = MediaStore.MediaColumns.DATE_ADDED + " DESC"
        try {
            contentResolver.query(collection, projection, selection, args, sort)?.use { cursor ->
                if (cursor.count == 0) {
                    recordingsContainer.addView(TextView(this).apply {
                        text = "Пока нет записей"
                        setPadding(0, 8, 0, 8)
                    })
                }
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                    val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
                    val date = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)) * 1000L
                    val uri = ContentUris.withAppendedId(collection, id)
                    val duration = readWavDuration(uri)
                    val dateText = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(date))
                    val item = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(0, 12, 0, 12)
                    }
                    item.addView(TextView(this).apply {
                        text = name
                        textSize = 15f
                    })
                    item.addView(TextView(this).apply {
                        text = "$dateText • $duration"
                        textSize = 13f
                    })
                    val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    actions.addView(Button(this@MainActivity).apply {
                        text = "ПРОСЛУШАТЬ"
                        setOnClickListener { playRecording(uri) }
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    actions.addView(Button(this@MainActivity).apply {
                        text = "ПОДЕЛИТЬСЯ"
                        setOnClickListener { shareRecording(uri) }
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    item.addView(actions)
                    recordingsContainer.addView(item)
                    recordingsContainer.addView(View(this).apply {
                        setBackgroundColor(0xFFBBBBBB.toInt())
                    }, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    ))
                }
            }
        } catch (e: Exception) {
            recordingsContainer.addView(TextView(this).apply {
                text = "Не удалось прочитать список записей: " + (e.message ?: "ошибка")
            })
        }
    }

    private fun readWavDuration(uri: Uri): String {
        return try {
            val header = ByteArray(44)
            val input = contentResolver.openInputStream(uri) ?: return "длительность неизвестна"
            input.use {
                var offset = 0
                while (offset < header.size) {
                    val read = it.read(header, offset, header.size - offset)
                    if (read <= 0) break
                    offset += read
                }
            }
            val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val byteRate = buffer.getInt(28)
            val dataSize = buffer.getInt(40).toLong() and 0xffffffffL
            if (byteRate <= 0) "длительность неизвестна" else {
                val seconds = dataSize / byteRate
                String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60)
            }
        } catch (_: Exception) {
            "длительность неизвестна"
        }
    }

    private fun playRecording(uri: Uri) {
        try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(this@MainActivity, uri)
                setOnCompletionListener {
                    it.release()
                    if (player === it) player = null
                }
                setOnErrorListener { mp, _, _ ->
                    mp.release()
                    if (player === mp) player = null
                    Toast.makeText(this@MainActivity, "Не удалось воспроизвести запись", Toast.LENGTH_SHORT).show()
                    true
                }
                prepare()
                start()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка воспроизведения: " + (e.message ?: "неизвестно"), Toast.LENGTH_LONG).show()
        }
    }

    private fun shareRecording(uri: Uri) {
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, "Поделиться записью"))
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
