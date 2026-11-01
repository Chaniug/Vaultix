#!/usr/bin/env python3
"""检查发布类 workflow 的「上传哪些文件」是否真的被 YAML 解析成了一个键（而不是被吞进正文）。

## 为什么要这个（2026-11-01，CI run 41284293711 之后的现场）

`Publish Debug APK to preview Release` 步骤**结论绿**，日志里从头到尾没有一句
`Uploading asset`，Release 页 `assets=0` —— 用户看到的就是「最新的 dev 构建没出 APK 包」。

原因（commit `261c6ee` 引入）：改文案时把 `files: |` **粘在了 `body:` 最后一行末尾**：

    > 与上一个预览包…安装前请先导出备份。          files: |
    app/build/outputs/apk/*/debug/*.apk

YAML 解析器**完全接受**这段 —— 它只是一行更长的普通文本，`body` 多了一串字，
而 `with` 下面 `files` 这个键**根本不存在**。于是 action 走「没有 files 输入」的分支：
建一个 release、什么也不传、返回成功。

这是最难发现的一类错：**结构合法、语义丢失**。
`bash -n` 过、`yamllint` 过、action 跑完 success、Release 页也确实更新了 ——
只有点进 Assets 才会发现是空的，而"空"不会有人主动去看。

## 两步检查

1. **结构**：每个 `action-gh-release` 步骤的 `with` 必须真的解析出 `files`（非空字符串）。
2. **粘连**：`with.body` 里不得出现「非空白字符 + 2 个以上空格 + 某个 action 输入键名 + `:`」
   —— 那就是"键被吞进正文"的指纹。正文里出现 `files: xxx`（单空格）不算。

另外建议（本脚本也检查）`fail_on_unmatched_files: true`：万一 glob 未来真的匹配不到
APK（改了 flavor 名 / 改了输出目录），让 action **当场失败**，而不是安静地发个空 Release。
"""
import os
import re
import sys

import yaml

# action-gh-release 认识的 `with` 键（用它们来判断"正文里是不是混进了键"）。
# 只列会出现在 `with:` 里的，避免误伤正文中正常的英文冒号。
ACTION_INPUTS = (
    'files', 'body', 'body_path', 'name', 'tag_name', 'target_commitish',
    'draft', 'prerelease', 'make_latest', 'fail_on_unmatched_files',
    'overwrite_files', 'append_body', 'generate_release_notes', 'discussion_category_name',
)

# 「非空白 + 至少两个空格 + 键名 + 冒号」⇒ 键被粘在上一段文字末尾。
GLUED_RE = re.compile(
    r'\S[ \t]{2,}(?:' + '|'.join(ACTION_INPUTS) + r')\s*:'
)


def iter_workflows(root):
    d = os.path.join(root, '.github', 'workflows')
    if not os.path.isdir(d):
        return
    for name in sorted(os.listdir(d)):
        if name.endswith(('.yml', '.yaml')):
            yield os.path.join(d, name)


def iter_steps(doc):
    for job in (doc.get('jobs') or {}).values():
        for step in job.get('steps') or []:
            yield step


def rel(root, path):
    return os.path.relpath(path, root)


def main(argv):
    root = argv[1] if len(argv) > 1 else os.getcwd()
    problems = []
    checked = 0

    for path in iter_workflows(root):
        try:
            doc = yaml.safe_load(open(path, encoding='utf-8'))
        except Exception as exc:                     # noqa: BLE001 - 解析失败本身就该红
            problems.append(f'{rel(root, path)}: YAML 解析失败：{exc}')
            continue
        if not isinstance(doc, dict):
            continue

        for step in iter_steps(doc):
            uses = str(step.get('uses') or '')
            if 'action-gh-release' not in uses:
                continue
            checked += 1
            where = f"{rel(root, path)} › {step.get('name') or uses}"

            with_block = step.get('with')
            if not isinstance(with_block, dict):
                problems.append(f'{where}: 没有 `with:` 块')
                continue

            # ① 结构：files 必须真的存在
            files = with_block.get('files')
            if not files or not str(files).strip():
                problems.append(
                    f'{where}: `with.files` 缺失或为空 —— '
                    f'实际解析到的键只有 {sorted(with_block.keys())}'
                )

            # ② 粘连：正文里混进了键名
            body = str(with_block.get('body') or '')
            for line_no, line in enumerate(body.splitlines(), 1):
                m = GLUED_RE.search(line)
                if m:
                    problems.append(
                        f'{where}: `body` 第 {line_no} 行疑似吞掉了一个键 —— '
                        f'在 {line[m.start():m.end()]!r} 附近，'
                        f'把 `{m.group(0).split(":")[0].strip()}:` 自成一行、与 body 同级缩进'
                    )

            # ③ glob 匹配不到时不要安静发空包
            if not with_block.get('fail_on_unmatched_files'):
                problems.append(
                    f'{where}: 缺少 `fail_on_unmatched_files: true` —— '
                    f'glob 匹配不到文件时 action 会安静地发一个 0 附件的 Release'
                )

    if checked == 0:
        print('::warning title=release assets::没有找到任何 action-gh-release 步骤（检查逻辑可能已失效）')

    if problems:
        print('::error title=release assets::发布产物检查未通过')
        for p in problems:
            print('  ✗', p)
        return 1

    print(f'✅ 发布产物检查通过（{checked} 个 action-gh-release 步骤）')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
