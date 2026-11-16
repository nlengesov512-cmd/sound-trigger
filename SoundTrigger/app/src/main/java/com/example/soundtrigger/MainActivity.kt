package com.example.soundtrigger

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : Activity() {
    private lateinit var thresholdLabel: TextView
    private lateinit var thresholdBar: SeekBar
    private val requestAudio = 51
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(28, 36, 28, 24)
        }

        root.addView(TextView(this).apply {
            text = "SOUND TRIGGER"
            textSize = 25f
            setPadding(0, 0, 0, 20)
        })
        root.addView(TextView(this).apply {
            text = "Автоматическая запись по уровню звука"
            textSize = 17f
        })

        thresholdLabel = TextView(this).apply {
            textSize = 18f
            setPadding(0, 24, 0, 8)
        }
        root.addView(thresholdLabel)

        thresholdBar = SeekBar(this).apply {
            max = 45
            progress = prefs.getInt("threshold_progress", 25).coerceIn(0, 45)
        }
        root.addView(thresholdBar)
        updateThresholdLabel()
        thresholdBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateThresholdLabel()
                if (fromUser) prefs.edit().putInt("threshold_progress", progress).apply()
            }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })

        root.addView(TextView(this).apply {
            text = "Порог относительный (dBFS): чем ближе значение к 0, тем громче должен быть звук для запуска. После запуска запись сохраняется до 13 секунд тишины. Если один непрерывный фрагмент записи длится 30 секунд, запускается 10-минутный таймер."
            textSize = 14f
            setPadding(0, 12, 0, 20)
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
            text = "ОСТАНОВИТЬ МОНИТОРИНГ И ТАЙМЕР"
            setOnClickListener {
                startService(Intent(this@MainActivity, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
                Toast.makeText(this@MainActivity, "Мониторинг остановлен; ожидающий будильник отменён", Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(Button(this).apply {
            text = "ПОДЕЛИТЬСЯ ПОСЛЕДНЕЙ ЗАПИСЬЮ"
            setOnClickListener { shareLastRecording() }
        })
        root.addView(TextView(this).apply {
            text = "Записи сохраняются в папке приложения Music/SoundTrigger. Для работы с выключенным экраном разрешите микрофон и уведомления, а также исключите приложение из энергосбережения."
            textSize = 13f
            setPadding(0, 20, 0, 0)
        })

        setContentView(root)
    }

    private fun updateThresholdLabel() {
        val dbfs = -65 + thresholdBar.progress
        thresholdLabel.text = "Порог запуска: $dbfs dBFS"
    }

    private fun requestPermissionsThenStart() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), requestAudio)
        } else {
            startMonitoring()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == requestAudio && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startMonitoring()
        } else if (requestCode == requestAudio) {
            Toast.makeText(this, "Для работы нужен доступ к микрофону", Toast.LENGTH_LONG).show()
        }
    }

    private fun startMonitoring() {
        val threshold = -65 + thresholdBar.progress
        val intent = Intent(this, RecorderService::class.java)
            .putExtra(RecorderService.EXTRA_THRESHOLD, threshold)
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, "Мониторинг запущен. Порог: $threshold dBFS", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(this, "Откройте настройки специальных разрешений и разрешите точные будильники", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, "Точные будильники поддерживаются системой", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareLastRecording() {
        val path = getSharedPreferences("recordings", MODE_PRIVATE).getString("last_path", null)
        if (path.isNullOrBlank()) {
            Toast.makeText(this, "Пока нет сохранённых записей", Toast.LENGTH_SHORT).show()
            return
        }
        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(this, "Файл записи не найден", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, "Поделиться записью"))
    }
}
