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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import androidx.core.content.edit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureShizukuPermission(this)

        setContent {
            MaterialTheme {
                LogcatController()
            }
        }
    }

    private fun ensureShizukuPermission(context: Context) {
        if (!Shizuku.pingBinder()) return

        when (Shizuku.checkSelfPermission()) {
            PackageManager.PERMISSION_GRANTED -> {}
            else -> {
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
    val prefs = context.getSharedPreferences("app_state", Context.MODE_PRIVATE)

    // --- 起動時にサービス状態を復元 ---
    var isRunning by remember {
        mutableStateOf(prefs.getBoolean("isServiceRunning", false))
    }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var selectedItem by remember { mutableStateOf("main") }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    text = stringResource(R.string.menu_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(16.dp)
                )

                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.menu_main)) },
                    selected = selectedItem == "main",
                    onClick = {
                        selectedItem = "main"
                        scope.launch { drawerState.close() }
                    }
                )

                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.menu_settings)) },
                    selected = selectedItem == "settings",
                    onClick = {
                        selectedItem = "settings"
                        scope.launch { drawerState.close() }
                    }
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(
                                Icons.Default.Menu,
                                contentDescription = stringResource(R.string.menu_title)
                            )
                        }
                    }
                )
            }
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                when (selectedItem) {
                    "main" -> MainScreen(
                        context,
                        isRunning = isRunning
                    ) { running ->
                        isRunning = running
                        prefs.edit { putBoolean("isServiceRunning", running) }
                    }

                    "settings" -> SettingsScreen()
                }
            }
        }
    }
}

@Composable
fun MainScreen(
    context: Context,
    isRunning: Boolean,
    onRunningChange: (Boolean) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    var dialogTitle by remember { mutableStateOf("") }
    var dialogMessage by remember { mutableStateOf("") }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.dialog_ok))
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
        // --- サービス開始ボタン ---
        Button(
            onClick = {
                if (!isRunning) {
                    val shizukuGranted = checkShizukuPermission(context, 0)
                    if (!shizukuGranted) {
                        dialogTitle = context.getString(R.string.dialog_permission_title)
                        dialogMessage = context.getString(R.string.dialog_permission_message)
                        showDialog = true
                        return@Button
                    }

                    val hasReadLogs =
                        context.checkSelfPermission("android.permission.READ_LOGS") == PackageManager.PERMISSION_GRANTED

                    if (!hasReadLogs && !Shizuku.pingBinder()) {
                        dialogTitle = context.getString(R.string.dialog_no_read_logs_title)
                        dialogMessage = context.getString(R.string.dialog_no_read_logs_message)
                        showDialog = true
                        return@Button
                    }

                    if (!hasReadLogs && Shizuku.pingBinder()) {
                        try {
                            Shizuku.newProcess(
                                arrayOf("pm", "grant", context.packageName, "android.permission.READ_LOGS"),
                                null, null
                            )
                            val granted =
                                context.checkSelfPermission("android.permission.READ_LOGS") == PackageManager.PERMISSION_GRANTED
                            if (!granted) {
                                dialogTitle = context.getString(R.string.dialog_grant_failed_title)
                                dialogMessage = context.getString(R.string.dialog_grant_failed_message)
                                showDialog = true
                                return@Button
                            }
                        } catch (e: Exception) {
                            dialogTitle = context.getString(R.string.dialog_grant_exception_title)
                            dialogMessage =
                                context.getString(R.string.dialog_grant_exception_message, e.message ?: "unknown")
                            showDialog = true
                            return@Button
                        }
                    }

                    // --- 多重起動防止 ---
                    if (!LogcatService.isRunning) {
                        val intent = Intent(context, LogcatService::class.java)
                        context.startForegroundService(intent)
                        onRunningChange(true)
                    }
                }
            },
            enabled = !isRunning,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.start_service))
        }

        Spacer(Modifier.height(16.dp))

        // --- サービス停止ボタン ---
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
            Text(stringResource(R.string.stop_service))
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
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.dialog_ok))
                }
            },
            title = { Text(stringResource(R.string.dialog_settings_saved_title)) },
            text = { Text(stringResource(R.string.dialog_settings_saved_message)) }
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
            label = { Text(stringResource(R.string.webhook_url)) },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = privateServerUrl,
            onValueChange = { privateServerUrl = it },
            label = { Text(stringResource(R.string.private_server_url)) },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                prefs.edit {
                    putString("WEBHOOK_URL", webhookUrl)
                        .putString("PRIVATE_SERVER_URL", privateServerUrl)
                }
                showDialog = true
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.save_settings))
        }
    }
}

fun checkShizukuPermission(context: Context, code: Int): Boolean {
    if(context.checkSelfPermission("android.permission.READ_LOGS") == PackageManager.PERMISSION_GRANTED){
        return true
    }
    if (!Shizuku.pingBinder()) {
        return false
    }
    return when {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> true
        Shizuku.shouldShowRequestPermissionRationale() -> false
        else -> {
            Shizuku.requestPermission(code)
            false
        }
    }
}
