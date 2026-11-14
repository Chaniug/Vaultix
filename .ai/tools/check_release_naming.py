#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""发布产物 / Release 标题的「短命名」门禁（#292）。

约定真源：`.ai/conventions/8.12-发布产物命名.md`（为什么这样命名、边界在哪）。
本文件只实现**机器可校验的那部分**，不复制约定正文 —— 同一判据存两份必然漂移
（#291 / #160 同源老毛病）。门禁报红时**不是**让你加白名单，而是回到约定文档
重新拍板：形状变了，判据就得跟着变，两处一起改。

## 为什么会需要它

2026-11-14 用户反馈：`Vaultix-v0.13.1-dev-fcb77f9-preview.apk` 太长，
Release 标题 `Vaultix v0.13.1-dev-fcb77f9 · Preview` 也偏长，
「以后这种名字短一点，简洁一点」。

但 #218 是同一位用户 2026-11-01 拍的硬约束：**Release 标题必须带版本 + 短 SHA**
（当时的问题正是标题只有一长串 SHA、在 Releases 列表里被截断、看不出哪个版本）。
⇒ 能砍的只有：APK 名里重复堆的版本段 + `-preview` 后缀、标题里冗余的产品名前缀。
身份信息由 **Release 页**承载（标题 + 版本/提交/构建/签名表 + `preview` tag），
APK 名只留「产品名 + 7 位提交」，下载后可与页面「提交」列一一对上。

## 四条规则

1. 精确钉住 4 个模板（preview APK / preview 标题 / stable APK / stable 标题）。
2. 模板里变量占位符 ≤ 1 个 ⇒ 从结构上挡住「版本 + SHA + 渠道」重新堆叠。
3. 按最坏样本渲染后：APK 名 ≤ 24 字符、Release 标题 ≤ 34 字符。
4. #218 身份不退化：preview 标题必须含版本变量、正文表必须含提交（SHA）行。

「找不到被检查的步骤 / 找不到改名语句」一律报红 —— 静默放过等于门禁失效
（#218.7：改了门禁却没触发 CI，给的是**假的绿灯**，比没有门禁更贵）。
"""
import os
import re
import sys

import yaml

# ---- 唯一真源：4 个模板的期望形状 ------------------------------------------
# 改这里之前，先改 `.ai/conventions/8.12-发布产物命名.md` 并同步自测用例。
EXPECT = {
    'preview_apk': 'Vaultix-${short_sha}.apk',
    'preview_name': 'v${{ steps.apk_meta.outputs.version_name }} · Preview',
    'stable_apk': 'Vaultix-v${{ steps.meta.outputs.version }}.apk',
    'stable_name': 'Vaultix v${{ steps.meta.outputs.version }}',
}

# 渲染长度预算（按最坏样本估）：长名 45 / 38 字符，短名 18 / 29 字符。
MAX_APK_LEN = 24
MAX_NAME_LEN = 34

# `${{ ... }}` 优先，`${ ... }` 其次；用于「变量个数」与「渲染长度」两处。
PLACEHOLDER_RE = re.compile(r'\$\{\{.*?\}\}|\$\{[^}]*\}')
DST_RE = re.compile(r'_dst="\$\(dirname "\$_src"\)/([^"\n]+)"')
DST_RE_STABLE = DST_RE  # 两个 workflow 的改名语句形状一致

PREVIEW_META_STEP = 'Collect debug APK metadata'
PREVIEW_PUBLISH_STEP = 'Publish Debug APK to preview Release'
STABLE_META_STEP = 'Collect APK + SHA-256'
STABLE_PUBLISH_STEP = 'Publish Stable Release'

# 提交身份：正文表「提交」行引用的是这个输出；删掉它 = 回到 #218 之前的无身份状态。
SUBMIT_REF = 'steps.apk_meta.outputs.short_sha'


def _load(root, name):
    path = os.path.join(root, '.github', 'workflows', name)
    if not os.path.isfile(path):
        return None, f'缺少 {name}'
    try:
        with open(path, encoding='utf-8') as fh:
            doc = yaml.safe_load(fh)
    except Exception as exc:                      # noqa: BLE001 - 解析失败本身就该红
        return None, f'{name} YAML 解析失败：{exc}'
    if not isinstance(doc, dict):
        return None, f'{name} 顶层不是 mapping'
    return doc, None


def _step(doc, step_name):
    for job in (doc.get('jobs') or {}).values():
        if not isinstance(job, dict):
            continue
        for step in job.get('steps') or []:
            if isinstance(step, dict) and step.get('name') == step_name:
                return step
    return None


def _sample(expr):
    """把占位符换成最坏情况样本，用来算渲染后的名字长度。"""
    if 'version_name' in expr:
        return '0.13.1-dev-fcb77f9'
    if 'short_sha' in expr:
        return 'fcb77f9'
    if 'version' in expr:
        return '0.13.1'
    return 'X' * 16                              # 未知变量按较长处理，别给绕过留口子


def check(root):
    """返回问题列表；空列表 = 通过。"""
    problems = []

    debug, err = _load(root, 'ci-debug.yml')
    if err:
        return [err]
    release, err = _load(root, 'release.yml')
    if err:
        return [err]

    # ---- ① 找出 4 个步骤，缺一即红 ------------------------------------------
    p_meta = _step(debug, PREVIEW_META_STEP)
    p_pub = _step(debug, PREVIEW_PUBLISH_STEP)
    s_meta = _step(release, STABLE_META_STEP)
    s_pub = _step(release, STABLE_PUBLISH_STEP)
    for label, step, where in (
        (PREVIEW_META_STEP, p_meta, 'ci-debug.yml'),
        (PREVIEW_PUBLISH_STEP, p_pub, 'ci-debug.yml'),
        (STABLE_META_STEP, s_meta, 'release.yml'),
        (STABLE_PUBLISH_STEP, s_pub, 'release.yml'),
    ):
        if step is None:
            problems.append(f'{where}: 找不到步骤 `{label}`（改名 / 改标题后步骤被重命名，门禁会静默失效）')

    # ---- ② 两个 APK 改名模板 -------------------------------------------------
    apk_tpls = {}
    for key, step, where in (
        ('preview_apk', p_meta, 'ci-debug.yml'),
        ('stable_apk', s_meta, 'release.yml'),
    ):
        if not step:
            continue
        run = str(step.get('run') or '')
        m = DST_RE.search(run) or DST_RE_STABLE.search(run)
        if not m:
            problems.append(f'{where} › {step.get("name")}: 找不到 `_dst="$(dirname "$_src")/…"` 改名语句')
            continue
        apk_tpls[key] = m.group(1)
        if m.group(1) != EXPECT[key]:
            problems.append(
                f'{where} › {step.get("name")}: APK 名模板不符 ——\n'
                f'      实际 `{m.group(1)}`\n'
                f'      期望 `{EXPECT[key]}`（约定见 8.12；改形状要连约定与自测一起改）'
            )
        # 长名残留：版本 + preview 后缀同时出现在文件名里，正是本轮要删掉的东西。
        if key == 'preview_apk' and '-preview.apk' in m.group(1):
            problems.append(
                f'{where} › {step.get("name")}: APK 名仍带 `-preview` 后缀 —— '
                f'tag 是 `preview`、标题有 `· Preview`、Release 有 prerelease 徽标，三处已足够'
            )

    # ---- ③ 两个 Release 标题 -------------------------------------------------
    name_tpls = {}
    for key, step, where in (
        ('preview_name', p_pub, 'ci-debug.yml'),
        ('stable_name', s_pub, 'release.yml'),
    ):
        if not step:
            continue
        with_block = step.get('with') or {}
        actual = str(with_block.get('name') or '')
        name_tpls[key] = actual
        if actual != EXPECT[key]:
            problems.append(
                f'{where} › {step.get("name")}: Release 标题模板不符 ——\n'
                f'      实际 `{actual}`\n'
                f'      期望 `{EXPECT[key]}`'
            )

    # ---- ④ #218 身份不退化 ---------------------------------------------------
    p_name = name_tpls.get('preview_name', '')
    if 'version_name' not in p_name:
        problems.append(
            'ci-debug.yml › Publish Debug APK to preview Release: 标题里没有版本变量 —— '
            '#218 判据「能被用户验证的才叫发布物的身份」，只留 SHA 会在列表里被截断、看不出版本'
        )
    p_body = str((p_pub or {}).get('with', {}).get('body') or '')
    if SUBMIT_REF not in p_body:
        problems.append(
            'ci-debug.yml › Publish Debug APK to preview Release: 正文表缺「提交」行 '
            f'（应引用 `{SUBMIT_REF}`）—— APK 名短到只剩 7 位 SHA，页面必须能对上这次构建'
        )

    # ---- ⑤ 结构预算：变量数 + 渲染长度 ---------------------------------------
    for key, tpl in list(apk_tpls.items()) + list(name_tpls.items()):
        holders = PLACEHOLDER_RE.findall(tpl)
        if len(holders) > 1:
            problems.append(
                f'{key}: 模板里有 {len(holders)} 个变量（{holders}）—— '
                f'短命名的结构判据是「一个模板只带一个变量」，别把版本 / SHA / 渠道重新堆回去'
            )
        rendered = PLACEHOLDER_RE.sub(lambda m: _sample(m.group(0)), tpl)
        limit = MAX_APK_LEN if key.endswith('_apk') else MAX_NAME_LEN
        if len(rendered) > limit:
            problems.append(
                f'{key}: 渲染后 `{rendered}` 共 {len(rendered)} 字符 > 上限 {limit} —— '
                f'约定是「以后这种名字短一点」，长名请回到 8.12 重新拍板'
            )

    return problems


def main(argv):
    root = argv[1] if len(argv) > 1 else os.getcwd()
    problems = check(root)
    if problems:
        print('::error title=release naming::发布命名检查未通过')
        for p in problems:
            print('  ✗', p)
        return 1
    print('✅ 发布命名检查通过（4 个模板 · 变量数 ≤ 1 · 渲染长度达标 · #218 身份在位）')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
