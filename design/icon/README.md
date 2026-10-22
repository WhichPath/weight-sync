# 阿福同步 · 应用图标（V7 定稿）

自适应图标三层：`background`（纯色）+ `foreground`（三 logo + 三叉）+ `monochrome`（主题图标单色层）。

```
design/icon/
├── afu.svg            蚂蚁阿福（程序化描摹）
├── healthconnect.svg  Health Connect（程序化描摹）
├── garmin.svg         Garmin 三角（官方 SVG）
├── trident.svg        三叉同步标（Tabler Icons，官方 SVG）
├── compose.py         组合脚本：归一化 → 摆位旋转 → 输出资源与预览
├── preview.png        设计预览（方图 / 圆形遮罩 / 圆角方形遮罩）
└── out/               脚本输出（.gitignore）
```

运行 `python3 design/icon/compose.py` 会重新生成：

- `app/src/main/res/drawable/ic_launcher_{background,foreground,monochrome}.xml`
- `app/src/main/res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher{,_round}.png`
- `design/icon/preview.png`

依赖：`rsvg-convert`（librsvg）、Python `pillow` + `numpy`（仅生成阶段，不进 APK）。

---

## 1. 素材来源

| 元素 | 来源 | 授权 |
|---|---|---|
| 蚂蚁阿福 | Google Play 应用图标（512×512 PNG），程序化矢量化 | 品牌素材，仅本 App 内使用 |
| Health Connect | Google Play「Health Connect」图标（512×512 PNG），程序化矢量化 | 品牌素材 |
| Garmin 三角 | [Wikimedia `Garmin_logo_2006.svg`](https://commons.wikimedia.org/wiki/File:Garmin_logo_2006.svg) 的官方路径 | 商标，仅标识用途 |
| 三叉同步标 | [Tabler Icons `arrows-split-2`](https://github.com/tabler/tabler-icons) | MIT |

## 2. 矢量化方法

没有手绘，全部程序化：

1. **颜色掩膜**：从原图统计主色，按色距分离图层（HC 深青 `#003144` / 亮蓝 `#4285F4`）。
2. **真镂空**：用 `scipy.ndimage.label` 做连通域标记，找出被色块包围的白色区域（HC 的心形药丸孔、阿福尾巴小圆孔），作为内孔参与 `fill-rule="evenodd"`，而不是涂白。
3. **矢量化**：`vtracer`（Rust 曲线拟合器）按颜色层分别描摹；`corner/length/splice/precision` 四参数做 54 组网格寻优，以**与原图的 IoU** 为评分，取最优。
4. **描摹质量**（对 512px 原图）：

   | 元素 | IoU |
   |---|---|
   | 阿福 | 0.941 |
   | Health Connect 深青 | 0.955 |
   | Health Connect 亮蓝（含孔） | 0.976 |

5. vtracer 输出的每个 `<path>` 自带 `translate(...)`，重建时必须把变换烘焙进 `pathData`，否则图形错位。

## 3. 颜色

| 用途 | 值 | 说明 |
|---|---|---|
| 背景 | `#00897B` | App 主题主绿 `MintAccentGreen`（`ui/theme/Color.kt`），纯色不用渐变 |
| 阿福渐变 | `#4164F6 → #BF75F9` | 对不透明像素多点采样 → PCA 求主轴 → 逐通道线性回归拟合（残差 ≈8/255），轴 (114.8,75.5)→(363.5,461.0) |
| HC 深青 | `#003144` | 原图实测 |
| HC 亮蓝 | `#4285F4` | 原图实测 |
| Garmin | `#007CC2` | Garmin 官方品牌蓝 |
| 三叉 | `#FFFFFF`，`strokeAlpha=0.2` | 整体用 `<group>` 变换（不改写 pathData，弧线由渲染器原生处理，避免走样）；合并为单 `<path>`：多 path 描边交叉处 alpha 会二次叠加（0.3 → 实测 0.51），合并后描边先求并集再上色，全局均匀 |

## 4. 尺寸归一化

每个 logo 取**真实紧致外接框**（光栅化实测；贝塞尔控制点会超出曲线，不能直接用），等比缩放到**最大边 = 1**、外接框居中 → 三者视觉大小一致，各自至少触及单位方框一条边。三者合并的最小正方形即单位正方形。

## 5. 布局（108×108 视口）

每个元素以**自身十字（外接框中轴）**定位：旋转角使 **y 轴 = 该元素到画布中心 (54,54) 的径向线**。

| 元素 | 中心 | 旋转 | 方框边长 |
|---|---|---|---|
| 蚂蚁阿福 | (27.0, 27.0) | −45° | 50 |
| Garmin 三角 | (103.2, 36.1) | +70° | 50 |
| Health Connect | (56.8, 90.0) | −4.4° | 50 |
| 三叉 | 外接框中心 = (54,54) | −45°（与阿福 y 轴同线） | 4× 放大 |

三叉在三个 logo **下层**，作为同步语义的浅色底纹。

## 6. 校验

- `compose.py` 对三叉做**迭代居中**：渲染 → 量外接框 → 平移，直到外接框中心与画布中心误差 < 0.1。
- 生成的 VectorDrawable 用 `vd2svg` 反解回 SVG，与组合 SVG 同底色渲染对比：逐像素最大差 101（仅边缘抗锯齿），无一处 >120，视觉等价。

## 7. 注意事项

- **三叉不要烘焙坐标**：官方字形含弧线命令（`a`），自行改写坐标时弧线极易走样（表现为根部弯曲、箭头错位）。正确做法是保持 pathData 原样，用 `<g>` / `<group>` 变换整体旋转缩放平移。
- 前景层里三个 logo 的坐标已烘焙到 108 视口，`android:fillType` 要用 `evenOdd`（大小写敏感，写成 `evenodd` 会导致镂空失效）。
- 图层顺序：三叉在三个 logo **之下**，SVG 与 VectorDrawable 必须保持一致。
- Android 15+ 的自适应图标会按各厂商遮罩裁切，外圈 18dp 属于安全出血区，不要再往外挪元素。
