package com.fasa70.bettertouchpad.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fasa70.bettertouchpad.system.ThreeFingerMode

// ─── Page 1: Root Check ───────────────────────────────────────────────────

sealed class RootCheckState {
    object Checking : RootCheckState()
    object Success : RootCheckState()
    object Failure : RootCheckState()
}

@Composable
fun RootCheckPage(
    rootState: RootCheckState,
    onCheckRoot: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit
) {
    LaunchedEffect(Unit) { onCheckRoot() }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "Root 权限检查",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "BetterTouchpad 需要 Root 权限才能读取触控板设备并注入输入事件。\n请确保您的设备已获取 Root 权限。",
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(32.dp))

            when (rootState) {
                RootCheckState.Checking -> {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("正在检查 Root 权限...", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                RootCheckState.Success -> {
                    Text(
                        "Root 权限已获取",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                RootCheckState.Failure -> {
                    Text(
                        "未检测到 Root 权限",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "如无 Root 权限，应用将无法正常工作。\n您可以选择强制继续，但功能可能不可用。",
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Bottom buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
        TextButton(onClick = onSkip) {
            Text("跳过")
        }
        Button(onClick = onNext) {
            Text("下一步")
        }
        }
    }
}

// ─── Page 2: Natural Scroll ───────────────────────────────────────────────

@Composable
fun NaturalScrollPage(
    initialNaturalScroll: Boolean,
    onSelectionChanged: (Boolean) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSkip: () -> Unit
) {
    var selected by remember { mutableStateOf(initialNaturalScroll) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(48.dp))
            Text(
                "自然滚动",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "自然滚动模式下，双指上滑时内容上移，双指下滑时内容下移，与手机上浏览网页的方向一致。\n关闭后则为传统鼠标滚动方向。",
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(32.dp))

            // Two option cards
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Natural scroll ON
                OptionCard(
                    modifier = Modifier.weight(1f),
                    title = "开启（推荐）",
                    description = "双指上滑 → 内容上移\n双指下滑 → 内容下移",
                    selected = selected,
                    onClick = { selected = true; onSelectionChanged(true) }
                )
                // Natural scroll OFF
                OptionCard(
                    modifier = Modifier.weight(1f),
                    title = "关闭",
                    description = "双指上滑 → 内容下移\n双指下滑 → 内容上移",
                    selected = !selected,
                    onClick = { selected = false; onSelectionChanged(false) }
                )
            }
        }

        // Bottom buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
        TextButton(onClick = onPrevious) { Text("上一步") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onSkip) { Text("跳过") }
            Button(onClick = onNext) { Text("下一步") }
        }
        }
    }
}

// ─── Page 3: Three-Finger Mode ────────────────────────────────────────────

@Composable
fun ThreeFingerModePage(
    naturalScroll: Boolean,
    onPrevious: () -> Unit,
    onFinish: (naturalScroll: Boolean, threeFingerMode: com.fasa70.bettertouchpad.system.ThreeFingerMode) -> Unit
) {
    var threeFingerMode by remember { mutableStateOf(com.fasa70.bettertouchpad.system.ThreeFingerMode.NAVBAR) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(48.dp))
            Text(
                "三指手势方案",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "三指手势（返回桌面/应用切换/截图）有两种实现方式，请根据您的系统选择。",
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(32.dp))

            Text(
                "选择三指手势实现方案",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Two option cards
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OptionCard(
                    modifier = Modifier.weight(1f),
                    title = "通用方案",
                    description = "向系统底部导航条注入触摸\n三指上划-最近任务/返回桌面\n三指横划-快切应用\n三指下滑-快捷截屏\n适合非澎湃系统",
                    selected = threeFingerMode == ThreeFingerMode.NAVBAR,
                    onClick = { threeFingerMode = ThreeFingerMode.NAVBAR }
                )
                OptionCard(
                    modifier = Modifier.weight(1f),
                    title = "澎湃OS专用方案",
                    description = "向屏幕中央注入三点触摸\n三指上划-最近任务/返回桌面\n三指上划并横向划动-快切应用\n三指横划-快捷分屏\n三指长按不动-区域截屏\n三指下划-快捷截屏\n适合 HyperOS / 澎湃系统",
                    selected = threeFingerMode == ThreeFingerMode.LEGACY,
                    onClick = { threeFingerMode = ThreeFingerMode.LEGACY }
                )
            }
        }

        // Bottom buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
        TextButton(onClick = onPrevious) { Text("上一步") }
        Button(onClick = { onFinish(naturalScroll, threeFingerMode) }) {
            Text("完成")
        }
        }
    }
}

// ─── Shared Option Card ───────────────────────────────────────────────────

@Composable
private fun OptionCard(
    modifier: Modifier = Modifier,
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val bgColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest

    Card(
        modifier = modifier
            .clickable(onClick = onClick)
            .border(2.dp, borderColor, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                description,
                fontSize = 13.sp,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
