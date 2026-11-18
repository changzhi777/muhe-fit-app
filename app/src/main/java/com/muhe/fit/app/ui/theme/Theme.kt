package com.muhe.fit.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 全局唯一配色方案 = login-design.md §2.2 的 darkColorScheme 映射，深色唯一主题下全屏共用。
 * 已给出的 token 逐字对照设计文档；未给出的 token 由蓝灰阶梯/accent 派生（逐项注明派生来源），
 * 后续设计稿覆盖时以新稿为准。
 */
private val MuheDarkScheme = darkColorScheme(
    // ── 设计文档实给 token（§2.1/§2.2 逐字）──
    primary = MuhePrimary,
    onPrimary = MuheOnPrimary,
    primaryContainer = MuhePrimaryContainer,
    onPrimaryContainer = MuheOnPrimaryContainer,
    error = MuheError,
    onError = MuheOnError,
    errorContainer = MuheErrorContainer,
    onErrorContainer = MuheOnErrorContainer,
    surface = MuheSurface,
    onSurface = MuheOnSurface,
    surfaceContainerLowest = MuheSurfaceLowest,
    surfaceContainerLow = MuheSurfaceLow,
    surfaceContainer = MuheSurfaceContainer,
    surfaceContainerHigh = MuheSurfaceHigh,
    surfaceContainerHighest = MuheSurfaceHighest,
    onSurfaceVariant = MuheOnSurfaceVariant,
    outline = MuheOutline,
    outlineVariant = MuheOutlineVariant,
    scrim = MuheScrim,

    // ── 派生 token（文档未给，按「表面靠亮度阶梯、色相不新增」纪律派生）──
    background = MuheSurface,          // 画布即 surface
    onBackground = MuheOnSurface,
    surfaceVariant = MuheSurfaceContainer,  // M3 填充面（chip/sheet）取阶梯中位
    surfaceTint = MuhePrimary,              // M3 默认海拔着色沿用 accent
    surfaceDim = MuheSurface,               // 深色唯一：dim = 画布
    surfaceBright = MuheSurfaceHighest,     // bright 取阶梯最高级
    inverseSurface = MuheOnSurface,         // 反转面 = 浅色文本色（如 snackbar 底）
    inverseOnSurface = MuheSurface,
    inversePrimary = MuhePrimaryContainer,  // 浅底上的深绿

    // secondary/tertiary：文档明确「色彩只花在 accent 与状态」——归入蓝灰中性族，不引入第二主色
    secondary = MuheOnSurfaceVariant,
    onSecondary = MuheSurface,
    secondaryContainer = MuheSurfaceHigh,
    onSecondaryContainer = MuheOnSurface,
    tertiary = MuheOnSurfaceVariant,
    onTertiary = MuheSurface,
    tertiaryContainer = MuheSurfaceContainer,
    onTertiaryContainer = MuheOnSurfaceVariant,
)

/** 扩展 token（login-design.md §2.2：textTertiary 放 MaterialTheme 体系外携带） */
data class MuheExtendedColors(val textTertiary: Color)

val LocalMuheExtendedColors = staticCompositionLocalOf {
    MuheExtendedColors(textTertiary = MuheTextTertiary)
}

/** 对象级取用：MaterialTheme.muheExtended.textTertiary */
val MaterialTheme.muheExtended: MuheExtendedColors
    @Composable
    get() = LocalMuheExtendedColors.current

/**
 * 深色唯一主题（裁定 #1 / C10）：
 * isSystemInDarkTheme() 故意忽略——不做浅色皮肤、不做主题切换开关；
 * 不启用 dynamicColorScheme——品牌绿固定不随壁纸漂移（login-design.md §八）。
 */
@Composable
fun MuHeTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMuheExtendedColors provides MuheExtendedColors(textTertiary = MuheTextTertiary)) {
        MaterialTheme(
            colorScheme = MuheDarkScheme,
            typography = MuheTypography,
            content = content,
        )
    }
}
