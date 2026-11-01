#!/usr/bin/env python3
"""检查每一个引用了 `${{ secrets.X }}`（经由 shell 里的 `${X}`）的步骤，是否真的注入了该变量。

为什么要这个（2026-11-01，同一句教训已经踩了两遍）：
  · 2026-09-08：`Decode signing keystore` 忘了 `env:` ⇒ `${SIGNING_STORE_PASSWORD}` 恒空
    ⇒ 回落 `android` ⇒ 校验必失败 ⇒ 每次都用一次性密钥（包盖不上）。
  · 2026-11-01（CI #341）：`Build Debug APK` 又忘了 `env:` ⇒ 同上 ⇒ 而且这次更糟，
    它打了一条**假的** `Keystore store password wrong`，与上一步的「解码通过」互相矛盾。

单看 bash 永远正确：`${VAR:-android}` 语法完美、`bash -n` 也过。错在「这个变量从来不会被赋值」，
这是 YAML 层面的缺陷，只有跨文件比对才看得出来。

规则：workflow 里凡出现 `${NAME}` / `${NAME:-...}` 形式、且 NAME 以 `SIGNING_` 开头
（即预期来自 Secret）的，所在 step 必须显式声明 `env.NAME` 或 `env` 里引用该 secret；
或 job/全局 `env` 里给了它。
"""
import os
import re
import sys

import yaml

SECRET_VAR = re.compile(r'\$\{(SIGNING_[A-Z0-9_]+)\b')
SECRET_REF = re.compile(r"secrets\.(SIGNING_[A-Z0-9_]+)")


def iter_steps(doc):
    for job_name, job in (doc.get('jobs') or {}).items():
        # 注意 collect_env 返回的是 set（变量名集合），这里必须用 |=，不能 update(dict 语义)
        env = set(collect_env(doc.get('env'))) | set(collect_env(job.get('env')))
        for step in job.get('steps') or []:
            yield job_name, step, env


def collect_env(env_block):
    out = set()
    for k, v in (env_block or {}).items():
        out.add(k)
        if isinstance(v, str):
            m = SECRET_REF.search(v)
            if m:
                out.add(m.group(1))
    return out


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else '.'
    wf_dir = os.path.join(root, '.github', 'workflows')
    problems = []
    checked = 0
    for name in sorted(os.listdir(wf_dir)):
        if not name.endswith(('.yml', '.yaml')):
            continue
        path = os.path.join(wf_dir, name)
        with open(path, encoding='utf-8') as f:
            doc = yaml.safe_load(f) or {}
        for job_name, step, outer_env in iter_steps(doc):
            run = step.get('run') or ''
            if not run:
                continue
            step_env = set((step.get('env') or {}).keys()) | set(collect_env(step.get('env')))
            available = outer_env | step_env
            for var in sorted(set(SECRET_VAR.findall(run))):
                checked += 1
                if var not in available:
                    problems.append(
                        '%s · %s · step「%s」引用了 ${%s} 但没有注入它 '
                        '（预期 env: %s: ${{ secrets.%s }}）'
                        % (name, job_name, step.get('name', '<未命名>'), var, var, var))

    if problems:
        print('❌ 有步骤使用了从未注入的 Secret 变量（会静默回落默认值）：')
        for p in problems:
            print('   - ' + p)
        print('\n判据：`${VAR:-default}` 语法永远合法，`bash -n` 永远通过 —— '
              '「这个变量是否会被赋值」只能跨 YAML 比对。')
        return 1

    print('✅ %d 处 ${SIGNING_*} 引用均已注入。' % checked)
    return 0


if __name__ == '__main__':
    sys.exit(main())
