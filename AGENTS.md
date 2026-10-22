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
- **本地编译（唯一方式，不再使用 GitHub Actions）**：
  - **自 2.0.14 起，所有编译都在本地完成，不再触发 GitHub Actions 云端构建**；
    `.github/workflows/build-apk.yml` 保留但不再使用，Agent **不得**再执行 `git push` 触发云端编译或用 `gh run watch` 等待云端产物。
  - 本地 Android 工具链统一位于 **`/home/beta/BigCangku/Android/`**：
    ```
    /home/beta/BigCangku/Android/sdk/          # Android SDK（platform-tools / build-tools / platforms）
    /home/beta/BigCangku/Android/sdk/emulator/emulator   # 模拟器
    /home/beta/BigCangku/Android/avd/          # AVD（Pixel_8_API_35，Android 15 / google_apis）
    ```
  - 环境变量：`ANDROID_HOME=/home/beta/BigCangku/Android/sdk`，`adb` 位于 `$ANDROID_HOME/platform-tools`。
  - 标准编译命令（在工作区根目录执行）：
    ```
    ./gradlew test            # 回归测试（解析器用例）
    ./gradlew assembleRelease # 产出 Release APK
    ```
  - 产物路径：`app/build/outputs/apk/release/app-release.apk`，编译完成后按第 3 节规范归档到 `output_apk/`。
- **模拟器（本地可视化验证）**：
  - 启动命令（带图形界面，配合本机 `DISPLAY=:0`）：
    ```
    $ANDROID_HOME/emulator/emulator -avd Pixel_8_API_35 -no-audio -no-boot-anim -no-snapshot
    ```
  - 用途：验证应用图标在真实启动器中的呈现、UI 布局与无障碍弹窗交互。
  - 注意：若模拟器启动后卡在 "detected a hanging thread 'QEMU2 ...'"（容器内 QEMU 线程调度受限），
    属于环境限制而非工程问题；此时改用「矢量化渲染自检」的方式验证图标（见第 6 节）。

---

## 3. APK 产物保存与命名规范

- **输出目录**：
  - 本地统一存放在工作区根目录的 `output_apk/` 文件夹中。
  - 该目录必须在 `.gitignore` 中忽略，不得提交至 Git。
- **文件命名规则**：
  - 编译产出的 APK 必须按当前版本号重命名为：
    ```
    output_apk/app-v{versionName}-release.apk
    ```
    （例如：`app-v2.0.14-release.apk`）
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

---

## 6. 应用图标与视觉资源规范

- **自适应图标三层结构**（`app/src/main/res/`）：
  - `mipmap-anydpi-v26/ic_launcher.xml` → `drawable/ic_launcher_background.xml`（背景层）+ `drawable/ic_launcher_foreground.xml`（前景层）+ `drawable/ic_launcher_monochrome.xml`（主题图标单色层）。
  - 三层统一使用 **108×108 视口**，安全区为中心 **66×66**；允许元素出血（溢出裁切）以增强动感。
- **当前设计（2.0.14）**：主题绿渐变背景 + 蚂蚁阿福轮廓（白色）+ Health Connect 官方蓝药丸形 + Garmin 官方三角作同步箭头 + 白色同步弧。
- **矢量化自检流程（改图标后必须执行）**：
  1. 用脚本把品牌位图描摹为 SVG path（contour + Douglas-Peucker + Catmull-Rom 平滑），保留矢量；
  2. 由同一份参数同时产出 **SVG 预览** 与 **Android VectorDrawable XML**，保证"所见即将打包"；
  3. 把 VectorDrawable 反解为 SVG 渲染成 PNG（`rsvg-convert`），套用圆形遮罩模拟启动器裁切后**由 Agent 自行目视评估并迭代**；
  4. 重点核查 VectorDrawable 陷阱：未声明 `android:fillColor` 的 `<path>` 在 Android 中**默认填充黑色**，
     纯描边路径必须显式写 `android:fillColor="#00000000"`。
- **传统 PNG 兜底**：`mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher(.round).png` 需同步重新生成。
