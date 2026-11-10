# -*- coding: utf-8 -*-
"""对拍：HEAD 版 computeFrom 的方法体 vs 现在 StatsComputer.compute 的方法体。
只做归一化（缩进、全包名、DAO 注入、打卡/册数读表合并），其余必须逐字相同。"""
import difflib
import re
import subprocess

OLD = 'app/src/main/java/com/bai/bookshelf/ui/stats/StatsFragment.java'
NEW = 'app/src/main/java/com/bai/bookshelf/stats/StatsComputer.java'

src = subprocess.run(['git', 'show', 'HEAD:' + OLD], capture_output=True,
                     encoding='utf-8', errors='replace').stdout
old_all = src.splitlines()
# HEAD 版：700 是 `BookshelfApp app = ...`，1050 是方法的收尾 `}`
old_body = old_all[700:1035]        # 701..1048（1-based），跳过 app.io 外壳与最后的 ui 回调

new_all = open(NEW, encoding='utf-8').read().splitlines()
i = next(n for n, l in enumerate(new_all) if 'public Result compute(List<Book> raw) {' in l)
j = next(n for n, l in enumerate(new_all) if l.strip().startswith('return new Result(snap,'))
new_body = new_all[i + 1:j + 1]

DAO_MAP = [
    (r'com\.bai\.bookshelf\.app\.BookshelfApp\.get\(\)\.db\(\)\s*\.\s*checkInDao\(\)\.getInRange\(0, 99999999\)', 'source.checkIns()'),
    (r'BookshelfApp\.get\(\)\.db\(\)\.bookCopyDao\(\)\.getAll\(\)', 'source.bookCopies()'),
    (r'BookshelfApp\.get\(\)\.db\(\)\.borrowRecordDao\(\)\.getAll\(\)', 'source.borrowRecords()'),
    (r'BookshelfApp\.get\(\)\.db\(\)\s*\.\s*checkInDao\(\)\.getInRange\(0, 99999999\)', 'source.checkIns()'),
    (r'BookshelfApp\.get\(\)\.db\(\)\.categoryDao\(\)\.getAll\(\)', 'source.categories()'),
    (r'BookshelfApp\.get\(\)\.db\(\)\.noteImageDao\(\)\.getAll\(\)', 'source.noteImages()'),
    (r'BookshelfApp\.get\(\)\.db\(\)\.excerptDao\(\)\.getAll\(\)', 'source.excerpts()'),
    (r'BookshelfApp\.get\(\)\.db\(\)\.wishBookDao\(\)\.getAll\(\)', 'source.wishBooks()'),
]


def norm(lines):
    out = []
    for l in lines:
        s = l.strip()
        if not s or s.startswith('//') or s.startswith('/*') or s.startswith('*'):
            continue
        for pat, rep in DAO_MAP:
            s = re.sub(pat, rep, s)
        s = re.sub(r'com\.bai\.bookshelf\.(util|data\.entity|stats|ui\.stats)\.', '', s)
        s = re.sub(r'\bjava\.util\.', '', s)
        s = re.sub(r'\s+', ' ', s)
        out.append(s)
    return out


a, b = norm(old_body), norm(new_body)
diff = [d for d in difflib.unified_diff(a, b, 'HEAD.computeFrom', 'StatsComputer.compute', lineterm='')]
same = sum(1 for x, y in zip(a, b) if x == y)
print('old 有效语句行: %d   new 有效语句行: %d' % (len(a), len(b)))
print('差异行数（含上下文头）: %d' % sum(1 for d in diff if d[:1] in '+-' and d[:3] not in ('---', '+++')))
print('\n'.join(diff))
