#!/usr/bin/env python3
"""生成离线品牌图标（`app/src/main/res/drawable/brand_*.xml` + `BrandIconCatalog.kt`）。

## 为什么要有这个脚本

图标是**数据**：229 个 XML 手写不现实，而"只入库产物、不留脚本"会让下次加图标时
只能靠猜。⇒ 清单（`slugs.txt`）、品牌色（`colors.json`）与生成逻辑都入库，产物可重现。

## 用法

```bash
python3 tools/brand-icons/generate.py            # 需要能访问 jsdelivr / api.iconify.design
python3 tools/brand-icons/generate.py --offline  # 只用 build/brand-icons/svg 下的缓存
```

## 输出

1. `app/src/main/res/drawable/brand_<slug>.xml` —— 24×24 VectorDrawable，白色单色路径；
2. `app/src/main/java/io/vaultix/vaultix/ui/common/BrandIconCatalog.kt` —— slug → (drawable, 品牌色)。

## 数据来源与许可

- **simple-icons**（CC0-1.0）：主来源，能对上的一律用它（单 path，最干净）。
- **Font Awesome Free 6 / brands**（CC BY 4.0）：补齐 simple-icons 因商标原因下掉的品牌
  （microsoft / amazon / linkedin / twitter / slack / java / golang …）。多 path，等比缩放居中。
- 商标归各品牌所有，仅用于识别用户自己保存的站点。详见仓库根 `NOTICE`。

## 体积纪律

「不要让 app 体积膨胀太多」是硬前提 ⇒ **只打精选子集**（当前 229 个 ≈ 358 KB 源码）。
不要改成"全量 3467 个"：那是 4–6 MB，收益只是长尾站点多一个标志。
"""

import json
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
DIR = ROOT / "tools" / "brand-icons"
CACHE = ROOT / "build" / "brand-icons"
DRAWABLE = ROOT / "app" / "src" / "main" / "res" / "drawable"
CATALOG = ROOT / "app" / "src" / "main" / "java" / "io" / "vaultix" / "vaultix" / "ui" / "common" / "BrandIconCatalog.kt"

# 固定版本：用 latest 会让每次生成产出不同的产物。
SI_VERSION = "16.36.0"
SI_ICON = "https://fastly.jsdelivr.net/npm/simple-icons@{v}/icons/{slug}.svg"
ICONIFY = "https://api.iconify.design/fa6-brands.json?icons={names}"

# jsdelivr 的镜像：单一域名会偶发连不上（实测 cdn 时好时坏），逐个降级重试。
# ⚠️ 换镜像只影响**下载**，产物由版本号冻结 ⇒ 与镜像无关。
MIRRORS = ("https://fastly.jsdelivr.net", "https://gcore.jsdelivr.net", "https://cdn.jsdelivr.net")

# Font Awesome 没有品牌色（simple-icons 才有）；colors.json 里已经含这 14 个，此处不再单列。
PATHD = re.compile(r'<path[^>]*?\sd="([^"]+)"')
SAFE = re.compile(r"[^a-z0-9]")
OFFLINE = "--offline" in sys.argv


def try_fetch(url: str) -> bytes | None:
    """下载，失败返回 None（按 jsdelivr 镜像逐个降级重试）。"""
    candidates = [url]
    for mirror in MIRRORS[1:]:
        if url.startswith(MIRRORS[0]):
            candidates.append(url.replace(MIRRORS[0], mirror, 1))
    for candidate in candidates:
        for _ in range(2):
            out = subprocess.run(["curl", "-sfL", "--max-time", "90", candidate],
                                 capture_output=True)
            if out.returncode == 0 and out.stdout:
                return out.stdout
    return None


def wanted_slugs() -> list[str]:
    seen, out = set(), []
    for token in (DIR / "slugs.txt").read_text(encoding="utf-8").split():
        slug = token.strip().lower()
        if slug and slug not in seen:
            seen.add(slug)
            out.append(slug)
    return out


def simple_icon_svg(slug: str) -> bytes | None:
    """取一个 simple-icons 的 SVG（带本地缓存，避免每次重跑都重新下载）。"""
    cached = CACHE / "svg" / f"{slug}.svg"
    if cached.exists() and cached.stat().st_size > 80:
        return cached.read_bytes()
    if OFFLINE:
        return None
    data = try_fetch(SI_ICON.format(v=SI_VERSION, slug=slug))
    if data:
        cached.parent.mkdir(parents=True, exist_ok=True)
        cached.write_bytes(data)
    return data


def fa_bodies(slugs: list[str]) -> dict[str, dict]:
    if not slugs:
        return {}
    cached = CACHE / "fa.json"
    if cached.exists():
        return json.loads(cached.read_text(encoding="utf-8"))
    if OFFLINE:
        return {}
    raw = try_fetch(ICONIFY.format(names=",".join(slugs)))
    if not raw:
        return {}
    icons = json.loads(raw).get("icons", {})
    cached.parent.mkdir(parents=True, exist_ok=True)
    cached.write_text(json.dumps(icons), encoding="utf-8")
    return icons


def emit(slug: str, paths: list[str], group: dict | None = None) -> int:
    name = "brand_" + SAFE.sub("_", slug)
    lines = []
    if group:
        lines.append(
            '    <group android:translateX="{tx:.4f}" android:translateY="{ty:.4f}"\n'
            '        android:scaleX="{s:.6f}" android:scaleY="{s:.6f}">'.format(**group)
        )
        indent = "        "
    else:
        indent = "    "
    for d in paths:
        lines.append(f'{indent}<path android:fillColor="#FFFFFFFF" android:pathData="{d}" />')
    if group:
        lines.append("    </group>")
    xml = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<!-- 品牌图标：见 tools/brand-icons/generate.py 与 NOTICE。不要手改。 -->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="24dp"\n'
        '    android:height="24dp"\n'
        '    android:viewportWidth="24"\n'
        '    android:viewportHeight="24">\n'
        + "\n".join(lines) + "\n</vector>\n"
    )
    (DRAWABLE / f"{name}.xml").write_text(xml, encoding="utf-8")
    return len(xml.encode("utf-8"))


def main() -> None:
    DRAWABLE.mkdir(parents=True, exist_ok=True)
    colors = json.loads((DIR / "colors.json").read_text(encoding="utf-8"))

    wanted = wanted_slugs()
    entries: list[tuple[str, str, str]] = []
    pending_fa: list[str] = []
    total = 0

    for slug in wanted:
        svg = simple_icon_svg(slug)
        paths = PATHD.findall(svg.decode("utf-8")) if svg else []
        if not paths:
            pending_fa.append(slug)
            continue
        total += emit(slug, paths)
        entries.append((slug, "brand_" + SAFE.sub("_", slug), colors.get(slug, "5E5E5E")))

    missing: list[str] = []
    for slug, icon in fa_bodies(pending_fa).items():
        paths = PATHD.findall(icon["body"])
        if not paths:
            missing.append(slug)
            continue
        w, h = icon.get("width", 448), icon.get("height", 512)
        scale = 24.0 / max(w, h)
        group = dict(s=scale, tx=(24 - w * scale) / 2.0, ty=(24 - h * scale) / 2.0)
        total += emit(slug, paths, group)
        entries.append((slug, "brand_" + SAFE.sub("_", slug), colors.get(slug, "5E5E5E")))
    missing += [s for s in pending_fa if s not in {e[0] for e in entries} and s not in missing]

    if missing:
        print("两份来源都没有（回退首字母）：", " ".join(sorted(missing)))

    # 清掉"上一轮有、这一轮没有"的残留 XML：删清单里某个品牌后，文件不该留在 res 里。
    keep = {e[1] for e in entries}
    for old in DRAWABLE.glob("brand_*.xml"):
        if old.stem not in keep:
            old.unlink()

    entries.sort()
    write_catalog(entries)
    print(f"完成：{len(entries)} 个图标，{total / 1024:.1f} KB")


def write_catalog(entries: list[tuple[str, str, str]]) -> None:
    """按 46 条一组切块：detekt 的 `LongMethod` 上限 60 行，单块 48 行。"""
    chunks = [entries[i:i + 46] for i in range(0, len(entries), 46)]
    out = [CA_HEADER.format(count=len(entries))]
    out += [f"        putAll(chunk{i}())\n" for i in range(len(chunks))]
    out.append("    }\n\n")
    out.append(CA_BODY.format(capacity=len(entries)))
    for i, chunk in enumerate(chunks):
        out.append(f"\n    private fun chunk{i}(): Map<String, BrandIcon> = mapOf(\n")
        for slug, res, color in chunk:
            out.append(f'        "{slug}" to BrandIcon(R.drawable.{res}, 0xFF{color.upper()}),\n')
        out.append("    )\n")
    out.append("}\n")
    CATALOG.write_text("".join(out), encoding="utf-8")


CA_HEADER = '''/*
 * Vaultix — app:ui:common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * ---------------------------------------------------------------------------
 * **离线品牌图标目录**（`app/src/main/res/drawable/brand_*.xml` 的索引）。
 *
 * ⚠️ 本文件由 `tools/brand-icons/generate.py` 生成，**不要手改**。
 *    要加图标：改 `tools/brand-icons/slugs.txt` 后重跑脚本 ——
 *    否则"文件在、索引里没有"= 永远不会被用到（不报错，只是静默不生效）。
 *
 * ## 图标从哪来
 *
 * | 来源 | 用途 | 许可 |
 * |---|---|---|
 * | simple-icons v16.36.0 | 主来源 | CC0-1.0 |
 * | Font Awesome Free 6（brands） | 补齐 simple-icons 因商标下掉的那批 | CC BY 4.0 |
 *
 * 商标归各品牌所有，此处仅用于**识别用户自己保存的登录站点**（与浏览器 favicon 同性质）。
 * 详见仓库根 `NOTICE`。
 *
 * ## 为什么是精选子集而不是全集
 *
 * 「不要让 app 体积膨胀太多」是硬前提：simple-icons 全集 3467 个 ≈ 4–6 MB，
 * 这里是 {count} 个常用站点，覆盖绝大多数密码条目。表外站点仍走网络图标或首字母兜底 ——
 * **不影响功能，只少一个标志**。
 * ---------------------------------------------------------------------------
 */

// 品牌色是**数据**（{count} 条 ARGB 字面量），逐个提成常量只会把表拆散、更难核对。
@file:Suppress("MagicNumber")

package io.vaultix.vaultix.ui.common

import io.vaultix.vaultix.R

/** 一个离线品牌图标：[drawableRes] 是白色单色路径，[color] 是品牌色（ARGB，用来画圆形底衬）。 */
internal class BrandIcon(
    val drawableRes: Int,
    val color: Long,
)

/**
 * 品牌 slug → 离线图标（只读表）。
 *
 * slug 就是 simple-icons 的文件名（如 `github`、`google`、`playstation`）。
 * 域名到 slug 的换算**不在这里**（见 [brandSlugForHost]）—— 两者分开才能各自单测：
 * 一个是"包里有啥"，一个是"这个域名像谁"。
 */
internal object BrandIconCatalog {{

    private val icons: Map<String, BrandIcon> = buildMap(CAPACITY) {{
'''

CA_BODY = '''    /** 查图标；null = 包里没有这个品牌（**不是错误**，调用方回退）。 */
    fun of(slug: String): BrandIcon? = icons[slug]

    /**
     * 全部已知 slug，**按长度降序**（供 [brandSlugForHost] 做前缀匹配）。
     *
     * ⚠️ 必须最长优先：否则 `microsoft` 会抢走 `microsoftazure` 这类更具体的前缀。
     * 这条约束是结构性的，不依赖当前数据长什么样。
     */
    val slugsLongestFirst: List<String> by lazy {{
        icons.keys.sortedByDescending {{ it.length }}
    }}

    /** 图标总数（单测用它拦住"生成跑了一半 / 资源被误删"）。 */
    val size: Int get() = icons.size

    private const val CAPACITY = {capacity}
'''


if __name__ == "__main__":
    main()
