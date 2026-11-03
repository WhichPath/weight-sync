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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.OpenInNew
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
import com.example.dianzicheng.accessibility.AccessibilityState
import com.example.dianzicheng.accessibility.AccessibilitySystem
import com.example.dianzicheng.accessibility.PrivilegeMode
import com.example.dianzicheng.accessibility.PrivilegeStatus
import com.example.dianzicheng.accessibility.PrivilegedAccess
import com.example.dianzicheng.accessibility.ShizukuState
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
    var showPrivilegeSheet by remember { mutableStateOf(false) }
    val adbCommand = remember { PrivilegedAccess.adbProvisionCommand(context) }

    val openAfu = {
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage("com.antgroup.aijk.android")
        if (launchIntent != null) {
            context.startActivity(launchIntent)
        } else {
            Toast.makeText(context, "未找到阿福 App，请确认已安装", Toast.LENGTH_SHORT).show()
        }
    }

    val requestIgnoreBatteryOptimizations = {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshStatus()
    }

    // 统一处理 ViewModel 抛出的"需要 Activity 执行"的动作
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ScaleUiEvent.Toast ->
                    Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()

                ScaleUiEvent.OpenAccessibilityServiceSettings ->
                    AccessibilitySystem.openServiceSettings(context)

                ScaleUiEvent.OpenAppDetails ->
                    AccessibilitySystem.openAppDetails(context)

                ScaleUiEvent.ShowPrivilegeDialog ->
                    showPrivilegeSheet = true
            }
        }
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
                        Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = "权限向导")
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
                onAccessibilitySwitchChanged = { enabled ->
                    viewModel.onAccessibilitySwitchChanged(enabled)
                },
                onRepairAccessibility = { viewModel.repairAccessibility() },
                onProvisionPermanent = { viewModel.requestPrivilegedPermission() },
                onOpenPrivilegeDialog = { showPrivilegeSheet = true },
                onRequestIgnoreBatteryOptimizations = requestIgnoreBatteryOptimizations,
                onLaunchAfu = openAfu,
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
                    onLaunchAfu = openAfu,
                    onOpenSettings = {
                        viewModel.onAccessibilitySwitchChanged(true)
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
                privilege = uiState.privilege,
                isProvisioning = uiState.isProvisioning,
                isBatteryIgnored = uiState.isBatteryOptimizationIgnored,
                onOpenAppDetails = { AccessibilitySystem.openAppDetails(context) },
                onOpenAccessibilitySettings = { AccessibilitySystem.openServiceSettings(context) },
                onOpenPrivilegeSetup = {
                    showGuideSheet = false
                    showPrivilegeSheet = true
                },
                onOpenShizukuDownload = { PrivilegedAccess.openShizukuDownload(context) },
                onRequestIgnoreBatteryOptimizations = requestIgnoreBatteryOptimizations,
                onDismiss = { showGuideSheet = false }
            )
        }
    }

    // ── 一次性永久授权 BottomSheet（GKD 同款「高级授权」流程） ──────────────────
    if (showPrivilegeSheet) {
        ModalBottomSheet(
            onDismissRequest = { showPrivilegeSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            PrivilegeSetupSheetContent(
                privilege = uiState.privilege,
                isProvisioning = uiState.isProvisioning,
                adbCommand = adbCommand,
                isShizukuInstalled = remember(uiState.privilege.mode) {
                    PrivilegedAccess.isShizukuInstalled(context)
                },
                managerLabel = remember(uiState.privilege.mode) {
                    PrivilegedAccess.managerLabel(context)
                },
                onProvision = { viewModel.requestPrivilegedPermission() },
                onOpenShizuku = { PrivilegedAccess.openShizuku(context) },
                onCopyCommand = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText("adb_provision", adbCommand))
                    Toast.makeText(context, "ADB 命令已复制", Toast.LENGTH_SHORT).show()
                },
                onRecheck = { viewModel.onPrivilegeRechecked() },
                onOpenSystemSettings = {
                    showPrivilegeSheet = false
                    AccessibilitySystem.openServiceSettings(context)
                },
                onDismiss = { showPrivilegeSheet = false }
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
    onAccessibilitySwitchChanged: (Boolean) -> Unit,
    onRepairAccessibility: () -> Unit,
    onProvisionPermanent: () -> Unit,
    onOpenPrivilegeDialog: () -> Unit,
    onRequestIgnoreBatteryOptimizations: () -> Unit,
    onLaunchAfu: () -> Unit,
    onOpenGuide: () -> Unit
) {
    val a11yState = uiState.accessibilityState
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
                // 无障碍服务状态指示（三态：运行中 / 已开启但没运行 / 未开启）
                StatusPill(
                    isActive = a11yState == AccessibilityState.RUNNING,
                    activeLabel = "运行中",
                    inactiveLabel = when (a11yState) {
                        AccessibilityState.ENABLED_NOT_RUNNING -> "未运行"
                        else -> "未开启"
                    }
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
                        text = if (uiState.isBatteryOptimizationIgnored) "后台已加白" else "后台未加白",
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

            // 应用内无障碍开关：可直接在应用内开启 / 关闭，无需跳转系统设置
            AccessibilitySwitchRow(
                state = a11yState,
                busy = uiState.isAccessibilityBusy,
                isProvisioning = uiState.isProvisioning,
                privilege = uiState.privilege,
                onSwitchChanged = onAccessibilitySwitchChanged,
                onProvisionPermanent = onProvisionPermanent,
                onOpenPrivilegeDialog = onOpenPrivilegeDialog
            )

            // 描述提示语 (精简、克制)
            Text(
                text = when (a11yState) {
                    AccessibilityState.RUNNING ->
                        "在阿福打开测量详情即可自动抓取。"

                    AccessibilityState.ENABLED_NOT_RUNNING ->
                        "服务未运行，点下方按钮修复。"

                    AccessibilityState.DISABLED ->
                        "开启后可在阿福自动抓取；无通知保活。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 受限设置拦截提示（Android 13+ 侧载 APK 的典型坑）
            if (uiState.isRestrictedBySystem) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "应用处于「受限设置」，系统会拒绝启动无障碍。去应用信息页允许受限设置。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }

            // 核心行动按钮：占据主导视觉空间，尺寸规范充裕
            when (a11yState) {
                AccessibilityState.RUNNING -> {
                    Button(
                        onClick = onLaunchAfu,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("打开阿福", style = MaterialTheme.typography.labelLarge)
                    }
                }

                AccessibilityState.ENABLED_NOT_RUNNING -> {
                    Button(
                        onClick = onRepairAccessibility,
                        enabled = !uiState.isAccessibilityBusy && !uiState.isProvisioning,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            if (uiState.isAccessibilityBusy) "修复中..." else "修复服务",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }

                AccessibilityState.DISABLED -> {
                    Button(
                        onClick = { onAccessibilitySwitchChanged(true) },
                        enabled = !uiState.isAccessibilityBusy && !uiState.isProvisioning,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.Accessibility, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            if (uiState.isAccessibilityBusy) "开启中..." else "开启无障碍",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
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
                        Text("后台无限制", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                TextButton(
                    onClick = onOpenGuide,
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("权限向导", style = MaterialTheme.typography.labelMedium)
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
        color = if (isActive) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (isActive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isActive) activeLabel else inactiveLabel,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (isActive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
            )
        }
    }
}

/**
 * 应用内无障碍开关行。
 *
 * 参照 GKD 的交互：开关直接在应用内生效（有永久授权/兼容管理器时静默改写系统设置），
 * 没有特权通道时引导一次性授权，系统设置仅作为兜底入口。
 */
@Composable
private fun AccessibilitySwitchRow(
    state: AccessibilityState,
    busy: Boolean,
    isProvisioning: Boolean,
    privilege: PrivilegeStatus,
    onSwitchChanged: (Boolean) -> Unit,
    onProvisionPermanent: () -> Unit,
    onOpenPrivilegeDialog: () -> Unit
) {
    val permanent = privilege.mode == PrivilegeMode.PERMANENT
    val channelClickable = !permanent && !isProvisioning

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "无障碍服务",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = when (state) {
                            AccessibilityState.RUNNING -> "已连接"
                            AccessibilityState.ENABLED_NOT_RUNNING -> "开关开启但服务未运行"
                            AccessibilityState.DISABLED -> "未开启"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = state != AccessibilityState.DISABLED,
                    onCheckedChange = { onSwitchChanged(it) },
                    enabled = !busy && !isProvisioning
                )
            }

            // 特权通道状态：决定能否在应用内直接开关 / 重启
            val channelText = when (privilege.mode) {
                PrivilegeMode.PERMANENT -> "永久授权 · 免跳转"
                PrivilegeMode.SHIZUKU_READY -> "Shizuku 就绪 · 点此永久授权"
                PrivilegeMode.SHIZUKU_NEED_PERMISSION -> "Shizuku 待授权 · 点此授权"
                PrivilegeMode.NONE -> "未授权 · 点此查看方法"
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (channelClickable) {
                            Modifier.clickable {
                                when (privilege.mode) {
                                    PrivilegeMode.SHIZUKU_READY,
                                    PrivilegeMode.SHIZUKU_NEED_PERMISSION -> onProvisionPermanent()

                                    PrivilegeMode.NONE -> onOpenPrivilegeDialog()
                                    PrivilegeMode.PERMANENT -> Unit
                                }
                            }
                        } else {
                            Modifier
                        }
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (permanent) Icons.Default.Bolt else Icons.Default.LockOpen,
                    contentDescription = null,
                    tint = if (permanent) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = channelText,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (permanent) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f)
                )
                if (isProvisioning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp
                    )
                } else if (channelClickable) {
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
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
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Garmin 已同步", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
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
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary
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
                            color = MaterialTheme.colorScheme.secondary
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

            Text("等待测量数据", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            Text(
                text = "开启无障碍后，在阿福打开测量详情即可自动抓取。",
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
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("前往阿福")
                }
            } else {
                Button(
                    onClick = onOpenSettings,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Accessibility, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("开启无障碍")
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
    privilege: PrivilegeStatus,
    isProvisioning: Boolean,
    isBatteryIgnored: Boolean,
    onOpenAppDetails: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenPrivilegeSetup: () -> Unit,
    onOpenShizukuDownload: () -> Unit,
    onRequestIgnoreBatteryOptimizations: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("权限与保活", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "关闭")
            }
        }

        GuideStep(
            title = "1. 一次性授权",
            status = when (privilege.mode) {
                PrivilegeMode.PERMANENT -> "已授权 · 永久免跳转"
                PrivilegeMode.SHIZUKU_READY -> "Shizuku 就绪"
                PrivilegeMode.SHIZUKU_NEED_PERMISSION -> "Shizuku 待授权"
                PrivilegeMode.NONE -> "未授权"
            },
            ok = privilege.mode == PrivilegeMode.PERMANENT,
            actionText = if (privilege.mode == PrivilegeMode.PERMANENT) "已生效" else "去授权",
            actionEnabled = !isProvisioning && privilege.mode != PrivilegeMode.PERMANENT,
            onAction = onOpenPrivilegeSetup
        )

        GuideStep(
            title = "2. 开启无障碍",
            status = "授权后可直接在首页开关",
            ok = false,
            actionText = "系统设置",
            actionEnabled = true,
            onAction = onOpenAccessibilitySettings
        )

        GuideStep(
            title = "3. 后台与通知",
            status = if (isBatteryIgnored) "后台已加白 · 无通知保活" else "建议开启电池无限制",
            ok = isBatteryIgnored,
            actionText = if (isBatteryIgnored) "已加白" else "去加白",
            actionEnabled = !isBatteryIgnored,
            onAction = onRequestIgnoreBatteryOptimizations
        )

        Text(
            text = "可选：在通知栏快捷开关添加「阿福同步」磁贴，掉线时点一下即可恢复。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onOpenAppDetails) {
                Text("解除受限设置", style = MaterialTheme.typography.labelMedium)
            }
            TextButton(onClick = onOpenShizukuDownload) {
                Text("获取 Shizuku", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun GuideStep(
    title: String,
    status: String,
    ok: Boolean,
    actionText: String,
    actionEnabled: Boolean,
    onAction: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ok) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            TextButton(onClick = onAction, enabled = actionEnabled) {
                Text(actionText, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/**
 * 一次性永久授权说明与操作面板。
 *
 * 对齐 GKD 的「高级授权」：Shizuku 只负责执行一次 pm grant，
 * 之后 App 直接写 Secure Settings，不再依赖 Shizuku 常驻。
 */
@Composable
private fun PrivilegeSetupSheetContent(
    privilege: PrivilegeStatus,
    isProvisioning: Boolean,
    adbCommand: String,
    isShizukuInstalled: Boolean,
    managerLabel: String?,
    onProvision: () -> Unit,
    onOpenShizuku: () -> Unit,
    onCopyCommand: () -> Unit,
    onRecheck: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    val permanent = privilege.mode == PrivilegeMode.PERMANENT
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("一次性授权", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "关闭")
            }
        }

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (permanent) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            }
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = if (permanent) "✓ 已授权" else privilege.mode.label(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (permanent) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (permanent) {
                        "重启、关闭开发者选项、Shizuku 退出后仍可用。"
                    } else {
                        "授权一次，永久免跳转。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (!permanent) {
            PermissionBullet("永久有效", "重启手机、关闭开发者选项后仍可用")
            PermissionBullet("包含无障碍开关", "应用内直接开启、关闭、修复")
            PermissionBullet("无通知保活", "不占前台服务，不挂常驻通知")

            Text(
                text = "支持 Shizuku / Shizuku+ / Stellar，其他兼容管理器同样可用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            when (privilege.shizukuState) {
                ShizukuState.READY -> {
                    Button(
                        onClick = onProvision,
                        enabled = !isProvisioning,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (isProvisioning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isProvisioning) "授权中..." else "Shizuku 一键授权")
                    }
                }

                ShizukuState.NEED_PERMISSION -> {
                    Button(
                        onClick = onProvision,
                        enabled = !isProvisioning,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isProvisioning) "授权中..." else "授权 Shizuku")
                    }
                }

                ShizukuState.UNAVAILABLE -> {
                    Button(
                        onClick = onOpenShizuku,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            if (isShizukuInstalled) {
                                "打开 ${managerLabel ?: "管理器"}"
                            } else {
                                "获取 Shizuku"
                            }
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = onCopyCommand,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("复制 ADB 命令")
            }

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                SelectionContainer {
                    Text(
                        text = adbCommand,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
            Text(
                text = "电脑执行一次，回来点「重新检测」。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onRecheck, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("重新检测")
            }
        } else {
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("完成")
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        Text(
            text = "保活：系统托管。可在通知栏快捷开关添加「阿福同步」磁贴，无常驻通知。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        TextButton(onClick = onOpenSystemSettings, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("去系统设置")
        }
    }
}

@Composable
private fun PermissionBullet(title: String, description: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun PrivilegeMode.label(): String = when (this) {
    PrivilegeMode.PERMANENT -> "已授权"
    PrivilegeMode.SHIZUKU_READY -> "Shizuku 就绪"
    PrivilegeMode.SHIZUKU_NEED_PERMISSION -> "Shizuku 待授权"
    PrivilegeMode.NONE -> "未授权"
}

private fun trimNum(value: Double): String {
    val rounded = (value * 10.0).roundToInt() / 10.0
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
}

private fun pctText(value: Double): String = if (value > 0.0) "${trimNum(value)}%" else "--"

private fun kgText(value: Double): String = if (value > 0.0) "${trimNum(value)} kg" else "--"
