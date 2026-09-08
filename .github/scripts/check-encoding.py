#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
源文件编码门禁：检查仓库内源码/文档是否出现中文乱码（mojibake）。

背景（2026-09-08 真实事故）：
    BitwardenAuthRepository.kt 的中文注释曾整文件被写坏——UTF-8 字节被按 GBK
    误读后再次存盘，且三字节序列的尾字节被替换成 0x3F（'?'），属于**有损**损坏。
    由于编译与测试都不关心注释内容，损坏静默进入仓库，直到人工阅读才发现。

本脚本拦两类问题：
    1. 文件不是合法 UTF-8（多半是被按 GBK 存盘）；
    2. 文件是合法 UTF-8，但内容是「UTF-8 被按 GBK 误读」的乱码串。

判据（关键，避免把正常中文误判为乱码）：
    乱码串 S 按 GBK 编码回字节、再按 UTF-8 解码得到 F，
    仅当 F **全部**由汉字 / 中文标点 / 全角字符组成才认定是乱码。
    原理：正常中文的 GBK 次字节通常 > 0xBF（A1-FE），按 UTF-8 解读要么非法、
    要么落在 U+0080-U+07FF（拉丁/希腊等），不会落在 CJK 区；只有真正的
    「UTF-8 被按 GBK 误读」才会还原出汉字。
    （朴素的「能 encode gbk 再 decode utf-8 就算乱码」会把「为」「状态」等
    正常中文误判，本脚本已规避。）

用法：
    python3 .github/scripts/check-encoding.py            # 检查仓库根
    python3 .github/scripts/check-encoding.py <dir>       # 检查指定目录
退出码：0 = 无问题；1 = 发现问题（CI 会失败）。
"""

import os
import re
import sys

# vendored 第三方参考源码 / 构建产物 / 版本库元信息：不参与检查
EXCLUDE_DIRS = {
    "reference", "build", ".git", ".gradle", ".idea", "gradle",
    ".workbuddy", "captures", ".kotlin", "node_modules",
}
EXTS = {
    ".kt", ".kts", ".java", ".xml", ".md", ".yml", ".yaml",
    ".json", ".pro", ".toml", ".gradle",
}

SEG_RE = re.compile(r"[^\x00-\x7f]+")


def is_cjk_text(s: str) -> bool:
    """是否全部由汉字 / 中文标点 / 全角字符 / 中英混排常用标点组成。"""
    if not s:
        return False
    for ch in s:
        o = ord(ch)
        if 0x4E00 <= o <= 0x9FFF:          # CJK 统一汉字
            continue
        if 0x3000 <= o <= 0x303F:          # 中文标点
            continue
        if 0xFF00 <= o <= 0xFFEF:          # 全角
            continue
        if o in (0x2014, 0x2013, 0x2018, 0x2019, 0x201C, 0x201D):  # 破折号/引号
            continue
        return False
    return True


def try_fix_segment(seg: str):
    """尝试把乱码段还原为中文；无法还原或判据不成立返回 None。"""
    if len(seg) < 2:
        return None
    try:
        raw = seg.encode("gbk")
    except UnicodeEncodeError:
        return None
    try:
        fixed = raw.decode("utf-8")
    except UnicodeDecodeError:
        return None
    if not is_cjk_text(fixed):
        return None
    return fixed


def scan(root):
    non_utf8 = []
    mojibake = {}
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in EXCLUDE_DIRS]
        for fn in filenames:
            if os.path.splitext(fn)[1].lower() not in EXTS:
                continue
            path = os.path.join(dirpath, fn)
            try:
                with open(path, "rb") as f:
                    raw = f.read()
            except OSError:
                continue

            # 允许 UTF-8 BOM，但不鼓励（Gradle/Kotlin 能处理，此处不报错）
            body = raw[3:] if raw.startswith(b"\xef\xbb\xbf") else raw

            try:
                text = body.decode("utf-8")
            except UnicodeDecodeError as e:
                non_utf8.append((os.path.relpath(path, root), str(e)))
                continue

            if "�" in text:
                non_utf8.append(
                    (os.path.relpath(path, root),
                     f"含 {text.count(chr(0xFFFD))} 个 U+FFFD 替换字符（有损转换痕迹）")
                )
                continue

            samples = []
            total = 0
            for m in SEG_RE.finditer(text):
                fixed = try_fix_segment(m.group(0))
                if fixed is not None:
                    total += 1
                    if len(samples) < 3:
                        samples.append((m.group(0), fixed))
            if total:
                mojibake[os.path.relpath(path, root)] = (total, samples)

    return non_utf8, mojibake


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    root = os.path.abspath(root)
    non_utf8, mojibake = scan(root)

    if not non_utf8 and not mojibake:
        print(f"编码检查通过：{root} 未发现乱码或非 UTF-8 文件。")
        return 0

    print("::error title=encoding-gate::发现源文件编码问题")
    if non_utf8:
        print("\n[1] 非 UTF-8 / 含替换字符的文件（多半被按 GBK 存盘或有损转换）：")
        for rel, why in non_utf8:
            print(f"    - {rel}\n        {why}")
    if mojibake:
        print("\n[2] 疑似乱码（UTF-8 被按 GBK 误读）：")
        for rel, (total, samples) in sorted(mojibake.items(), key=lambda kv: -kv[1][0]):
            print(f"    - {rel}  ({total} 段)")
            for seg, fixed in samples:
                print(f"        {seg[:46]!r}  ->  {fixed[:46]!r}")
    print("\n修复建议：从 git 历史中检出该文件的完好版本（git checkout <rev> -- <path>），"
          "再重新应用改动；不要用「按 GBK 打开再另存为 UTF-8」——已丢失的字节无法还原。")
    return 1


if __name__ == "__main__":
    sys.exit(main())
