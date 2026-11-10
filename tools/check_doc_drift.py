#!/usr/bin/env python3
"""文档说的数字必须等于代码里的数字（防「文档比代码旧」）。

从代码读真值：app/build.gradle 的 versionName/versionCode、AppDatabase.VERSION、
app/src/test 与 app/src/androidTest 的 @Test 计数，（可选）lint 报告里的 error/告警数；
再在文档里找「当前态」表述（所在行含「当前」/「最新」才算，历史沿革里的 DB v17 那种不动）。
对不上就逐行列出，退出码非 0。

用法：
    python -X utf8 tools/check_doc_drift.py [--doc <路径> ...] [--project <工程根>]
默认文档 = 仓库内 docs/ 的小书房-项目总览.md + 小书房-功能清单.md + 发版记录.md。
**不再兜底上一级工作空间**：CI 只 checkout 本仓库，上位目录在 CI 上必然不存在，
留着兜底就等于让本机比到 0 处还报绿（v5.9.5 那次假绿就是这么来的）。
退出码：0 = 零差异；1 = 有差异；2 = 读不到代码真值 / 一句当前态都没比到。
"""

import argparse
import io
import os
import re
import sys

CURRENT_GATE = ('当前版本', '最新版本')
TEST_RE = re.compile(r'@Test\b')
VERSION_NAME_RE = re.compile(r'versionName\s+"(\d+(?:\.\d+){1,3})"|'
                             r'(?:当前版本|最新版本)\s+v?(\d+(?:\.\d+){1,3})')
VERSION_CODE_RE = re.compile(r'versionCode\s+(\d+)')
DB_RE = re.compile(r'(?:\bDB|数据库)\s+v(\d+)')
DB_LINE_RE = re.compile(r'当前\s+DB\s+v(\d+)')
UNIT_RE = re.compile(r'单测\s*(\d+)')
INSTR_RE = re.compile(r'androidTest\s*(?:共\s*)?(\d+)')
LINT_ERR_RE = re.compile(r'lint\s+(\d+)\s*error')
LINT_WARN_RE = re.compile(r'(\d+)\s*(?:条)?\s*(?:告警|warnings)')
LINT_SUMMARY_RE = re.compile(r'(\d+)\s+errors?,\s*(\d+)\s+warnings?')
CODE_VERSION_RE = re.compile(r'versionName\s+"([^"]+)"')
CODE_CODE_RE = re.compile(r'versionCode\s+(\d+)')
DB_CONST_RE = re.compile(r'VERSION\s*=\s*(\d+)')


def read(path):
    return io.open(path, encoding='utf-8', errors='replace').read()


def count_tests(dirpath):
    total = 0
    for dirpath, _, names in os.walk(dirpath):
        for name in names:
            if name.endswith('.java'):
                total += len(TEST_RE.findall(read(os.path.join(dirpath, name))))
    return total


def truth(project):
    gradle = os.path.join(project, 'app', 'build.gradle')
    database = os.path.join(project, 'app', 'src', 'main', 'java', 'com', 'bai',
                            'bookshelf', 'data', 'AppDatabase.java')
    if not (os.path.isfile(gradle) and os.path.isfile(database)):
        return None
    text = read(gradle)
    values = {
        'versionName': CODE_VERSION_RE.search(text).group(1),
        'versionCode': CODE_CODE_RE.search(text).group(1),
        'DB': DB_CONST_RE.search(read(database)).group(1),
        '单测': str(count_tests(os.path.join(project, 'app', 'src', 'test'))),
        'androidTest': str(count_tests(os.path.join(project, 'app', 'src', 'androidTest'))),
    }
    lint = None
    for base, pattern in ((os.path.join(project, 'app', 'build', 'reports'), 'lint-results-%s.txt'),
                          (os.path.join(project, 'app', 'build', 'reports'), 'lint-results-%s.xml')):
        for variant in ('release', 'debug'):
            path = os.path.join(base, pattern % variant)
            if not os.path.isfile(path):
                continue
            text = read(path)
            counts = None
            if path.endswith('.txt'):
                found = LINT_SUMMARY_RE.findall(text)
                if found:
                    counts = found[-1]
            else:
                counts = (str(len(re.findall(r'severity="Error"', text))),
                          str(len(re.findall(r'severity="Warning"', text))))
            if counts:
                lint = {'lint error': counts[0], 'lint 告警': counts[1]}
                values.update(lint)
                values['_lint_source'] = path
            break
        if lint:
            break
    if lint is None:
        values['lint error'] = None
        values['lint 告警'] = None
    return values


def claims(path):
    """返回 [(行号, 类别, 声称值, 原文片段)]，只认「当前版本 / 最新版本」这类当前态句子。

    两个细节：
    - 只扫描门控词之后的片段：「由 v4.5（versionCode 44）整理……当前版本 5.9.0（versionCode 136……」
      里的 44 是历史值，不能被当成当前。
    - 「## 4. 数据模型（Room，当前 DB v23）」这种小标题没有「当前版本」，单独按 当前 DB 取。
    """
    out = []
    for number, line in enumerate(read(path).split('\n'), 1):
        starts = [line.find(gate) for gate in CURRENT_GATE if line.find(gate) >= 0]
        if starts:
            segment = line[min(starts):]
            groups = ((VERSION_NAME_RE, 'versionName'), (VERSION_CODE_RE, 'versionCode'),
                      (DB_RE, 'DB'), (UNIT_RE, '单测'), (INSTR_RE, 'androidTest'),
                      (LINT_ERR_RE, 'lint error'), (LINT_WARN_RE, 'lint 告警'))
        elif DB_LINE_RE.search(line):
            segment, groups = line, ((DB_LINE_RE, 'DB'),)
        else:
            continue
        for regex, kind in groups:
            for match in regex.finditer(segment):
                out.append((number, kind, next(g for g in match.groups() if g), segment.strip()[:70]))
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    parser.add_argument('--doc', action='append', default=None)
    args = parser.parse_args()

    project = os.path.abspath(args.project)
    docs = args.doc or [os.path.join(project, 'docs', name) for name in
                        (u'小书房-项目总览.md', u'小书房-功能清单.md', u'发版记录.md')]
    values = truth(project)
    if values is None:
        print('[FAIL] 读不到代码真值（--project 指对了吗）：%s' % project)
        return 2

    print('代码真值：versionName %s / versionCode %s / DB v%s / 单测 %s / androidTest %s / %s'
          % (values['versionName'], values['versionCode'], values['DB'], values['单测'],
             values['androidTest'],
             'lint %s error / %s 告警（%s）' % (values['lint error'], values['lint 告警'],
                                            os.path.relpath(values['_lint_source'], project))
             if values.get('lint error') else 'lint 报告缺失，本轮不比对 lint 数字'))

    drift = total = skipped = 0
    for doc in docs:
        if not os.path.isfile(doc):
            print('\n· 文档缺失，跳过：%s' % os.path.relpath(doc, project).replace(os.sep, '/'))
            skipped += 1
            continue
        rel = os.path.relpath(doc, project).replace(os.sep, '/')
        rows = claims(doc)
        print('\n· %s：%d 处当前态表述' % (rel, len(rows)))
        for number, kind, claimed, snippet in rows:
            actual = values.get(kind)
            if actual is None:
                print('   ?  行%-4d %-12s 声称 %s（无真值可比）' % (number, kind, claimed))
                continue
            total += 1
            flag = 'OK  ' if claimed == actual else 'DRIFT'
            if claimed != actual:
                drift += 1
                print('   %s 行%-4d %-12s 声称 %-8s 实际 %-8s ｜ %s' % (flag, number, kind, claimed, actual, snippet))
    print('\n比对 %d 处：一致 %d，漂移 %d，跳过文档 %d 份' % (total, total - drift, drift, skipped))
    if total == 0:
        sys.stdout.flush()
        print('[FAIL] 本轮一句当前态都没比到（文档缺失 %d 份）：空跑不算绿，'
              '要么 docs/ 没进仓库，要么 --project 指错了' % skipped, file=sys.stderr)
        return 2
    if drift:
        print('[FAIL] 文档与代码有差异，按上面的行号改文档（或确认真值是否该变）')
        return 1
    print('[OK] 文档与代码零差异')
    return 0


if __name__ == '__main__':
    sys.exit(main())
