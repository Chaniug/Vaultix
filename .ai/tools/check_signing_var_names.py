#!/usr/bin/env python3
"""跨文件核对签名变量名：workflow 交给 gradle 的密钥，gradle 到底会不会用。

=== 为什么需要它（2026-11-01，用户报「最新安装包与本机 dev 包签名不一致」）===

两套变量名并行且互不相认：

  · `.github/workflows/*.yml` 走 `SIGNING_*` Secret，并以 **gradle 命令行属性**
    `-Pandroid.injected.signing.*` 交给构建 —— 这是 AGP 的注入通道；
  · `app/build.gradle.kts` 只认**环境变量** `VAULTIX_KEYSTORE_PATH` / `VAULTIX_STORE_PASSWORD`
    与 `keystore.properties` —— 它**从不读** `android.injected.signing.*`。

于是：

  hasReleaseSigning == false
    → debug 用 Android 一次性 debug key 签（每次 CI 一把新的）
    → 与本机 dev 包证书不同 ⇒ 盖不上。

这件事**不会有任何报错**：workflow 的 `keytool -certreq` 校验是真的通过（密钥确实有效），
gradle 的构建也真的成功（debug 签名本来总是成功），日志里两句话都是对的。
只有把「包里实际的证书」与「本机包的证书」摆在一起才看得出来 —— 这不是注释能防的。

=== 规则 ===

从每个 workflow 的 `run:` 里抽出它**实际产出的**两样东西：
  ① `-DNAME=...` / `-PNAME=...` 形式的 gradle 参数名；
  ② 被赋成 `$N` / `$GITHUB_WORKSPACE/xxx` 的 shell 变量名（去掉 `_OPTS` 等拼接后缀）。
再去 `app/build.gradle.kts`（及其它 `*.gradle.kts`）里找出它**读取**的名字
（`providers.gradleProperty("X")` / `System.getenv("X")` / `"X"` 出现在 signing 相关行）。

判定：凡名字里带 KEYSTORE / SIGN / STORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD 的，
workflow 产出的名字必须与 gradle 读取的名字集合**有交集**；空交集 = 这条签名通道是断的。

退出码：0 = 通（或有交集）；1 = 断链；2 = 无法判定（找不到文件）。
"""
import os
import re
import sys

# workflow 侧：-Pname=value / -Dname=value
GRADLE_PROP_RE = re.compile(r'(?<![\w-])-[PD]([A-Za-z0-9_.-]+)=')
# workflow 侧：某 shell 变量被赋成含 KEYSTORE / .jks / .keystore 的值（说明它是密钥库路径）。
# ⚠️ 只收「密钥库」与「Secret 本身」两类，**不要**把 `KS_OPTS` / `KS_FILE` 这类中间量算进来：
#    它们的值是 `-Pandroid.injected...`，被当成"gradle 认这个名字"就会让检查恒绿。
SHELL_ASSIGN_RE = re.compile(
    r'^\s*([A-Z][A-Z0-9_]*)\s*=', re.M)
SECRET_BLOCK_RE = re.compile(r'secrets\.([A-Z][A-Z0-9_]*)')

# gradle 侧：显式读取
GRADLE_READ_RE = re.compile(
    r'(?:providers\.gradleProperty|System\.getenv|gradleProperty|findProperty|'
    r'Property<String>)\s*\(\s*"([^"]+)"')
# gradle 侧：**经包装函数间接读**（本仓库就是这种写法，见 app/build.gradle.kts 的
# `signingValue("VAULTIX_KEYSTORE_PATH", "storeFile")`）。只认显式 API 会漏掉整整一类，
# 而漏掉的后果是「门禁说没问题」—— 比没有门禁更糟，所以这里必须一起认。
# 只取形如大写常量 / 小写属性名的字符串字面量，避免把注释里的中文也吃进来。
GRADLE_LITERAL_RE = re.compile(r'"([A-Za-z_][A-Za-z0-9_.]{3,})"')

SIGNING_HINT = re.compile(
    r'(KEYSTORE|KEY_ALIAS|KEY_PASSWORD|STORE_PASSWORD|SIGNING|SIGN)')

TOML_PLUGINS_START = re.compile(r'^\s*\[plugins\]\s*$', re.M)


def is_signing_name(name):
    return bool(SIGNING_HINT.search(name))


def workflow_names(text):
    """返回 (gradle 参数名集合, 注入到 shell 的变量名集合)。

    后者只算两类，理由见 SHELL_ASSIGN_RE 上方的警告：
      · `secrets.NAME` 被注入 env 的（NAME 即变量名）；
      · 左侧名字本身像密钥库路径变量（`*_KEYSTORE` / `*_PATH` / `*_JKS`）的赋值。
    """
    props = {m.group(1) for m in GRADLE_PROP_RE.finditer(text)}
    props = {p for p in props if is_signing_name(p)}

    injected = {m.group(1) for m in SECRET_BLOCK_RE.finditer(text)
                if is_signing_name(m.group(1))}

    paths = set()
    for m in SHELL_ASSIGN_RE.finditer(text):
        n = m.group(1)
        if not is_signing_name(n):
            continue
        # 排除中间量：名字以 _OPTS / _ARGS 结尾的，存的是参数串而不是变量名。
        if n.endswith(('_OPTS', '_ARGS', '_ARGS_STR')):
            continue
        paths.add(n)
    return props, injected | paths


def strip_comments(text):
    """去掉 // 行注释与 /* */ 块注释 —— 否则注释里提到的变量名会被当成"gradle 认它"。

    ⚠️ 这一点是必须的：build.gradle.kts 的注释里恰好写着
       「CI 用 GitHub Secrets 注入（`VAULTIX_KEYSTORE_PATH` / ...）」，
       不剥注释就会把"文档提到过"误判成"代码读过"。
    """
    text = re.sub(r'/\*.*?\*/', '', text, flags=re.S)
    text = re.sub(r'(?m)^\s*//.*$', '', text)
    text = re.sub(r'(?m)//.*$', '', text)
    return text


def gradle_reads(root):
    """扫描所有 *.gradle.kts，返回它读取过的属性/环境变量名（含 signing 关键字的）。"""
    names = {}
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames
                       if d not in ('.git', 'build', '.gradle', 'node_modules')]
        for fn in filenames:
            if not fn.endswith('.gradle.kts'):
                continue
            path = os.path.join(dirpath, fn)
            with open(path, encoding='utf-8') as f:
                text = strip_comments(f.read())
            for m in GRADLE_READ_RE.finditer(text):
                n = m.group(1)
                if is_signing_name(n):
                    names.setdefault(n, path)
            for m in GRADLE_LITERAL_RE.finditer(text):
                n = m.group(1)
                # 只在名字本身像"环境变量/属性键"时才收（排除 "release"、"debug" 这类配置名）
                if not is_signing_name(n):
                    continue
                if n.islower() and '.' not in n:
                    continue
                names.setdefault(n, path)
    return names


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else '.'
    wf_dir = os.path.join(root, '.github', 'workflows')
    if not os.path.isdir(wf_dir):
        print('SKIP 找不到 %s' % wf_dir)
        return 2

    reads = gradle_reads(root)
    if not reads:
        print('SKIP 没在 *.gradle.kts 里找到任何签名相关的属性读取 —— '
              '说明这个仓库不用 gradle 变量传签名，本检查不适用。')
        return 2

    print('gradle 侧读取的签名变量：')
    for n in sorted(reads):
        print('  · %-32s (%s)' % (n, os.path.relpath(reads[n], root)))

    problems = []
    for fn in sorted(os.listdir(wf_dir)):
        if not fn.endswith(('.yml', '.yaml')):
            continue
        path = os.path.join(wf_dir, fn)
        with open(path, encoding='utf-8') as f:
            text = f.read()
        props, shells = workflow_names(text)
        if not props and not shells:
            continue
        produced = props | shells
        if not produced & set(reads):
            problems.append((fn, sorted(props), sorted(shells)))

    if problems:
        print()
        print('❌ 这些 workflow 把密钥交给了 gradle，但 gradle 一个都不认（签名通道是断的）：')
        for fn, props, shells in problems:
            print('   - .github/workflows/%s' % fn)
            if props:
                print('       gradle 参数 ：%s' % ', '.join(props))
            if shells:
                print('       环境变量    ：%s' % ', '.join(shells))
            print('       gradle 只读  ：%s' % ', '.join(sorted(reads)))
        print()
        print('判据：命令行的 -P 参数与 System.getenv() 是两个不同命名空间，')
        print('      名字不对不会报错、不会告警，只会静默地"没签上"。')
        print('修法：让 gradle 也读 workflow 实际产出的那个名字（两套名字都认最稳）。')
        return 1

    print()
    print('✅ %d 个 workflow 的签名参数与 gradle 读取的名字有交集。'
          % sum(1 for fn in os.listdir(wf_dir) if fn.endswith(('.yml', '.yaml'))))
    return 0


if __name__ == '__main__':
    sys.exit(main())
