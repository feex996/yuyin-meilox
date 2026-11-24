package com.ljyh.mei.ui.local

import androidx.compose.runtime.staticCompositionLocalOf

/** 余音：页面内打开现有的全屏播放界面（底部播放器展开）。 */
val LocalOpenFullPlayer = staticCompositionLocalOf<() -> Unit> { {} }
