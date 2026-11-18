package com.muhe.fit.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 字型基线（login-design.md §3.3 字号层级）。
 * 原则：中文正文走系统默认字体栈（MiSans/HarmonyOS Sans/思源黑随设备），不打包字体文件；
 * 屏内个别覆写（如主 CTA label w600、配对码等宽）在组件层做，不污染全局基线。
 */
val MuheTypography = Typography(
    // 品牌名：28/36 w600
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
    // slogan / 输入值基线：16/24
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    // 隐私句 / 辅助说明：13/20
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 20.sp),
    // 按钮 label 基线：16/24 w500（主 CTA 的 w600 由组件覆写）
    labelLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
)
