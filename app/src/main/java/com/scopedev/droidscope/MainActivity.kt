package com.scopedev.droidscope

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // === 起動時にShizuku権限を自動リクエスト ===
        ensureShizukuPermission(this)

        setContent {
            MaterialTheme {
                LogcatController()
            }
        }
    }

    /**
     * 起動時にShizuku権限を確認し、必要なら自動でリクエスト
     */
    private fun ensureShizukuPermission(context: Context) {
        // Shizukuが生きてるか確認
        if (!Shizuku.pingBinder()) {
            // サービス起動していない場合は何もできないので戻る
            return
        }

        // すでに権限があるか？
        when (Shizuku.checkSelfPermission()) {
            PackageManager.PERMISSION_GRANTED -> {
                // 権限OK → 何もしない
            }
            else -> {
                // rationale出す必要がなければリクエスト
                if (!Shizuku.shouldShowRequestPermissionRationale()) {
                    Shizuku.requestPermission(0)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogcatController() {
    val context = LocalContext.current
    var isRunning by remember { mutableStateOf(false) }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var selectedItem by remember { mutableStateOf("main") }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    text = "メニュー",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(16.dp)
                )

                NavigationDrawerItem(
                    label = { Text("設定") },
                    selected = selectedItem == "settings",
                    onClick = {
                        selectedItem = "settings"
                        scope.launch { drawerState.close() }
                    }
                )

                NavigationDrawerItem(
                    label = { Text("メイン") },
                    selected = selectedItem == "main",
                    onClick = {
                        selectedItem = "main"
                        scope.launch { drawerState.close() }
                    }
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("DroidScope") },
                    navigationIcon = {
                        IconButton(onClick = {
                            scope.launch { drawerState.open() }
                        }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    }
                )
            }
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                when (selectedItem) {
                    "main" -> MainScreen(context, isRunning) { isRunning = it }
                    "settings" -> SettingsScreen()
                }
            }
        }
    }
}

@Composable
fun MainScreen(context: Context, isRunning: Boolean, onRunningChange: (Boolean) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    var dialogTitle by remember { mutableStateOf("") }
    var dialogMessage by remember { mutableStateOf("") }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) { Text("OK") }
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
        Button(
            onClick = {
                if (!isRunning) {
                    val shizukuGranted = checkShizukuPermission(context, 0)
                    if (!shizukuGranted) {
                        dialogTitle = "Shizuku 権限必要"
                        dialogMessage = "Shizuku を利用するには権限が必要です。"
                        showDialog = true
                        return@Button
                    }

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
                                    "pm", "grant",
                                    context.packageName,
                                    "android.permission.READ_LOGS"
                                ),
                                null, null
                            )
                            val granted =
                                context.checkSelfPermission("android.permission.READ_LOGS") == PackageManager.PERMISSION_GRANTED
                            if (!granted) {
                                dialogTitle = "権限取得失敗"
                                dialogMessage = "READ_LOGS権限の取得に失敗しました。"
                                showDialog = true
                                return@Button
                            }
                        } catch (e: Exception) {
                            dialogTitle = "権限付与失敗"
                            dialogMessage = "エラー：${e.message}"
                            showDialog = true
                            return@Button
                        }
                    }

                    val intent = Intent(context, LogcatService::class.java)
                    context.startForegroundService(intent)
                    onRunningChange(true)
                }
            },
            enabled = !isRunning,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Start Logcat Service")
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                if (isRunning) {
                    val intent = Intent(context, LogcatService::class.java)
                    context.stopService(intent)
                    onRunningChange(false)
                }
            },
            enabled = isRunning,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Stop Service")
        }
    }
}

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("app_config", Context.MODE_PRIVATE)
    var webhookUrl by remember { mutableStateOf(prefs.getString("WEBHOOK_URL", "") ?: "") }
    var privateServerUrl by remember { mutableStateOf(prefs.getString("PRIVATE_SERVER_URL", "") ?: "") }

    var showDialog by remember { mutableStateOf(false) }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = { TextButton(onClick = { showDialog = false }) { Text("OK") } },
            title = { Text("設定保存") },
            text = { Text("Webhook URL と Private Server URL を保存しました") }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Top
    ) {
        OutlinedTextField(
            value = webhookUrl,
            onValueChange = { webhookUrl = it },
            label = { Text("Webhook URL") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = privateServerUrl,
            onValueChange = { privateServerUrl = it },
            label = { Text("Private Server URL") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                prefs.edit().putString("WEBHOOK_URL", webhookUrl)
                    .putString("PRIVATE_SERVER_URL", privateServerUrl)
                    .apply()
                showDialog = true
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("設定を保存")
        }
    }
}

/**
 * Shizuku 権限チェック
 */
fun checkShizukuPermission(context: Context, code: Int): Boolean {
    return when {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> true
        Shizuku.shouldShowRequestPermissionRationale() -> false
        else -> {
            Shizuku.requestPermission(code)
            false
        }
    }
}
