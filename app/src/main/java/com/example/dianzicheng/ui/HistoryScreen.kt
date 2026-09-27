package com.example.dianzicheng.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dianzicheng.domain.BodyMeasurement
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    onNavigateToDetail: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val history by viewModel.history.collectAsState()
    val syncMessage by viewModel.syncMessage.collectAsState()

    val dateFormat = remember { SimpleDateFormat("MM月dd日 HH:mm", Locale.getDefault()) }
    var measurementToDelete by remember { mutableStateOf<BodyMeasurement?>(null) }

    LaunchedEffect(syncMessage) {
        syncMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearSyncMessage()
        }
    }

    if (measurementToDelete != null) {
        val target = measurementToDelete!!
        AlertDialog(
            onDismissRequest = { measurementToDelete = null },
            title = { Text("确认删除", fontWeight = FontWeight.Bold) },
            text = {
                Text("确定要删除 ${dateFormat.format(Date(target.measuredAtEpochMs))} 的 ${String.format("%.2f", target.weightKg)} kg 测量记录吗？")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteMeasurement(target)
                        measurementToDelete = null
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { measurementToDelete = null }) { Text("取消") }
            }
        )
    }

    Scaffold(
        topBar = {
            MediumTopAppBar(
                title = { Text("测量历史", fontWeight = FontWeight.Bold) }
            )
        },
        modifier = modifier
    ) { innerPadding ->
        if (history.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        modifier = Modifier.size(80.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("暂无", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "暂无测量历史",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(history, key = { it.id }) { measurement ->
                    MeasurementHistoryCard(
                        measurement = measurement,
                        dateStr = dateFormat.format(Date(measurement.measuredAtEpochMs)),
                        onDelete = { measurementToDelete = measurement },
                        onSyncGarmin = { viewModel.syncToGarmin(measurement) }
                    )
                }
            }
        }
    }
}

@Composable
private fun MeasurementHistoryCard(
    measurement: BodyMeasurement,
    dateStr: String,
    onDelete: () -> Unit,
    onSyncGarmin: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: Date & Delete
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = dateStr,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Main Weight Display & Garmin Sync Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = String.format("%.2f", measurement.weightKg),
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        text = " kg",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 6.dp),
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                // Garmin status badge
                if (measurement.syncedToGarmin) {
                    SuggestionChip(
                        onClick = {},
                        label = { Text("Garmin 已同步", fontSize = 11.sp) },
                        icon = { Icon(Icons.Default.CloudDone, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(16.dp)) },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = Color(0xFFE8F5E9),
                            labelColor = Color(0xFF2E7D32)
                        ),
                        border = null
                    )
                } else {
                    OutlinedButton(
                        onClick = onSyncGarmin,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("上传 Garmin", fontSize = 11.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))

            // Body Metrics 8-grid
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetricItem("BMI", String.format("%.1f", measurement.bmi), "")
                MetricItem("体脂率", if (measurement.bodyFatPct > 0) "${measurement.bodyFatPct}%" else "--", "")
                MetricItem("水分率", if (measurement.waterPct > 0) "${measurement.waterPct}%" else "--", "")
                MetricItem("肌肉量", if (measurement.muscleKg > 0) "${measurement.muscleKg}kg" else "--", "")
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetricItem("骨量", if (measurement.boneMassKg > 0) "${measurement.boneMassKg}kg" else "--", "")
                MetricItem("基础代谢", if (measurement.basalMetKcal > 0) "${measurement.basalMetKcal.toInt()}kcal" else "--", "")
                MetricItem("内脏脂肪", if (measurement.visceralFatRating > 0) "${measurement.visceralFatRating}级" else "--", "")
                MetricItem("身体年龄", if (measurement.metabolicAge > 0) "${measurement.metabolicAge}岁" else "--", "")
            }
        }
    }
}

@Composable
private fun MetricItem(label: String, value: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = "$value$unit", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}
