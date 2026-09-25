"""检查版本化的发布说明是否够短，并链接到详细记录。

GitHub Release 的正文由 ``docs/release-notes/v<版本>.md`` 加上工作流追加的
「## 构建信息」一节拼成。正文必须保持简短：一段概述、主要更新，以及指向详细记录的链接。
使用方式、真机验收和构建范围的细节属于 ``docs/architecture.md`` 等文档，不写进发布说明。

除文件参数外，也可以用 ``--tag vX.Y.Z`` 指定要检查的版本。

用法：
    python3 scripts/verify_release_notes.py docs/release-notes/v0.2.0.md
    python3 scripts/verify_release_notes.py --tag v0.2.0
"""

import argparse
import re
import sys
from pathlib import Path

SUMMARY_HEADING = "## 主要更新"
FORBIDDEN_HEADINGS = (
    "## 使用说明",
    "## 真机验收",
    "## 构建与兼容范围",
    "## 兼容边界",
)
IDENTITY_HEADINGS = ("## 版本信息", "## 构建信息")
# 指向仓库内某个标签下的文档，例如 /blob/v0.2.0/docs/architecture.md
DETAIL_LINK = re.compile(r"/blob/v[^/\s]+/")
MAX_LINES = 40
NOTES_DIR = Path("docs/release-notes")


def check(path: Path) -> list[str]:
    errors: list[str] = []
    text = path.read_text(encoding="utf-8")
    lines = text.splitlines()

    if not lines:
        return [f"{path}: file is empty"]
    if lines[0].startswith("# "):
        errors.append(
            f"{path}: must not start with an H1 heading; drop the product/version title line"
        )
    for heading in FORBIDDEN_HEADINGS:
        if any(line.strip() == heading for line in lines):
            errors.append(
                f"{path}: must not contain the section {heading!r}; details belong in docs/"
            )
    identity_hits = [line.strip() for line in lines if line.strip() in IDENTITY_HEADINGS]
    if len(identity_hits) > 1:
        errors.append(
            f"{path}: keep only one version/build identity section, found {identity_hits!r}"
        )
    if SUMMARY_HEADING not in text:
        errors.append(f"{path}: missing the summary section {SUMMARY_HEADING!r}")
    if not DETAIL_LINK.search(text):
        errors.append(
            f"{path}: missing a link to the detailed record "
            "(expected a /blob/v<tag>/... URL)"
        )
    if len(lines) > MAX_LINES:
        errors.append(f"{path}: {len(lines)} lines exceeds the {MAX_LINES}-line brevity limit")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("notes", nargs="*", help="release note files")
    parser.add_argument("--tag", help="release tag, for example v0.2.0")
    args = parser.parse_args()

    paths = [Path(item) for item in args.notes]
    if not paths and args.tag:
        paths = [NOTES_DIR / f"{args.tag}.md"]
    if not paths:
        print(
            "give at least one release note file, or a tag with --tag",
            file=sys.stderr,
        )
        return 1

    errors: list[str] = []
    for path in paths:
        if not path.is_file():
            errors.append(f"{path}: file does not exist")
            continue
        errors.extend(check(path))
    if errors:
        print("release note format check failed:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print(f"release note format contract verified across {len(paths)} file(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
