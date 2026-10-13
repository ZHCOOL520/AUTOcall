修复这个深色模式无法使用的问题package com.example.autocall.ui.theme

import androidx.compose.ui.graphics.Color

// ====== 液态玻璃 (Liquid Glass) 调色板 v2 ======
// 设计理念：通透 · 轻盈 · 层次感
// 强调色系：冰蓝 + 薰衣草紫 + 薄荷绿

// ==================== 浅色主题 ====================
// 基调：水晶霜白 —— 柔和明亮的冷色调背景

// 主色调 - 冰蓝（清澈、通透）
val LightPrimary = Color(0xFF3D7EC7)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFD6E8F9)
val LightOnPrimaryContainer = Color(0xFF163B66)

// 次要色 - 薰衣草紫（优雅、柔和）
val LightSecondary = Color(0xFF7B6BA5)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFE8DFF5)
val LightOnSecondaryContainer = Color(0xFF33285A)

// 第三色 - 薄荷绿（清新、自然）
val LightTertiary = Color(0xFF3A9D7C)
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFFC8F0DF)
val LightOnTertiaryContainer = Color(0xFF0F3D2B)

// 错误色 - 柔和珊瑚红
val LightError = Color(0xFFC94D4D)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFFBD9D9)
val LightOnErrorContainer = Color(0xFF6B1A1A)

// 背景与表面 - 珍珠蓝灰，柔和明亮
val LightBackground = Color(0xFFEEF2F7)
val LightOnBackground = Color(0xFF1B1D23)
val LightSurface = Color(0xFFF6F8FB)
val LightOnSurface = Color(0xFF1B1D23)
val LightSurfaceVariant = Color(0xFFE2E8F0)
val LightOnSurfaceVariant = Color(0xFF4A5068)

// 玻璃专用颜色 —— 半透明白色带微妙蓝调
val LightGlassSurface = Color(0xB8FFFFFF)       // 72% 白色玻璃面，通透感
val LightGlassSurfaceVariant = Color(0x90DCE8F5) // 56% 冰蓝调玻璃
val LightGlassHighlight = Color(0x4DFFFFFF)      // 柔和高光层
val LightGlassBorder = Color(0x40E8EEF6)         // 冷色调边框高光
val LightGlassShadow = Color(0x0F1B3050)         // 极淡蓝灰阴影

// 轮廓 - 柔和不刺眼
val LightOutline = Color(0x221B2540)
val LightOutlineVariant = Color(0x141B2540)

// ==================== 深色主题 ====================
// 基调：午夜极光 —— 深靛蓝底调，非纯黑，高级感

// 主色调 - 极光蓝（柔和发光感）
val DarkPrimary = Color(0xFF8BB8E8)
val DarkOnPrimary = Color(0xFF0E2F54)
val DarkPrimaryContainer = Color(0xFF1C4578)
val DarkOnPrimaryContainer = Color(0xFFCCE0F5)

// 次要色 - 月光薰衣草
val DarkSecondary = Color(0xFFB8A8D8)
val DarkOnSecondary = Color(0xFF2A1F48)
val DarkSecondaryContainer = Color(0xFF3E3260)
val DarkOnSecondaryContainer = Color(0xFFDDD0F0)

// 第三色 - 翡翠薄荷
val DarkTertiary = Color(0xFF7CC8A8)
val DarkOnTertiary = Color(0xFF0A3828)
val DarkTertiaryContainer = Color(0xFF155040)
val DarkOnTertiaryContainer = Color(0xFFB8E8D0)

// 错误色 - 柔和玫瑰红
val DarkError = Color(0xFFF08080)
val DarkOnError = Color(0xFF5C1414)
val DarkErrorContainer = Color(0xFF7A2020)
val DarkOnErrorContainer = Color(0xFFF5C8C8)

// 背景与表面 - 深靛蓝，OLED 友好但非纯黑
val DarkBackground = Color(0xFF0B0E18)
val DarkOnBackground = Color(0xFFDDE2EC)
val DarkSurface = Color(0xFF111520)
val DarkOnSurface = Color(0xFFDDE2EC)
val DarkSurfaceVariant = Color(0xFF1A2035)
val DarkOnSurfaceVariant = Color(0xFFB8C0D4)

// 玻璃专用颜色 —— 半透明深靛蓝带微光折射
val DarkGlassSurface = Color(0x70182848)        // 44% 深靛蓝玻璃面
val DarkGlassSurfaceVariant = Color(0x55152040)  // 33% 深海玻璃
val DarkGlassHighlight = Color(0x18C8D8FF)       // 极微弱蓝紫高光
val DarkGlassBorder = Color(0x20B0C0E0)          // 微弱冷色边框
val DarkGlassShadow = Color(0x40000008)          // 深邃阴影

// 轮廓 - 柔和白光
val DarkOutline = Color(0x1EC0CCE0)
val DarkOutlineVariant = Color(0x12C0CCE0)

// ==================== 通用玻璃渐变色 ====================
val GlassShineStart = Color(0x28FFFFFF)
val GlassShineEnd = Color(0x00FFFFFF)
val GlassBorderLight = Color(0x45E0E8F4)
val GlassBorderDark = Color(0x22B8C8E0)
