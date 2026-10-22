# Weight Sync (阿福体脂秤 Garmin 同步应用)

专为**阿福体脂秤**及标准 BLE 广播体脂秤定制的 Android 健康同步应用。解决原开源项目多成员匹配导致的体脂归零 Bug，下秤数据冲刷缺陷，以及 ColorOS / Android 14+ 系统的 Health Connect 权限锁死问题，并通过原生纯 Kotlin 算法直接与 **Garmin Connect (国际区)** 云端进行 FIT 格式健康数据同步。

---

## ✨ 核心特性

- 🎯 **聚焦单人自用**: 彻底剔除冗余的多成员匹配逻辑，避免因体重浮动或未选成员导致多项身体成分指标被赋 0 的致命缺陷。
- ⚖️ **会话防抖与防冲刷 (ActiveSession)**:
  - 踩秤时动态实时显示爬升重量，低于 3kg 屏蔽阻抗与稳定锁定；
  - 称重完成与阻抗测定后自动锁定不可变快照；
  - 下秤时体脂秤上报的 `0.00kg` 报文仅用于提交保存，绝不冲刷抹零已测得的阻抗与多项指标。
- 🌐 **Garmin Connect 原生直连 (国际区)**:
  - 纯 Kotlin 原生实现 Garmin Index Smart Scale (Product 2429) FIT 二进制编码协议（Msg 30 体重与身体成分）；
  - 内置 Garmin 官方移动端 SSO WebView 登录与换票机制，支持 OAuth 1.0a 签名与 OAuth 2.0 Bearer Token 自动续期；
  - 完全无外部私有 Key / 闭源 JAR 依赖，透明安全；
  - 支持称重完成自动静默上传，以及历史记录一键补传与批量全量同步。
- 📊 **覆盖 10 项 Garmin 身体健康指标**:
  - 体重 (Weight)
  - 身体质量指数 (BMI)
  - 体脂率 (Body Fat %)
  - 水分率 (Hydration / Water %)
  - 肌肉量 (Muscle Mass)
  - 骨量 (Bone Mass)
  - 蛋白质比例 (Protein %)
  - 基础代谢率 (BMR)
  - 内脏脂肪等级 (Visceral Fat Rating)
  - 身体年龄 / 代谢年龄 (Metabolic Age)
- 📱 **现代 Android 15+ 与 ColorOS 深度适配**:
  - 目标架构 `compileSdk = 35`, `minSdk = 35`, `targetSdk = 35`；
  - 全面配置 Android 14+ / Android 15 标准 `android.intent.action.VIEW_PERMISSION_USAGE` 与 `android.permission.START_VIEW_PERMISSION_USAGE` Activity Alias，彻底修复在 ColorOS 等魔改系统上“健康权限呈灰色不可点击”的系统级限制；
  - 彻底移除小米健康等冗余依赖与服务。
- ♿ **无障碍服务应用内可控（参考 GKD）**:
  - 状态三态化：`未开启` / `已开启但未运行` / `运行中`，不再把“系统里滑块是开的”误判成“服务在跑”；
  - 首页内置**无障碍开关**，配合可选的 Shizuku 特权通道，可在应用内直接开启 / 关闭 / 重启无障碍服务，无需跳转系统设置手动关开；
  - App 被后台清理后首次打开会自动检测“开着但没跑”的异常态并尝试重新拉起；
  - 前台保活改用 `specialUse` 类型，修复 Android 14+ 因缺少 `READ_HEALTH_DATA_IN_BACKGROUND` 权限导致 `startForeground` 抛 `SecurityException`（保活形同虚设）以及 Android 15 `dataSync` 6 小时超时掉线的问题；
  - 内置「受限设置」自检与一键跳转应用信息页指引。

---

## 🏗️ 架构与编译

本项目采用**本地编译**（不再使用 GitHub Actions 云端构建）。

### 本地编译
本地 Android 工具链位于 `/home/beta/BigCangku/Android/`（SDK + 模拟器 + `Pixel_8_API_35` AVD），
在工作区根目录执行：

```bash
export ANDROID_HOME=/home/beta/BigCangku/Android/sdk
./gradlew test            # 解析器回归用例
./gradlew assembleRelease # 产出已签名的 Release APK
```

产物位于 `app/build/outputs/apk/release/app-release.apk`，并按 `output_apk/app-v{version}-release.apk` 归档。

### 图标
应用图标为自适应图标（108×108 视口）：主题绿渐变背景 + 蚂蚁阿福轮廓 + Health Connect 官方蓝药丸形 +
Garmin 官方三角作同步箭头 + 白色同步弧，并提供 Android 13+ 主题图标单色层。
