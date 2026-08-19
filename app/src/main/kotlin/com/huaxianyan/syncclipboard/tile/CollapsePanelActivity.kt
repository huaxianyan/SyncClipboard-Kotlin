package com.huaxianyan.syncclipboard.tile

import android.app.Activity
import android.os.Bundle

/** 通过系统支持的 Activity 启动路径收起快速设置面板，不显示任何界面。 */
class CollapsePanelActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
