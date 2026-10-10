package com.example.dianzicheng.ui

import androidx.compose.foundation.background
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
import com.example.dianzicheng.ui.components.GlassCard
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

    val dateFormat = remember { SimpleDateFormat("yyyy年MM月dd日 HH:mm", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("测量详情", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
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
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 1. Hero 概览卡片 (体重、体脂与同步状态)
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = dateFormat.format(Date(measurement.measuredAtEpochMs)),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.outline
                            )

                            if (measurement.syncedToGarmin) {
                                Surface(
                                    shape = RoundedCornerShape(100.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.CloudDone, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Garmin 已同步", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            } else {
                                Button(
                                    onClick = { viewModel.syncToGarmin(measurement) },
                                    shape = RoundedCornerShape(100.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("上传 Garmin", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }

                        // 核心体重数值
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
                    }
                }

                // 2. Garmin Connect 官方同步指标 (6项)
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(
                            text = "Garmin Connect 官方同步指标",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        val skelVal = if (measurement.skeletalMuscleKg > 0) {
                            "${measurement.skeletalMuscleKg} kg"
                        } else if (measurement.muscleKg > 0) {
                            "${measurement.muscleKg} kg"
                        } else {
                            "--"
                        }

                        val garminItems = listOf(
                            "体重" to "${measurement.weightKg} kg",
                            "BMI" to if (measurement.bmi > 0) String.format(Locale.getDefault(), "%.1f", measurement.bmi) else "--",
                            "体内脂肪" to if (measurement.bodyFatPct > 0) "${measurement.bodyFatPct}%" else "--",
                            "骨骼肌质量" to skelVal,
                            "骨骼质量" to if (measurement.boneMassKg > 0) "${measurement.boneMassKg} kg" else "--",
                            "体内水分" to if (measurement.waterPct > 0) "${measurement.waterPct}%" else "--"
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            garminItems.chunked(3).forEach { row ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    row.forEach { (label, value) ->
                                        DetailItemCard(label = label, value = value, modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }

                // 3. 全量身体成分指标明细 (17项)
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = "身体成分全量指标明细",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )

                        // 脂肪分布
                        CategorySection(
                            title = "脂肪分布",
                            items = listOf(
                                "体脂率" to formatPct(measurement.bodyFatPct),
                                "脂肪量" to formatKg(measurement.fatMassKg),
                                "内脏脂肪" to if (measurement.visceralFatRating > 0) "${measurement.visceralFatRating} 级" else "--",
                                "皮下脂肪率" to formatPct(measurement.subcutaneousFatPct),
                                "皮下脂肪量" to formatKg(measurement.subcutaneousFatKg)
                            )
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                        // 肌肉与骨骼
                        CategorySection(
                            title = "肌肉与骨骼",
                            items = listOf(
                                "骨骼肌量" to formatKg(measurement.skeletalMuscleKg),
                                "骨骼肌率" to formatPct(measurement.skeletalMusclePct),
                                "肌肉总量" to formatKg(measurement.muscleKg),
                                "肌肉率" to formatPct(measurement.musclePct),
                                "骨量" to formatKg(measurement.boneMassKg),
                                "骨量占比" to formatPct(measurement.boneMassPct)
                            )
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                        // 水分与蛋白质
                        CategorySection(
                            title = "水分、蛋白与代谢",
                            items = listOf(
                                "水分率" to formatPct(measurement.waterPct),
                                "水分量" to formatKg(measurement.waterKg),
                                "蛋白质率" to formatPct(measurement.proteinPct),
                                "蛋白质量" to formatKg(measurement.proteinKg),
                                "基础代谢" to if (measurement.basalMetKcal > 0) "${measurement.basalMetKcal.toInt()} kcal" else "--",
                                "身体年龄" to if (measurement.metabolicAge > 0) "${measurement.metabolicAge} 岁" else "--"
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CategorySection(title: String, items: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        items.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { (label, value) ->
                    DetailItemCard(label = label, value = value, modifier = Modifier.weight(1f))
                }
                repeat(3 - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun DetailItemCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

private fun trimNum(value: Double): String {
    val rounded = Math.round(value * 10.0) / 10.0
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
}

private fun formatPct(value: Double): String = if (value > 0.0) "${trimNum(value)}%" else "--"

private fun formatKg(value: Double): String = if (value > 0.0) "${trimNum(value)} kg" else "--"
