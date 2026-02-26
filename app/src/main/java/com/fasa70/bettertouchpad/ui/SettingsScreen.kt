package com.fasa70.bettertouchpad.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fasa70.bettertouchpad.SettingsRepository

@Composable
fun SettingsScreen(repo: SettingsRepository) {
    val settings by repo.settings.collectAsState()
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // ── Feature toggles ───────────────────────────────────────────────
        Text("功能开关", fontSize = 18.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 8.dp))

        FeatureSwitch("单指划动 (移动光标)", settings.singleFingerMove) {
            repo.update { copy(singleFingerMove = it) }
        }
        FeatureSwitch("单指单击 (鼠标左键)", settings.singleFingerTap) {
            repo.update { copy(singleFingerTap = it) }
        }
        FeatureSwitch("按下触控板 (鼠标左键/长按)", settings.physicalClick) {
            repo.update { copy(physicalClick = it) }
        }
        FeatureSwitch("轻触两下以拖移", settings.doubleTapDrag) {
            repo.update { copy(doubleTapDrag = it) }
        }
        FeatureSwitch("双指单击 (鼠标右键)", settings.twoFingerTap) {
            repo.update { copy(twoFingerTap = it) }
        }
        FeatureSwitch("双指划动 (滚轮)", settings.twoFingerScroll) {
            repo.update { copy(twoFingerScroll = it) }
        }
        FeatureSwitch("双指边缘内划 (注入触摸)", settings.edgeSwipe) {
            repo.update { copy(edgeSwipe = it) }
        }
        FeatureSwitch("三指移动 (注入三点触摸)", settings.threeFingerMove) {
            repo.update { copy(threeFingerMove = it) }
        }

        // ── Sensitivity ───────────────────────────────────────────────────
        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        Text("灵敏度设置", fontSize = 18.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp))

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
        FeatureSwitch("自然滚动 (手指方向与内容方向一致)", settings.naturalScroll) {
            repo.update { copy(naturalScroll = it) }
        }
        SensitivityRow(
            label = "触摸注入速度",
            value = settings.touchInjectSpeed,
            range = 0.01f..3.0f,
            onValueChange = { repo.update { copy(touchInjectSpeed = it) } },
            onDone = { focusManager.clearFocus() }
        )

        // ── Axis correction ───────────────────────────────────────────────
        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        Text("触摸注入方向校正", fontSize = 18.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp))
        Text(
            "当双指/三指手势注入的触摸方向不正确时，使用以下选项进行修正。",
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

        // ── Touchpad coordinate range ─────────────────────────────────────
        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        Text("触控板坐标范围 (兼容性设置)", fontSize = 18.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp))
        Text(
            "自定义触控板的最大坐标值以兼容不同设备。\n默认值适用于 Xiaomi Touch (28790 × 17990)。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        CoordInput("X 轴最大值", settings.padMaxX.toString()) { v ->
            v.toIntOrNull()?.takeIf { it > 0 }?.let { repo.update { copy(padMaxX = it) } }
        }
        CoordInput("Y 轴最大值", settings.padMaxY.toString()) { v ->
            v.toIntOrNull()?.takeIf { it > 0 }?.let { repo.update { copy(padMaxY = it) } }
        }

        SensitivityRow(
            label = "边缘触发区域宽度 (占X轴比例)",
            value = settings.edgeThreshold,
            range = 0.01f..0.30f,
            onValueChange = { repo.update { copy(edgeThreshold = it) } },
            onDone = { focusManager.clearFocus() }
        )

        Spacer(modifier = Modifier.height(32.dp))
    }
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

/**
 * A row with a slider + a small text field that both control the same Float value.
 * The slider covers [range] continuously (no fixed steps → smooth).
 * The text field lets the user type an exact value and confirms on Done / focus-loss.
 */
@Composable
private fun SensitivityRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onDone: () -> Unit = {}
) {
    // Local text state — only committed to the repo when valid
    var textValue by remember(value) { mutableStateOf("%.3f".format(value)) }
    // Track whether the text field is being edited so we don't fight the slider
    var isEditing by remember { mutableStateOf(false) }

    fun commitText(raw: String) {
        val f = raw.toFloatOrNull() ?: return
        val clamped = f.coerceIn(range.start, range.endInclusive)
        onValueChange(clamped)
        textValue = "%.3f".format(clamped)
        isEditing = false
    }

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
                    isEditing = true
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction    = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = { commitText(textValue); onDone() }
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

