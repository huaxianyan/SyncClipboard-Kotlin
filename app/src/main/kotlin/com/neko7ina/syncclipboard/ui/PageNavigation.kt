package com.neko7ina.syncclipboard.ui

/**
 * 应用的顶层页面。
 *
 * 层级与次序只在这里定义一次：转场方向、底栏选中项、顶部栏标题和返回归属全部由它派生。
 * 新增页面时只改这个枚举，不需要再改任何一处转场判断。
 *
 * @param depth 层级。二级页面的值大于一级页面。
 * @param order 同一层级内的先后顺序，决定同层页面之间是前进还是后退。
 */
internal enum class AppPage(
    val title: String,
    val depth: Int,
    val order: Int,
) {
    HOME("首页", depth = 0, order = 0),
    TEXT_SYNC_HISTORY("文本同步历史", depth = 0, order = 1),
    SETTINGS("设置", depth = 0, order = 2),
    AUTOMATIC_SYNC_EVENTS("自动同步记录", depth = 1, order = 0),
}

/**
 * 这一页的上一级，也就是返回手势应该回到哪一页。为 null 表示它本身就是返回栈的根，
 * 返回交给系统处理（回上一个 Activity 或桌面）。
 *
 * 页面归属只在这里定义一次，不散落到返回回调里按页面名逐个判断。
 */
internal val AppPage.parentPage: AppPage?
    get() = when (this) {
        AppPage.AUTOMATIC_SYNC_EVENTS -> AppPage.HOME
        AppPage.HOME,
        AppPage.TEXT_SYNC_HISTORY,
        AppPage.SETTINGS,
        -> null
    }

/**
 * 从 [initial] 到当前页面是前进还是后退。
 *
 * 先比层级，同层再比次序。手势返回时调用方传进来的是「当前页 → 上一页」，
 * 所以这里返回 false，转场才会走背景页短距离滑回、当前页整页退出的那一套。
 */
internal fun AppPage.isForwardFrom(initial: AppPage): Boolean =
    depth > initial.depth || depth == initial.depth && order > initial.order
