#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Vaultix 应用图标 · 折纸 V 的几何生成与自校验（唯一真源）
==========================================================

本脚本是原创实现。几何判据（面积质心 / 左右对称 / 安全圈 / 越界采样）依据项目自己的
`.ai/issues/06-界面与交互-part2.md` 第 89 条，未复用任何第三方源码。

为什么必须有这个文件（2026-11-09）
----------------------------------
旧「徽章盾 + 金 V」的几何由 `build/sign/v5_stretch.py` 与 `v5_write.py` 生成，
但这两个脚本不在 git 里（build/ 被忽略）且已丢失 ⇒ 文件头注释里那句
「想再调比例就重跑脚本」成了死链，下一代只能手改顶点，而手改顶点正是 #89 明令禁止的。
本脚本把参数、生成、四条校验、XML 一致性检查全部收进版本控制，堵住这个接力断层。

用法
----
    python tools/icon/generate_launcher_icon.py --report   # 只打印四条校验报告
    python tools/icon/generate_launcher_icon.py --check    # 门禁：四条校验 + XML 一致性（默认）
    python tools/icon/generate_launcher_icon.py --write    # 重生成 foreground / monochrome 两个 XML

⚠️ 改几何只改下面的 LIGHT_POLY / INK_TRAP，然后依次跑 --write 与 --check。
   不要手改 XML 里的 pathData：--check 会把它们与本脚本产物逐字符比对。

设计约束（改动前必读）
----------------------
1. 画布 108x108dp，系统只保证中心 66dp 圆（半径 33）内可见；描边宽度的一半必须计入。
2. 左右对称是构造性成立的：右半由左半镜像生成，不做任何手工偏移。
3. 两半在中线处刻意重叠 1dp，防止折叠处露出背景色的缝。
4. 墨阱用「背景色实心 path」覆盖，不是 fillType=evenOdd 挖洞
   （Apple WWDC 2026：用独立实心形状表达层叠，别用带洞的复合路径）。
5. 单色层只有一种着色，折叠与墨阱退化为纯剪影：只画两半 V，不画墨阱。
   两半 pathData 与彩色层逐字符一致，保证普通图标与主题图标形状语义不错位。
"""

from __future__ import annotations

import argparse
import math
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "app" / "src" / "main" / "res"
FG_PATH = RES / "drawable" / "ic_launcher_foreground.xml"
MONO_PATH = RES / "drawable" / "ic_launcher_monochrome.xml"
COLORS_PATH = RES / "values" / "colors.xml"

CANVAS = 108.0
CENTER = 54.0
SAFE_R = 33.0
STROKE = 1.5

# 折纸 V 左半（受光面）顶点，顺时针。x 越过中线 1dp 与右半重叠，防止折缝露底。
# 笔画约 8 至 10dp；V 全是斜笔画，已按 optical correction 统一略收细。
LIGHT_POLY = [
    (37.0, 40.0),
    (47.5, 40.0),
    (55.0, 61.0),
    (55.0, 74.0),
    (51.5, 74.0),
]

# 内角功能性墨阱：把 V 内角「拉尖」，24dp 下笔画不糊成一坨。
INK_TRAP = [
    (50.8, 55.0),
    (57.2, 55.0),
    (54.0, 66.0),
]

REQUIRED_COLORS = (
    "ic_launcher_background",
    "ic_launcher_foreground_light",
    "ic_launcher_foreground_shade",
)


# ---------------------------------------------------------------------------
# 几何
# ---------------------------------------------------------------------------
def mirror(poly: list[tuple[float, float]]) -> list[tuple[float, float]]:
    """绕 x=CENTER 镜像；反转顶点顺序以保持顺时针。左右对称由此构造性成立。"""
    return [(2 * CENTER - x, y) for x, y in reversed(poly)]


def to_path(poly: list[tuple[float, float]]) -> str:
    head = "M%.2f,%.2f" % poly[0]
    tail = " ".join("L%.2f,%.2f" % p for p in poly[1:])
    return f"{head} {tail} Z"


def outline(poly: list[tuple[float, float]], step: int = 200) -> list[tuple[float, float]]:
    """稠密采样路径上的点（含各顶点）。step 越大质心越准。"""
    pts: list[tuple[float, float]] = []
    for i in range(len(poly)):
        x0, y0 = poly[i]
        x1, y1 = poly[(i + 1) % len(poly)]
        for k in range(step + 1):
            t = k / step
            pts.append((x0 + (x1 - x0) * t, y0 + (y1 - y0) * t))
    return pts


def centroid(poly: list[tuple[float, float]]) -> tuple[tuple[float, float], float]:
    """面积质心与面积。视觉居中的正确判据是面积质心，不是包围盒中心。"""
    area = cx = cy = 0.0
    for i in range(len(poly)):
        x0, y0 = poly[i]
        x1, y1 = poly[(i + 1) % len(poly)]
        cross = x0 * y1 - x1 * y0
        area += cross
        cx += (x0 + x1) * cross
        cy += (y0 + y1) * cross
    area *= 0.5
    return (cx / (6 * area), cy / (6 * area)), abs(area)


def inside(pt: tuple[float, float], poly: list[tuple[float, float]]) -> bool:
    """点在多边形内（ray casting）。"""
    x, y = pt
    hit = False
    j = len(poly) - 1
    for i in range(len(poly)):
        xi, yi = poly[i]
        xj, yj = poly[j]
        if (yi > y) != (yj > y) and x < (xj - xi) * (y - yi) / (yj - yi) + xi:
            hit = not hit
        j = i
    return hit


def polys() -> dict[str, list[tuple[float, float]]]:
    return {
        "light": LIGHT_POLY,
        "shade": mirror(LIGHT_POLY),
        "trap": INK_TRAP,
    }


def paths() -> dict[str, str]:
    return {name: to_path(poly) for name, poly in polys().items()}


# ---------------------------------------------------------------------------
# 四条校验
# ---------------------------------------------------------------------------
def verify() -> bool:
    p = polys()
    samples = {name: outline(poly) for name, poly in p.items()}
    all_pts = [pt for pts in samples.values() for pt in pts]
    results: list[tuple[str, str, bool]] = []

    # ① 安全圈：所有 path 采样点 + 描边一半，必须落在 r=33 内。
    rmax = max(math.hypot(x - CENTER, y - CENTER) for x, y in all_pts)
    reached = rmax + STROKE / 2
    results.append((
        "安全圈",
        "rmax %.3f + stroke/2 %.2f = %.3f <= %.1f  余量 %.3f"
        % (rmax, STROKE / 2, reached, SAFE_R, SAFE_R - reached),
        reached <= SAFE_R,
    ))

    # ② 面积质心 / 左右对称：两半加权质心的 x 必须精确落在中线上。
    weighted_x = weighted_y = total_area = 0.0
    for name in ("light", "shade"):
        (cx, cy), area = centroid(p[name])
        weighted_x += cx * area
        weighted_y += cy * area
        total_area += area
    gx, gy = weighted_x / total_area, weighted_y / total_area
    results.append((
        "面积质心(左右对称)",
        "(%.5f, %.5f)  x 对中线偏差 %+.6f  |  y 偏差 %+.3f（V 固有形状，不强制归零）"
        % (gx, gy, gx - CENTER, gy - CENTER),
        abs(gx - CENTER) < 1e-3,
    ))

    # ③ 左右留白差：V 到画布左右边缘的空隙必须相等。
    xs = [pt[0] for name in ("light", "shade") for pt in p[name]]
    gap_left, gap_right = min(xs), CANVAS - max(xs)
    results.append((
        "左右留白差",
        "左 %.3f  右 %.3f  差 %+.6f（构造性对称）"
        % (gap_left, gap_right, gap_left - gap_right),
        abs(gap_left - gap_right) < 1e-3,
    ))

    # ④ 越界采样：全部点落在画布内；且墨阱确实切到了 V（否则等于没画）。
    oob = sum(1 for x, y in all_pts if not (0 <= x <= CANVAS and 0 <= y <= CANVAS))
    trap_hits = sum(
        1 for pt in samples["trap"]
        if inside(pt, p["light"]) or inside(pt, p["shade"])
    )
    results.append((
        "越界采样",
        "画布越界 %d / %d 点；墨阱落在 V 内 %d 点（须 > 0）"
        % (oob, len(all_pts), trap_hits),
        oob == 0 and trap_hits > 0,
    ))

    print("=" * 72)
    print("Vaultix 折纸 V · 几何四条校验")
    print("=" * 72)
    for name, detail, ok in results:
        print("  [%s] %s" % ("PASS" if ok else "FAIL", name))
        print("        %s" % detail)

    # 附带报告：小尺寸折算与中线重叠
    ys = [pt[1] for name in ("light", "shade") for pt in p[name]]
    height = max(ys) - min(ys)
    overlap = CENTER + 1.0 - (CENTER - 1.0)  # 左半到 55、右半到 53，重叠 2dp
    print("-" * 72)
    print("  笔画宽约 10.5dp(108 画布)  ->  48dp 实显 %.2fdp / 24dp %.2fdp"
          % (10.5 * 48 / 108, 10.5 * 24 / 108))
    print("  V 高 %.2fdp  ->  48dp %.2f / 24dp %.2f；中线重叠 %.1fdp 防折缝露底"
          % (height, height * 48 / 108, height * 24 / 108, overlap))
    print("=" * 72)
    return all(ok for _, _, ok in results)


# ---------------------------------------------------------------------------
# XML 模板
# ---------------------------------------------------------------------------
FG_TEMPLATE = """<?xml version="1.0" encoding="utf-8"?>
<!--
  Vaultix 应用图标 · 前景层（自适应图标的 foreground）

  图形：折纸 V（Folded V）。一条纸带沿中线折成 V，左半是受光面、右半是背光面，
  折缝即两档明度的分界；内角的功能性墨阱把负空间拉尖，保证 24dp 下不糊。

  本文件由 tools/icon/generate_launcher_icon.py 生成，请勿手改 pathData。
  要调几何只改脚本里的 LIGHT_POLY / INK_TRAP，再依次跑 write 与 check 模式。

  规范依据（2026-11-09）：
  - Android 自适应图标：108x108dp、中心 66dp 安全圈、外层 18dp 留给遮罩；clean edges，
    轮廓周围不得有遮罩或背景阴影；矢量优先。
  - 单色层由系统着色，故彩色层的折叠与墨阱在单色层退化为纯剪影（见 monochrome 文件头）。
  - 配色是现行低饱和蓝 #3B5BC4（对齐 VaultixBrandColor 的 primary），
    受光面 #FFFFFF、背光面 #B9C6F0；三档明度是本图形成立的前提。

  历史：旧的「徽章盾 + 金 V」经 V1 至 V5 五轮几何校准，已随该图形退役。
  原文归档在 Docs/progress/app-icon-geometry-history.md；两条纪律仍适用：
  (1) 内层一律由外层「等比缩放 + 质心对齐」生成，禁止手工拼曲线；
  (2) 缝宽四边相等与左右对称在几何上不可兼得，本项目选左右对称。
  参考 .ai/issues/06-界面与交互-part2.md 第 89 条。
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <!-- 受光面（左半）：明度最高，折纸层次的第一档 -->
    <path
        android:pathData="{light}"
        android:fillColor="@color/ic_launcher_foreground_light"
        android:strokeColor="@color/ic_launcher_foreground_light"
        android:strokeWidth="1.5"
        android:strokeLineJoin="round" />
    <!-- 背光面（右半）：由左半镜像生成，同色相但低一档明度 -->
    <path
        android:pathData="{shade}"
        android:fillColor="@color/ic_launcher_foreground_shade"
        android:strokeColor="@color/ic_launcher_foreground_shade"
        android:strokeWidth="1.5"
        android:strokeLineJoin="round" />
    <!-- 功能性墨阱：用背景色实心覆盖，不是 evenOdd 挖洞 -->
    <path
        android:pathData="{trap}"
        android:fillColor="@color/ic_launcher_background" />
</vector>
"""

MONO_TEMPLATE = """<?xml version="1.0" encoding="utf-8"?>
<!--
  Vaultix 应用图标 · 单色层（自适应图标的 monochrome，Android 13+ 主题图标）

  开启「主题图标」后系统丢掉背景与配色，只取这一层的形状（alpha）并按壁纸重新着色，
  所以这里只能是单一纯色剪影，任何渐变或多色都会被压平。

  与彩色前景的差异（有意为之）：单色层只有一种着色，折叠的两档明度与内角墨阱
  都没有意义，故只画两半 V、不画墨阱，且不使用 fillType=evenOdd。
  ⚠️ 两半 pathData 必须与 ic_launcher_foreground.xml 逐字符一致，否则普通图标
     与主题图标会错位。这一致性由生成脚本的 check 模式钉住。
  ⚠️ 填充色用白色只是「给个纯色」，实际渲染由系统着色覆盖；换黑也一样。
  ⚠️ 本注释里不能出现连续两个短横线（XML 注释的硬限制）。

  本文件由 tools/icon/generate_launcher_icon.py 生成，请勿手改 pathData。
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:pathData="{light}"
        android:fillColor="#FFFFFFFF"
        android:strokeColor="#FFFFFFFF"
        android:strokeWidth="1.5"
        android:strokeLineJoin="round" />
    <path
        android:pathData="{shade}"
        android:fillColor="#FFFFFFFF"
        android:strokeColor="#FFFFFFFF"
        android:strokeWidth="1.5"
        android:strokeLineJoin="round" />
</vector>
"""


# ---------------------------------------------------------------------------
# 写入与门禁
# ---------------------------------------------------------------------------
def write_xml() -> None:
    p = paths()
    FG_PATH.write_text(FG_TEMPLATE.format(**p), encoding="utf-8")
    MONO_PATH.write_text(MONO_TEMPLATE.format(**p), encoding="utf-8")
    print("已写入 %s" % FG_PATH.relative_to(ROOT))
    print("已写入 %s" % MONO_PATH.relative_to(ROOT))


def check_xml() -> bool:
    p = paths()
    expected_fg = [p["light"], p["shade"], p["trap"]]
    expected_mono = [p["light"], p["shade"]]
    checks: list[tuple[str, str, bool]] = []

    for label, path, expected in (
        ("foreground pathData", FG_PATH, expected_fg),
        ("monochrome pathData", MONO_PATH, expected_mono),
    ):
        if not path.exists():
            checks.append((label, "文件不存在", False))
            continue
        text = path.read_text(encoding="utf-8")
        actual = re.findall(r'android:pathData="([^"]+)"', text)
        checks.append((
            label,
            "实际 %d 条 / 期望 %d 条，逐字符一致" % (len(actual), len(expected)),
            actual == expected,
        ))

    mono_text = MONO_PATH.read_text(encoding="utf-8") if MONO_PATH.exists() else ""
    checks.append((
        "monochrome 无 evenOdd 挖洞",
        "fillType=evenOdd 出现 %d 次" % mono_text.count('fillType="evenOdd"'),
        'fillType="evenOdd"' not in mono_text,
    ))

    colors = COLORS_PATH.read_text(encoding="utf-8") if COLORS_PATH.exists() else ""
    for name in REQUIRED_COLORS:
        checks.append((
            "颜色 %s" % name,
            "在 colors.xml 中已定义",
            ('name="%s"' % name) in colors,
        ))

    # 两层 pathData 逐字符一致（形状语义不错位）
    fg_text = FG_PATH.read_text(encoding="utf-8") if FG_PATH.exists() else ""
    fg_paths = re.findall(r'android:pathData="([^"]+)"', fg_text)
    mono_paths = re.findall(r'android:pathData="([^"]+)"', mono_text)
    checks.append((
        "彩色层 / 单色层前两条 path 一致",
        "保证普通图标与主题图标形状语义一致",
        fg_paths[:2] == mono_paths[:2] and len(mono_paths) == 2,
    ))

    # XML 注释内容里禁止连续两个短横线（XML 规范硬限制）。
    # 旧 monochrome 文件头明确警告过这一条，本轮生成时仍踩过一次，故固化为门禁。
    for label, text in (("foreground", fg_text), ("monochrome", mono_text)):
        bad = [
            m.group(1)
            for m in re.finditer(r"<!--(.*?)-->", text, re.S)
            if "--" in m.group(1)
        ]
        checks.append((
            "%s 注释无非法短横线" % label,
            "含连续两个短横线的注释块 %d 个" % len(bad),
            not bad,
        ))

    print("=" * 72)
    print("XML 一致性门禁")
    print("=" * 72)
    for name, detail, ok in checks:
        print("  [%s] %s" % ("PASS" if ok else "FAIL", name))
        print("        %s" % detail)
    print("=" * 72)
    return all(ok for _, _, ok in checks)


def main() -> int:
    ap = argparse.ArgumentParser(description="Vaultix 折纸 V 图标生成与自校验")
    group = ap.add_mutually_exclusive_group()
    group.add_argument("--write", action="store_true", help="重生成两个 drawable XML")
    group.add_argument("--report", action="store_true", help="只打印四条几何校验")
    group.add_argument("--check", action="store_true", help="四条校验 + XML 一致性（默认）")
    args = ap.parse_args()

    geom_ok = verify()
    if args.write:
        write_xml()
        return 0 if geom_ok else 1
    if args.report:
        return 0 if geom_ok else 1
    xml_ok = check_xml()
    return 0 if (geom_ok and xml_ok) else 1


if __name__ == "__main__":
    sys.exit(main())
