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
import android.content.Context
import java.text.NumberFormat
import java.util.Locale

class LogcatService : Service() {
    private var lastState: String? = null
    private var lastBiome: String? = null
    private var biomeData: JSONObject? = null
    private var auraData: JSONObject? = null
    private var client = OkHttpClient()
    private var logcatJob: Job? = null
    private var webhookUrl: String = ""
    private var privateServerUrl: String = ""
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceNotification()

        scope.launch {
            delay(1000) // ← Context準備待ち。100〜300msで十分
            biomeData = loadJsonData("biomes.json")
            println("biomes.json loaded")
            auraData = loadJsonData("auras.json")

            val prefs = getSharedPreferences("app_config", Context.MODE_PRIVATE)
            webhookUrl = prefs.getString("WEBHOOK_URL", "") ?: ""
            privateServerUrl = prefs.getString("PRIVATE_SERVER_URL", "") ?: ""

            startLogcatReader()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        logcatJob?.cancel()
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun loadJsonData(fileName: String): JSONObject {
        val inputStream = assets.open(fileName)
        val jsonString = inputStream.bufferedReader().use { it.readText() }
        return JSONObject(jsonString)
    }



    private fun startForegroundServiceNotification() {
        val channelId = "logcat_foreground"
        val channelName = "Logcat Foreground Service"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_HIGH
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(chan)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("DroidScope is running✅")
            .setContentText("catching logs...")
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
            state = state.removePrefix("Equipped ").trim().trim('"')
        }
        createPayload(state, biome)
    }
    private fun getAuraColor(rarity: Int): Int {
        return when {
            rarity >= 99_999_999 -> 0xFF0000  // 赤
            rarity >= 10_000_000 -> 0x8000FF  // 紫
            rarity >= 1_000_000  -> 0xFF69B4  // ピンク
            else -> 0xFFFFFF                  // 白
        }
    }

    private fun createPayload(state: String, biome: String) {
        // --- Aura Equipped ---
        if (state.isNotEmpty() && state != lastState) {
            val auraImage = auraData?.optJSONObject(state.lowercase())?.optString("img_url") ?: ""
            val auraRarity = auraData?.optJSONObject(state.lowercase())?.optInt("rarity", 0) ?: 0
            val auraColor = getAuraColor(auraRarity)
            val formattedAuraRarity = if (auraRarity == 0) {
                "Unknown"
            } else {
                NumberFormat.getNumberInstance(Locale.US).format(auraRarity)
            }
            val auraEmbed = JSONObject().apply {
                put("title", "Aura Equipped - $state")
                put("color", auraColor)
                put("footer", JSONObject().put("text", "DroidScope | Beta v1.0.0"))
                put("thumbnail", JSONObject().put("url",auraImage))
                put(
                    "fields", JSONArray().put(
                        JSONObject()
                            .put("name", "Rarity:")
                            .put("value", "1 in $formattedAuraRarity")
                            .put("inline", true)
                    )
                )
            }
            val payload = JSONObject().put("embeds", JSONArray().put(auraEmbed))
            sendWebhook(payload)
            lastState = state
        }

        // --- Biome Changed ---
        if (biome.isNotEmpty() && biome != lastBiome) {
            val now = Instant.now().epochSecond
            lastBiome?.let {
                val biomeEndColorStr = biomeData?.optJSONObject(it)?.optString("colour", "#00BFFF") ?: "#FFFFFF"
                val biomeEndColor = biomeEndColorStr.removePrefix("#").toInt(16)
                val endEmbed = JSONObject().apply {
                    put("title", "Biome Ended - $it")
                    put("description", "**<t:${now}:T>** (**<t:${now}:R>**)")
                    put("color", biomeEndColor)
                    put("footer", JSONObject().put("text", "DroidScope | Beta v1.0.0"))
                }
                val endPayload = JSONObject().put("embeds", JSONArray().put(endEmbed))
                sendWebhook(endPayload)
            }
            println(biome)
            val biomeStartColorStr = biomeData?.optJSONObject(biome)?.optString("colour", "#00BFFF") ?: "#FFFFFF"
            val biomeStartColor = biomeStartColorStr.removePrefix("#").toInt(16)
            val biomeStartImage = biomeData?.optJSONObject(biome)?.optString("img_url", "https://images.teepublic.com/derived/production/designs/10267605_0/1589729993/i_p:c_191919,bps_fr,s_630,q_90.jpg") ?: "https://images.teepublic.com/derived/production/designs/10267605_0/1589729993/i_p:c_191919,bps_fr,s_630,q_90.jpg"
            val startEmbed = JSONObject().apply {
                put("title", "Biome Started - $biome")
                put("description", "**<t:${now}:T>** (**<t:${now}:R>**)")
                put("color", biomeStartColor)
                put("thumbnail", JSONObject().put("url", biomeStartImage))
                put("footer", JSONObject().put("text", "DroidScope | Beta v1.0.0"))
                put(
                    "fields", JSONArray().put(
                        JSONObject()
                            .put("name","Private Server")
                            .put("value",privateServerUrl)
                            .put("inline",false)
                    )
                )
            }
            val startPayload = if (biome == "GLITCHED" || biome == "DREAMSPACE") {
                JSONObject().apply {
                    put("content", "@everyone")
                    put("embeds", JSONArray().put(startEmbed))
                }
            } else {
                JSONObject().put("embeds", JSONArray().put(startEmbed))
            }
            scope.launch {
                delay(300)
                sendWebhook(startPayload)
            }
            lastBiome = biome
        }
    }

    private fun sendWebhook(payload: JSONObject) {
        val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(webhookUrl)
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
