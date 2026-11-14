#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""自检 check_release_naming.py：拿**真实 workflow** 做变异，断言门禁分别判红/判绿。

=== 为什么必须自检 ===

真仓库改完之后只会得到一条绿，而这条绿**无法区分**「检查在工作、确认名字是短的」
和「检查根本没在检查」。所以这里把真实 `ci-debug.yml` / `release.yml` 复制出来，
逐个改坏一个点，要求门禁红 —— 哪天有人「简化」了检测逻辑，某个用例会先红。

与 `selftest_release_assets.py` 的差别：那边造的是最小 demo YAML，
这边刻意用**真实文件**做字符串变异 —— 命名门禁钉的是具体模板，用最小样本
会漏掉「步骤名 / 改名语句形状变了」这类静默失效（#218.7 的假绿灯）。

=== 用例 ===

| 用例 | 改坏什么 | 期望 |
|---|---|---|
| `baseline` | 无（真实短名） | rc=0 |
| `long_apk` | preview APK 改回长名 + `-preview` 后缀 | rc=1 |
| `no_sha_apk` | preview APK 去掉 7 位提交 ⇒ 丢身份 | rc=1 |
| `vaultix_prefix` | preview 标题加回冗余 `Vaultix ` 前缀 | rc=1 |
| `no_version` | preview 标题删掉版本变量（#218 硬约束） | rc=1 |
| `no_submit_row` | 正文表删「提交」行 ⇒ 短名无从对上构建 | rc=1 |
| `long_stable_apk` | stable APK 改成堆版本 + SHA | rc=1 |
| `stable_suffix` | stable 标题加 ` · Stable` | rc=1 |
| `two_vars` | 一个模板塞两个变量 | rc=1 |
| `renamed_step` | 把步骤改名 ⇒ 门禁不得静默通过 | rc=1 |
| `missing_workflow` | 删掉 release.yml ⇒ 门禁不得静默通过 | rc=1 |

⚠️ 每个变异都用 `sub()` 断言「原文里确实有这个子串」；替换不生效 ⇒ 自检直接失败，
不给「看起来全绿」的机会（与 8.11「假绿灯比没有绿灯更贵」同源）。
"""
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
CHECKER = os.path.join(os.path.dirname(HERE), 'check_release_naming.py')
ROOT = os.path.abspath(os.path.join(HERE, '..', '..', '..'))
WF = os.path.join(ROOT, '.github', 'workflows')


def sub(text, old, new, label):
    """替换并断言替换真的发生（防止文本漂移后用例变成空操作）。"""
    if old not in text:
        raise AssertionError('变异 %s 失效：原文里找不到 %r' % (label, old))
    if old == new:
        raise AssertionError('变异 %s 是空操作' % label)
    return text.replace(old, new, 1)


def drop_line(text, needle, label):
    if needle not in text:
        raise AssertionError('变异 %s 失效：原文里找不到 %r' % (label, needle))
    return '\n'.join(ln for ln in text.splitlines() if needle not in ln) + '\n'


def make_repo(root, debug_text, release_text):
    d = os.path.join(root, '.github', 'workflows')
    os.makedirs(d, exist_ok=True)
    with open(os.path.join(d, 'ci-debug.yml'), 'w', encoding='utf-8') as f:
        f.write(debug_text)
    if release_text is not None:
        with open(os.path.join(d, 'release.yml'), 'w', encoding='utf-8') as f:
            f.write(release_text)


def run(root):
    p = subprocess.run([sys.executable, CHECKER, root],
                       capture_output=True, text=True)
    return p.returncode, (p.stdout or '') + (p.stderr or '')


def build_cases(debug, release):
    """返回 (name, debug_text, release_text, want_rc, why)。"""
    cases = [('baseline', debug, release, 0, '真实短名 ⇒ 必须判绿')]

    cases.append((
        'long_apk',
        sub(debug, '/Vaultix-${short_sha}.apk"',
            '/Vaultix-v${version_name}-preview.apk"', 'long_apk'),
        release, 1, 'preview APK 改回长名 + `-preview` 后缀 ⇒ 必须判红'))

    cases.append((
        'no_sha_apk',
        sub(debug, '/Vaultix-${short_sha}.apk"', '/Vaultix-preview.apk"', 'no_sha_apk'),
        release, 1, 'preview APK 无提交 SHA ⇒ 多个预览包互相覆盖、身份丢失 ⇒ 必须判红'))

    cases.append((
        'vaultix_prefix',
        sub(debug, 'name: "v${{ steps.apk_meta.outputs.version_name }} · Preview"',
            'name: "Vaultix v${{ steps.apk_meta.outputs.version_name }} · Preview"',
            'vaultix_prefix'),
        release, 1, '标题加回冗余 `Vaultix ` 前缀 ⇒ 必须判红'))

    cases.append((
        'no_version',
        sub(debug, 'name: "v${{ steps.apk_meta.outputs.version_name }} · Preview"',
            'name: "Preview"', 'no_version'),
        release, 1, '标题删掉版本变量（#218：只留 SHA 等于没身份）⇒ 必须判红'))

    cases.append((
        'no_submit_row',
        drop_line(debug, 'steps.apk_meta.outputs.short_sha', 'no_submit_row'),
        release, 1, '正文表删「提交」行 ⇒ 短名无从对上构建 ⇒ 必须判红'))

    cases.append((
        'long_stable_apk',
        debug,
        sub(release, '/Vaultix-v${{ steps.meta.outputs.version }}.apk"',
            '/Vaultix-v${{ steps.meta.outputs.version }}-preview-${{ github.sha }}.apk"',
            'long_stable_apk'),
        1, 'stable APK 堆版本 + 长 SHA ⇒ 必须判红'))

    cases.append((
        'stable_suffix',
        debug,
        sub(release, 'name: "Vaultix v${{ steps.meta.outputs.version }}"',
            'name: "Vaultix v${{ steps.meta.outputs.version }} · Stable"', 'stable_suffix'),
        1, 'stable 标题加多余后缀 ⇒ 必须判红（稳定侧保持既有短名）'))

    cases.append((
        'two_vars',
        sub(debug, '/Vaultix-${short_sha}.apk"',
            '/Vaultix-${version_name}-${short_sha}.apk"', 'two_vars'),
        release, 1, '一个模板塞两个变量 ⇒ 结构上重新堆长名 ⇒ 必须判红'))

    cases.append((
        'renamed_step',
        sub(debug, 'name: Collect debug APK metadata',
            'name: Collect APK metadata (renamed)', 'renamed_step'),
        release, 1, '步骤改名 ⇒ 门禁不得静默通过（#218.7 假绿灯）'))

    cases.append((
        'missing_workflow', debug, None, 1, '缺 release.yml ⇒ 门禁不得静默通过'))

    return cases


def main():
    with open(os.path.join(WF, 'ci-debug.yml'), encoding='utf-8') as f:
        debug = f.read()
    with open(os.path.join(WF, 'release.yml'), encoding='utf-8') as f:
        release = f.read()

    tmp = tempfile.mkdtemp(prefix='release-naming-')
    fails = []
    try:
        cases = build_cases(debug, release)
        for name, dtext, rtext, want, why in cases:
            root = os.path.join(tmp, name)
            make_repo(root, dtext, rtext)
            rc, out = run(root)
            if rc != want:
                fails.append('%s：期望 rc=%d，实际 rc=%d（%s）\n%s'
                             % (name, want, rc, why, out.strip()[:900]))
            else:
                print('OK   %-17s rc=%d  %s' % (name, rc, why))

        if fails:
            print()
            print('FAIL check_release_naming 自检')
            for f in fails:
                print('  - ' + f)
            return 1
        print()
        print('OK   %d 个用例全部符合预期' % len(cases))
        return 0
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == '__main__':
    sys.exit(main())
