# -*- coding: utf-8 -*-
"""
verify_douban.py — v3.8 豆瓣评分链路回归脚本（Python 等价实现 DoubanService，跑真接口）

链路：聚美智数返回的书名/作者 → book.douban.com/j/subject_suggest（按书名，
ISBN 直查已失效）→ 作者匹配挑条目 → 详情页 HTML 解析 rating_num。

全部失败静默返回 None，不阻塞扫码入库主流程。
"""

import json
import re
import ssl
import urllib.parse
import urllib.request

CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE
UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"}

fails = []


def check(desc, cond):
    print(f"  [{'OK' if cond else 'FAIL'}] {desc}")
    if not cond:
        fails.append(desc)


def get_html(url):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=10, context=CTX) as r:
        if r.status != 200:
            return None
        return r.read().decode("utf-8", "ignore")


def normalize(s):
    return re.sub(r"\s+", "", s or "").lower()


def find_subject_url(title, author):
    if not title or not title.strip():
        return None
    url = "https://book.douban.com/j/subject_suggest?q=" + urllib.parse.quote(title.strip())
    body = get_html(url)
    if body is None:
        return None
    arr = json.loads(body)
    if not arr:
        return None
    a = normalize(author)
    fallback = None
    for item in arr:
        if item.get("type") != "b":
            continue
        if fallback is None:
            fallback = item.get("url")
        if a:
            da = normalize(item.get("author_name"))
            if da and (a in da or da in a):
                return item.get("url")
    return fallback


RATING_RE = re.compile(r"rating_num[^>]*>\s*([\d.]+)\s*<")


def parse_rating(html):
    if not html:
        return None
    m = RATING_RE.search(html)
    if not m:
        return None
    v = float(m.group(1))
    if v < 0 or v > 10:
        return None
    return m.group(1)


def fetch_rating_sync(title, author):
    try:
        u = find_subject_url(title, author)
        if not u:
            return None
        return parse_rating(get_html(u))
    except Exception:
        return None


print("== 端到端：真实图书（书名来自 ISBN 接口的典型返回）==")
cases = [
    ("三体", "刘慈欣", "8.9"),
    ("活着", "余华", None),        # 评分只断言存在且在 0-10，具体值见输出
    ("算法导论", "", None),        # 无作者输入也应能查到
]
for title, author, expect in cases:
    rating = fetch_rating_sync(title, author)
    ok = rating is not None and 0 <= float(rating) <= 10
    if expect is not None:
        ok = ok and rating == expect
    check(f"《{title}》({author or '无作者'}) → 评分 {rating}", ok)

print("== 边界与兜底 ==")
check("不存在的书名 → None（静默）", fetch_rating_sync("这本书豆瓣肯定没有zzz999", "无人") is None)
check("空书名 → None", fetch_rating_sync("", "某人") is None)
check("评分解析：无评分 HTML → None", parse_rating("<div>没有评分</div>") is None)
check("评分解析：标准片段 8.9", parse_rating('<strong class="ll rating_num" property="v:average"> 8.9 </strong>') == "8.9")
check("评分解析：超范围 11.5 → None", parse_rating('<strong class="rating_num">11.5</strong>') is None)

print()
if fails:
    print(f"存在 {len(fails)} 个失败项")
    raise SystemExit(1)
print("全部通过：豆瓣评分链路（真接口）可用，兜底行为正确")
