package com.example.dianzicheng.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dianzicheng.data.ble.BleScaleClient
import com.example.dianzicheng.domain.BodyMeasurement

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    uiState: ScaleUiState,
    onStartScan: () -> Unit,
    onNavigateToPairing: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("阿福体脂秤", fontWeight = FontWeight.Bold) },
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
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // ── 连接状态提示条 ────────────────────────────────────────────────
            ConnectionStatusChip(
                connection = uiState.connection,
                isDeviceRemembered = uiState.isDeviceRemembered,
                onNavigateToPairing = onNavigateToPairing,
                onStartScan = onStartScan
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ── 核心体重仪表圆盘 ──────────────────────────────────────────────
            val displayWeight = if (uiState.liveWeightKg > 0.0) {
                uiState.liveWeightKg
            } else {
                uiState.currentMeasurement?.weightKg ?: 0.0
            }

            WeightDisplayCircle(
                weightKg = displayWeight,
                isMeasuring = uiState.connection == BleScaleClient.ConnectionState.MEASURING || uiState.isStable,
                impedanceOhm = uiState.impedanceOhm
            )

            Spacer(modifier = Modifier.height(20.dp))

            // ── Garmin 同步状态提示 ───────────────────────────────────────────
            if (uiState.isGarminSyncing || uiState.garminSyncResult != null) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (uiState.isGarminSyncing) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("正在上传至 Garmin...", fontSize = 12.sp)
                        } else {
                            Icon(Icons.Default.CloudDone, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(6.dp))
                            Text(uiState.garminSyncResult ?: "", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            // ── 详细身体指标卡片（持久显示本次或最近一次测量） ───────────────
            val measurement = uiState.currentMeasurement
            if (measurement != null) {
                MeasurementSummaryCard(measurement = measurement)
            } else {
                EmptyPromptCard(
                    isDeviceRemembered = uiState.isDeviceRemembered,
                    onNavigateToPairing = onNavigateToPairing
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ConnectionStatusChip(
    connection: BleScaleClient.ConnectionState,
    isDeviceRemembered: Boolean,
    onNavigateToPairing: () -> Unit,
    onStartScan: () -> Unit
) {
    if (!isDeviceRemembered) {
        AssistChip(
            onClick = onNavigateToPairing,
            label = { Text("未配对设备，点击去配对") },
            leadingIcon = { Icon(Icons.Default.BluetoothSearching, contentDescription = null) }
        )
    } else {
        val (text, color, icon) = when (connection) {
            BleScaleClient.ConnectionState.CONNECTED -> Triple("体脂秤已连接，请上秤", MaterialTheme.colorScheme.primary, Icons.Default.BluetoothConnected)
            BleScaleClient.ConnectionState.MEASURING -> Triple("正在称重与测量体脂...", MaterialTheme.colorScheme.tertiary, Icons.Default.Speed)
            BleScaleClient.ConnectionState.CONNECTING -> Triple("正在连接体脂秤...", MaterialTheme.colorScheme.secondary, Icons.Default.BluetoothSearching)
            BleScaleClient.ConnectionState.SCANNING -> Triple("正在搜索体脂秤...", MaterialTheme.colorScheme.secondary, Icons.Default.BluetoothSearching)
            BleScaleClient.ConnectionState.IDLE -> Triple("空闲中，点击连接", MaterialTheme.colorScheme.outline, Icons.Default.Bluetooth)
        }

        Surface(
            onClick = { if (connection == BleScaleClient.ConnectionState.IDLE) onStartScan() },
            shape = CircleShape,
            color = color.copy(alpha = 0.12f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun WeightDisplayCircle(
    weightKg: Double,
    isMeasuring: Boolean,
    impedanceOhm: Double
) {
    Surface(
        modifier = Modifier.size(240.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        border = androidx.compose.foundation.BorderStroke(
            4.dp,
            if (isMeasuring) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = String.format("%.2f", weightKg),
                fontSize = 54.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-1).sp
            )
            Text(
                text = "kg",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.outline
            )
            if (impedanceOhm > 0.0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "阻抗: ${impedanceOhm.toInt()} Ω",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun MeasurementSummaryCard(measurement: BodyMeasurement) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "本次测量身体成分",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                DetailMetric("BMI", String.format("%.1f", measurement.bmi), "")
                DetailMetric("体脂率", if (measurement.bodyFatPct > 0) "${measurement.bodyFatPct}%" else "--", "")
                DetailMetric("水分率", if (measurement.waterPct > 0) "${measurement.waterPct}%" else "--", "")
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                DetailMetric("肌肉量", if (measurement.muscleKg > 0) "${measurement.muscleKg} kg" else "--", "")
                DetailMetric("骨量", if (measurement.boneMassKg > 0) "${measurement.boneMassKg} kg" else "--", "")
                DetailMetric("基础代谢", if (measurement.basalMetKcal > 0) "${measurement.basalMetKcal.toInt()} kcal" else "--", "")
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                DetailMetric("内脏脂肪", if (measurement.visceralFatRating > 0) "${measurement.visceralFatRating} 级" else "--", "")
                DetailMetric("身体年龄", if (measurement.metabolicAge > 0) "${measurement.metabolicAge} 岁" else "--", "")
                DetailMetric("Garmin 同步", if (measurement.syncedToGarmin) "已完成" else "待同步", "")
            }
        }
    }
}

@Composable
private fun EmptyPromptCard(
    isDeviceRemembered: Boolean,
    onNavigateToPairing: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (isDeviceRemembered) "请赤脚站上体脂秤，稍等片刻即可获得多维身体指标并自动同步至 Garmin" else "请先点击上方配对您的阿福体脂秤",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun DetailMetric(title: String, value: String, unit: String) {
    Column(modifier = Modifier.width(96.dp)) {
        Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(2.dp))
        Text("$value$unit", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}
