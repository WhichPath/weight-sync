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
    var showInspectionDialog by remember { mutableStateOf(false) }

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
                            "v2.0.7 纯原生无障碍抓取",
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
            // 1. 无障碍服务状态与快速设置引导
            ServiceStatusCard(
                uiState = uiState,
                onOpenAppDetails = {
                    try {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        // ignore
                    }
                },
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

            // 2. 状态反馈与实时排查入口
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
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

                    TextButton(onClick = { showInspectionDialog = true }) {
                        Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("排查日志", fontSize = 12.sp)
                    }
                }
            }

            if (showInspectionDialog) {
                AlertDialog(
                    onDismissRequest = { showInspectionDialog = false },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.BugReport, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("无障碍抓取实时排查", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    },
                    text = {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = uiState.rawInspectionLog,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
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
                                Toast.makeText(context, "排查日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("复制全部日志")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showInspectionDialog = false }) {
                            Text("关闭")
                        }
                    }
                )
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
                            "开启无障碍服务后，打开阿福 App 测秤并展开身体指标记录弹窗，系统将自动识别全量 17 项指标供你核对。",
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
    onOpenAppDetails: () -> Unit,
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
                    "无障碍服务状态",
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
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusBadge(
                    label = "无障碍监听",
                    isActive = uiState.isAccessibilityEnabled,
                    activeText = "已开启 (正常监听)",
                    inactiveText = "未开启"
                )

                if (uiState.isAccessibilityEnabled) {
                    Button(
                        onClick = onLaunchAfu,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("打开阿福 App", fontSize = 13.sp)
                    }
                }
            }

            if (!uiState.isAccessibilityEnabled) {
                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // Android 13+ 受限制设置引导卡片
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "重要：若系统设置里本服务是灰色的",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Text(
                            "Android 13+ 对自行安装的应用有安全限制。若在无障碍列表中开关呈灰色且点开提示受限制，请按以下步骤解除：\n" +
                                    "1. 点击下方【1. 前往应用信息】，进入本 App 详情页\n" +
                                    "2. 点击右上角『三个点 (⋮)』\n" +
                                    "3. 点击『允许受限制的设置』并验证锁屏密码\n" +
                                    "4. 点击下方【2. 前往无障碍设置】，开关即可正常开启！",
                            style = MaterialTheme.typography.bodySmall,
                            lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 操作按键
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onOpenAppDetails,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("1. 前往应用信息", fontSize = 12.sp)
                        }

                        Button(
                            onClick = onOpenAccessibilitySettings,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Accessibility, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("2. 前往无障碍设置", fontSize = 12.sp)
                        }
                    }

                    OutlinedButton(
                        onClick = onLaunchAfu,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("打开阿福 App", fontSize = 13.sp)
                    }
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
    Column(horizontalAlignment = Alignment.Start) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (isActive) Color(0xFF4CAF50) else Color(0xFFF44336))
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                if (isActive) activeText else inactiveText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
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
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("上传中...")
                        } else {
                            Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("确认上传至 Garmin")
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

            // 多项身体指标网格（镜像阿福 17 项全量数据）
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MetricItem("BMI", "${measurement.bmi}")
                    MetricItem("肌肉量", "${measurement.muscleKg} kg")
                    MetricItem("骨量", "${measurement.boneMassKg} kg")
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MetricItem("体水分率", "${measurement.waterPct}%")
                    MetricItem("蛋白占比", "${measurement.proteinPct}%")
                    MetricItem("内脏脂肪", "${measurement.visceralFatRating} 级")
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MetricItem("基础代谢", "${measurement.basalMetKcal.toInt()} kcal")
                    MetricItem("脂肪量", if (measurement.fatMassKg > 0) "${measurement.fatMassKg} kg" else "--")
                    MetricItem("骨骼肌量", if (measurement.skeletalMuscleKg > 0) "${measurement.skeletalMuscleKg} kg" else "--")
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MetricItem("肌肉率", if (measurement.musclePct > 0) "${measurement.musclePct}%" else "--")
                    MetricItem("皮下脂肪率", if (measurement.subcutaneousFatPct > 0) "${measurement.subcutaneousFatPct}%" else "--")
                    MetricItem("骨量占比", if (measurement.boneMassPct > 0) "${measurement.boneMassPct}%" else "--")
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
