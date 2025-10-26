package com.scopedev.droidscope

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import android.util.Log

class LogcatService : Service() {

    private var logcatJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceNotification()
        startLogcatReader()
    }

    override fun onDestroy() {
        super.onDestroy()
        logcatJob?.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundServiceNotification() {
        val channelId = "logcat_foreground"
        val channelName = "Logcat Foreground Service"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(chan)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("DroidScope Logcat Running")
            .setContentText("Shizuku経由でlogcatを監視中…")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }

    private fun startLogcatReader() {
        if (!Shizuku.pingBinder()) {
            println("⚠️ Shizukuが起動していません")
            stopSelf()
            return
        }

        val perm = Shizuku.checkSelfPermission()
        if (perm == PackageManager.PERMISSION_GRANTED) {
            logcatJob = scope.launch { readLogcat() }
        } else {
            println("🟡 Shizuku権限リクエストが必要です")
            stopSelf()
        }
    }

    private suspend fun readLogcat() = withContext(Dispatchers.IO) {
        try {
            val process = Shizuku.newProcess(
                arrayOf("logcat", "-v", "time"),
                null,
                null
            )
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?

            while (reader.readLine().also { line = it } != null && isActive) {
                line?.let { text ->
                    if ("[BloxstrapRPC]" in text) {
                        println("🟢 $text")
                        Log.d("DroidScope",text)
                    }
                }
            }

            reader.close()
            process.destroy()
        } catch (e: Exception) {
            println("💥 Logcat読み込みエラー: ${e.message}")
        }
    }
}
