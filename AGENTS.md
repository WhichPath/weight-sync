# AGENTS.md - 工作区开发与运维规范指南

本文档定义了本工作区（`weight-sync`）的开发规范、编译流程、参考仓库处理方式及版本管理约定。所有在本工作区协助开发的 Agent 必须严格遵循以下规则。

---

## 1. 参考仓库规范（只读）

- 本工作区根目录下已拉取了两个核心参考仓库：
  - `ref_smart_body_scale/`：开源体脂秤 Android App 参考（含阿福体脂秤 BLE GATT 协议解析）。
  - `ref_garmin_weight_sync/`：Garmin 体重与身体成分数据同步实现参考（含 FIT 格式生成与 Garmin SSO/API 上传逻辑）。
- **严禁重复执行 `git clone`**：任何 Agent **不得**再次运行 `git clone` 下载这两个仓库。
- **只读原则**：这两个目录为本地参考资料，任何代码编写或修改均应在主项目（`app/` 等目录）中进行，不得污染参考目录。

---

## 2. 构建与打包规范

- **仅输出 Release APK**：
  - 项目日常编译与发布**直接产出 Release 版本**（`./gradlew assembleRelease`），**不需要**编译 Debug 版本。
  - `app/build.gradle.kts` 的 `buildTypes.release` 配置必须包含 `signingConfig = signingConfigs.getByName("debug")`（或专有签名），确保编译出的 Release APK 带有签名，可直接在真实设备（Android 15+ / ColorOS）上安装运行。
  - 禁止在仓库中硬编码任何私有私钥/敏感 Token。
- **云端构建**：
  - 依赖 GitHub Actions 进行自动化编译（`.github/workflows/build-apk.yml`）。
  - 触发构建后通过 `gh run watch` 等待完成并下载产物。

---

## 3. APK 产物保存与命名规范

- **输出目录**：
  - 本地统一存放在工作区根目录的 `output_apk/` 文件夹中。
  - 该目录必须在 `.gitignore` 中忽略，不得提交至 Git。
- **文件命名规则**：
  - 下载后的 APK 必须按当前版本号重命名为：
    ```
    output_apk/app-v{versionName}-release.apk
    ```
    （例如：`app-v1.0.1-release.apk`）
  - 同时复制/生成一份别名文件，方便直接快速安装：
    ```
    output_apk/app-release.apk
    ```

---

## 4. 版本号管理规范

- **修改即增版**：
  - 每次完成代码修改并触发编译时，**必须递增版本号**。
- **默认自增末尾数字（Patch）**：
  - 默认情况下仅增加版本号最末尾的数字：
    - `versionCode`：每次 +1（例如从 `1` -> `2` -> `3`）。
    - `versionName`：末位递增（例如从 `1.0.0` -> `1.0.1` -> `1.0.2`）。
- **特殊版本变更**：
  - 只有在用户明确说明需要增加中间位（Minor，如 `1.1.0`）或首位数字（Major，如 `2.0.0`）时，才可调整对应位数。

---

## 5. 问题沟通与排查模式

- **用户反馈驱动**：
  - 用户会在实机测试后直接向 Agent 描述碰到的问题、Bug 现象或日志。
- **深入协议与原理排查**：
  - 遇到硬件交互（如阿福体脂秤 BLE）或第三方平台（Garmin、系统健康）问题时，Agent 需对比参考仓库实现与系统底层协议（如 Android 15 GATT 行为、ColorOS 权限特性）。
  - **严禁盲目复制**不适用的逻辑（例如误把纯 GATT 秤当作蓝牙广播秤），杜绝幽灵连接与异常读数。
- **权限规范**：
  - 针对 Android 12+ / 15+，蓝牙扫描使用 `BLUETOOTH_SCAN` 并配置 `usesPermissionFlags="neverForLocation"`，杜绝不必要的定位权限索取。
