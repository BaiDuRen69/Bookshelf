# -*- coding: utf-8 -*-
"""
verify_edition.py — v3.7 版次三段式回归脚本（Python 等价实现 BookSanitizer 逻辑）

复现用户 bug：编辑页版次只填年 → 旧 joinEdition 存「2022-|」→
下次打开编辑页 fillEditionFields 正则不匹配 → 整串「2022-|」塞回年框。

验证：joinEdition 不再产生悬空分隔符、splitEdition 兼容旧脏数据与自由文本、
sanitizeEdition 对存量脏数据自愈、编辑页「保存→重开」回显闭环。
"""

import re


def nz(s):
    return "" if s is None else s


def join_edition(y, m, n):
    y, m, n = nz(y).strip(), nz(m).strip(), nz(n).strip()
    if not y and not m and not n:
        return ""
    sb = y
    if m:
        sb += "-" + m
    if n:
        sb += "|" + n
    return sb


def is_digits_or_empty(s):
    return all(c.isdigit() for c in s)


def split_edition(stored):
    s = nz(stored).strip()
    if not s:
        return None
    no = ""
    pipe = s.find("|")
    if pipe >= 0:
        no = s[pipe + 1:].strip()
        s = s[:pipe].strip()
    dash = s.find("-")
    if dash >= 0:
        y, m = s[:dash].strip(), s[dash + 1:].strip()
    else:
        y, m = s, ""
    if not (is_digits_or_empty(y) and is_digits_or_empty(m) and is_digits_or_empty(no)):
        return None
    if not (y or m or no):
        return None
    return [y, m, no]


def sanitize_edition(stored):
    p = split_edition(stored)
    return nz(stored) if p is None else join_edition(*p)


fails = []


def check(desc, cond):
    print(f"  [{'OK' if cond else 'FAIL'}] {desc}")
    if not cond:
        fails.append(desc)


print("== joinEdition 拼接规则（根因修复）==")
check("只填年 → 2022（旧版是 2022-|）", join_edition("2022", "", "") == "2022")
check("年+月 → 2022-5", join_edition("2022", "5", "") == "2022-5")
check("年+第几版 → 2022|2", join_edition("2022", "", "2") == "2022|2")
check("三段全填 → 2022-5|2（与旧标准格式一致）", join_edition("2022", "5", "2") == "2022-5|2")
check("三段全空 → 空串", join_edition("", "", "") == "")
check("只填月 → -5（可无歧义拆回）", join_edition("", "5", "") == "-5")

print("== splitEdition 拆分兼容 ==")
check("标准格式 2022-5|2 → [2022,5,2]", split_edition("2022-5|2") == ["2022", "5", "2"])
check("旧脏数据 2022-| → [2022,'','']（本次 bug）", split_edition("2022-|") == ["2022", "", ""])
check("纯年 2022 → [2022,'','']", split_edition("2022") == ["2022", "", ""])
check("年+月 2022-5 → [2022,5,'']", split_edition("2022-5") == ["2022", "5", ""])
check("年版 2022|2 → [2022,'',2]", split_edition("2022|2") == ["2022", "", "2"])
check("自由文本「2022年5月」 → null（原文保留年框）", split_edition("2022年5月") is None)
check("自由文本「第2版」 → null", split_edition("第2版") is None)
check("空串 → null", split_edition("") is None)

print("== sanitizeEdition 存量脏数据自愈 ==")
check("2022-| → 2022", sanitize_edition("2022-|") == "2022")
check("2022-5| → 2022-5", sanitize_edition("2022-5|") == "2022-5")
check("|2 → |2 保留（只填版次）", sanitize_edition("|2") == "|2")
check("2022-5|2 不变", sanitize_edition("2022-5|2") == "2022-5|2")
check("自由文本原样保留", sanitize_edition("1998年一版") == "1998年一版")

print("== 编辑页闭环：保存 → 重开回显 → 再保存 ==")
# 用户只填年保存
stored = join_edition("2022", "", "")
# 重开编辑页：fillEditionFields 拆分回显
p = split_edition(stored)
year_shown = p[0] if p else stored  # 拆不开才原文塞年框
check("重开后年框显示 2022（不是 2022-|）", year_shown == "2022")
check("月框/版框为空", p[1] == "" and p[2] == "")
# 用户不动直接再保存
stored2 = join_edition(year_shown, p[1], p[2])
check("再保存后存储值仍为 2022（稳定收敛）", stored2 == "2022")
# 旧脏数据书的自愈：库里已存「2022-|」→ normalize → 编辑页回显 → 保存
healed = sanitize_edition("2022-|")
p2 = split_edition(healed)
check("旧脏数据自愈后回显年框 2022", p2[0] == "2022")
check("自愈后再保存保持 2022", join_edition(*p2) == "2022")

print()
if fails:
    print(f"存在 {len(fails)} 个失败项")
    raise SystemExit(1)
print("全部通过：版次三段式修复有效，存量脏数据可自愈")
