package com.neko7ina.syncclipboard.ui

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.TweenSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页面转场时序的回归用例。
 *
 * 这些不变式来自预测性返回真实返工过的一次问题：把普通点击切页的交叉淡化直接交给手势寻址，
 * 手势停在中段时旧页已经淡出、新页还没显现，两页都发白。判据是「中段的进度上两页都不透明」，
 * 所以进入淡化必须很短，前景页的退出淡化必须留到转场后段。
 *
 * 这里只锁时序关系，不锁具体数字；调参可以，调到违反关系就会被拒绝。
 */
class PageMotionTest {

    @Test
    fun `手势中段两页都保持不透明`() {
        assertTrue(
            "进入淡化要在一开始就完成，新页才来得及变实",
            PAGE_ENTER_FADE_MILLIS <= PAGE_FOREGROUND_MILLIS / 3,
        )
        assertTrue(
            "前景页退出淡化的延迟必须落在转场后段，中段两页才不会同时发白",
            PAGE_FOREGROUND_EXIT_FADE_DELAY_MILLIS >= PAGE_FOREGROUND_MILLIS * 2 / 3,
        )
    }

    @Test
    fun `退出淡化在转场结束前完成`() {
        assertTrue(
            PAGE_FOREGROUND_EXIT_FADE_DELAY_MILLIS + PAGE_EXIT_FADE_MILLIS
                <= PAGE_FOREGROUND_MILLIS,
        )
        assertTrue(
            PAGE_BACKGROUND_EXIT_FADE_DELAY_MILLIS + PAGE_EXIT_FADE_MILLIS
                <= PAGE_BACKGROUND_MILLIS,
        )
    }

    @Test
    fun `背景页位移比前景页短且只走一小段`() {
        assertTrue(PAGE_BACKGROUND_MILLIS < PAGE_FOREGROUND_MILLIS)
        assertTrue(PAGE_BACKGROUND_OFFSET_DIVISOR > 1)
        assertTrue(PAGE_BACKGROUND_SCALE < 1f)
    }

    @Test
    fun `缩放只做轻微变化`() {
        // 页面整体缩放幅度过大会露出后面的背景，看起来像「页面浮起来」。
        for (scale in listOf(
            PAGE_FOREGROUND_ENTER_SCALE,
            PAGE_FOREGROUND_EXIT_SCALE,
            PAGE_BACKGROUND_SCALE,
        )) {
            assertTrue("缩放 $scale 超出了轻微变化", scale in 0.85f..1f)
        }
        assertTrue(PAGE_FOREGROUND_EXIT_SCALE < PAGE_BACKGROUND_SCALE)
    }

    @Test
    fun `收尾时长按剩余进度折算`() {
        // 松手时已经走完的部分不再重复走一遍：全程 300 ms，走了一半就只剩一半。
        assertEquals(PAGE_FOREGROUND_MILLIS, durationOf(pageProgressSpec(0f)))
        assertEquals(PAGE_FOREGROUND_MILLIS / 2, durationOf(pageProgressSpec(0.5f)))
        assertEquals(1, durationOf(pageProgressSpec(1f)))
        assertEquals(PAGE_FOREGROUND_MILLIS / 2, durationOf(pageProgressSpec(0.5f, 0f)))
        assertEquals(PAGE_FOREGROUND_MILLIS / 2, durationOf(pageProgressSpec(0.25f, 0.75f)))
    }

    private fun durationOf(spec: FiniteAnimationSpec<Float>): Int =
        (spec as TweenSpec<Float>).durationMillis
}
