# -*- coding: utf-8 -*-
"""按「语句」对拍：HEAD 版 computeFrom 方法体 vs StatsComputer.compute 方法体。
先把折行拼平（括号配平），再去掉包名/DAO 调用差异之外的空白，然后逐条比对。"""
import difflib
import re
import subprocess

OLD = 'app/src/main/java/com/bai/bookshelf/ui/stats/StatsFragment.java'
NEW = 'app/src/main/java/com/bai/bookshelf/stats/StatsComputer.java'

src = subprocess.run(['git', 'show', 'HEAD:' + OLD], capture_output=True,
                     encoding='utf-8', errors='replace').stdout
old_all = src.splitlines()
old_body = old_all[701:1035]

new_all = open(NEW, encoding='utf-8').read().splitlines()
i = next(n for n, l in enumerate(new_all) if 'public Result compute(List<Book> raw) {' in l)
j = next(n for n, l in enumerate(new_all) if l.strip().startswith('return new Result(snap,'))
new_body = new_all[i + 1:j + 1]

DAO = [
    (r'com\.bai\.bookshelf\.app\.BookshelfApp\.get\(\)\.db\(\)\.checkInDao\(\)\.getInRange\(0, 99999999\)', 'source.checkIns()'),
    (r'BookshelfApp\.get\(\)\.db\(\)\.bookCopyDao\(\)\.getAll\(\)', 'source.bookCopies()'),
    (r'BookshelfApp\.get\(\)\.db\(\)\.borrowRecordDao\(\)\.getAll\(\)', 'source.borrowRecords()'),
    (r'BookshelfApp\.get\(\)\.db\(\)\.categoryDao\(\)\.getAll\(\)', 'source.categories()'),
    (r'BookshelfApp\.get\(\)\.db\(\)\.noteImageDao\(\)\.getAll\(\)', 'source.noteImages()'),
    (r'BookshelfApp\.get\(\)\.db\(\)\.excerptDao\(\)\.getAll\(\)', 'source.excerpts()'),
    (r'BookshelfApp\.get\(\)\.db\(\)\.wishBookDao\(\)\.getAll\(\)', 'source.wishBooks()'),
]


def statements(lines):
    """返回「一条语句/一行控制流」的规范化文本列表：折行拼平、去注释、去包名、去空白。"""
    out = []
    buf = ''
    for raw in lines:
        s = raw.strip()
        if not s or s.startswith('//') or s.startswith('/*') or s.startswith('*'):
            continue
        for pat, rep in DAO:
            s = re.sub(pat, rep, s)
        s = re.sub(r'com\.bai\.bookshelf\.(util|data\.entity|stats|ui\.stats)\.', '', s)
        s = re.sub(r'\bjava\.util\.', '', s)
        buf = (buf + ' ' + s).strip() if buf else s
        if buf.count('(') == buf.count(')') and buf.count('{') == buf.count('}') \
                and buf.count('[') == buf.count(']'):
            out.append(re.sub(r'\s+', ' ', buf))
            buf = ''
    if buf:
        out.append(re.sub(r'\s+', ' ', buf))
    return out


a, b = statements(old_body), statements(new_body)
sm = difflib.SequenceMatcher(None, a, b, autojunk=False)
eq = sum(i2 - i1 for tag, i1, i2, _, _ in sm.get_opcodes() if tag == 'equal')
print('HEAD 语句条数: %d   现在: %d   逐字相同: %d 条（%.2f%%）' % (len(a), len(b), eq, 100.0 * eq / len(a)))
print('--- 只出现在 HEAD 的 %d 条 ---' % (len(a) - eq))
for tag, i1, i2, j1, j2 in sm.get_opcodes():
    if tag in ('delete', 'replace'):
        for s in a[i1:i2]:
            print('   - ' + s)
print('--- 只出现在现在的 %d 条 ---' % (len(b) - eq))
for tag, i1, i2, j1, j2 in sm.get_opcodes():
    if tag in ('insert', 'replace'):
        for s in b[j1:j2]:
            print('   + ' + s)
