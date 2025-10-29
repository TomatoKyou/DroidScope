package com.scopedev.droidscope

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import org.json.JSONObject
import org.json.JSONArray
import java.time.Instant
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import okhttp3.RequestBody.Companion.toRequestBody
import android.content.pm.PackageManager

class LogcatService : Service() {
    private var lastState: String? = null
    private var lastBiome: String? = null
    private var client = OkHttpClient()
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
            .setContentText("logcatを監視中…")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }

    private fun startLogcatReader() {
        logcatJob = scope.launch { readLogcat() }
    }

    private suspend fun readLogcat() = withContext(Dispatchers.IO) {
        println("Imhere")
        val hasReadLogs = checkSelfPermission("android.permission.READ_LOGS") == PackageManager.PERMISSION_GRANTED
        println("🧭 READ_LOGS権限あり？ $hasReadLogs")
        try {
            // Runtime経由で直接実行
            val process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-v", "time")

            )

            val reader = BufferedReader(InputStreamReader(process.inputStream), 1024 * 1024)
            var line: String?

            while (reader.readLine().also { line = it } != null && isActive) {
                line?.let { text ->
                    if ("[BloxstrapRPC]" in text) {
                        println("logfound")
                        val jsonStart = text.indexOf("{")
                        if (jsonStart >= 0) {
                            val jsonStr = text.substring(jsonStart)
                            try {
                                val json = JSONObject(jsonStr)
                                getJsonData(json)
                            } catch (e: Exception) {
                                println("JSON parse error: ${e.message}")
                            }
                        }
                    }
                }
            }

            reader.close()
        } catch (e: Exception) {
            println("💥 Logcat読み込みエラー: ${e.message}")
        }
    }

    private fun getJsonData(json: JSONObject) {
        val data = json.optJSONObject("data") ?: return
        var state = data.optString("state", "")
        val biome = data.optJSONObject("largeImage")?.optString("hoverText", "") ?: ""

        println("🧭 getJsonData() called → state=$state, biome=$biome")
        if (state.startsWith("Equipped ")) {
            state = state.removePrefix("Equipped ").trim('"')
        }
        createPayload(state, biome)
    }

    private fun createPayload(state: String, biome: String) {
        // --- Aura Equipped ---
        if (state.isNotEmpty() && state != lastState) {
            val auraEmbed = JSONObject().apply {
                put("title", "Aura Equipped - $state")
                put("color", 0xFFD700)
                put("footer", JSONObject().put("text", "DroidScope"))
            }
            val payload = JSONObject().put("embeds", JSONArray().put(auraEmbed))
            sendWebhook(payload)
            lastState = state
        }

        // --- Biome Changed ---
        if (biome.isNotEmpty() && biome != lastBiome) {
            lastBiome?.let {
                val endEmbed = JSONObject().apply {
                    put("title", "Biome Ended - $it")
                    put("description", "<t:${Instant.now().epochSecond}:T>")
                    put("color", 0xAAAAAA)
                }
                val endPayload = JSONObject().put("embeds", JSONArray().put(endEmbed))
                sendWebhook(endPayload)
            }

            val startEmbed = JSONObject().apply {
                put("title", "Biome Started - $biome")
                put("description", "<t:${Instant.now().epochSecond}:T>")
                put("color", 0x00BFFF)
            }
            val startPayload = JSONObject().put("embeds", JSONArray().put(startEmbed))
            sendWebhook(startPayload)
            lastBiome = biome
        }
    }

    private fun sendWebhook(payload: JSONObject) {
        val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("https://discord.com/api/webhooks/1390268358913556480/INlgmKisfwBCKwiEZ83OAr7l0H78zVfjCLWRM6zDgFcoXmTdQypRhhFQlhDcnOtZ80_k")
            .post(body)
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                println("Webhook送信失敗: ${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                println("Webhookの送信に成功しました")
                response.close()
            }
        })
    }
}
