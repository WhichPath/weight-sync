package com.example.dianzicheng.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.health.connect.client.PermissionController
import com.example.dianzicheng.data.local.AppLogger
import com.example.dianzicheng.domain.Sex
import com.example.dianzicheng.domain.UserProfile
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
    val pairedMac by viewModel.pairedMac.collectAsState()
    val pairedDeviceName by viewModel.pairedDeviceName.collectAsState()
    val healthConnectEnabled by viewModel.healthConnectEnabled.collectAsState()
    val isOperating by viewModel.isOperating.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()

    var showEditProfileDialog by remember { mutableStateOf(false) }
    var showGarminLoginDialog by remember { mutableStateOf(false) }
    var showUnpairConfirmDialog by remember { mutableStateOf(false) }

    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }

    LaunchedEffect(statusMessage) {
        statusMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearStatusMessage()
        }
    }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text("我的与设置", fontWeight = FontWeight.Bold) }
            )
        },
        modifier = modifier
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // ── 1. 个人身体档案 ──────────────────────────────────────────────
            item {
                SectionHeader("个人身体档案")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "档案用于准确计算 BMI、体脂率、肌肉量、骨量及基础代谢率",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        HorizontalDivider()
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
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("编辑身体档案")
                        }
                    }
                }
            }

            // ── 2. Garmin Connect 同步 ──────────────────────────────────────
            item {
                SectionHeader("Garmin Connect 同步 (国际区)")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
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
                                    text = if (isGarminLoggedIn) "已登录: ${garminUsername ?: "Garmin User"}" else "未登录",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isGarminLoggedIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                )
                            }
                            if (isGarminLoggedIn) {
                                OutlinedButton(onClick = { viewModel.logoutGarmin() }) {
                                    Text("退出登录")
                                }
                            } else {
                                Button(onClick = { showGarminLoginDialog = true }) {
                                    Icon(Icons.Default.Login, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("网页登录")
                                }
                            }
                        }

                        if (isGarminLoggedIn) {
                            HorizontalDivider()
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("称重自动上传", fontWeight = FontWeight.SemiBold)
                                    Text("每次锁定称重结果后自动后台上传至 Garmin", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(
                                    checked = garminAutoSync,
                                    onCheckedChange = { viewModel.setGarminAutoSync(it) }
                                )
                            }
                            FilledTonalButton(
                                onClick = { viewModel.syncAllToGarmin() },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isOperating
                            ) {
                                Icon(Icons.Default.Sync, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("一键同步所有历史记录至 Garmin")
                            }
                        }
                    }
                }
            }

            // ── 3. 安卓系统健康连接 ──────────────────────────────────────────
            item {
                SectionHeader("系统健康连接 (Health Connect)")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
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
                                Text("自动将体重与体脂率同步写入 Android 15 系统健康", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                enabled = !isOperating
                            ) {
                                Text("一键同步历史数据到系统健康")
                            }
                        }
                    }
                }
            }

            // ── 4. 数据获取模式 ─────────────────────────────────────────
            item {
                SectionHeader("数据获取模式")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("工作模式", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("无障碍 UI 自动化截获", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                        Text(
                            text = "无需配对电子秤，只需在阿福 App 测秤后打开身体指标详情页，即可自动提取 100% 官方计算指标并上传 Garmin。抓取排查日志已统合至首页【排查日志】。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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

    // ── 解除配对确认弹窗 ──────────────────────────────────────────────────────
    if (showUnpairConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showUnpairConfirmDialog = false },
            title = { Text("确认解绑体脂秤") },
            text = { Text("解除绑定后，App 将重新开启扫描，您可以在配对页重新选择设备。") },
            confirmButton = {
                TextButton(onClick = {
                    showUnpairConfirmDialog = false
                    viewModel.resetPairing()
                }) { Text("确认解绑", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showUnpairConfirmDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 8.dp)
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
        title = { Text("设置个人身体档案") },
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
