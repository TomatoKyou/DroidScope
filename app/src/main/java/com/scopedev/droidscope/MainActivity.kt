package com.scopedev.droidscope

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                LogcatController()
            }
        }
    }
}

@Composable
fun LogcatController() {
    var isRunning by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // --- SharedPreferences ---
    val prefs = context.getSharedPreferences("app_config", Context.MODE_PRIVATE)
    var webhookUrl by remember { mutableStateOf(prefs.getString("WEBHOOK_URL", "") ?: "") }
    var privateServerUrl by remember { mutableStateOf(prefs.getString("PRIVATE_SERVER_URL", "") ?: "") }

    // ダイアログ関連の状態変数
    var showDialog by remember { mutableStateOf(false) }
    var dialogTitle by remember { mutableStateOf("") }
    var dialogMessage by remember { mutableStateOf("") }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("OK")
                }
            },
            title = { Text(dialogTitle) },
            text = { Text(dialogMessage) }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center
    ) {
        // --- Webhook URL 入力 ---
        OutlinedTextField(
            value = webhookUrl,
            onValueChange = { webhookUrl = it },
            label = { Text("Webhook URL") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(16.dp))

        // --- Private Server URL 入力 ---
        OutlinedTextField(
            value = privateServerUrl,
            onValueChange = { privateServerUrl = it },
            label = { Text("Private Server URL") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(16.dp))

        // --- 設定を適用ボタン ---
        Button(
            onClick = {
                prefs.edit().putString("WEBHOOK_URL", webhookUrl)
                    .putString("PRIVATE_SERVER_URL", privateServerUrl)
                    .apply()
                dialogTitle = "設定保存"
                dialogMessage = "Webhook URL と Private Server URL を保存しました"
                showDialog = true
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("設定を適用")
        }

        Spacer(Modifier.height(32.dp))

        // --- Start ボタン ---
        Button(
            onClick = {
                if (!isRunning) {
                    val hasReadLogs =
                        context.checkSelfPermission("android.permission.READ_LOGS") == PackageManager.PERMISSION_GRANTED

                    if (!hasReadLogs && !Shizuku.pingBinder()) {
                        dialogTitle = "権限エラー"
                        dialogMessage = "READ_LOGS権限がありません！\nShizukuを実行するか、ADBコマンドを実行してください。"
                        showDialog = true
                        return@Button
                    }

                    if (!hasReadLogs && Shizuku.pingBinder()) {
                        try {
                            Shizuku.newProcess(
                                arrayOf(
                                    "pm",
                                    "grant",
                                    context.packageName,
                                    "android.permission.READ_LOGS"
                                ),
                                null,
                                null
                            )
                            val granted =
                                context.checkSelfPermission("android.permission.READ_LOGS") == PackageManager.PERMISSION_GRANTED
                            if (!granted) {
                                dialogTitle = "権限取得失敗"
                                dialogMessage =
                                    "READ_LOGS権限の取得に失敗しました。"
                                showDialog = true
                                return@Button
                            }
                        } catch (e: Exception) {
                            dialogTitle = "権限付与失敗"
                            dialogMessage = "エラー： ${e.message}"
                            showDialog = true
                            return@Button
                        }
                    }

                    val intent = Intent(context, LogcatService::class.java)
                    context.startForegroundService(intent)
                    isRunning = true
                }
            },
            enabled = !isRunning,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Start Logcat Service")
        }

        Spacer(Modifier.height(16.dp))

        // --- Stop ボタン ---
        Button(
            onClick = {
                if (isRunning) {
                    val intent = Intent(context, LogcatService::class.java)
                    context.stopService(intent)
                    isRunning = false
                }
            },
            enabled = isRunning,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Stop Service")
        }
    }
}
