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

---

## 🏗️ 架构与编译

本项目推荐直接通过 **GitHub Actions** 进行云端无污染自动化编译生成 APK。

### 云端编译 (GitHub Actions)
代码推送至 `main` 分支或在 GitHub Actions 页面点击 `Run workflow` 后，CI 会自动启动 JDK 17 环境执行 `./gradlew assembleDebug`，并生成 `weight-sync-debug-apk` 构件供随时下载安装。
