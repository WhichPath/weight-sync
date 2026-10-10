package com.example.dianzicheng.ui.theme

import androidx.compose.ui.graphics.Color

// ============================================================================
// 确定性个性化主题配色 (Deterministic Personality Palette)
// 核心主调：优雅浅薰衣草紫 (Lavender Purple) × 清新活力薄荷青绿 (Mint / Cyan-Green)
// 配色美学：冷暖平衡、高呼吸感、健康科技感，大面积呈现紫与青绿双主色
// ============================================================================

// ── Light Scheme ────────────────────────────────────────────────────────────
// 主色 (Primary) - 浅薰衣草淡雅紫
val PrimaryLight = Color(0xFF7A4E9E)
val OnPrimaryLight = Color(0xFFFFFFFF)
val PrimaryContainerLight = Color(0xFFF3E7FE)      // 大面积柔和淡紫底色
val OnPrimaryContainerLight = Color(0xFF320955)

// 副色 (Secondary) - 活力清爽薄荷青绿
val SecondaryLight = Color(0xFF00897B)
val OnSecondaryLight = Color(0xFFFFFFFF)
val SecondaryContainerLight = Color(0xFFCCFBF1)    // 大面积清润薄荷青底色
val OnSecondaryContainerLight = Color(0xFF003731)

// 辅色 (Tertiary) - 活力暖珊瑚粉 (用于局部体脂/代谢指标呼应)
val TertiaryLight = Color(0xFF9E4767)
val OnTertiaryLight = Color(0xFFFFFFFF)
val TertiaryContainerLight = Color(0xFFFFD8E4)
val OnTertiaryContainerLight = Color(0xFF3E0522)

// 表面层级 (Surfaces) - 带微量紫露微温的中性底，告别生硬死白
val BackgroundLight = Color(0xFFFAF7FD)
val OnBackgroundLight = Color(0xFF1D1A21)
val SurfaceLight = Color(0xFFFAF7FD)
val OnSurfaceLight = Color(0xFF1D1A21)

val SurfaceContainerLowestLight = Color(0xFFFFFFFF)
val SurfaceContainerLowLight = Color(0xFFF4EFF9)
val SurfaceContainerLight = Color(0xFFEEE7F4)       // 浅紫调玻璃容器底色
val SurfaceContainerHighLight = Color(0xFFE8E0EE)
val SurfaceContainerHighestLight = Color(0xFFE2D9E8)

val OutlineLight = Color(0xFF7F7487)
val OutlineVariantLight = Color(0xFFD0C3D8)

// ── Dark Scheme ─────────────────────────────────────────────────────────────
// 暗色主色 - 荧光粉紫 (Pastel Lilac)
val PrimaryDark = Color(0xFFDEB7FF)
val OnPrimaryDark = Color(0xFF481C6C)
val PrimaryContainerDark = Color(0xFF603584)
val OnPrimaryContainerDark = Color(0xFFF3E7FE)

// 暗色副色 - 荧光青绿 (Glowing Aqua Mint)
val SecondaryDark = Color(0xFF5EEAD4)
val OnSecondaryDark = Color(0xFF003731)
val SecondaryContainerDark = Color(0xFF005047)
val OnSecondaryContainerDark = Color(0xFFCCFBF1)

// 暗色辅色 - 柔珊瑚粉
val TertiaryDark = Color(0xFFFFAFD0)
val OnTertiaryDark = Color(0xFF611838)
val TertiaryContainerDark = Color(0xFF7F2F4F)
val OnTertiaryContainerDark = Color(0xFFFFD8E4)

// 暗色表面层级 - 深紫曜石黑
val BackgroundDark = Color(0xFF151218)
val OnBackgroundDark = Color(0xFFE8E0EC)
val SurfaceDark = Color(0xFF151218)
val OnSurfaceDark = Color(0xFFE8E0EC)

val SurfaceContainerLowestDark = Color(0xFF100D13)
val SurfaceContainerLowDark = Color(0xFF1D1A21)
val SurfaceContainerDark = Color(0xFF221E27)
val SurfaceContainerHighDark = Color(0xFF2D2833)
val SurfaceContainerHighestDark = Color(0xFF38333E)

val OutlineDark = Color(0xFF988E9F)
val OutlineVariantDark = Color(0xFF4C4452)

// ── 语义化微组件色彩 (Semantic Badges & Accents) ─────────────────────────────
val MintAccentGreen = Color(0xFF00897B)
val MintAccentContainer = Color(0xFFCCFBF1)
val LavenderAccentPurple = Color(0xFF7A4E9E)
val LavenderAccentContainer = Color(0xFFF3E7FE)

val StatusSuccess = Color(0xFF00897B)
val StatusSuccessContainer = Color(0xFFCCFBF1)
val StatusWarning = Color(0xFFD97706)
val StatusWarningContainer = Color(0xFFFEF3C7)
val StatusError = Color(0xFFDC2626)
val StatusErrorContainer = Color(0xFFFEE2E2)