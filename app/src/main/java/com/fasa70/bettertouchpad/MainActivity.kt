package com.fasa70.bettertouchpad

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fasa70.bettertouchpad.ui.SettingsScreen
import com.fasa70.bettertouchpad.ui.theme.BetterTouchpadTheme

class MainActivity : ComponentActivity() {
    private lateinit var settingsRepo: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsRepo = SettingsRepository(applicationContext)
        enableEdgeToEdge()
        setContent {
            BetterTouchpadTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        @OptIn(ExperimentalMaterial3Api::class)
                        TopAppBar(title = { Text("BetterTouchpad") })
                    }
                ) { innerPadding ->
                    Column(modifier = Modifier.padding(innerPadding)) {
                        ServiceControlCard()
                        SettingsScreen(settingsRepo)
                    }
                }
            }
        }
    }

    @Composable
    private fun ServiceControlCard() {
        var running by remember { mutableStateOf(TouchpadService.isRunning) }

        // Refresh every second
        LaunchedEffect(Unit) {
            while (true) {
                running = TouchpadService.isRunning
                kotlinx.coroutines.delay(1000)
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("触控板状态", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (running) "运行中" else "已停止",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (running) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                }
                Button(onClick = {
                    if (running) {
                        stopService(Intent(this@MainActivity, TouchpadService::class.java))
                    } else {
                        startForegroundService(
                            Intent(this@MainActivity, TouchpadService::class.java)
                        )
                    }
                    running = !running
                }) {
                    Text(if (running) "停止" else "启动")
                }
            }
        }
    }
}