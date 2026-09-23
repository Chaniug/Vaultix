#!/usr/bin/env python3
"""
验证 Bitwarden 同步字段透传修复的自洽性。

沙箱无 Android SDK / Gradle 依赖缓存 ⇒ 跑不了真编译。不假装能编译，
改为把每一个被引入的引用逐一拿真实定义去对。
"""
import re
import sys
from pathlib import Path

ROOT = Path("/workspace/Vaultix/data/bitwarden/src/main/java/io/vaultix/data/bitwarden")
dto = (ROOT / "model/BitwardenDto.kt").read_text(encoding="utf-8")
mapper = (ROOT / "mapper/CipherMapper.kt").read_text(encoding="utf-8")
service = (ROOT / "sync/BitwardenSyncService.kt").read_text(encoding="utf-8")

problems = []


def check(desc, cond, where=""):
    if cond:
        print(f"  OK   {desc}")
    else:
        print(f"  FAIL {desc}")
        problems.append(f"{desc} {where}")


print("[1] CipherRequest 必须新增三个透传字段")
req = re.search(r"data class CipherRequest\((.*?)\n\)", dto, re.S)
assert req, "CipherRequest 未找到"
body = req.group(1)
req_fields = set(re.findall(r"val (\w+):", body))
for f in ("passwordHistory", "organizationId", "lastKnownRevisionDate"):
    check(f"CipherRequest.{f}", f in req_fields)

print("\n[2] 三个字段的 SerialName 必须与服务端 JSON 名一致")
for f, sn in [("passwordHistory", "passwordHistory"), ("organizationId", "organizationId"),
              ("lastKnownRevisionDate", "lastKnownRevisionDate")]:
    check(f'{f} -> "{sn}"', f'@SerialName("{sn}")' in body)

print("\n[3] 透传的源字段必须在 CipherDto 上真实存在（否则读不到，透传成 null）")
cipher_dto = re.search(r"data class CipherDto\((.*?)\n\)", dto, re.S).group(1)
stored_fields = set(re.findall(r"val (\w+):", cipher_dto))
for src in ("passwordHistory", "organizationId", "revisionDate"):
    check(f"CipherDto.{src}", src in stored_fields)

print("\n[4] toUpdateRequest 必须真的引用这三次透传")
upd = mapper[mapper.index("fun toUpdateRequest("):]
upd = upd[:upd.index("\n    /**")] if "\n    /**" in upd else upd
for expr in ("stored.passwordHistory", "stored.organizationId", "stored.revisionDate.takeIf"):
    check(f"引用 {expr}", expr in upd)

print("\n[5] 透传值的类型必须与声明匹配")
check("passwordHistory 类型一致（List<PasswordHistoryDto>）",
      "val passwordHistory: List<PasswordHistoryDto>" in body
      and "val passwordHistory: List<PasswordHistoryDto>" in cipher_dto)
check("organizationId 类型一致（String?）",
      "val organizationId: String? " in body.replace("\n", " ")
      or "val organizationId: String? = null" in body)

print("\n[6] ★ 所有 CipherRequest( 构造点：只有「更新」路径该带这三段")
# 这一条是本探针**最有价值**的部分：新建路径带 lastKnownRevisionDate 是错的
# （那时服务端还没有这条记录，没有"已知版本"可言）。
# 第一版探针把函数名猜成 toCreateRequest（实际叫 toRequest），于是静默跳过了 ——
# 教训：**探针"跳过"某项时，先怀疑探针**，别当成"没问题"。

def enclosing_fn(text, pos):
    """往前找最近的 fun 名。"""
    names = re.findall(r"fun (\w+)\(", text[:pos])
    return names[-1] if names else "?"


def call_body(text, pos):
    """从构造点起，按括号配平截出这次调用的完整文本。"""
    out, depth = [], 0
    i = pos
    while i < len(text):
        ch = text[i]
        out.append(ch)
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                break
        i += 1
    return "".join(out)


def is_top_level_arg(seg, field):
    """判断 `field =` 是本次构造的**顶层具名实参**，还是被包在 lambda /
    嵌套块里（例如 `card = if (...) { x.also { organizationId = y } }`）。

    两道判据：
      1. 该字段所在行**向左**找到的第一个"有代码内容的行"，其末字符必须是
         实参分隔符 `(` 或 `,`。注意要跳过空行与 `//` 注释行。
      2. 从那个分隔符到字段之间，`{` 与 `}` 必须**严格配平**。

    走过的坑（都是"探针坏了"，不是"代码没问题"）：
      · 第一版只做 `field in seg` **子串匹配**，被"顶层多带一段"骗过。
      · 第二版按**字符**向左回扫，撞上注释分隔线 `// ----` 末尾的 `-`，
        把真实存在的 passwordHistory 误报成**缺失**（假阴性）。
      · 第三版 `between` 窗口起点取"上一行末尾"，而嵌套块场景下上一行末尾
        正好是 `{` 本身，窗口里看不到那个 `{`，配平恒真。
        修正：窗口起点改为**分隔符本身**（含开括号）。

    一次"变异逃逸"的复盘，值得记下来：
      我曾把「往 `card = if (...) { ... .also { organizationId = x } }` 里插一个
      `organizationId =`」当成应当被抓住的泄漏。**这个前提是错的** ——
      它压根不是本次构造的顶层实参，只是某个嵌套 lambda 里的局部赋值，
      对请求体没有任何影响。探针判它"不算透传"是**语义正确**的。
      ⇒ 教训：**变异逃逸时，先确认"期望它被抓"这个前提本身对不对**，
        再怀疑探针。反向指标：这类"假泄漏"若被误报，反而会掩盖真问题。
    """
    for m in re.finditer(r"(?<![\w.])" + re.escape(field) + r"\s*=", seg):
        # [判据 1] 向左按行回扫，跳过空行与注释行
        line_start = seg.rfind("\n", 0, m.start()) + 1
        prev_end = line_start - 1
        sep_found = None
        while prev_end > 0:
            prev_start = seg.rfind("\n", 0, prev_end) + 1
            prev = seg[prev_start:prev_end].strip()
            if prev and not prev.startswith("//"):
                sep_found = prev[-1]
                break
            prev_end = prev_start - 1
        if prev_end <= 0:
            return True          # 构造点第一行（`CipherRequest(` 之后紧跟）
        if sep_found not in "(,":
            continue

        # [判据 2] 从分隔符本身开始配平（含该字符！）
        between = seg[prev_end:m.start()]
        if between.count("{") != between.count("}"):
            continue
        return True
    return False


sites = []
for m2 in re.finditer(r"CipherRequest\(", mapper):
    line_no = mapper[:m2.start()].count("\n") + 1
    sites.append((line_no, enclosing_fn(mapper, m2.start()), call_body(mapper, m2.start())))

check(f"找到 {len(sites)} 个构造点：{[(l, f) for l, f, _ in sites]}", len(sites) >= 2, "CipherMapper")
check("构造点数不超过 3（防止误匹配到 KDoc 示例里）", len(sites) <= 3, "CipherMapper")

PASS_THROUGH = ("passwordHistory", "organizationId", "lastKnownRevisionDate")
for line_no, fn, seg in sites:
    present = [f for f in PASS_THROUGH if is_top_level_arg(seg, f)]
    if fn == "toUpdateRequest":
        check(f"{fn} (L{line_no}) 带全部三段透传（顶层实参）",
              len(present) == 3, f"实际只带 {present}")
    else:
        check(f"{fn} (L{line_no}) **不**带任何透传（新建无服务端原值）",
              not present, f"多带了 {present}")

print("\n[7] 行宽 <= 120")
for p in ["model/BitwardenDto.kt", "mapper/CipherMapper.kt"]:
    lines = (ROOT / p).read_text(encoding="utf-8").splitlines()
    long = [i for i, l in enumerate(lines, 1) if len(l) > 120]
    check(f"{p} 行宽", not long, f"超长 {long}")

print("\n[8] 括号配平")
for p in ["model/BitwardenDto.kt", "mapper/CipherMapper.kt"]:
    t = (ROOT / p).read_text(encoding="utf-8")
    check(f"{p} 花括号", t.count("{") == t.count("}"), f"{t.count('{')} vs {t.count('}')}")
    check(f"{p} 圆括号", t.count("(") == t.count(")"), f"{t.count('(')} vs {t.count(')')}")

print()
if problems:
    print(f"!!! {len(problems)} 项未通过")
    for x in problems:
        print("  -", x)
    sys.exit(1)
print("全部通过")
