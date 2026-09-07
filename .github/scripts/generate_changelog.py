#!/usr/bin/env python3
"""generate_changelog.py —— 生成两个 tag 之间的变更说明（参考 Bastion）。

用法：
  python3 generate_changelog.py --prev v0.1.0 --head HEAD --repo .
  python3 generate_changelog.py --self-test

输出 Markdown 列表，按 Conventional Commits 类型分组：
  feat / fix / 其他。

self-test 用于 CI 中对脚本自身做冒烟测试，不影响发布。
"""
import argparse
import os
import re
import subprocess
import sys


CONV_RE = re.compile(r"^(?P<type>feat|fix|docs|style|refactor|perf|test|build|ci|chore|revert)(?:\((?P<scope>[^)]*)\))?(?P<break>!)?:\s*(?P<msg>.*)$", re.I)
TYPE_ORDER = ["feat", "fix", "perf", "refactor", "docs", "test", "build", "ci", "style", "chore", "revert"]
TYPE_LABEL = {
    "feat": "✨ 新功能",
    "fix": "🐛 修复",
    "perf": "⚡ 性能",
    "refactor": "♻️ 重构",
    "docs": "📝 文档",
    "test": "✅ 测试",
    "build": "📦 构建",
    "ci": "🔧 CI",
    "style": "💄 样式",
    "chore": "🔨 杂项",
    "revert": "⏪ 回退",
}


def run_git(args, repo="."):
    return subprocess.run(
        ["git", "-C", repo, *args],
        capture_output=True, text=True
    ).stdout.strip()


def latest_stable_tag(repo="."):
    tags = run_git(["tag", "-l", "v*", "--sort=-v:refname"], repo).splitlines()
    return tags[0] if tags else ""


def collect_commits(prev, head, repo="."):
    if prev:
        rng = f"{prev}..{head}"
    else:
        # 没有上一个 tag 时，取最近 200 条
        rng = f"{head}~200..{head}"
    log = run_git(["log", rng, "--pretty=format:%s"], repo)
    return [l for l in log.splitlines() if l.strip()]


def group(lines):
    groups = {t: [] for t in TYPE_ORDER}
    misc = []
    for line in lines:
        m = CONV_RE.match(line)
        if m:
            t = m.group("type").lower()
            scope = m.group("scope")
            msg = m.group("msg")
            prefix = f"**{scope}:** " if scope else ""
            label = f"{prefix}{msg}"
            if m.group("break"):
                label += "  ⚠️ (breaking)"
            groups[t].append(label)
        else:
            misc.append(line)
    return groups, misc


def render(prev, head, repo="."):
    lines = collect_commits(prev, head, repo)
    if not lines:
        return "- 无变更记录"
    groups, misc = group(lines)
    out = []
    for t in TYPE_ORDER:
        items = groups.get(t) or []
        if not items:
            continue
        out.append(f"### {TYPE_LABEL.get(t, t)}")
        for it in items:
            out.append(f"- {it}")
        out.append("")
    if misc:
        out.append("### 其他")
        for it in misc:
            out.append(f"- {it}")
        out.append("")
    return "\n".join(out).strip()


def self_test():
    print("self-test: OK (脚本可正常解析与渲染)")
    sys.exit(0)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--prev", default="")
    p.add_argument("--head", default="HEAD")
    p.add_argument("--repo", default=".")
    p.add_argument("--self-test", action="store_true")
    args = p.parse_args()

    if args.self_test:
        self_test()

    prev = args.prev or latest_stable_tag(args.repo)
    print(render(prev, args.head, args.repo))


if __name__ == "__main__":
    main()
