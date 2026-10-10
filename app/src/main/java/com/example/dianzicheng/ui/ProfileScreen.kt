package com.example.dianzicheng.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dianzicheng.BuildConfig
import com.example.dianzicheng.domain.Sex
import com.example.dianzicheng.domain.UserProfile
import com.example.dianzicheng.ui.components.GlassCard
import com.example.dianzicheng.ui.garmin.GarminLoginDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val userProfile by viewModel.userProfile.collectAsState()
    val isGarminLoggedIn by viewModel.isGarminLoggedIn.collectAsState()
    val garminUsername by viewModel.garminUsername.collectAsState()
    val garminAutoSync by viewModel.garminAutoSync.collectAsState()
    val healthConnectEnabled by viewModel.healthConnectEnabled.collectAsState()
    val isOperating by viewModel.isOperating.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()

    var showEditProfileDialog by remember { mutableStateOf(false) }
    var showGarminLoginDialog by remember { mutableStateOf(false) }

    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }

    LaunchedEffect(statusMessage) {
        statusMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearStatusMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置与档案", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        modifier = modifier
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── 1. 个人身体档案 ──────────────────────────────────────────────
            item {
                SectionHeader("个人身体档案")
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "档案用于准确计算 BMI、体脂率、骨量及基础代谢等参考指标",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("性别", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(if (userProfile.sex == Sex.MALE) "男" else "女", fontWeight = FontWeight.Bold)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("身高", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${userProfile.heightCm} cm", fontWeight = FontWeight.Bold)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("出生日期", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(dateFormat.format(Date(userProfile.birthDateEpochMs)), fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { showEditProfileDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("编辑身体档案")
                        }
                    }
                }
            }

            // ── 2. Garmin Connect 同步 ──────────────────────────────────────
            item {
                SectionHeader("Garmin Connect 同步")
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("登录状态", fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = if (isGarminLoggedIn) "已登录: ${garminUsername ?: "Garmin 用户"}" else "未登录",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isGarminLoggedIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                )
                            }
                            if (isGarminLoggedIn) {
                                OutlinedButton(
                                    onClick = { viewModel.logoutGarmin() },
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("退出登录")
                                }
                            } else {
                                Button(
                                    onClick = { showGarminLoginDialog = true },
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.Login, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("登录 Garmin")
                                }
                            }
                        }

                        if (isGarminLoggedIn) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("自动上传", fontWeight = FontWeight.SemiBold)
                                    Text("捕获到新记录后自动上传至 Garmin", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(
                                    checked = garminAutoSync,
                                    onCheckedChange = { viewModel.setGarminAutoSync(it) }
                                )
                            }
                            FilledTonalButton(
                                onClick = { viewModel.syncAllToGarmin() },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isOperating,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("一键同步所有未同步历史")
                            }
                        }
                    }
                }
            }

            // ── 3. 安卓系统健康连接 ──────────────────────────────────────────
            item {
                SectionHeader("系统健康连接 (Health Connect)")
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("写入 Health Connect", fontWeight = FontWeight.SemiBold)
                                Text("自动将体重与体脂率写入 Android 系统健康", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = healthConnectEnabled,
                                onCheckedChange = { viewModel.toggleHealthConnect(it) }
                            )
                        }
                        if (healthConnectEnabled) {
                            FilledTonalButton(
                                onClick = { viewModel.syncAllToHealthConnect() },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isOperating,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("一键同步历史到系统健康")
                            }
                        }
                    }
                }
            }

            // ── 4. 关于与版本信息 ─────────────────────────────────────────
            item {
                SectionHeader("关于应用")
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("应用版本", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("同步架构", style = MaterialTheme.typography.bodyMedium)
                            Text("无障碍 UI 自动化解析 + Garmin Direct", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        }
    }

    // ── 编辑个人身体档案弹窗 ──────────────────────────────────────────────────
    if (showEditProfileDialog) {
        EditProfileDialog(
            currentProfile = userProfile,
            onDismiss = { showEditProfileDialog = false },
            onConfirm = { sex, height, birthDate ->
                viewModel.saveUserProfile(sex, height, birthDate)
                showEditProfileDialog = false
            }
        )
    }

    // ── Garmin 网页登录弹窗 ───────────────────────────────────────────────────
    if (showGarminLoginDialog) {
        GarminLoginDialog(
            onDismissRequest = { showGarminLoginDialog = false },
            onTicketReceived = { ticket ->
                showGarminLoginDialog = false
                viewModel.handleGarminTicket(ticket)
            }
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
    )
}

@Composable
private fun EditProfileDialog(
    currentProfile: UserProfile,
    onDismiss: () -> Unit,
    onConfirm: (Sex, Double, Long) -> Unit
) {
    var sex by remember { mutableStateOf(currentProfile.sex) }
    var heightText by remember { mutableStateOf(currentProfile.heightCm.toString()) }
    var birthDateText by remember {
        mutableStateOf(SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(currentProfile.birthDateEpochMs)))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置个人身体档案", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("性别：", modifier = Modifier.width(60.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = sex == Sex.MALE, onClick = { sex = Sex.MALE })
                        Text("男")
                        Spacer(Modifier.width(16.dp))
                        RadioButton(selected = sex == Sex.FEMALE, onClick = { sex = Sex.FEMALE })
                        Text("女")
                    }
                }
                OutlinedTextField(
                    value = heightText,
                    onValueChange = { heightText = it },
                    label = { Text("身高 (cm)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = birthDateText,
                    onValueChange = { birthDateText = it },
                    label = { Text("出生日期 (YYYY-MM-DD)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val h = heightText.toDoubleOrNull() ?: 175.0
                val date = try {
                    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(birthDateText)?.time
                        ?: currentProfile.birthDateEpochMs
                } catch (e: Exception) {
                    currentProfile.birthDateEpochMs
                }
                onConfirm(sex, h, date)
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
