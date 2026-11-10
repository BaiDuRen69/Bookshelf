# -*- coding: utf-8 -*-
"""逐字符对拍：把两边的有效语句行去掉全部空白后比对，只留下真正换了的东西。"""
import difflib
import re
import subprocess

OLD = 'app/src/main/java/com/bai/bookshelf/ui/stats/StatsFragment.java'
NEW = 'app/src/main/java/com/bai/bookshelf/stats/StatsComputer.java'

src = subprocess.run(['git', 'show', 'HEAD:' + OLD], capture_output=True,
                     encoding='utf-8', errors='replace').stdout
old_all = src.splitlines()
old_body = old_all[701:1035]          # 跳过 app.io 外壳那一行，到 this.nextPick 为止

new_all = open(NEW, encoding='utf-8').read().splitlines()
i = next(n for n, l in enumerate(new_all) if 'public Result compute(List<Book> raw) {' in l)
j = next(n for n, l in enumerate(new_all) if l.strip().startswith('return new Result(snap,'))
new_body = new_all[i + 1:j + 1]

DAO = [
    (r'com\.bai\.bookshelf\.app\.BookshelfApp\.get\(\)\.db\(\)\.checkInDao\(\)\.getInRange\(0,99999999\)', 'SRC_CHECKIN'),
    (r'BookshelfApp\.get\(\)\.db\(\)\.bookCopyDao\(\)\.getAll\(\)', 'SRC_COPIES'),
    (r'BookshelfApp\.get\(\)\.db\(\)\.borrowRecordDao\(\)\.getAll\(\)', 'SRC_LOANS'),
    (r'BookshelfApp\.get\(\)\.db\(\)\.categoryDao\(\)\.getAll\(\)', 'SRC_CATS'),
    (r'BookshelfApp\.get\(\)\.db\(\)\.noteImageDao\(\)\.getAll\(\)', 'SRC_IMAGES'),
    (r'BookshelfApp\.get\(\)\.db\(\)\.excerptDao\(\)\.getAll\(\)', 'SRC_EXCERPTS'),
    (r'BookshelfApp\.get\(\)\.db\(\)\.wishBookDao\(\)\.getAll\(\)', 'SRC_WISHES'),
]


def squash(lines):
    out = []
    for l in lines:
        s = l.strip()
        if not s or s.startswith('//') or s.startswith('/*') or s.startswith('*'):
            continue
        for pat, rep in DAO:
            s = re.sub(pat, rep, s)
        s = re.sub(r'com\.bai\.bookshelf\.(util|data\.entity|stats|ui\.stats)\.', '', s)
        s = re.sub(r'\bjava\.util\.', '', s)
        out.append(re.sub(r'\s+', '', s))
    return ''.join(out)


a, b = squash(old_body), squash(new_body)
sm = difflib.SequenceMatcher(None, a, b, autojunk=False)
ops = [op for op in sm.get_opcodes() if op[0] != 'equal']
print('old 压缩后字符数: %d   new: %d' % (len(a), len(b)))
print('不同片段: %d 处' % len(ops))
for k, (tag, i1, i2, j1, j2) in enumerate(ops, 1):
    print('#%d %s' % (k, tag))
    print('   HEAD : %s' % a[max(0, i1 - 28):i2 + 28].replace(a[i1:i2], '【' + a[i1:i2] + '】'))
    print('   NOW  : %s' % b[max(0, j1 - 28):j2 + 28].replace(b[j1:j2], '【' + b[j1:j2] + '】'))
eq = sum(i2 - i1 for tag, i1, i2, _, _ in sm.get_opcodes() if tag == 'equal')
print('逐字符相同的部分: %d / %d（%.2f%%）' % (eq, len(a), 100.0 * eq / len(a)))
