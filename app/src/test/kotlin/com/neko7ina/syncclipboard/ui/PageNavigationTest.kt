package com.neko7ina.syncclipboard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页面层级判定的回归用例。
 *
 * 转场方向、顶部栏返回箭头的可见性、返回手势的启用条件都由这几个判定派生，
 * 一旦某个页面的层级或次序写错，表现是「返回时页面往前翻」这种很难复现的问题。
 */
class PageNavigationTest {

    @Test
    fun `同级页面按次序前进`() {
        assertTrue(AppPage.TEXT_SYNC_HISTORY.isForwardFrom(AppPage.HOME))
        assertTrue(AppPage.SETTINGS.isForwardFrom(AppPage.HOME))
        assertTrue(AppPage.SETTINGS.isForwardFrom(AppPage.TEXT_SYNC_HISTORY))
    }

    @Test
    fun `同级页面反向切换是后退`() {
        assertFalse(AppPage.HOME.isForwardFrom(AppPage.TEXT_SYNC_HISTORY))
        assertFalse(AppPage.TEXT_SYNC_HISTORY.isForwardFrom(AppPage.SETTINGS))
    }

    @Test
    fun `层级优先于次序`() {
        // 自动同步记录的 order 比首页小，但层级更高，进入它仍然是前进。
        // 判定写成「先比 order」的话，这里会反过来，返回时页面会朝错误的方向退。
        assertTrue(AppPage.AUTOMATIC_SYNC_EVENTS.isForwardFrom(AppPage.HOME))
        assertFalse(AppPage.HOME.isForwardFrom(AppPage.AUTOMATIC_SYNC_EVENTS))
    }

    @Test
    fun `同一页不算前进也不算后退`() {
        for (page in AppPage.entries) {
            assertFalse(page.isForwardFrom(page))
        }
    }

    @Test
    fun `二级页面返回上一级`() {
        assertEquals(AppPage.HOME, AppPage.AUTOMATIC_SYNC_EVENTS.parentPage)
    }

    @Test
    fun `一级页面不接管返回`() {
        assertNull(AppPage.HOME.parentPage)
        assertNull(AppPage.TEXT_SYNC_HISTORY.parentPage)
        assertNull(AppPage.SETTINGS.parentPage)
    }

    @Test
    fun `二级页面的上一级本身是根页面且层级更低`() {
        for (page in AppPage.entries) {
            val parent = page.parentPage ?: continue
            assertNull("${page.name} 的上一级 ${parent.name} 自己也应该不接管返回", parent.parentPage)
            assertTrue("${page.name} 的层级应该高于 ${parent.name}", page.depth > parent.depth)
        }
    }

    @Test
    fun `返回二级页面的上一级走后退转场`() {
        // 顶部栏返回箭头的可见性与返回手势的启用条件都读 parentPage，转场方向读 isForwardFrom。
        // 两者必须一致：能被返回手势接管的页面，返回时不能走成前进。
        for (page in AppPage.entries) {
            val parent = page.parentPage ?: continue
            assertFalse(parent.isForwardFrom(page))
        }
    }

    @Test
    fun `同一层级的页面之间方向唯一`() {
        // order 重复会让两个页面互相判定为「不前进」，转场方向就没了确定答案。
        val roots = AppPage.entries.filter { it.depth == 0 }
        for (page in roots) {
            for (other in roots) {
                if (page != other) {
                    assertTrue(
                        "${page.name} 与 ${other.name} 之间必须有确定的前进方向",
                        page.isForwardFrom(other) != other.isForwardFrom(page),
                    )
                }
            }
        }
    }
}
