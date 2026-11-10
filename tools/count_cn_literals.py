"""4.1 批次一：统计某个 Java 文件里的硬编码中文字符串字面量。

用法：python -X utf8 tools/count_cn_literals.py <相对路径> [<相对路径> ...]
不给参数就扫 app/src/main/java 全项目。
"""
import re
import sys
import os

CN = re.compile(r"[\u4e00-\u9fff]")
LIT = re.compile(r'"((?:[^"\\]|\\.)*)"')


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


def main():
    args = sys.argv[1:]
    if args:
        targets = args
    else:
        targets = []
        for root, _dirs, files in os.walk("app/src/main/java"):
            for fn in files:
                if fn.endswith(".java"):
                    targets.append(os.path.join(root, fn))
        targets.sort()
    g_lines = g_total = g_cn = g_start = 0
    for t in targets:
        n, total, cn, start, _hits = scan(t)
        g_lines += n
        g_total += total
        g_cn += cn
        g_start += start
        print("%-6d 字面量 %-5d 含中文 %-5d 中文开头 %-5d %s" % (n, total, cn, start, t))
    print("合计：行 %-6d 字面量 %-6d 含中文 %-6d 中文开头 %d" % (g_lines, g_total, g_cn, g_start))
    if len(targets) == 1:
        _n, _t, _c, _s, hits = scan(targets[0])
        for i, s in hits:
            print("  L%-5d %s" % (i, s[:70]))


main()
