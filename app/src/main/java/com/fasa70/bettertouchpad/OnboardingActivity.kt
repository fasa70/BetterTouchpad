package com.fasa70.bettertouchpad

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fasa70.bettertouchpad.system.ThreeFingerMode
import com.fasa70.bettertouchpad.ui.*
import com.fasa70.bettertouchpad.ui.theme.BetterTouchpadTheme
import kotlinx.coroutines.launch

class OnboardingActivity : ComponentActivity() {

    private lateinit var settingsRepo: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsRepo = SettingsRepository(applicationContext)

        // If already seen onboarding, skip to MainActivity
        if (settingsRepo.get().hasSeenOnboarding) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        enableEdgeToEdge()
        setContent {
            BetterTouchpadTheme {
                OnboardingFlow(
                    settingsRepo = settingsRepo,
                    onFinish = {
                        startActivity(Intent(this@OnboardingActivity, MainActivity::class.java))
                        finish()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OnboardingFlow(
    settingsRepo: SettingsRepository,
    onFinish: () -> Unit
) {
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 3 })
    var rootState by remember { mutableStateOf<RootCheckState>(RootCheckState.Checking) }
    var naturalScroll by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    fun checkRoot() {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "echo OK"))
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            rootState = if (exitCode == 0 && output.contains("OK")) {
                RootCheckState.Success
            } else {
                RootCheckState.Failure
            }
        } catch (e: Exception) {
            rootState = RootCheckState.Failure
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column {
            HorizontalPager(state = pagerState) { page ->
                when (page) {
                    0 -> RootCheckPage(
                        rootState = rootState,
                        onCheckRoot = { checkRoot() },
                        onNext = { scope.launch { pagerState.animateScrollToPage(1) } },
                        onSkip = { scope.launch { pagerState.animateScrollToPage(2) } }
                    )
                    1 -> NaturalScrollPage(
                        initialNaturalScroll = true,
                        onSelectionChanged = { naturalScroll = it },
                        onNext = { scope.launch { pagerState.animateScrollToPage(2) } },
                        onPrevious = { scope.launch { pagerState.animateScrollToPage(0) } },
                        onSkip = { scope.launch { pagerState.animateScrollToPage(2) } }
                    )
                    2 -> ThreeFingerModePage(
                        naturalScroll = naturalScroll,
                        onPrevious = { scope.launch { pagerState.animateScrollToPage(1) } },
                        onFinish = { ns, tfm ->
                            settingsRepo.update {
                                copy(
                                    hasSeenOnboarding = true,
                                    naturalScroll = ns,
                                    threeFingerMode = tfm
                                )
                            }
                            onFinish()
                        }
                    )
                }
            }

            // Page indicator
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                repeat(3) { index ->
                    val color = if (pagerState.currentPage == index) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.Gray.copy(alpha = 0.4f)
                    }
                    androidx.compose.foundation.Canvas(
                        modifier = Modifier.size(8.dp)
                    ) {
                        drawCircle(color = color)
                    }
                    if (index < 2) {
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                }
            }
        }
    }
}
