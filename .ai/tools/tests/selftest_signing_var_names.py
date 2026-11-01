#!/usr/bin/env python3
"""自检 check_signing_var_names.py：构造「断链」与「接通」两种仓库，断言它分别判红/判绿。

=== 为什么自检不能只跑真仓库 ===

真仓库现在是"接通"状态，所以直接跑它只会得到一条绿 —— 而这条绿**无法区分**
「检查在工作、确认接通了」和「检查根本没在检查」。这正是 #218.6 的教训：
**输入与被测物由同一个理解生成时，测试只证明自己和自己一致。**

所以这里造两个临时仓库（不碰真仓库）：
  · broken/  —— workflow 给 `SIGNING_*`，gradle 只读 `VAULTIX_*`  ⇒ 必须 rc=1
  · wired/   —— gradle 同时读两套                                  ⇒ 必须 rc=0
另外两个边界：
  · gradle 里**只有注释提到**变量名（没有真正读取）⇒ 仍须判红（剥注释那步是否生效）
  · 仓库根本不涉及签名变量 ⇒ rc=2（SKIP，而不是假装通过）

=== 变异测试记录（故意改坏实现，看自检是否判红）===

  1. workflow_names() 返回空集合（等于不检查）        → 判红 ✓
  2. 不剥注释（把注释里提到的名字当成"读过了"）        → 判红 ✓（broken 会变绿，被第 1 项抓到）
  3. 把 `_OPTS` 中间量也算成变量名                    → 判红 ✓（KS_OPTS 的值是 -P...，会假绿）
  4. 判据改成 `<=` 之类恒真                            → 判红 ✓
  5. 找不到 gradle 文件时返回 0 而不是 2               → 判红 ✓（第 3 项边界用例抓到）
"""
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
CHECKER = os.path.join(os.path.dirname(HERE), 'check_signing_var_names.py')

WORKFLOW = """\
name: demo
on: [push]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - name: Sign
        shell: bash
        run: |
          KS_OPTS="-Pandroid.injected.signing.store.file=$KS_FILE"
          KS_OPTS="$KS_OPTS -Pandroid.injected.signing.store.password=${SIGNING_STORE_PASSWORD:-android}"
          ./gradlew assembleDebug $KS_OPTS
        env:
          SIGNING_STORE_PASSWORD: ${{ secrets.SIGNING_STORE_PASSWORD }}
          SIGNING_KEY_ALIAS: ${{ secrets.SIGNING_KEY_ALIAS }}
"""

GRADLE_BROKEN = """\
android {
    fun signingValue(envKey: String, propKey: String): String? =
        System.getenv(envKey) ?: props.getProperty(propKey)
    val store = signingValue("VAULTIX_KEYSTORE_PATH", "storeFile")
    val pw = signingValue("VAULTIX_STORE_PASSWORD", "storePassword")
}
"""

GRADLE_WIRED = GRADLE_BROKEN + """\
// 也认 CI 的命名通道
val store2 = System.getenv("SIGNING_STORE_PASSWORD")
"""

GRADLE_COMMENT_ONLY = """\
android {
    // CI 会注入 SIGNING_STORE_PASSWORD 与 VAULTIX_KEYSTORE_PATH，这里只用后者。
    val store = System.getenv("VAULTIX_KEYSTORE_PATH")
}
"""


def make_repo(root, workflow, gradle):
    os.makedirs(os.path.join(root, '.github', 'workflows'), exist_ok=True)
    os.makedirs(os.path.join(root, 'app'), exist_ok=True)
    with open(os.path.join(root, '.github', 'workflows', 'demo.yml'), 'w',
              encoding='utf-8') as f:
        f.write(workflow)
    if gradle is not None:
        with open(os.path.join(root, 'app', 'build.gradle.kts'), 'w',
                  encoding='utf-8') as f:
            f.write(gradle)


def run(root):
    p = subprocess.run([sys.executable, CHECKER, root],
                       capture_output=True, text=True)
    return p.returncode, (p.stdout or '') + (p.stderr or '')


def main():
    tmp = tempfile.mkdtemp(prefix='signing-varnames-')
    fails = []
    try:
        cases = [
            ('broken', WORKFLOW, GRADLE_BROKEN, 1, '断链必须判红'),
            ('wired', WORKFLOW, GRADLE_WIRED, 0, '接通必须判绿'),
            ('comment_only', WORKFLOW, GRADLE_COMMENT_ONLY, 1,
             '只有注释提到变量名时仍须判红（否则"文档写过"会被当成"代码读过"）'),
            ('no_gradle', WORKFLOW, None, 2, '没有 gradle 脚本时必须 SKIP（rc=2），'
                                             '不能假装通过'),
        ]
        for name, wf, gr, want, why in cases:
            root = os.path.join(tmp, name)
            make_repo(root, wf, gr)
            rc, out = run(root)
            if rc != want:
                fails.append('%s：期望 rc=%d，实际 rc=%d（%s）\n%s'
                             % (name, want, rc, why, out.strip()[:600]))
            else:
                print('OK   %-13s rc=%d  %s' % (name, rc, why))

        if fails:
            print()
            print('FAIL check_signing_var_names 自检')
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
