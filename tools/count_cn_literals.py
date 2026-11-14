"""硬编码中文计数 —— 项目唯一口径：`app/src/main/java` 里「含中文」的字符串字面量条数。

用法：
    python -X utf8 tools/count_cn_literals.py [--max N] [<相对路径> ...]
不给路径就扫全项目（扫描根按 `__file__` 推导，任意目录可跑）；给了路径就只扫那几个文件。
不给路径时才套用 `--max`：单文件读数只是本机查阅用，不是闸门。

口径（就这一句，别的数字要引用必须自己标明口径）：逐行取 `//` 之前的部分，正则抓字符串字面量，
**串内只要有 ≥1 个汉字就算一条**（不去块注释、不按 ≥2 汉字收严）。同一行两个中文字面量算两条。
`tools/audit_cn_and_catch.py` 是另一套（去注释 + ≥2 汉字），数字天然比这里小，两者不能互相「更正」。

`--max` 默认 2657 = 本文件当前全项目读数。`.github/workflows/ci.yml` 挂
`python3 tools/count_cn_literals.py --max 2657` 做「只减不增」护栏；阈值写在脚本里而只把结论抄进文档，
是为了让文档数字和闸门数字出自同一处——要放宽必须显式改这里并说明理由。
负向自测在 `tools/selftest_checks.py`：阈值调高必须仍绿、调低必须红，防的是「护栏其实没通电」。

退出码：0 = 不超阈值；2 = 超阈值 / 一个 Java 文件都没扫到 / 指定的文件不存在（空跑不算绿，见总览坑 55）。
"""
import argparse
import os
import re
import sys

CN = re.compile(r"[\u4e00-\u9fff]")
LIT = re.compile(r'"((?:[^"\\]|\\.)*)"')
REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_ROOT = os.path.join('app', 'src', 'main', 'java')
DEFAULT_MAX = 2657


def scan(path):
    with open(path, encoding="utf-8") as f:
        lines = f.read().split("\n")
    total = cn = cn_start = 0
    hits = []
    for i, line in enumerate(lines, 1):
        code = line.split("//")[0]
        for m in LIT.finditer(code):
            total += 1
            if CN.search(m.group(1)):
                cn += 1
                if CN.match(m.group(1)):
                    cn_start += 1
                hits.append((i, m.group(1)))
    return len(lines), total, cn, cn_start, hits


def targets_from(paths):
    """没给参数 = 全项目；给了 = 逐个解析（先按当前目录，再按工程根）。返回 (文件列表, 没找到的输入)。"""
    if paths:
        found, missing = [], []
        for raw in paths:
            for cand in (raw, os.path.join(REPO, raw)):
                if os.path.isfile(cand):
                    found.append(cand)
                    break
            else:
                missing.append(raw)
        return sorted(found), missing
    root = os.path.join(REPO, DEFAULT_ROOT)
    if not os.path.isdir(root):
        return [], [DEFAULT_ROOT]
    found = []
    for dirpath, _dirs, names in os.walk(root):
        for name in names:
            if name.endswith(".java"):
                found.append(os.path.join(dirpath, name))
    return sorted(found), []


def main():
    parser = argparse.ArgumentParser(description="硬编码中文字面量计数（项目唯一口径）+ 只减不增护栏")
    parser.add_argument("--max", dest="max_cn", type=int, default=DEFAULT_MAX,
                        help="含中文条数的上限，超过就 exit 2（默认 %(default)s，仅全项目扫描时生效）")
    parser.add_argument("paths", nargs="*", help="只扫这几个 .java（相对当前目录或工程根）")
    args = parser.parse_args()

    targets, missing = targets_from(args.paths)
    if missing:
        print("[FAIL] 这些路径下没有 .java：%s（空跑不算绿）" % "、".join(missing), file=sys.stderr)
        return 2
    if not targets:
        print("[FAIL] 一个 Java 文件都没扫到（扫描根指对了吗）", file=sys.stderr)
        return 2

    g_lines = g_total = g_cn = g_start = 0
    for t in targets:
        n, total, cn, start, _hits = scan(t)
        g_lines += n
        g_total += total
        g_cn += cn
        g_start += start
        print("%-6d 字面量 %-5d 含中文 %-5d 中文开头 %-5d %s" % (n, total, cn, start, t))
    print("合计：文件 %-5d 行 %-6d 字面量 %-6d 含中文 %-6d 中文开头 %d"
          % (len(targets), g_lines, g_total, g_cn, g_start))

    guarded = len(args.paths) == 0
    if not guarded:
        print("（指定了文件，本轮不判阈值；口径读数见上）")
        verdict = 0
    elif g_cn > args.max_cn:
        print("[FAIL] 含中文 %d > --max %d：硬编码中文比基线多了 %d 条"
              % (g_cn, args.max_cn, g_cn - args.max_cn), file=sys.stderr)
        verdict = 2
    else:
        print("[OK] 含中文 %d ≤ --max %d（余量 %d）" % (g_cn, args.max_cn, args.max_cn - g_cn))
        verdict = 0

    if len(targets) == 1:
        _n, _t, _c, _s, hits = scan(targets[0])
        for i, s in hits:
            print("  L%-5d %s" % (i, s[:70]))
    return verdict


if __name__ == "__main__":
    sys.exit(main())
