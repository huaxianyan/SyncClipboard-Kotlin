"""本地冒烟检查：核对两个仓库构建出的 APK 身份，并汇总单元测试结果。

主体应用在本仓库，系统扩展在独立仓库（默认 `../syncclipboard-extension`，
可用环境变量 `SYNCCLIPBOARD_EXTENSION_DIR` 覆盖）。缺哪个包就跳过哪个，不算失败。

Android SDK 位置依次取 `ANDROID_HOME`、`ANDROID_SDK_ROOT`、本仓库
`local.properties` 的 `sdk.dir`，再在 `build-tools` 下选版本号最大的一个。
"""

import glob
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

KEYS = (
    "package:",
    "sdkVersion",
    "targetSdkVersion",
    "uses-permission: name='android.permission.ACCESS_LOCAL_NETWORK'",
)

EXTENSION_DIR = os.environ.get("SYNCCLIPBOARD_EXTENSION_DIR", "../syncclipboard-extension")


def android_sdk():
    for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(key):
            return os.environ[key]
    if os.path.isfile("local.properties"):
        with open("local.properties", encoding="utf-8") as handle:
            for line in handle:
                if line.startswith("sdk.dir="):
                    return line.split("=", 1)[1].strip().replace("\\:", ":").replace("\\\\", "\\")
    sys.exit("找不到 Android SDK：ANDROID_HOME / ANDROID_SDK_ROOT 未设置，local.properties 也没有 sdk.dir")


def aapt2():
    versions = glob.glob(os.path.join(android_sdk(), "build-tools", "*"))
    if not versions:
        sys.exit("Android SDK 里没有 build-tools")
    # 版本号排序：按数值段比较，避免 9 排在 36 之后。
    def key(path):
        return [int(part) for part in re.findall(r"\d+", os.path.basename(path))]

    tool = "aapt2.exe" if os.name == "nt" else "aapt2"
    return os.path.join(max(versions, key=key), tool)


APKS = (
    "app/build/outputs/apk/debug/app-debug.apk",
    os.path.join(EXTENSION_DIR, "app/build/outputs/apk/debug/app-debug.apk"),
)

aapt = aapt2()
for apk in APKS:
    if not os.path.isfile(apk):
        print("== (未构建)", apk)
        continue
    out = subprocess.run([aapt, "dump", "badging", apk], capture_output=True, text=True).stdout
    print("==", apk)
    for line in out.splitlines():
        if line.startswith(KEYS):
            print("  ", line)

tests = failures = 0
for path in glob.glob("app/build/test-results/testDebugUnitTest/*.xml"):
    root = ET.parse(path).getroot()
    tests += int(root.get("tests"))
    failures += int(root.get("failures")) + int(root.get("errors"))
print("unit tests: %d, failures: %d" % (tests, failures))
sys.exit(1 if failures else 0)
