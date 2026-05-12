package com.fasa70.bettertouchpad.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fasa70.bettertouchpad.TouchpadService
import com.fasa70.bettertouchpad.TouchpadService.Companion.isRunning

@Composable
fun ServiceControlCard() {
    val context = LocalContext.current
    var running by remember { mutableStateOf(isRunning) }

    // Refresh every second
    LaunchedEffect(Unit) {
        while (true) {
            running = isRunning
            kotlinx.coroutines.delay(1000)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("程序运行状态", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (running) "运行中" else "已停止",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (running) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
            }
            Button(onClick = {
                if (running) {
                    context.stopService(Intent(context, TouchpadService::class.java))
                } else {
                    context.startForegroundService(Intent(context, TouchpadService::class.java))
                }
                running = !running
            }) {
                Text(if (running) "停止" else "启动")
            }
        }
    }
}
