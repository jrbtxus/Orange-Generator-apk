#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""安卓资源静态检查（不需要 Android SDK / Gradle）。

做两件事：

1. **XML 合法性**：所有 `android/app/src/main` 下的 XML 用 expat 解析一遍。
   别小看这一步 —— XML 注释里出现连续两个短横线（比如把 CSS 变量名 `--sky`
   写进注释）是**非法**的，aapt 会以
   `The string "--" is not permitted within comments` 让整个构建失败，
   而这个错只有真正编译资源时才暴露。

2. **资源引用**：把 `@drawable/... @mipmap/... @color/... @string/... @style/...`
   之类的引用和实际定义对一遍，抓错别字（比如 `@drawable/splash_art` 写错一个字母）。

   用法：python3 scripts/check-android-res.py
   退出码非 0 表示有问题。
"""

from __future__ import annotations

import re
import sys
import xml.parsers.expat
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MAIN = ROOT / "android/app/src/main"
RES = MAIN / "res"

REF_RE = re.compile(
    r"@(?!android:|null\b|\*)([a-z]+)/([A-Za-z0-9_.]+)"
)
# res/values 里的资源声明
DECL_RE = re.compile(
    r"<(color|string|style|dimen|bool|integer|array|string-array|integer-array|"
    r"plurals|attr|item|declare-styleable)\b[^>]*?name=\"([^\"]+)\"",
    re.S,
)
FILE_RES_DIR_RE = re.compile(r"^(drawable|mipmap|layout|xml|anim|animator|font|raw|menu)(-|$)")


def iter_xml() -> list[Path]:
    return sorted(p for p in MAIN.rglob("*.xml") if p.is_file())


def check_xml_wellformed(paths: list[Path]) -> list[str]:
    problems = []
    for path in paths:
        parser = xml.parsers.expat.ParserCreate()
        try:
            parser.ParseFile(open(path, "rb"))
        except xml.parsers.expat.ExpatError as exc:
            rel = path.relative_to(ROOT)
            problems.append(f"{rel}: XML 不合法 —— {exc}")
    return problems


def collect_definitions() -> dict[str, set[str]]:
    defined: dict[str, set[str]] = {}

    def add(kind: str, name: str) -> None:
        defined.setdefault(kind, set()).add(name)

    for entry in RES.iterdir():
        if not entry.is_dir():
            continue
        match = FILE_RES_DIR_RE.match(entry.name)
        if not match:
            continue
        kind = match.group(1)
        for f in entry.iterdir():
            if f.is_file() and f.suffix != ".xml" or (f.is_file() and f.suffix == ".xml"):
                add(kind, f.stem)

    for values in sorted((RES / "values").glob("*.xml")) if (RES / "values").is_dir() else []:
        text = values.read_text(encoding="utf-8")
        for kind, name in DECL_RE.findall(text):
            if kind == "item":
                # <item type="drawable" name="x"> 之类
                item_type = re.search(
                    r"<item\b[^>]*?type=\"([a-z]+)\"[^>]*?name=\"([^\"]+)\"", text
                )
                if item_type:
                    add(item_type.group(1), item_type.group(2))
                continue
            add(kind, name)
    return defined


def check_references(paths: list[Path], defined: dict[str, set[str]]) -> list[str]:
    problems = []
    for path in paths:
        rel = path.relative_to(ROOT)
        text = path.read_text(encoding="utf-8")
        for kind, name in REF_RE.findall(text):
            if kind in {"id", "string"} and kind == "id":
                continue
            if name.startswith("android:"):
                continue
            known = defined.get(kind)
            if known is None:
                # 未知类型（比如 @xml/、@array/ 之外的），只在明显是资源类型时报
                if kind in {"drawable", "mipmap", "color", "string", "style", "layout", "xml", "font", "array", "dimen", "bool", "integer", "anim", "animator", "menu", "raw"}:
                    problems.append(f"{rel}: 引用了 @{kind}/{name}，但仓库里没有这类资源")
                continue
            if name not in known:
                problems.append(
                    f"{rel}: 引用了 @{kind}/{name}，但没有找到定义"
                    f"（现有：{', '.join(sorted(known)[:8])}{'…' if len(known) > 8 else ''}）"
                )
    return problems


def main() -> int:
    if not MAIN.is_dir():
        print(f"× 找不到 {MAIN}")
        return 1

    paths = iter_xml()
    problems = check_xml_wellformed(paths)
    defined = collect_definitions()
    problems += check_references(paths, defined)

    kinds = ", ".join(f"{k}={len(v)}" for k, v in sorted(defined.items()))
    print(f"* 解析了 {len(paths)} 个 XML；已登记资源：{kinds}")

    if problems:
        print("\n发现问题：")
        for problem in problems:
            print(f"  × {problem}")
        return 1

    print("* 通过：XML 全部合法，资源引用都能对上")
    return 0


if __name__ == "__main__":
    sys.exit(main())
