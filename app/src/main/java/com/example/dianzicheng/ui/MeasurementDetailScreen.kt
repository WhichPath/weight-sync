package com.example.dianzicheng.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dianzicheng.domain.BodyMeasurement
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeasurementDetailScreen(
    measurementId: String,
    viewModel: HistoryViewModel,
    onNavigateBack: () -> Unit
) {
    val history by viewModel.history.collectAsState()
    val measurement = history.find { it.id == measurementId }

    val dateFormat = remember { SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("测量详情", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        if (measurement == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text("未找到该测量记录", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = dateFormat.format(Date(measurement.measuredAtEpochMs)),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.outline
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Garmin 同步状态卡片
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (measurement.syncedToGarmin) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (measurement.syncedToGarmin) Icons.Default.CloudDone else Icons.Default.CloudUpload,
                                contentDescription = null,
                                tint = if (measurement.syncedToGarmin) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (measurement.syncedToGarmin) "已同步至 Garmin Connect" else "未同步至 Garmin",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = if (measurement.syncedToGarmin) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurface
                            )
                        }

                        if (!measurement.syncedToGarmin) {
                            Button(
                                onClick = { viewModel.syncToGarmin(measurement) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("立即上传", fontSize = 12.sp)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 主重量与 BMI 显示
                Text(
                    text = String.format(Locale.getDefault(), "%.2f", measurement.weightKg),
                    fontSize = 52.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "kg",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.outline
                )

                Spacer(modifier = Modifier.height(24.dp))

                // ── 卡片 1：Garmin Connect 官方同步 6 项指标 ──
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(
                            text = "佳明 Garmin Connect 同步指标 (6项)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 14.dp)
                        )

                        Row(modifier = Modifier.fillMaxWidth()) {
                            DetailGridItem("体重", "${measurement.weightKg} kg", modifier = Modifier.weight(1f))
                            DetailGridItem("BMI", if (measurement.bmi > 0) String.format(Locale.getDefault(), "%.1f", measurement.bmi) else "--", modifier = Modifier.weight(1f))
                            DetailGridItem("体内脂肪", if (measurement.bodyFatPct > 0) "${measurement.bodyFatPct}%" else "--", modifier = Modifier.weight(1f))
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            val skelVal = if (measurement.skeletalMuscleKg > 0) {
                                "${measurement.skeletalMuscleKg} kg"
                            } else if (measurement.muscleKg > 0) {
                                "${measurement.muscleKg} kg"
                            } else {
                                "--"
                            }
                            DetailGridItem("骨骼肌质量", skelVal, modifier = Modifier.weight(1f))
                            DetailGridItem("骨骼质量", if (measurement.boneMassKg > 0) "${measurement.boneMassKg} kg" else "--", modifier = Modifier.weight(1f))
                            DetailGridItem("体内水分", if (measurement.waterPct > 0) "${measurement.waterPct}%" else "--", modifier = Modifier.weight(1f))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // ── 卡片 2：阿福身体成分全量指标（17项明细） ──
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(
                            text = "阿福身体成分详测明细 (17项)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        // 脂肪与内脏
                        CategoryHeader("脂肪分布")
                        Row(modifier = Modifier.fillMaxWidth()) {
                            DetailGridItem("体脂率", formatPct(measurement.bodyFatPct), modifier = Modifier.weight(1f))
                            DetailGridItem("脂肪量", formatKg(measurement.fatMassKg), modifier = Modifier.weight(1f))
                            DetailGridItem("内脏脂肪", if (measurement.visceralFatRating > 0) "${measurement.visceralFatRating} 级" else "--", modifier = Modifier.weight(1f))
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            DetailGridItem("皮下脂肪率", formatPct(measurement.subcutaneousFatPct), modifier = Modifier.weight(1f))
                            DetailGridItem("皮下脂肪量", formatKg(measurement.subcutaneousFatKg), modifier = Modifier.weight(1f))
                            Spacer(modifier = Modifier.weight(1f))
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                        // 肌肉与骨骼
                        CategoryHeader("肌肉与骨骼")
                        Row(modifier = Modifier.fillMaxWidth()) {
                            DetailGridItem("骨骼肌量", formatKg(measurement.skeletalMuscleKg), modifier = Modifier.weight(1f))
                            DetailGridItem("骨骼肌率", formatPct(measurement.skeletalMusclePct), modifier = Modifier.weight(1f))
                            DetailGridItem("肌肉总量", formatKg(measurement.muscleKg), modifier = Modifier.weight(1f))
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            DetailGridItem("肌肉率", formatPct(measurement.musclePct), modifier = Modifier.weight(1f))
                            DetailGridItem("骨量", formatKg(measurement.boneMassKg), modifier = Modifier.weight(1f))
                            DetailGridItem("骨量占比", formatPct(measurement.boneMassPct), modifier = Modifier.weight(1f))
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                        // 水分与蛋白质
                        CategoryHeader("水分与蛋白质")
                        Row(modifier = Modifier.fillMaxWidth()) {
                            DetailGridItem("水分率", formatPct(measurement.waterPct), modifier = Modifier.weight(1f))
                            DetailGridItem("水分量", formatKg(measurement.waterKg), modifier = Modifier.weight(1f))
                            DetailGridItem("蛋白质率", formatPct(measurement.proteinPct), modifier = Modifier.weight(1f))
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            DetailGridItem("蛋白质量", formatKg(measurement.proteinKg), modifier = Modifier.weight(1f))
                            Spacer(modifier = Modifier.weight(1f))
                            Spacer(modifier = Modifier.weight(1f))
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                        // 代谢与体态
                        CategoryHeader("代谢与身体状态")
                        Row(modifier = Modifier.fillMaxWidth()) {
                            DetailGridItem("基础代谢", if (measurement.basalMetKcal > 0) "${measurement.basalMetKcal.toInt()} kcal" else "--", modifier = Modifier.weight(1f))
                            DetailGridItem("身体年龄", if (measurement.metabolicAge > 0) "${measurement.metabolicAge} 岁" else "--", modifier = Modifier.weight(1f))
                            val impText = if (measurement.impedanceOhm > 0) "${measurement.impedanceOhm.toInt()} Ω" else "未测出"
                            DetailGridItem("电阻抗", impText, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

private fun formatPct(value: Double): String {
    return if (value > 0.0) String.format(Locale.getDefault(), "%.1f%%", value) else "--"
}

private fun formatKg(value: Double): String {
    return if (value > 0.0) String.format(Locale.getDefault(), "%.2f kg", value) else "--"
}

@Composable
fun DetailGridItem(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}
