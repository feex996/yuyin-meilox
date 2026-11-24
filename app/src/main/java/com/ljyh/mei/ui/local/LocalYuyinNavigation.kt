package com.ljyh.mei.ui.local

import androidx.compose.runtime.staticCompositionLocalOf
import com.ljyh.mei.ui.screen.Index

/**
 * 余音：沉浸页面（「推荐」）隐藏了底部标签栏，通过侧边抽屉导航。
 * [openTab] 与底部标签栏点按行为一致（记住所选标签并切换顶层页面），[openSearch] 打开搜索并聚焦输入框。
 */
class YuyinNavigation(
    val openTab: (Index) -> Unit,
    val openSearch: () -> Unit,
)

val LocalYuyinNavigation = staticCompositionLocalOf<YuyinNavigation?> { null }
