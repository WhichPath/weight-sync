package com.example.dianzicheng.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dianzicheng.domain.BodyMeasurement
import com.example.dianzicheng.ui.components.GlassCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: ScaleViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showInspectionDialog by remember { mutableStateOf(false) }
    var showGuideSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refreshStatus()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "健康概览",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshStatus() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新状态")
                    }
                    IconButton(onClick = { showInspectionDialog = true }) {
                        Icon(Icons.Default.Terminal, contentDescription = "排查日志")
                    }
                    IconButton(onClick = { showGuideSheet = true }) {
                        Icon(Icons.Default.HelpOutline, contentDescription = "权限向导")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. 无障碍服务核心控制卡片 (遵循 M3 Expressive 大圆角与层级规划)
            HeroServiceStatusCard(
                uiState = uiState,
                onOpenAccessibilitySettings = {
                    try {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    } catch (_: Exception) {}
                },
                onRequestIgnoreBatteryOptimizations = {
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        try {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        } catch (_: Exception) {}
                    }
                },
                onLaunchAfu = {
                    val launchIntent = context.packageManager.getLaunchIntentForPackage("com.antgroup.aijk.android")
                    if (launchIntent != null) {
                        context.startActivity(launchIntent)
                    } else {
                        Toast.makeText(context, "未找到阿福 App，请确认已安装", Toast.LENGTH_SHORT).show()
                    }
                },
                onOpenGuide = { showGuideSheet = true }
            )

            // 2. 紧凑型状态速报胶囊 (取代臃肿的大卡片，兼顾信息感知与视觉呼吸感)
            StatusTickerPill(
                lastLogMessage = uiState.lastLogMessage,
                onOpenLog = { showInspectionDialog = true }
            )

            // 3. 最新身体指标 Hero 展示区域
            uiState.currentMeasurement?.let { measurement ->
                CapturedMeasurementCard(
                    measurement = measurement,
                    isSyncing = uiState.isGarminSyncing,
                    syncResult = uiState.garminSyncResult,
                    onSyncGarmin = { viewModel.syncCurrentToGarmin() }
                )
            } ?: run {
                EmptyMeasurementCard(
                    isServiceActive = uiState.isAccessibilityEnabled,
                    onLaunchAfu = {
                        val launchIntent = context.packageManager.getLaunchIntentForPackage("com.antgroup.aijk.android")
                        if (launchIntent != null) {
                            context.startActivity(launchIntent)
                        } else {
                            Toast.makeText(context, "未找到阿福 App", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onOpenSettings = {
                        try {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        } catch (_: Exception) {}
                    }
                )
            }
        }
    }

    // ── 详细排查日志弹窗 ────────────────────────────────────────────────────────
    if (showInspectionDialog) {
        AlertDialog(
            onDismissRequest = { showInspectionDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.BugReport, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("抓取排查日志", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = uiState.rawInspectionLog,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val clip = ClipData.newPlainText("weight_sync_log", uiState.rawInspectionLog)
                        clipboard?.setPrimaryClip(clip)
                        Toast.makeText(context, "日志已复制", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("复制全部")
                }
            },
            dismissButton = {
                TextButton(onClick = { showInspectionDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }

    // ── 权限与后台常驻配置向导 BottomSheet (渐进式呈现详细说明) ──────────────────
    if (showGuideSheet) {
        ModalBottomSheet(
            onDismissRequest = { showGuideSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            PermissionGuideSheetContent(
                isBatteryIgnored = uiState.isBatteryOptimizationIgnored,
                onOpenAppDetails = {
                    try {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(intent)
                    } catch (_: Exception) {}
                },
                onOpenAccessibilitySettings = {
                    try {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    } catch (_: Exception) {}
                },
                onRequestIgnoreBatteryOptimizations = {
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        try {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        } catch (_: Exception) {}
                    }
                },
                onDismiss = { showGuideSheet = false }
            )
        }
    }
}

/**
 * 核心服务状态与快速操作卡片。
 * 彻底重构布局结构：状态指示与启动按钮垂直解耦，杜绝文字挤压按钮；字数精确收敛。
 */
@Composable
private fun HeroServiceStatusCard(
    uiState: ScaleUiState,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestIgnoreBatteryOptimizations: () -> Unit,
    onLaunchAfu: () -> Unit,
    onOpenGuide: () -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 状态指示行：双胶囊状态徽章
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 无障碍服务状态指示
                StatusPill(
                    isActive = uiState.isAccessibilityEnabled,
                    activeLabel = "监听运行中",
                    inactiveLabel = "服务未就绪"
                )

                // 保活状态徽章
                Surface(
                    shape = RoundedCornerShape(100.dp),
                    color = if (uiState.isBatteryOptimizationIgnored)
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    else
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                ) {
                    Text(
                        text = if (uiState.isBatteryOptimizationIgnored) "常驻已加白" else "后台待保活",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (uiState.isBatteryOptimizationIgnored)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            // 描述提示语 (精简、克制)
            Text(
                text = if (uiState.isAccessibilityEnabled) {
                    "前台监听服务常驻保护中，在阿福中完成测秤即可自动抓取并同步。"
                } else {
                    "需要开启无障碍服务，以便在打开阿福身体成分弹窗时自动解析数据。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 核心行动按钮：占据主导视觉空间，尺寸规范充裕
            if (uiState.isAccessibilityEnabled) {
                Button(
                    onClick = onLaunchAfu,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("打开阿福 App 测秤", style = MaterialTheme.typography.labelLarge)
                }
            } else {
                Button(
                    onClick = onOpenAccessibilitySettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.Accessibility, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("前往开启无障碍服务", style = MaterialTheme.typography.labelLarge)
                }
            }

            // 底部快捷操作与向导入口
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!uiState.isBatteryOptimizationIgnored) {
                    TextButton(
                        onClick = onRequestIgnoreBatteryOptimizations,
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        Icon(Icons.Default.Shield, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("一键开启后台无限制", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                TextButton(
                    onClick = onOpenGuide,
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Icon(Icons.Default.HelpOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("权限配置向导", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/**
 * 状态胶囊指示器
 */
@Composable
private fun StatusPill(
    isActive: Boolean,
    activeLabel: String,
    inactiveLabel: String
) {
    Surface(
        shape = RoundedCornerShape(100.dp),
        color = if (isActive) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (isActive) Color(0xFF2E7D32) else Color(0xFFD32F2F))
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isActive) activeLabel else inactiveLabel,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (isActive) Color(0xFF2E7D32) else Color(0xFFD32F2F)
            )
        }
    }
}

/**
 * 轻量化状态速报条
 */
@Composable
private fun StatusTickerPill(
    lastLogMessage: String,
    onOpenLog: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onOpenLog),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = lastLogMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "日志 ›",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * 捕获到的身体数据指标卡片
 */
@Composable
private fun CapturedMeasurementCard(
    measurement: BodyMeasurement,
    isSyncing: Boolean,
    syncResult: String?,
    onSyncGarmin: () -> Unit
) {
    val timeStr = remember(measurement.measuredAtEpochMs) {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(measurement.measuredAtEpochMs))
    }

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 头部：记录时间与 Garmin 同步状态按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("最近捕获数据", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(timeStr, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }

                if (measurement.syncedToGarmin) {
                    Surface(
                        shape = RoundedCornerShape(100.dp),
                        color = Color(0xFFE8F5E9)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Garmin 已同步", color = Color(0xFF2E7D32), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else {
                    Button(
                        onClick = onSyncGarmin,
                        enabled = !isSyncing,
                        shape = RoundedCornerShape(100.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("上传中...", style = MaterialTheme.typography.labelMedium)
                        } else {
                            Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("同步至 Garmin", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            // 核心双数值展示：超大字体与呼吸感
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = String.format(Locale.getDefault(), "%.2f", measurement.weightKg),
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text("体重 (kg)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                    }

                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(40.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    )

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${trimNum(measurement.bodyFatPct)}%",
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text("体脂率", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }

            // 16 项指标矩阵：规整的 3 列微卡片容器
            val metricCells = listOf(
                "BMI" to trimNum(measurement.bmi),
                "内脏脂肪" to "${measurement.visceralFatRating} 级",
                "基础代谢" to if (measurement.basalMetKcal > 0) "${trimNum(measurement.basalMetKcal)} kcal" else "--",
                "肌肉量" to kgText(measurement.muscleKg),
                "肌肉率" to pctText(measurement.musclePct),
                "骨骼肌量" to kgText(measurement.skeletalMuscleKg),
                "骨骼肌率" to pctText(measurement.skeletalMusclePct),
                "脂肪量" to kgText(measurement.fatMassKg),
                "皮下脂肪量" to kgText(measurement.subcutaneousFatKg),
                "皮下脂肪率" to pctText(measurement.subcutaneousFatPct),
                "体水分量" to kgText(measurement.waterKg),
                "体水分率" to pctText(measurement.waterPct),
                "骨量" to kgText(measurement.boneMassKg),
                "骨量占比" to pctText(measurement.boneMassPct),
                "蛋白含量" to kgText(measurement.proteinKg),
                "蛋白占比" to pctText(measurement.proteinPct)
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                metricCells.chunked(3).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        row.forEach { (label, value) ->
                            MetricMiniCard(label = label, value = value, modifier = Modifier.weight(1f))
                        }
                        repeat(3 - row.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            if (syncResult != null) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = syncResult,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 12.dp)
                    )
                }
            }
        }
    }
}

/**
 * 细分指标微卡片
 */
@Composable
private fun MetricMiniCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

/**
 * 空状态卡片
 */
@Composable
private fun EmptyMeasurementCard(
    isServiceActive: Boolean,
    onLaunchAfu: () -> Unit,
    onOpenSettings: () -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(64.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.MonitorWeight,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Text("等待捕获测量数据", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            Text(
                text = "开启无障碍服务后，在阿福 App 中测秤并点开身体指标详情弹窗，全量 17 项健康指标将自动识别并呈现在此。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(4.dp))

            if (isServiceActive) {
                FilledTonalButton(
                    onClick = onLaunchAfu,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("前往阿福 App 测秤")
                }
            } else {
                Button(
                    onClick = onOpenSettings,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Accessibility, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("开启无障碍服务")
                }
            }
        }
    }
}

/**
 * 权限配置与受限设置向导 BottomSheet 内容
 */
@Composable
private fun PermissionGuideSheetContent(
    isBatteryIgnored: Boolean,
    onOpenAppDetails: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestIgnoreBatteryOptimizations: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 36.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("无障碍与保活配置向导", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "关闭")
            }
        }

        // 第 1 步：受限设置解除说明
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LockOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("1. 解除受限设置 (Android 13+)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                }
                Text(
                    text = "自行安装的 APK 默认受系统安全限制。若在系统无障碍设置中发现开关呈灰色不可点击，请：\n" +
                            "• 点击下方按钮进入『应用信息』\n" +
                            "• 点击右上角三个点 (⋮)，选择『允许受限制的设置』并验证锁屏密码。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
                Button(
                    onClick = onOpenAppDetails,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("前往应用信息页")
                }
            }
        }

        // 第 2 步：开启无障碍
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Accessibility, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("2. 开启无障碍监听", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                }
                Text(
                    text = "在已下载的应用 / 无障碍列表中找到『阿福数据自动提取服务』并开启开关。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onOpenAccessibilitySettings,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("前往系统无障碍设置")
                }
            }
        }

        // 第 3 步：后台保活与加锁
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("3. 后台无限制防掉权限", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                }
                Text(
                    text = "系统会在待机或多任务清理时回收权限。建议：\n" +
                            "• 开启电池优化白名单 (无限制)\n" +
                            "• 在多任务任务列表中将本应用卡片下拉加锁。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
                if (!isBatteryIgnored) {
                    Button(
                        onClick = onRequestIgnoreBatteryOptimizations,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.BatteryChargingFull, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("一键开启电池无限制")
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFE8F5E9),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "✓ 电池无限制白名单已生效",
                            color = Color(0xFF2E7D32),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun trimNum(value: Double): String {
    val rounded = (value * 10.0).roundToInt() / 10.0
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
}

private fun pctText(value: Double): String = if (value > 0.0) "${trimNum(value)}%" else "--"

private fun kgText(value: Double): String = if (value > 0.0) "${trimNum(value)} kg" else "--"
