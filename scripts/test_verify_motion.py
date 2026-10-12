#!/usr/bin/env python3
"""预测性返回门禁的夹具检查，临时文件由 TemporaryDirectory 清理。

每条夹具都对应一个真实的失败模式，并且要求门禁在声明缺失或条件被反转时**拒绝**，
而不是只看正常输入能通过。注释与字面量里出现同名标识符不算接入，单独有一条夹具守住。
"""

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import verify_motion as verifier


MANIFEST_TEMPLATE = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application%s>
        <activity android:name=".ui.MainActivity"%s />
    </application>
</manifest>
"""

GRADLE_TEMPLATE = (
    "android {\n"
    "    compileSdk = 37\n"
    "    defaultConfig {\n"
    "        targetSdk = %d\n"
    "    }\n"
    "}\n"
)

OPT_IN = ' android:enableOnBackInvokedCallback="true"'

MAIN_ACTIVITY = (
    "import androidx.activity.compose.PredictiveBackHandler\n"
    "import androidx.compose.animation.core.SeekableTransitionState\n"
    "private fun app() {\n"
    "    PredictiveBackHandler(enabled = true) { event -> event.collect { } }\n"
    "}\n"
    "private val state = SeekableTransitionState(AppPage.HOME)\n"
)


class MotionGateTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.manifest = self.root / "AndroidManifest.xml"
        self.gradle = self.root / "build.gradle.kts"
        self.sources = self.root / "kotlin"
        self.sources.mkdir()
        self.write_manifest()
        self.write_gradle(37)
        self.write_source("PageNavigation.kt", "internal enum class AppPage { HOME }\n")
        self.write_source("MainActivity.kt", MAIN_ACTIVITY)

    def write_manifest(self, application=OPT_IN, activity=""):
        self.manifest.write_text(MANIFEST_TEMPLATE % (application, activity), encoding="utf-8")

    def write_gradle(self, target_sdk):
        self.gradle.write_text(GRADLE_TEMPLATE % target_sdk, encoding="utf-8")

    def write_source(self, name, text):
        (self.sources / name).write_text(text, encoding="utf-8")

    def errors(self):
        return verifier.check(self.manifest, self.gradle, self.sources)

    def test_valid_fixture_passes(self):
        self.assertEqual([], self.errors())

    def test_missing_opt_in_is_rejected(self):
        self.write_manifest(application="")
        self.assertTrue(self.errors())

    def test_explicit_opt_out_is_rejected(self):
        self.write_manifest(application=' android:enableOnBackInvokedCallback="false"')
        self.assertTrue(self.errors())

    def test_reversed_boolean_is_rejected(self):
        # 门禁要求的是「值为 true」，不是「出现过这个属性」。
        self.write_manifest(application=' android:enableOnBackInvokedCallback="TRUE"')
        self.assertTrue(self.errors())

    def test_low_target_sdk_is_rejected(self):
        self.write_gradle(35)
        self.assertTrue(self.errors())

    def test_screen_orientation_is_rejected(self):
        self.write_manifest(activity=' android:screenOrientation="portrait"')
        self.assertTrue(self.errors())

    def test_non_resizable_is_rejected(self):
        self.write_manifest(activity=' android:resizeableActivity="false"')
        self.assertTrue(self.errors())

    def test_legacy_back_override_is_rejected(self):
        self.write_source("Legacy.kt", "class Legacy {\n    override fun onBackPressed() { }\n}\n")
        self.assertTrue(self.errors())

    def test_keycode_back_is_rejected(self):
        self.write_source(
            "Legacy.kt",
            "class Legacy {\n    fun handle(code: Int) = code == KeyEvent.KEYCODE_BACK\n}\n")
        self.assertTrue(self.errors())

    def test_legacy_compose_handler_is_rejected(self):
        self.write_source("Legacy.kt", "private fun app() { BackHandler { } }\n")
        self.assertTrue(self.errors())

    def test_duplicate_page_model_is_rejected(self):
        self.write_source("Other.kt", "internal enum class AppPage { OTHER }\n")
        self.assertTrue(self.errors())

    def test_missing_gesture_handler_is_rejected(self):
        self.write_source("MainActivity.kt", "private val s = SeekableTransitionState(1)\n")
        self.assertTrue(self.errors())

    def test_duplicate_gesture_handler_is_rejected(self):
        self.write_source(
            "Other.kt",
            "private fun other() { PredictiveBackHandler(enabled = true) { it.collect { } } }\n")
        self.assertTrue(self.errors())

    def test_comments_and_literals_do_not_trip_the_gate(self):
        self.write_source("Comments.kt", (
            "// BackHandler 与 KEYCODE_BACK 只是注释里的说明\n"
            "/* override fun onBackPressed() { } */\n"
            "private val quote = '\"'\n"
            "private fun trim(text: String) = text.trim('\"')\n"
        ))
        self.assertEqual([], self.errors())


if __name__ == "__main__":
    unittest.main()
