#!/usr/bin/env python3
"""自检 check_release_assets.py：造四种仓库形态，断言它分别判红/判绿。

=== 为什么必须自检 ===

真仓库修好之后只会得到一条绿，而这条绿**无法区分**
「检查在工作、确认产物清单是好的」和「检查根本没在检查」。
所以这里把**真实事故的 YAML 原样复刻**成 broken/，要求门禁判红 ——
如果哪天有人"简化"了检测逻辑，这个用例会先红。

四种形态：

| 用例 | 形态 | 期望 |
|---|---|---|
| `glued` | `files: \\|` 粘在 `body` 末行末尾（**2026-11-01 真实事故**） | rc=1 |
| `missing` | 干脆没写 `files` | rc=1 |
| `no_fail_flag` | `files` 正常，但没有 `fail_on_unmatched_files` | rc=1 |
| `good` | 键齐全、缩进正确、带 fail 标记 | rc=0 |

=== 变异测试记录（故意改坏实现，看自检是否判红）===

  1. 去掉「键被吞进正文」那条正则                     → 判红 ✓（glued 变绿）
  2. 只看 `files` 是否存在、不管缩进语义               → 判红 ✓（glued 的 files 被吞，键不在，仍被抓）
  3. 把 `fail_on_unmatched_files` 检查删掉             → 判红 ✓（no_fail_flag 变绿）
  4. 把所有 action 步骤都当 gh-release（放宽匹配）      → 不受影响，但 good 仍须绿
"""
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
CHECKER = os.path.join(os.path.dirname(HERE), 'check_release_assets.py')

# ★ 真实事故的复刻：body 最后一行末尾粘着 `          files: |`。
#   YAML 解析器完全接受（它只是更长的一行文本），但 `with.files` 这个键不存在。
GLUED = """\
name: demo
on: [push]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - name: Publish Debug APK to preview Release
        uses: softprops/action-gh-release@v3
        with:
          tag_name: preview
          body: |
            **开发预览**

            > 安装前请先导出备份。          files: |
            app/build/outputs/apk/*/debug/*.apk
          fail_on_unmatched_files: true
          draft: false
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
"""

MISSING = """\
name: demo
on: [push]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - name: Publish
        uses: softprops/action-gh-release@v3
        with:
          tag_name: preview
          body: |
            只有正文，没有 files
          fail_on_unmatched_files: true
"""

NO_FAIL_FLAG = """\
name: demo
on: [push]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - name: Publish
        uses: softprops/action-gh-release@v3
        with:
          tag_name: preview
          body: |
            正文正常。
          files: |
            app/build/outputs/apk/*/debug/*.apk
"""

GOOD = """\
name: demo
on: [push]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - name: Publish Debug APK to preview Release
        uses: softprops/action-gh-release@v3
        with:
          tag_name: preview
          target_commitish: ${{ github.sha }}
          body: |
            **开发预览**

            > 安装前请先导出备份。
          files: |
            app/build/outputs/apk/*/debug/*.apk
            app/build/outputs/apk/*/debug/*.APK
          fail_on_unmatched_files: true
          draft: false
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
"""


def make_repo(root, workflow):
    os.makedirs(os.path.join(root, '.github', 'workflows'), exist_ok=True)
    with open(os.path.join(root, '.github', 'workflows', 'demo.yml'), 'w',
              encoding='utf-8') as f:
        f.write(workflow)


def run(root):
    p = subprocess.run([sys.executable, CHECKER, root],
                       capture_output=True, text=True)
    return p.returncode, (p.stdout or '') + (p.stderr or '')


def main():
    tmp = tempfile.mkdtemp(prefix='release-assets-')
    fails = []
    try:
        cases = [
            ('glued', GLUED, 1, 'files 粘在 body 末行 ⇒ 必须判红（真实事故复刻）'),
            ('missing', MISSING, 1, '没有 files 键 ⇒ 必须判红'),
            ('no_fail_flag', NO_FAIL_FLAG, 1,
             '缺 fail_on_unmatched_files ⇒ 必须判红（否则 glob 空时安静发空包）'),
            ('good', GOOD, 0, '键齐全 ⇒ 必须判绿'),
        ]
        for name, wf, want, why in cases:
            root = os.path.join(tmp, name)
            make_repo(root, wf)
            rc, out = run(root)
            if rc != want:
                fails.append('%s：期望 rc=%d，实际 rc=%d（%s）\n%s'
                             % (name, want, rc, why, out.strip()[:800]))
            else:
                print('OK   %-13s rc=%d  %s' % (name, rc, why))

        if fails:
            print()
            print('FAIL check_release_assets 自检')
            for f in fails:
                print('  - ' + f)
            return 1
        print()
        print('OK   4 个用例全部符合预期')
        return 0
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == '__main__':
    sys.exit(main())
