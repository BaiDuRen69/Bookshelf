#!/usr/bin/env python3
"""Java 单文件方法跨度体检：第一层方法清单、按行数排序、指定方法的起止行、正则误抓自检。

用法：
    python -X utf8 tools/audit_method_spans.py [相对工程根或绝对的 .java 路径] [方法名 ...]

不给路径时默认统计 ui/stats/StatsFragment.java；不给方法名时只按行数排前 12 个。
这是**审计工具不是闸门**：只打印结构事实，永远退 0。
前身是本轮的一次性脚本 tmp/audit_stats2.py，原来把 StatsFragment 的绝对路径写死在源码里。
"""
import os
import re
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_FILE = os.path.join(
    REPO, "app", "src", "main", "java", "com", "bai", "bookshelf",
    "ui", "stats", "StatsFragment.java")

CTRL = ("if", "for", "while", "switch", "catch", "return", "else", "try", "do")
SIG = re.compile(
    r'^((?:(?:private|protected|public|static|final|synchronized)\s+)+)'
    r'(?:[\w.$<>\[\], ?]+?\s+)?(\w+)\s*\(([^)]*)\)\s*(?:throws [\w., ]+)?\s*\{$')


def first_layer_methods(lines):
    """只认类体第一层（4 空格缩进）的方法声明，内部类和局部类不计。"""
    out = []
    for i, line in enumerate(lines, 1):
        if not line.startswith("    ") or line.startswith("     "):
            continue
        s = line.strip()
        m = SIG.match(s)
        if not m or m.group(2) in CTRL or "=" in s.split("(")[0]:
            continue
        out.append((i, m.group(2)))
    return out


def span(lines, start_line):
    depth = 0
    for i in range(start_line - 1, len(lines)):
        depth += lines[i].count("{") - lines[i].count("}")
        if depth == 0 and i > start_line - 1:
            return i + 1
    return None


def main(argv):
    path = argv[0] if argv else DEFAULT_FILE
    if not os.path.isabs(path):
        path = os.path.join(REPO, path)
    names = argv[1:]
    if not os.path.isfile(path):
        print("找不到文件：%s" % path)
        return 1
    lines = open(path, encoding="utf-8").read().split("\n")
    methods = first_layer_methods(lines)
    print("文件：%s（%d 行）" % (os.path.relpath(path, REPO), len(lines)))
    print("第一层方法 %d 个" % len(methods))

    rows = []
    for i, name in methods:
        end = span(lines, i)
        rows.append((name, i, end, None if end is None else end - i + 1))

    print("\n=== 最长的方法 ===")
    for name, i, end, n in sorted(
            [r for r in rows if r[3] is not None], key=lambda r: -r[3])[:12]:
        print("%-24s L%d–L%d（%d 行）" % (name, i, end, n))

    if names:
        print("\n=== 点名的方法 ===")
        for want in names:
            hits = [r for r in rows if r[0] == want]
            if not hits:
                print("%-24s 未找到" % want)
            for name, i, end, n in hits:
                print("%-24s L%d–L%d（%d 行）" % (name, i, end, n))

    print("\n=== 按前缀分组 ===")
    for pfx in ("render", "bind", "setup", "update", "load", "compute"):
        group = [n for _, n in methods if n.startswith(pfx)]
        if group:
            print("%-8s %2d 个  %s" % (pfx, len(group), " ".join(group)))

    print("\n=== db() 调用点 ===")
    for i, line in enumerate(lines, 1):
        if "db()." in line:
            print("   %4d %s" % (i, line.strip()[:104]))

    print("\n=== 同名方法（>1 次说明正则把非方法声明也抓进来了）===")
    flat = [n for _, n in methods]
    dup = {n: flat.count(n) for n in set(flat) if flat.count(n) > 1}
    print(dup if dup else "无")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
