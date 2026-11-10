#!/usr/bin/env python3
"""Java 侧文案/容错体量审计：硬编码中文、空 catch、catch(Exception)、Log.* 处数、strings 资源条数。

用法：python -X utf8 tools/audit_cn_and_catch.py

这是**审计工具不是闸门**：只打印现状数字，永远退 0，改动前后各跑一次对着看。
闸门是 check_*.py 那四条（见坑 55）。
前身是本轮的一次性脚本 tmp/audit_card_d.py，路径已改成按 __file__ 解析，任意目录可跑。
"""
import glob
import os
import re
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ROOT = os.path.join(REPO, "app", "src", "main", "java", "com", "bai", "bookshelf")
RES = os.path.join(REPO, "app", "src", "main", "res")

HAN_IN_STRING = re.compile(r'"(?:[^"\\]|\\.)*?[\u4e00-\u9fff]{2,}(?:[^"\\]|\\.)*?"')
CATCH_BLOCK = re.compile(r'catch\s*\([^)]*\)\s*\{\s*\}')
CATCH_ANY = re.compile(r'catch\s*\(\s*(Exception|Throwable|RuntimeException|IOException|GeneralSecurityException)\b')
LOG_CALL = re.compile(r'\bLog\.[viwde]\s*\(')


def java_files():
    out = []
    for dirpath, _dirs, names in os.walk(ROOT):
        for n in names:
            if n.endswith(".java"):
                out.append(os.path.join(dirpath, n))
    return sorted(out)


def strip_comments(src):
    src = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
    src = re.sub(r'//[^\n]*', '', src)
    return src


def rel(p):
    return p.replace(ROOT + os.sep, "")


def main():
    files = java_files()
    spec_re = re.compile(r'"[\u4e00-\u9fff]{2,}')
    total = 0
    spec_total = 0
    per_file = {}
    spec_per_file = {}
    empty_catch = 0
    empty_catch_raw = 0
    catch_any = 0
    catch_exception_only = 0
    log_calls = 0
    log_files = {}
    for p in files:
        raw = open(p, encoding="utf-8").read()
        src = strip_comments(raw)
        n = len(HAN_IN_STRING.findall(src))
        s = len(spec_re.findall(src))
        if n:
            per_file[p] = n
            total += n
        if s:
            spec_per_file[p] = s
            spec_total += s
        empty_catch += len(CATCH_BLOCK.findall(src))
        empty_catch_raw += len(CATCH_BLOCK.findall(raw))
        catch_any += len(CATCH_ANY.findall(src))
        catch_exception_only += len(re.findall(r'catch\s*\(\s*Exception\b', src))
        lc = len(LOG_CALL.findall(src))
        if lc:
            log_files[p] = lc
            log_calls += lc
    print("== 硬编码中文（字符串字面量内含 ≥2 汉字，去注释）")
    print("   总计 %d 处，分布在 %d 个文件" % (total, len(per_file)))
    print("== 串首即中文（\"[\\p{Han}]{2,}\" 那一档口径）")
    print("   总计 %d 处，分布在 %d 个文件" % (spec_total, len(spec_per_file)))
    for p, n in sorted(per_file.items(), key=lambda kv: -kv[1])[:18]:
        print("   %4d (串首 %d)  %s" % (n, spec_per_file.get(p, 0), rel(p)))
    print("== 空 catch 块：去注释后 %d，含注释体 %d" % (empty_catch, empty_catch_raw))
    print("== catch(Exception) 字面：%d；含 Throwable/IOException 等 %d" % (catch_exception_only, catch_any))
    print("== Log.* 调用：%d 处，分布在 %d 个文件" % (log_calls, len(log_files)))
    for p, n in sorted(log_files.items(), key=lambda kv: -kv[1]):
        print("   %3d  %s" % (n, rel(p)))

    tot_items = 0
    for sp in sorted(glob.glob(os.path.join(RES, "values*", "strings.xml"))):
        d = open(sp, encoding="utf-8").read()
        c = len(re.findall(r"<(string|string-array|plurals)\b", d))
        tot_items += c
        print("== %s：%d 条" % (os.path.basename(os.path.dirname(sp)), c))
    print("== strings 资源合计：%d" % tot_items)
    return 0


if __name__ == "__main__":
    sys.exit(main())
