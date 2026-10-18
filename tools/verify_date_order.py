# -*- coding: utf-8 -*-
"""
verify_date_order.py — v4.0 日期排序回归脚本（Python 等价实现 BookSanitizer.parseDateOrder）

评审 #12：旧实现按字符串字典序排序，「2021年3月」与「2021-03」会排出错误顺序；
新实现解析前两段数字为 年/月，返回 年*12+月 加权值，解析不出返回 -1（排最后）。
"""

import re

fails = []


def check(desc, cond):
    print(f"  [{'OK' if cond else 'FAIL'}] {desc}")
    if not cond:
        fails.append(desc)


def parse_date_order(s):
    if s is None:
        return -1
    s = s.strip()
    ym = re.search(r"(\d{1,4})", s)
    if not ym:
        return -1
    year = float(ym.group(1))
    if year < 1000 or year > 3000:
        return -1
    month = 0.0
    mm = re.search(r"(\d{1,2})", s[ym.end():])
    if mm:
        v = float(mm.group(1))
        if 1 <= v <= 12:
            month = v
    return year * 12 + month


print("== 各种写法应得到相同的加权值 ==")
vals = [parse_date_order(x) for x in ["2021-03", "2021年3月", "2021.3", "2021/3", "2021-3-5", "2021/3/15"]]
check("六种写法同值：2021-03 / 2021年3月 / 2021.3 / 2021/3 / 2021-3-5 / 2021/3/15",
      len(set(vals)) == 1 and vals[0] == 2021 * 12 + 3)

print("== 排序语义 ==")
order = sorted(["2021年3月", "2020-12", "2021-11", "1998-5", "2021-03"],
               key=parse_date_order)
check("升序正确（跨格式混合）", order == ["1998-5", "2020-12", "2021年3月", "2021-03", "2021-11"])
check("空串排最后", parse_date_order("") == -1)
check("None 排最后", parse_date_order(None) == -1)
check("非日期文本排最后", parse_date_order("未知") == -1)
check("非年份大数字不误判", parse_date_order("128.50") == -1)  # 价格样例：128 不是 1000~3000
check("年份后无月 → 仅年加权", parse_date_order("2021") == 2021 * 12)
check("月超 12 忽略", parse_date_order("2021-13") == 2021 * 12)

print()
if fails:
    print(f"存在 {len(fails)} 个失败项")
    raise SystemExit(1)
print("全部通过：日期排序解析语义正确")
