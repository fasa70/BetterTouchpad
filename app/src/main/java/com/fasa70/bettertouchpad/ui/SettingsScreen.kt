package com.fasa70.bettertouchpad.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fasa70.bettertouchpad.SettingsRepository
import com.fasa70.bettertouchpad.system.ThreeFingerMode

// ─── Tab 1: Gesture Settings ─────────────────────────────────────────────

private var showThreeFingerHelpDialog by mutableStateOf(false)

@Composable
fun GestureSettingsTab(repo: SettingsRepository) {
    val settings by repo.settings.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        SectionTitle("单指手势")
        FeatureSwitch("单指划动 (移动光标)", settings.singleFingerMove) {
            repo.update { copy(singleFingerMove = it) }
        }
        FeatureSwitch("单指单击 (鼠标左键)", settings.singleFingerTap) {
            repo.update { copy(singleFingerTap = it) }
        }
        FeatureSwitch("轻触两下以拖移", settings.doubleTapDrag) {
            repo.update { copy(doubleTapDrag = it) }
        }

        SectionTitle("双指手势")
        FeatureSwitch("双指单击 (鼠标右键)", settings.twoFingerTap) {
            repo.update { copy(twoFingerTap = it) }
        }
        FeatureSwitch("双指划动 (鼠标滚轮)", settings.twoFingerScroll) {
            repo.update { copy(twoFingerScroll = it) }
        }
        FeatureSwitch("自然滚动", settings.naturalScroll) {
            repo.update { copy(naturalScroll = it) }
        }
        FeatureSwitch("双指捏合缩放（注入触摸）", settings.twoFingerZoom) {
            repo.update { copy(twoFingerZoom = it) }
        }
        FeatureSwitch("双指边缘内划 (返回上一级)", settings.edgeSwipe) {
            repo.update { copy(edgeSwipe = it) }
        }
        FeatureSwitch("双指顶部下滑（通知中心/控制中心）", settings.twoFingerTopSwipe) {
            repo.update { copy(twoFingerTopSwipe = it) }
        }
        FeatureSwitch("双指底部上划（鼠标后退/前进键）", settings.twoFingerBottomSwipe) {
            repo.update { copy(twoFingerBottomSwipe = it) }
        }

        SectionTitle("三指手势")
        FeatureSwitch("三指单击 (鼠标中键)", settings.threeFingerMiddleClick) {
            repo.update { copy(threeFingerMiddleClick = it) }
        }
        FeatureSwitch("三指系统级手势", settings.threeFingerMove) {
            repo.update { copy(threeFingerMove = it) }
        }
        if (settings.threeFingerMove) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "实现方案",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.width(4.dp))
                TextButton(onClick = { showThreeFingerHelpDialog = true }) {
                    Text("说明", fontSize = 12.sp)
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { repo.update { copy(threeFingerMode = ThreeFingerMode.LEGACY) } }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = settings.threeFingerMode == ThreeFingerMode.LEGACY,
                    onClick = { repo.update { copy(threeFingerMode = ThreeFingerMode.LEGACY) } }
                )
                Text("澎湃OS专用方案 (向屏幕中央注入三点触摸)", modifier = Modifier.padding(start = 8.dp))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { repo.update { copy(threeFingerMode = ThreeFingerMode.NAVBAR) } }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = settings.threeFingerMode == ThreeFingerMode.NAVBAR,
                    onClick = { repo.update { copy(threeFingerMode = ThreeFingerMode.NAVBAR) } }
                )
                Text("通用方案 (向系统底部导航条注入触摸)", modifier = Modifier.padding(start = 8.dp))
            }
        }

        SectionTitle("其他")
        FeatureSwitch("按下触控板 (鼠标左键)", settings.physicalClick) {
            repo.update { copy(physicalClick = it) }
        }
    }

    if (showThreeFingerHelpDialog) {
        AlertDialog(
            onDismissRequest = { showThreeFingerHelpDialog = false },
            title = { Text("三指手势方案差异") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("澎湃OS专用方案：向屏幕中央注入三点触摸\n三指上划-最近任务/返回桌面\n三指上划并横向划动-快切应用\n三指横划-快捷分屏\n三指长按不动-区域截屏\n三指下划-快捷截屏", fontSize = 14.sp)
                    Text("通用方案：\n三指上划-最近任务/返回桌面\n三指横划-快切应用\n三指下滑-快捷截屏", fontSize = 14.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { showThreeFingerHelpDialog = false }) {
                    Text("知道了")
                }
            }
        )
    }
}

// ─── Tab 2: Touch Settings ───────────────────────────────────────────────

@Composable
fun TouchSettingsTab(repo: SettingsRepository) {
    val settings by repo.settings.collectAsState()
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        SectionTitle("灵敏度")
        SensitivityRow(
            label = "光标灵敏度",
            value = settings.cursorSensitivity,
            range = 0.01f..5.0f,
            onValueChange = { repo.update { copy(cursorSensitivity = it) } },
            onDone = { focusManager.clearFocus() }
        )
        SensitivityRow(
            label = "滚轮灵敏度",
            value = settings.scrollSensitivity,
            range = 0.01f..5.0f,
            onValueChange = { repo.update { copy(scrollSensitivity = it) } },
            onDone = { focusManager.clearFocus() }
        )
        SensitivityRow(
            label = "双指捏合手势缩放灵敏度",
            value = settings.zoomSensitivity,
            range = 0.5f..5.0f,
            onValueChange = { repo.update { copy(zoomSensitivity = it) } },
            onDone = { focusManager.clearFocus() }
        )
        SensitivityRow(
            label = "双指捏合手势判定阈值 (px)",
            value = settings.minPinchDistance,
            range = 10f..300f,
            onValueChange = { repo.update { copy(minPinchDistance = it) } },
            onDone = { focusManager.clearFocus() }
        )
        SensitivityRow(
            label = "三指手势触摸注入灵敏度",
            value = settings.touchInjectSpeed,
            range = 0.01f..3.0f,
            onValueChange = { repo.update { copy(touchInjectSpeed = it) } },
            onDone = { focusManager.clearFocus() }
        )

        SectionTitle("双指手势触发区域")
        SensitivityRow(
            label = "边缘手势触发区域宽度 (占X轴比例)",
            value = settings.edgeThreshold,
            range = 0.01f..0.30f,
            onValueChange = { repo.update { copy(edgeThreshold = it) } },
            onDone = { focusManager.clearFocus() }
        )
        SensitivityRow(
            label = "顶部手势触发区域宽度 (占X轴比例)",
            value = settings.topEdgeZoneRatio,
            range = 0.01f..0.50f,
            onValueChange = { repo.update { copy(topEdgeZoneRatio = it) } },
            onDone = { focusManager.clearFocus() }
        )
        SensitivityRow(
            label = "顶部手势触发区域高度 (占Y轴比例)",
            value = settings.topEdgeThreshold,
            range = 0.01f..0.30f,
            onValueChange = { repo.update { copy(topEdgeThreshold = it) } },
            onDone = { focusManager.clearFocus() }
        )
        SensitivityRow(
            label = "底部手势触发区域宽度 (占X轴比例)",
            value = settings.bottomEdgeZoneRatio,
            range = 0.01f..0.50f,
            onValueChange = { repo.update { copy(bottomEdgeZoneRatio = it) } },
            onDone = { focusManager.clearFocus() }
        )
        SensitivityRow(
            label = "底部手势触发区域高度 (占Y轴比例)",
            value = settings.bottomEdgeThreshold,
            range = 0.01f..0.30f,
            onValueChange = { repo.update { copy(bottomEdgeThreshold = it) } },
            onDone = { focusManager.clearFocus() }
        )
        Text(
            "双指从触控板顶部触发区下滑：左侧触发通知中心，右侧触发控制中心。\n双指从触控板底部触发区上划：左侧触发鼠标后退键，右侧触发鼠标前进键。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        SectionTitle("双击拖移")
        DoubleTapDragIntervalSetting(repo, focusManager)
    }
}

// ─── Tab 3: Compatibility Settings ───────────────────────────────────────

@Composable
fun CompatibilitySettingsTab(repo: SettingsRepository) {
    val settings by repo.settings.collectAsState()
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        SectionTitle("触摸注入方向校正")
        Text(
            "当双指/三指手势注入的触摸方向不正确时，使用以下选项进行修正。\n默认开启 XY 轴对调和反转 Y 轴。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        FeatureSwitch("XY 轴对调 (交换横纵方向)", settings.swapAxes) {
            repo.update { copy(swapAxes = it) }
        }
        FeatureSwitch("反转 X 轴 (水平方向取反)", settings.invertX) {
            repo.update { copy(invertX = it) }
        }
        FeatureSwitch("反转 Y 轴 (垂直方向取反)", settings.invertY) {
            repo.update { copy(invertY = it) }
        }

        SectionTitle("兼容性设置")
        FeatureSwitch("独占设备 (EVIOCGRAB)", settings.exclusiveGrab) {
            repo.update { copy(exclusiveGrab = it) }
        }
        Text(
            "开启后，触控板输入事件将被本应用独占，系统其他进程无法读取，以防止跟系统手势产生冲突。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        FeatureSwitch("自动宽容 SELinux（setenforce 0）", settings.seLinuxEnforce) {
            repo.update { copy(seLinuxEnforce = it) }
        }
        Text(
            "开启后，应用在无法通过 root helper 获取 fd 时将尝试设置 SELinux 为宽容模式以确保设备访问正常。\n如设备访问无问题可关闭此选项减少安全风险。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        FeatureSwitch("启动后两秒执行 setenforce 1（强制模式）", settings.setenforceOneAfterStart) {
            repo.update { copy(setenforceOneAfterStart = it) }
        }
        Text(
            "开启后，在触控板服务启动约2秒后将 SELinux 设为强制模式，增强系统安全性。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        FeatureSwitch("自动匹配触控板设备路径和坐标值范围", settings.autoDetectDevice) {
            repo.update { copy(autoDetectDevice = it) }
        }
        Text(
            "开启后，程序启动时自动获取触控板设备路径及坐标最大值\n如程序未能正常运行，可尝试关闭此选项",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        if (!settings.autoDetectDevice) {
            var devicePathText by remember(settings.devicePath) { mutableStateOf(settings.devicePath) }
            OutlinedTextField(
                value = devicePathText,
                onValueChange = { devicePathText = it },
                label = { Text("设备路径（如 /dev/input/event5）") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (devicePathText.isNotBlank()) repo.update { copy(devicePath = devicePathText.trim()) }
                    focusManager.clearFocus()
                }),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            )

            Text(
                "触控板坐标最大值",
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
            )
            CoordInput("X 轴最大值", settings.padMaxX.toString()) { v ->
                v.toIntOrNull()?.takeIf { it > 0 }?.let { repo.update { copy(padMaxX = it) } }
            }
            CoordInput("Y 轴最大值", settings.padMaxY.toString()) { v ->
                v.toIntOrNull()?.takeIf { it > 0 }?.let { repo.update { copy(padMaxY = it) } }
            }
        } else {
            Text(
                "当前设备路径：${settings.devicePath}\n坐标最大值：X=${settings.padMaxX}  Y=${settings.padMaxY}\n（启动服务后自动更新）",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
    }
}

// ─── Tab 4: About ────────────────────────────────────────────────────────

@Composable
fun AboutTab() {
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "BetterTouchpad",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "让 Android 触控板行为更接近真实鼠标",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))

        Text(
            "by 风洒青泥",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            "觉得好用的话别忘了在 GitHub 上给我点个 ⭐~",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable {
                uriHandler.openUri("https://github.com/fasa70/BetterTouchpad")
            }
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "https://github.com/fasa70/BetterTouchpad",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ─── Shared Helpers ──────────────────────────────────────────────────────

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(vertical = 8.dp)
    )
    HorizontalDivider(modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun FeatureSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SensitivityRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onDone: () -> Unit = {}
) {
    var textValue by remember(value) { mutableStateOf("%.3f".format(value)) }
    var isEditing by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, fontSize = 14.sp, modifier = Modifier.padding(bottom = 2.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Slider(
                value = value,
                onValueChange = {
                    if (!isEditing) {
                        onValueChange(it)
                        textValue = "%.3f".format(it)
                    }
                },
                valueRange = range,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedTextField(
                value = textValue,
                onValueChange = {
                    textValue = it
                    val f = it.toFloatOrNull()
                    if (f != null) {
                        val clamped = f.coerceIn(range.start, range.endInclusive)
                        onValueChange(clamped)
                    }
                    isEditing = true
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction    = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = { onDone() }
                ),
                singleLine = true,
                modifier = Modifier.width(88.dp),
                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
            )
        }
    }
}

@Composable
private fun CoordInput(label: String, value: String, onValueChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { newVal ->
            text = newVal
            onValueChange(newVal)
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    )
}

@Composable
private fun DoubleTapDragIntervalSetting(repo: SettingsRepository, focusManager: androidx.compose.ui.focus.FocusManager) {
    val settings by repo.settings.collectAsState()

    Text("双击拖移间隔时间 (ms)", fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Slider(
            value = settings.doubleTapIntervalMs.toFloat(),
            onValueChange = { repo.update { copy(doubleTapIntervalMs = it.toInt()) } },
            valueRange = 10f..500f,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        var dtText by remember(settings.doubleTapIntervalMs) { mutableStateOf(settings.doubleTapIntervalMs.toString()) }
        OutlinedTextField(
            value = dtText,
            onValueChange = { dtText = it
                dtText.toIntOrNull()?.takeIf { v -> v in 50..500 }?.let { v ->
                    repo.update { copy(doubleTapIntervalMs = v) }
                }
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = {
                focusManager.clearFocus()
            }),
            singleLine = true,
            modifier = Modifier.width(88.dp),
            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
        )
    }
    Text(
        "两次点击间隔不超过此时间时，触发双击拖移",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}
