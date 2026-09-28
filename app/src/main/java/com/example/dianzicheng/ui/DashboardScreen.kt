package com.example.dianzicheng.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.*
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dianzicheng.domain.BodyMeasurement
import com.example.dianzicheng.service.ShizukuManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: ScaleViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.refreshStatus()
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("阿福数据同步", fontWeight = FontWeight.Bold)
                        Text(
                            "v2.0.0 UI 自动化捕获",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. 服务与授权状态卡片
            ServiceStatusCard(
                uiState = uiState,
                onEnableViaShizuku = { viewModel.enableAccessibilityViaShizuku() },
                onRequestShizukuPerm = { ShizukuManager.requestPermission(1001) },
                onOpenAccessibilitySettings = {
                    try {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    } catch (e: Exception) {
                        // ignore
                    }
                },
                onLaunchAfu = {
                    val launchIntent = context.packageManager.getLaunchIntentForPackage("com.antgroup.aijk.android")
                    if (launchIntent != null) {
                        context.startActivity(launchIntent)
                    }
                },
                onRefresh = { viewModel.refreshStatus() }
            )

            // 2. 状态反馈与实时日志条目
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = uiState.lastLogMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 3. 最近捕获记录卡片
            uiState.currentMeasurement?.let { measurement ->
                CapturedMeasurementCard(
                    measurement = measurement,
                    isSyncing = uiState.isGarminSyncing,
                    syncResult = uiState.garminSyncResult,
                    onSyncGarmin = { viewModel.syncCurrentToGarmin() }
                )
            } ?: run {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Visibility,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "暂无捕获数据",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "激活服务后，打开阿福 App 测秤并进入身体成分详情页，系统将自动识别并上传。",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ServiceStatusCard(
    uiState: ScaleUiState,
    onEnableViaShizuku: () -> Unit,
    onRequestShizukuPerm: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onLaunchAfu: () -> Unit,
    onRefresh: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "服务监控面板",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新状态")
                }
            }

            // 状态指示条
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatusBadge(
                    label = "Shizuku 服务",
                    isActive = uiState.isShizukuRunning,
                    activeText = "运行中",
                    inactiveText = "未启动"
                )
                StatusBadge(
                    label = "Shizuku 授权",
                    isActive = uiState.hasShizukuPermission,
                    activeText = "已授权",
                    inactiveText = "未授权"
                )
                StatusBadge(
                    label = "无障碍监听",
                    isActive = uiState.isAccessibilityEnabled,
                    activeText = "已激活",
                    inactiveText = "未激活"
                )
            }

            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // 操作按键
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!uiState.hasShizukuPermission && uiState.isShizukuRunning) {
                    Button(
                        onClick = onRequestShizukuPerm,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("申请 Shizuku 授权", fontSize = 13.sp)
                    }
                } else if (!uiState.isAccessibilityEnabled) {
                    Button(
                        onClick = onEnableViaShizuku,
                        enabled = uiState.hasShizukuPermission,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.FlashOn, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("一键 Shizuku 激活", fontSize = 13.sp)
                    }
                }

                OutlinedButton(
                    onClick = onLaunchAfu,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("打开阿福 App", fontSize = 13.sp)
                }
            }

            if (!uiState.isAccessibilityEnabled && !uiState.hasShizukuPermission) {
                TextButton(
                    onClick = onOpenAccessibilitySettings,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text("无 Shizuku？点此前往系统设置手动开启无障碍服务", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(
    label: String,
    isActive: Boolean,
    activeText: String,
    inactiveText: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (isActive) Color(0xFF4CAF50) else Color(0xFFF44336))
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                if (isActive) activeText else inactiveText,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun CapturedMeasurementCard(
    measurement: BodyMeasurement,
    isSyncing: Boolean,
    syncResult: String?,
    onSyncGarmin: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("最近捕获数据", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        .format(Date(measurement.measuredAtEpochMs))
                    Text(timeStr, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }

                if (measurement.syncedToGarmin) {
                    AssistChip(
                        onClick = {},
                        label = { Text("Garmin 已同步", color = Color(0xFF2E7D32)) },
                        leadingIcon = { Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF2E7D32)) }
                    )
                } else {
                    Button(
                        onClick = onSyncGarmin,
                        enabled = !isSyncing,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Text("同步 Garmin")
                        }
                    }
                }
            }

            // 核心数值
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${measurement.weightKg}", fontSize = 36.sp, fontWeight = FontWeight.ExtraBold)
                    Text("体重 (kg)", style = MaterialTheme.typography.labelMedium)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${measurement.bodyFatPct}%", fontSize = 36.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                    Text("体脂率", style = MaterialTheme.typography.labelMedium)
                }
            }

            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // 多项身体指标网格
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MetricItem("BMI", "${measurement.bmi}")
                    MetricItem("肌肉量", "${measurement.muscleKg} kg")
                    MetricItem("骨量", "${measurement.boneMassKg} kg")
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MetricItem("水分率", "${measurement.waterPct}%")
                    MetricItem("蛋白质", "${measurement.proteinPct}%")
                    MetricItem("内脏脂肪", "${measurement.visceralFatRating} 级")
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MetricItem("基础代谢", "${measurement.basalMetKcal.toInt()} kcal")
                    MetricItem("身体年龄", "${measurement.metabolicAge} 岁")
                    MetricItem("数据来源", "阿福官方")
                }
            }

            if (syncResult != null) {
                Text(
                    syncResult,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
    }
}

@Composable
private fun RowScope.MetricItem(label: String, value: String) {
    Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }
}
