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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import com.fasa70.bettertouchpad.ui.*
import com.fasa70.bettertouchpad.ui.theme.BetterTouchpadTheme

class MainActivity : ComponentActivity() {
    private lateinit var settingsRepo: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsRepo = SettingsRepository(applicationContext)
        enableEdgeToEdge()
        setContent {
            BetterTouchpadTheme {
                MainScreen(settingsRepo)
            }
        }
    }
}

@Composable
private fun MainScreen(repo: SettingsRepository) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf(
        TabInfo("手势", Icons.Default.Star),
        TabInfo("触控", Icons.Default.Refresh),
        TabInfo("兼容", Icons.Default.Settings),
        TabInfo("关于", Icons.Default.Info)
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            ServiceControlCard()

            when (selectedTab) {
                0 -> GestureSettingsTab(repo)
                1 -> TouchSettingsTab(repo)
                2 -> CompatibilitySettingsTab(repo)
                3 -> AboutTab()
            }
        }
    }
}

private data class TabInfo(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
