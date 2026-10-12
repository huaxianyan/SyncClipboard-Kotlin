#!/usr/bin/env python3
"""检查预测性返回的接入，以及 Android 17 的窗口可调整性声明。

三件事在源码层面就能判定，放到线上才发现代价很高：

1. **预测性返回已经接入。** 目标 SDK 达到 36 以后，系统在 API 36 及以上的设备上不再调用
   ``onBackPressed()``，也不再分发 ``KEYCODE_BACK``。清单必须显式声明
   ``android:enableOnBackInvokedCallback="true"``，否则 API 33 至 35 的设备仍然走旧路径。
   同时不允许出现 ``="false"``：那等于把退出开关重新打开。本项目全应用接入，没有需要单独
   排除的 Activity，所以接入名单就落在 ``<application>`` 这一处，不再维护第二份清单。
2. **页面返回手势真的消费了进度。** 只声明清单属性只能拿到系统动画；应用内部的返回栈还要
   用 ``PredictiveBackHandler`` 接管，否则拖动过程没有任何预览。
3. **没有残留的旧返回回调。** 不允许出现 ``override fun onBackPressed(`` 或对
   ``KEYCODE_BACK`` 的手工处理——它们在新路径下根本不会被调用，留着只会让人以为还在生效。

另外，API 37 起对 ``sw600dp`` 以上的设备强制可调整：``screenOrientation`` 与
``resizeableActivity="false"`` 都会被系统忽略，写了反而误导读者，所以一律拒绝。

用法（在仓库根目录执行）：
    python verify_motion.py
    python verify_motion.py --manifest app/src/main/AndroidManifest.xml

Python 3.7 以上，无第三方依赖。
"""

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ANDROID_NS = "http://schemas.android.com/apk/res/android"
ANDROID = "{%s}" % ANDROID_NS

DEFAULT_MANIFEST = Path("app/src/main/AndroidManifest.xml")
DEFAULT_GRADLE = Path("app/build.gradle.kts")
DEFAULT_SOURCES = Path("app/src/main/kotlin")

# 目标 SDK 达到这个值以后，预测性返回的行为变更才生效
MIN_TARGET_SDK = 36

BACK_ATTRIBUTE = ANDROID + "enableOnBackInvokedCallback"
BACK_ATTRIBUTE_NAME = "enableOnBackInvokedCallback"

# 新路径下不再被调用的旧返回入口
LEGACY_BACK_PATTERNS = (
    (re.compile(r"override\s+fun\s+onBackPressed\s*\("), "override fun onBackPressed("),
    (re.compile(r"\bKEYCODE_BACK\b"), "KEYCODE_BACK"),
)

# 页面归属、手势接入与寻址转场各自只应有一处定义
SINGLE_DEFINITION = (
    (re.compile(r"\benum\s+class\s+AppPage\b"), "AppPage 的枚举定义", 1),
    (re.compile(r"\bPredictiveBackHandler\b"), "PredictiveBackHandler 的接入点", 1),
    (re.compile(r"\bSeekableTransitionState\b"), "SeekableTransitionState 的接入点", 1),
)

# 不消费手势进度的旧式 Compose 返回处理
LEGACY_COMPOSE_HANDLER = re.compile(r"(?<!Predictive)\bBackHandler\b")


def blank(text, start, end):
    """把 [start, end) 换掉，只保留其中的换行，行号才不会整体位移。"""
    return "".join(char for char in text[start:end] if char == "\n")


def skip_quoted(text, start, quote):
    """返回 [start] 处引号之后的第一个位置，跳过转义字符。"""
    index = start + 1
    length = len(text)
    while index < length:
        if text[index] == "\\":
            index += 2
        elif text[index] == quote:
            return index + 1
        else:
            index += 1
    return length


def without_comments(text):
    """去掉注释与字面量，避免注释或字符串里的示例代码被当成真实接入。"""
    result = []
    index = 0
    length = len(text)
    while index < length:
        if text.startswith("//", index):
            end = text.find("\n", index)
            end = length if end == -1 else end
        elif text.startswith("/*", index):
            end = text.find("*/", index + 2)
            end = length if end == -1 else end + 2
        elif text.startswith('"""', index):
            end = text.find('"""', index + 3)
            end = length if end == -1 else end + 3
        elif text[index] in ('"', "'"):
            end = skip_quoted(text, index, text[index])
        else:
            result.append(text[index])
            index += 1
            continue
        result.append(blank(text, index, end))
        index = end
    return "".join(result)


def check_manifest(path):
    """清单必须打开预测性返回，并且不得声明会在 API 37 上被忽略的方向限制。"""
    root = ET.parse(str(path)).getroot()
    errors = []

    application = root.find("application")
    if application is None:
        return ["%s: 缺少 <application>" % path]

    declared = application.get(BACK_ATTRIBUTE)
    if declared != "true":
        errors.append(
            "%s: <application> 需要 android:%s=\"true\"，当前为 %r"
            % (path, BACK_ATTRIBUTE_NAME, declared))

    for element in root.iter():
        for attribute, value in element.attrib.items():
            if attribute.endswith(BACK_ATTRIBUTE_NAME) and value == "false":
                errors.append(
                    "%s: %s 上重新打开了退出开关（android:%s=\"false\"）"
                    % (path, element.get(ANDROID + "name", element.tag),
                       BACK_ATTRIBUTE_NAME))

    for element in root.iter("activity"):
        name = element.get(ANDROID + "name", "<未命名>")
        if element.get(ANDROID + "screenOrientation"):
            errors.append(
                "%s: %s 声明了 android:screenOrientation，API 37 起在 sw600dp 以上会被忽略"
                % (path, name))
        if element.get(ANDROID + "resizeableActivity") == "false":
            errors.append(
                "%s: %s 声明了 android:resizeableActivity=\"false\"，"
                "API 37 起在 sw600dp 以上会被忽略" % (path, name))
    return errors


def check_target_sdk(path, minimum=MIN_TARGET_SDK):
    """目标 SDK 低于门槛时，预测性返回的行为变更根本不会生效。"""
    match = re.search(r"^\s*targetSdk\s*=\s*(\d+)", path.read_text(encoding="utf-8"),
                      re.MULTILINE)
    if not match:
        return ["%s: 找不到 targetSdk 声明" % path]
    value = int(match.group(1))
    if value < minimum:
        return ["%s: targetSdk 为 %d，低于 %d，预测性返回的行为变更不会生效"
                % (path, value, minimum)]
    return []


def check_sources(directory):
    """旧返回回调必须缺席，页面模型与手势接入各自只留一个定义点。"""
    errors = []
    counts = {label: [] for _, label, _ in SINGLE_DEFINITION}
    for path in sorted(Path(directory).rglob("*.kt")):
        text = without_comments(path.read_text(encoding="utf-8"))
        for pattern, label, _ in SINGLE_DEFINITION:
            if pattern.search(text):
                counts[label].append(path)
        if LEGACY_COMPOSE_HANDLER.search(text):
            errors.append("%s: 出现 BackHandler，它不消费手势进度，应改用 PredictiveBackHandler"
                          % path)
        for number, line in enumerate(text.splitlines(), 1):
            for pattern, label in LEGACY_BACK_PATTERNS:
                if pattern.search(line):
                    errors.append("%s:%d: 出现 %s，新路径下不会被调用" % (path, number, label))
    for _, label, expected in SINGLE_DEFINITION:
        found = counts[label]
        if len(found) != expected:
            errors.append("%s 应只出现在 %d 处，实际出现在 %s"
                          % (label, expected, [str(item) for item in found] or "任何位置"))
    return errors


def check(manifest, gradle, sources):
    """汇总全部检查项，返回错误列表。空列表表示通过。"""
    return (check_manifest(manifest)
            + check_target_sdk(gradle)
            + check_sources(sources))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--gradle", type=Path, default=DEFAULT_GRADLE)
    parser.add_argument("--sources", type=Path, default=DEFAULT_SOURCES)
    args = parser.parse_args(argv)

    errors = check(args.manifest, args.gradle, args.sources)
    for error in errors:
        print(error, file=sys.stderr)
    if errors:
        print("预测性返回门禁未通过：%d 项" % len(errors), file=sys.stderr)
        return 1
    print("预测性返回门禁通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
