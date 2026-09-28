# 蚂蚁阿福数据获取方案调研分析（基于 OneMate）

> 记录时间：2026-09-28  
> 参考项目：[`ref_onemate`](file:///home/beta/MyCangku/weight-sync/ref_onemate) (`https://github.com/kalicyh/OneMate`)

---

## 1. 背景与问题

- **现状**：当前 `weight-sync` 采用独立 BLE 直连体脂秤获取原始体重和阻抗。由于体脂秤各厂商算法（特别是八电极多频阻抗加权公式）属于私有黑盒，手搓的开源算法与官方“蚂蚁阿福”App 呈现的结果存在一定误差。
- **调研目标**：探究能否直接借用或提取蚂蚁阿福官方 App 计算出的高精度身体成分指标。

---

## 2. OneMate 核心实现原理

参考项目 `OneMate` 本质是一个 LSPosed 模块，作者在其代码中实现了对**蚂蚁阿福**（代码中简称 `AQ`，即爱健康/蚂蚁阿福）的抓取逻辑：

### 2.1 拦截原理（LSPosed 运行时 Hook）
- **目标包名**：`com.antgroup.aijk.android`（见 [`ToolbarConfig.java`](file:///home/beta/MyCangku/weight-sync/ref_onemate/app/src/main/java/com/kalicyh/onemate/ToolbarConfig.java#L7) 及 [`scope.list`](file:///home/beta/MyCangku/weight-sync/ref_onemate/app/src/main/resources/META-INF/xposed/scope.list#L2)）。
- **框架特征**：蚂蚁阿福基于阿里/蚂蚁的 **mPaaS**（NebulaX 小程序/H5）架构，其网络请求通过统一的 RPC 代理分发。
- **Hook 关键入口**（见 [`HoneyboardModule.java`](file:///home/beta/MyCangku/weight-sync/ref_onemate/app/src/main/java/com/kalicyh/onemate/HoneyboardModule.java#L148-L185)）：
  - **Hook 类**：`com.alipay.mobile.nebulax.integration.mpaas.proxy.impl.rpc.NXRpcImpl`
  - **Hook 方法**：`sendSimpleRpc(node, isAsync, operationType, config, request, extParams)`
  - **拦截 operationType**：`com.alipay.sportshealth.biz.rpc.body.composition.indicator.query`
- **数据截获**：
  在原始 RPC 方法执行完毕后（`chain.proceed()`），通过反射调用返回对象的 `result.getResponse()`，直接提取出云端下发的原始明文 JSON。

### 2.2 跨进程广播传输
- 截获 JSON 后，通过应用内广播分发（见 [`AqBodyDataReceiver.kt`](file:///home/beta/MyCangku/weight-sync/ref_onemate/app/src/main/java/com/kalicyh/onemate/AqBodyDataReceiver.kt)）：
  - **Action**：`com.kalicyh.onemate.action.AQ_BODY_DATA`
  - **Payload**：附带完整的 RPC JSON 数据与安全 Token。

### 2.3 数据结构与字段映射
在 [`HealthConnectSync.kt`](file:///home/beta/MyCangku/weight-sync/ref_onemate/app/src/main/java/com/kalicyh/onemate/HealthConnectSync.kt#L120-L159) 中，云端返回的 JSON 结构清晰：
- 节点路径：`data.day.records` 中按 `recordTime` 获取最新记录。
- **核心数据字段**：
  - 体重：`record.optDoubleValue("weight")`
  - 体脂率：`record.metricValueOrNull("fatPercent")`
  - 脂肪重量：`record.metricValue("fatMass")`
  - 水分量：`record.metricValueOrNull("bodyWaterMass")`
  - 骨量：`record.metricValueOrNull("boneMass")`
  - 肌肉量：`record.metricValueOrNull("muscleMass")`
  - 瘦体重：`weightKg - fatMassKg`
  - 身高：`day.todayData.height`
  - 基础代谢率：从 `data.insightText` 正则提取（`基础代谢为\s*([0-9.]+)\s*kcal`）

---

## 3. weight-sync 项目借鉴与落地方向

针对我们当前的 Garmin 同步项目，该发现提供了两个层面的价值：

1. **路线 A：Root/LSPosed 模块化集成（100% 官方数据）**
   - 若运行设备有 Root + LSPosed，可引入类似 Hook 逻辑。当用户在阿福 App 中测秤或进入身材页触发查询时，直接截获指标 JSON 并自动构建 Garmin FIT 格式上传，彻底省去算法逆向与调参成本。
2. **路线 B：免 Root 算法参数校准（优化独立 BLE 算法）**
   - 借助 OneMate 导出的阿福真实全量 JSON 数据，结合体脂秤通过 BLE 传输的原始阻抗数据（如 50kHz、250kHz 阻抗值）建立多组样本对照，通过多元线性回归或参数微调，大幅提升免 Root 独立 App 的离线计算准确度。
