package com.muhe.fit.app.ui.theme

import androidx.compose.ui.graphics.Color

// ── 色彩 token ──────────────────────────────────────────────────────────────
// 唯一真源：ck/docs/app-design/login-design.md §2.1（全部 hex 为实测/实算定值，勿擅改）。
// 色板纪律：色彩只花在 accent 与状态上，表面靠蓝灰亮度阶梯（hue≈222°）分层。
// login-design 的 LoginDarkScheme 即全局方案——深色唯一主题下全屏共用（android-app.md §1.2）。

// 表面五级亮度阶梯 + 最深层
val MuheSurface = Color(0xFF0F1319)             // surface：页面画布、一切之底
val MuheSurfaceLowest = Color(0xFF090D12)       // surfaceContainerLowest：扫码遮罩凹陷、最深层
val MuheSurfaceLow = Color(0xFF161B22)          // surfaceContainerLow：展开面板
val MuheSurfaceContainer = Color(0xFF1C232E)    // surfaceContainer：卡片、辅助信息底
val MuheSurfaceHigh = Color(0xFF262D3A)         // surfaceContainerHigh：次按钮填充、输入框填充态
val MuheSurfaceHighest = Color(0xFF313947)      // surfaceContainerHighest：按压态、嵌套控件

// 文本三级
val MuheOnSurface = Color(0xFFE3E8F0)           // 主文本（vs surface 15.1:1 AAA）
val MuheOnSurfaceVariant = Color(0xFFBAC3CF)    // 次文本（vs surface 10.5:1 AAA）
val MuheTextTertiary = Color(0xFF8590A0)        // 三级文本·扩展 token（vs surface 5.8:1 AA）

// 边界
val MuheOutline = Color(0xFF5A6575)             // 输入框静默边框、取景框描边（非文本 ≥3:1）
val MuheOutlineVariant = Color(0xFF2A3140)      // 分隔线、装饰性 hairline（仅装饰）

// accent：品牌绿（上游 PWA 实测默认 accent 直接继承）
val MuhePrimary = Color(0xFF30D158)             // 主 CTA 填充、焦点边框、成功态、链接态
val MuheOnPrimary = Color(0xFF00190D)           // 主 CTA label/图标（vs primary 9.1:1 AAA）
val MuhePrimaryContainer = Color(0xFF145C33)    // 成功过场底、选中态容器
val MuheOnPrimaryContainer = Color(0xFFA8F0BE)  // 成功文案

// 语义色：error（继承上游 iOS 红）；success 复用 primary（品牌绿即成功色，避免双绿混淆）
val MuheError = Color(0xFFFF453A)               // 错误文本、错误边框（vs surface 5.5:1 AA）
val MuheOnError = Color(0xFF1A0503)
val MuheErrorContainer = Color(0xFF431511)      // 错误 banner 底
val MuheOnErrorContainer = Color(0xFFFFB4A9)    // banner 文本/图标

// scrim（扫码过场遮罩，60% 使用时经 alpha 叠加）
val MuheScrim = Color(0xFF000000)
