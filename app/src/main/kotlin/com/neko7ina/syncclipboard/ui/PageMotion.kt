package com.neko7ina.syncclipboard.ui

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import kotlin.math.abs
import kotlin.math.roundToInt

// 页面转场参数。取自项目指定的样板应用 PixelText 的 AppNavigation.kt：看它怎么组织转场、
// 公开了哪些参数，不照搬它的依赖版本。
//
// 普通点击切页、顶部返回按钮和预测性返回手势共用这一套：手势进度就是转场进度，
// 所以三者不需要各自一份动画，也不会出现「手势预览和点一下的效果不一样」。

/** 前景页（整页位移的那一页）的位移时长，也是整段转场的总时长。 */
internal const val PAGE_FOREGROUND_MILLIS = 300

/** 背景页（退到一边的那一页）的位移时长。比前景短，背景更早稳住。 */
internal const val PAGE_BACKGROUND_MILLIS = 260

/** 背景页的位移距离是整页宽度的几分之一。 */
internal const val PAGE_BACKGROUND_OFFSET_DIVISOR = 4

/** 前景页入场时的初始缩放。 */
internal const val PAGE_FOREGROUND_ENTER_SCALE = 0.96f

/** 前景页退场时的目标缩放。返回时当前页一边向外退出一边收小。 */
internal const val PAGE_FOREGROUND_EXIT_SCALE = 0.90f

/** 背景页的目标缩放。 */
internal const val PAGE_BACKGROUND_SCALE = 0.92f

/** 入场淡化时长。短，让新页尽早变成不透明。 */
internal const val PAGE_ENTER_FADE_MILLIS = 90

/** 退场淡化时长。 */
internal const val PAGE_EXIT_FADE_MILLIS = 90

/** 背景页退场淡化的延迟。 */
internal const val PAGE_BACKGROUND_EXIT_FADE_DELAY_MILLIS = 120

/**
 * 前景页退场淡化的延迟。
 *
 * 必须留到转场后段：手势停在中段时，新页的 90 ms 淡入早已结束，旧页还没开始淡出，
 * 两页都是不透明的，拖动过程中一直看得清。把这段延迟去掉或者调小，中段就会变成
 * 「两页都很淡」，这是手势转场最常见的返工点。
 */
internal const val PAGE_FOREGROUND_EXIT_FADE_DELAY_MILLIS = 210

/**
 * 前景页入场：整页宽度滑入，配快速淡入和轻微放大。
 */
private fun foregroundEnter(): EnterTransition =
    slideInHorizontally(
        animationSpec = tween(PAGE_FOREGROUND_MILLIS, easing = FastOutSlowInEasing),
        initialOffsetX = { fullWidth -> fullWidth },
    ) +
        fadeIn(tween(PAGE_ENTER_FADE_MILLIS)) +
        scaleIn(
            animationSpec = tween(PAGE_FOREGROUND_MILLIS, easing = FastOutSlowInEasing),
            initialScale = PAGE_FOREGROUND_ENTER_SCALE,
        )

/**
 * 背景页退场：退到四分之一页宽处并收小，淡化留到后段。
 */
private fun backgroundExit(): ExitTransition =
    slideOutHorizontally(
        animationSpec = tween(PAGE_BACKGROUND_MILLIS, easing = FastOutSlowInEasing),
        targetOffsetX = { fullWidth -> -fullWidth / PAGE_BACKGROUND_OFFSET_DIVISOR },
    ) +
        fadeOut(tween(PAGE_EXIT_FADE_MILLIS, delayMillis = PAGE_BACKGROUND_EXIT_FADE_DELAY_MILLIS)) +
        scaleOut(
            animationSpec = tween(PAGE_BACKGROUND_MILLIS, easing = FastOutSlowInEasing),
            targetScale = PAGE_BACKGROUND_SCALE,
        )

/**
 * 背景页入场：返回时上一页从四分之一页宽处滑回原位，同样只做短距离位移。
 */
private fun backgroundEnter(): EnterTransition =
    slideInHorizontally(
        animationSpec = tween(PAGE_BACKGROUND_MILLIS, easing = FastOutSlowInEasing),
        initialOffsetX = { fullWidth -> -fullWidth / PAGE_BACKGROUND_OFFSET_DIVISOR },
    ) +
        fadeIn(tween(PAGE_ENTER_FADE_MILLIS)) +
        scaleIn(
            animationSpec = tween(PAGE_BACKGROUND_MILLIS, easing = FastOutSlowInEasing),
            initialScale = PAGE_BACKGROUND_SCALE,
        )

/**
 * 前景页退场：返回时当前页整页宽度退出并收小，淡化延迟到转场后段。
 */
private fun foregroundExit(): ExitTransition =
    slideOutHorizontally(
        animationSpec = tween(PAGE_FOREGROUND_MILLIS, easing = FastOutSlowInEasing),
        targetOffsetX = { fullWidth -> fullWidth },
    ) +
        fadeOut(tween(PAGE_EXIT_FADE_MILLIS, delayMillis = PAGE_FOREGROUND_EXIT_FADE_DELAY_MILLIS)) +
        scaleOut(
            animationSpec = tween(PAGE_FOREGROUND_MILLIS, easing = FastOutSlowInEasing),
            targetScale = PAGE_FOREGROUND_EXIT_SCALE,
        )

/**
 * 组装一次页面转场。
 *
 * [targetZIndex] 必须让返回时上一页画在当前页**下面**，否则当前页向外退出时会被盖住。
 * 转场里只有目标页能带 zIndex，所以前进给正值、返回给负值就够了。
 *
 * `sizeTransform = null`：两个页面都是铺满的，没有尺寸变化，不需要尺寸动画。
 */
internal fun pageContentTransform(forward: Boolean, targetZIndex: Float): ContentTransform =
    ContentTransform(
        targetContentEnter = if (forward) foregroundEnter() else backgroundEnter(),
        initialContentExit = if (forward) backgroundExit() else foregroundExit(),
        targetContentZIndex = targetZIndex,
        sizeTransform = null,
    )

/**
 * 转场进度规格。进度线性映射到时间：手势拖动时进度就是时间，各条动画再各自套自己的
 * 缓动与延迟；点击切页也走同一条线，于是「拖到一半松手」和「点一下」逐帧一致。
 *
 * [fromFraction] 与 [toFraction] 用于收尾：手势松手时已经走完的部分不重复走一遍。
 */
internal fun pageProgressSpec(
    fromFraction: Float,
    toFraction: Float = 1f,
): FiniteAnimationSpec<Float> =
    tween(
        durationMillis = (abs(toFraction - fromFraction) * PAGE_FOREGROUND_MILLIS)
            .roundToInt()
            .coerceAtLeast(1),
        easing = LinearEasing,
    )
