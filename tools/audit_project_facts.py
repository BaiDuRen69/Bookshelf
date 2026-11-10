#!/usr/bin/env python3
"""工程事实盘点：测试数量、@Test 与实跑数是否对得上、Activity 基线与 setContentView、版本号与 DB schema。

用法：python -X utf8 tools/audit_project_facts.py

这是**审计工具不是闸门**：只打印事实，永远退 0。文档里的数字对不上时用它数一遍。
前身是本轮的一次性脚本 tmp/doc_audit.py，原来全部用相对路径，必须在工程根才能跑。
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA_MAIN = os.path.join("app", "src", "main", "java", "com", "bai", "bookshelf")


def java_files(*rel_parts):
    out = []
    for dirpath, _dirs, names in os.walk(os.path.join(REPO, *rel_parts)):
        for n in names:
            if n.endswith(".java"):
                out.append(os.path.join(dirpath, n))
    return sorted(out)


def read(*rel_parts):
    return open(os.path.join(REPO, *rel_parts),
                encoding="utf-8", errors="replace").read()


def count_tests(paths):
    return sum(len(re.findall(r"@Test\b",
                              open(p, encoding="utf-8", errors="replace").read()))
               for p in paths)


def main():
    unit = java_files("app", "src", "test")
    android = java_files("app", "src", "androidTest")
    ut, at = count_tests(unit), count_tests(android)
    xml = glob.glob(os.path.join(REPO, "app", "build", "test-results",
                                 "testDebugUnitTest", "*.xml"))
    xs = sum(int(ET.parse(p).getroot().get("tests")) for p in xml)
    print("== 单元测试：文件 %d，@Test %d，最近一次实跑 %d，差 %d" %
          (len(unit), ut, xs, ut - xs))
    if not xml:
        print("   （没有 testDebugUnitTest 结果，先跑一次 gradle 任务再比）")
    print("== instrumentation：文件 %d，@Test %d" % (len(android), at))

    acts = [p for p in java_files("app", "src", "main", "java")
            if p.endswith("Activity.java")]
    print("\n== Activity 文件 %d 个" % len(acts))
    missing = []
    for p in acts:
        src = open(p, encoding="utf-8", errors="replace").read()
        has = "setContentView" in src
        if not has:
            missing.append(os.path.relpath(p, REPO))
        ext = re.search(r"class\s+\w+\s+extends\s+(\w+)", src)
        print("   %-4s %-42s extends %s" % (
            "OK" if has else "NO", os.path.basename(p),
            ext.group(1) if ext else "?"))
    print("   无 setContentView 的 %d 个：%s" % (len(missing), missing))

    print("\n== BaseActivity 里 setContentView：%s" %
          ("setContentView" in read(JAVA_MAIN, "ui", "BaseActivity.java")))
    ver = re.search(r"VERSION\s*=\s*(\d+)",
                    read(JAVA_MAIN, "data", "AppDatabase.java"))
    print("== AppDatabase.VERSION：%s" % (ver.group(1) if ver else "?"))
    bg = read("app", "build.gradle")
    vn = re.search(r'versionName\s+"([^"]+)"', bg)
    vc = re.search(r"versionCode\s+(\d+)", bg)
    print("== versionName / versionCode：%s / %s" % (
        vn.group(1) if vn else "?", vc.group(1) if vc else "?"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
