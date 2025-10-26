package com.scopedev.droidscope

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Button(
            onClick = {
                if (!isRunning) {
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
