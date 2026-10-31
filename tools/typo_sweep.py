import io
import glob
import re

ROOT = r'D:/腾讯/WorkBuddy空间/小书架工作空间/Bookshelf/app/src/main/java/com/bai/bookshelf/'

MAP = {
    '9': 'text_micro',
    '11': 'text_caption',
    '12': 'text_caption_large',
    '13': 'text_body_small',
    '14': 'text_body_medium',
    '15': 'text_body',
    '16': 'text_body_large',
    '17': 'text_headline',
    '18': 'text_headline',
    '20': 'text_title_small',
    '22': 'text_title',
    '28': 'text_display',
}

# X.setTextSize(13);           → Typo.text(X, R.dimen.text_body_small);
# X.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13); → 同上
PAT1 = re.compile(r'(\b[\w.\(\)]+)\.setTextSize\((\d+)\);')
PAT2 = re.compile(r'(\b[\w.\(\)]+)\.setTextSize\(android\.util\.TypedValue\.COMPLEX_UNIT_SP,\s*(\d+)\);')
PAT3 = re.compile(r'(\b[\w.\(\)]+)\.setTextSize\(TypedValue\.COMPLEX_UNIT_SP,\s*(\d+)\);')

changed_files = 0
sites = 0
for p in glob.glob(ROOT + '**/*.java', recursive=True):
    s = io.open(p, encoding='utf-8').read()
    orig = s

    def rep(m, pat):
        global sites
        target, num = m.group(1), m.group(2)
        dimen = MAP.get(num)
        if not dimen:
            return m.group(0)
        sites += 1
        return 'Typo.text(%s, R.dimen.%s);' % (target, dimen)

    s = PAT2.sub(lambda m: rep(m, 2), s)
    s = PAT3.sub(lambda m: rep(m, 3), s)
    s = PAT1.sub(lambda m: rep(m, 1), s)

    if s == orig:
        continue
    # 补 import（R / Typo）
    if 'import com.bai.bookshelf.R;' not in s:
        s = s.replace('package com.bai.bookshelf', 'package com.bai.bookshelf', 1)
        # 在第一个 import 前插入
        idx = s.find('\nimport ')
        if idx > 0:
            s = s[:idx + 1] + 'import com.bai.bookshelf.R;\n' + s[idx + 1:]
    if 'import com.bai.bookshelf.util.Typo;' not in s and not p.endswith('/util/Typo.java'):
        idx = s.find('\nimport ')
        if idx > 0:
            s = s[:idx + 1] + 'import com.bai.bookshelf.util.Typo;\n' + s[idx + 1:]
    io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
    changed_files += 1

print('files', changed_files, 'sites', sites)
