# 架构说明（v2.1.0）

本文档记录 `weight-sync` 在 2.1.0 起的模块边界、依赖方向，以及无障碍特权通道的设计。

---

## 1. 分层与包结构

```
com.example.dianzicheng
├── WeightSyncApp.kt          # Application：唯一装配入口
├── MainActivity.kt           # 只负责 Activity 生命周期与 Compose 宿主
├── di/
│   └── AppContainer.kt       # 轻量依赖容器 + ViewModel 工厂
├── core/
│   └── logging/AppLogger.kt  # 跨模块日志（内存环形缓冲 + Logcat）
├── data/
│   ├── local/                # Room、DataStore、实体与映射
│   ├── garmin/               # Garmin SSO / FIT / 上传
│   ├── health/               # Health Connect
│   └── repository/           # 对 domain 暴露的数据仓库
├── domain/                   # 纯 Kotlin 模型与身体算法
├── accessibility/            # 无障碍子系统（服务、解析、特权、控制器）
│   ├── AfuAccessibilityService.kt
│   ├── AfuUiParser.kt
│   ├── AccessibilitySystem.kt
│   ├── AccessibilityController.kt
│   ├── PrivilegedAccess.kt
│   ├── SecureSettingsGateway.kt
│   ├── AccessibilityRepository.kt
│   └── AfuTileService.kt
└── ui/                       # Compose 页面、UI 状态、ViewModel
```

### 依赖规则

- `ui` 只能依赖 `domain`、`data.repository`、`accessibility.AccessibilityRepository`。
- `accessibility` 可以依赖 `domain`、`data.local`、`data.repository`、`core`。
- `data` 依赖 `domain`、`core`，不反向依赖 `ui` / `accessibility`。
- `domain` 不依赖 Android（`BodyAlgorithm`、`Models` 可在 JVM 单测中运行）。
- `AppContainer` 是唯一装配点；Activity / Service 不再各自 `new` 数据库、仓库或偏好实例。

### 本次修正的架构问题

1. `ScaleViewModel` 不再持有 `Context`，也不再直接调用 `Settings` / Shizuku；
   系统能力统一通过 `AccessibilityRepository` 进入。
2. `AfuAccessibilityService` 不再自建 `AppDatabase` / `ScaleRepository` / `PreferenceManager`，
   改为从 `AppContainer` 获取单例，避免 Service 与 Activity 看到不同的实例。
3. `AppLogger` 从 `data/local` 移到 `core/logging`，明确它是跨层基础设施。
4. `MainActivity` 从“手工组装 5 个依赖 + 3 个 ViewModel 工厂”简化为只消费 `AppContainer`。
5. `HistoryViewModel` / `ProfileViewModel` / `ScaleViewModel` 的依赖改为非空构造参数，
   去掉不必要的 `?` 与运行期空判断。

---

## 2. 无障碍特权通道

### 2.1 两种能力，优先级不同

| 能力 | 获取方式 | 生命周期 | 用途 |
| --- | --- | --- | --- |
| `WRITE_SECURE_SETTINGS` | Shizuku 或电脑 ADB 执行一次 `pm grant` | **永久**：重启手机、关闭开发者选项、Shizuku 退出后仍有效 | 直接读写 `Settings.Secure`，应用内开关无障碍 |
| Shizuku 临时命令 | Shizuku 授权 | 仅在 Shizuku 服务在线期间 | 完成上面的一次性授权；没有永久权限时作为临时写入通道 |

`PrivilegeStatus` 用 `PERMANENT` / `SHIZUKU_READY` / `SHIZUKU_NEED_PERMISSION` / `NONE`
四种模式表达当前状态，UI 只依据它决定按钮文案与可用性。

### 2.2 一次性授权流程

1. 用户点击「开启无障碍」或「一次性授权」。
2. 若没有可用通道，展示授权 BottomSheet，而不是直接跳系统设置。
3. 优先用 Shizuku 执行：

   ```text
   appops set <pkg> ACCESS_RESTRICTED_SETTINGS allow
   appops set <pkg> ACCESS_ACCESSIBILITY allow
   pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS
   ```

4. Shizuku 不可用时，展示等价的 ADB 命令，用户可在电脑上执行一次。
5. 回读 `checkSelfPermission(WRITE_SECURE_SETTINGS)` 确认授权真正生效。
6. 如果用户是在拨动开关时被引导去授权，授权成功后自动继续执行原本的开关动作。

### 2.3 应用内开关流程

`AccessibilityController` 使用 `SecureSettingsGateway`：

- 有永久权限：直接 `Settings.Secure.putInt/putString`；
- 仅 Shizuku：退化为 `settings put secure ...`；
- 都没有：返回 `NeedPrivilege`，由 UI 展示授权流程。

开启 / 修复时沿用 GKD 的“摘除 → 等待 → 挂回”序列：

1. `accessibility_enabled = 1`
2. 若本服务已在 `enabled_accessibility_services` 中（开着但没跑），先摘除并等待 600ms；
3. 再挂回本服务；
4. 每 100ms 轮询服务实例状态，最多等 2.5s，而不是固定 sleep。

---

## 3. 保活策略：没有前台服务，也没有常驻通知

v2.1.0 彻底移除了无障碍服务里的 `startForeground` 与通知渠道：

- 无障碍服务本身由系统绑定和托管，适合长期驻留；
- App 启动 / 回到前台时检测“已开启但未运行”，有权限就自动摘除再挂回；
- 可选的 `AfuTileService`（通知栏快捷开关）在面板打开时做一次自检，点击可开关 / 恢复服务；
- 不申请 `POST_NOTIFICATIONS`、`FOREGROUND_SERVICE*`，也不产生任何常驻通知。

这与 GKD 的“无感保活”思路一致：把保活从“长期挂通知”换成“系统托管 + 用户可见的快捷磁贴”。

### 运行状态为什么要用实例计数

系统重绑定时，可能先回调新实例的 `onServiceConnected`，再回调旧实例的 `onDestroy`。
如果只用 Boolean，旧实例的销毁会把“正在运行”错误地覆盖成 false。
`AfuAccessibilityService` 因此使用 `AtomicInteger` 统计存活实例：
只有计数归零才认为服务未运行。

---

## 4. 测试

- JVM：`./gradlew test`
  - `AfuUiParserTest`：真机弹窗文本回归；
  - `GarminFitEncoderTest`：FIT 编码；
  - `PrivilegeStatusTest`：特权模式与 UI 可用性决策。
- 实机 / 模拟器验证路径：
  1. `pm grant` 后不打开系统设置，直接拨动首页开关；
  2. 关闭再开启无障碍，确认无通知、无前台服务；
  3. 重启手机后确认 `WRITE_SECURE_SETTINGS` 仍为 granted；
  4. 在通知栏快捷开关中添加「阿福同步」磁贴，点击可开关 / 恢复服务。
