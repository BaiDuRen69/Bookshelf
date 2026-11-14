#!/usr/bin/env python3
"""文档说的数字必须等于代码里的数字（防「文档比代码旧」）。

从代码读真值：app/build.gradle 的 versionName/versionCode、AppDatabase.VERSION、
app/src/test 与 app/src/androidTest 的 @Test 计数，（可选）lint 报告里的 error/告警数；
再在文档里找「当前态」表述（所在行含「当前」/「最新」才算，历史沿革里的 DB v17 那种不动）。
对不上就逐行列出，退出码非 0。

用法：
    python -X utf8 tools/check_doc_drift.py [--doc <路径> ...] [--project <工程根>]
                                          [--lint-report <lint 报告路径>]
默认文档 = 仓库根的 README.md + docs/ 的小书房-项目总览.md + 小书房-功能清单.md + 发版记录.md。
README 也在清单里是因为它最容易被当成「营销文案」而放着旧版本号不管：v5.9.5 那轮它是唯一
写着「最新版本 v5.9.0（versionCode 136，Room 数据库 v24）」的文件，比不到它等于漏一整类漂移。
**不再兜底上一级工作空间**：CI 只 checkout 本仓库，上位目录在 CI 上必然不存在，
留着兜底就等于让本机比到 0 处还报绿（v5.9.5 那次假绿就是这么来的）。

另有三条**不看代码真值**的判定（数字全对也可能红）：

- 跨文档一致性（I1 · T2）：发版记录台账的最新可交付行写「未复跑」，而项目总览声明
  androidTest 已整套复跑 → 两份文档互相矛盾，交付判定失去单一真值，`exit 2`。
  这条比的不是代码真值而是两份文档彼此，所以单看任何一份都发现不了。
- 交付口径唯一性（I2 · T6）：总览里不许同时把 `debug` 和 `release` 说成**当前**交付物。
  按小句切开判，带「曾/以前/历史上」的沿革句不算当前态，所以
  「v4.1–v4.22 曾以 debug 构建交付；v4.23 起 release 为唯一交付物」是合法的。
- 基线陈旧条目（I2 · T6）：lint 报告里出现「listed in the baseline file … but not found in
  the project」（id `LintBaselineFixed`，severity 是 **Hint**，按 Error/Warning 过滤会漏）
  → `app/baseline.xml` 里登记着项目里已不存在的条目，`exit 2`。

三条都沿用防空跑约定：**扫描根缺失 / 0 比对一律 `exit 2`**，不许静默跳过算绿。
`--lint-report` 是给负向自测开的口：自测要能喂「有陈旧条目」与「干净」两份夹具跑红绿双向，
而不是被真实基线的当前状态绑死（真实基线此刻就有一条陈旧条目，见 docs/改进方案-2026-11-10.md 遗留 7）。

退出码：0 = 零差异且三条判定都过；1 = 有数字漂移；
        2 = 读不到代码真值 / 一句当前态都没比到 / 台账与总览矛盾 / 交付口径互斥 /
            基线有陈旧条目 / 任一判定的扫描根缺失。
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
# lint 一条新问题都没有时不写「0 errors, 0 warnings」，而且控制台与文本报告用的还不是同一句：
# 控制台「Lint found no new issues」，lint-results-*.txt「No errors or warnings」。
# 两句都不认就会落到「报告缺失」分支，把 lint 比对整段静默跳过（假绿）。
LINT_CLEAN_RE = re.compile(r'Lint found no new issues|No errors or warnings')
CODE_VERSION_RE = re.compile(r'versionName\s+"([^"]+)"')
CODE_CODE_RE = re.compile(r'versionCode\s+(\d+)')
DB_CONST_RE = re.compile(r'VERSION\s*=\s*(\d+)')

# 交付口径唯一性（I2 · T6）：同一份总览里不许同时把 debug 和 release 说成当前交付物。
# 按小句切开再判，带过去时标记的小句（「v4.1–v4.22 曾以 debug 构建交付」）是沿革不是当前态。
# 反引号必须容错：仓库里写的是 `debug`＝交付物，按字面 "debug＝交付物" grep 会漏掉它。
DELIVERY_PAST = (u'曾', u'以前', u'历史上', u'原先', u'此前')
CLAUSE_SPLIT_RE = re.compile(u'[。；;\\n]')
DEBUG_DELIVERY_RE = re.compile(u'debug`?\\s*[＝=]\\s*交付物|'
                               u'只交付一个[^。；\\n]*debug\\s*构建|'
                               u'debug\\s*构建[^。；\\n]*交付物')
RELEASE_SOLE_RE = re.compile(u'release`?\\s*(?:为|是|＝|=)\\s*唯一交付物|'
                             u'交付物(?:切换)?为\\s*\\*{0,2}\\s*release|'
                             u'唯一交付物[^。；\\n]*release')

# 基线陈旧条目（I2 · T6）：lint 自己报「基线里登记了、项目里已经找不到」。
# 这条的 severity 是 Hint、id 是 LintBaselineFixed，按 Error/Warning 过滤会整条漏掉；
# 判据只认「listed in the baseline file」+「but not found in the project」同时出现——
# 只有前半句的是正常的 [LintBaseline]（989 条被基线过滤），不是陈旧。
BASELINE_STALE_RE = re.compile(r'(\d+)\s+errors/warnings\s+were\s+listed\s+in\s+the\s+baseline\s+file'
                               r'[^\n]*?but not found in the project')
BASELINE_TYPES_RE = re.compile(r'Unmatched issue types:\s*([A-Za-z0-9_,\s|]+)')

LEDGER_NAME = u'发版记录.md'
OVERVIEW_NAME = u'小书房-项目总览.md'
# 台账列序（1 起）：日期1 文件名2 versionCode3 versionName4 SHA5 体积6 HEAD7 工作区8
#                  正式槽位9 androidTest10 判定11 —— 与 scripts/pack.ps1 的取列下标同一份口径
LEDGER_RERUN_COL = 9
LEDGER_VERDICT_COL = 10
RERUN_CLAIM_RE = re.compile(r'androidTest\s*共\s*(\d+)\s*条[^\n]*?'
                            r'(\d{4}-\d{2}-\d{2})\s*已在\s*([0-9A-Za-z]+)\s*整套复跑')


def read(path):
    return io.open(path, encoding='utf-8', errors='replace').read()


def count_tests(dirpath):
    total = 0
    for dirpath, _, names in os.walk(dirpath):
        for name in names:
            if name.endswith('.java'):
                total += len(TEST_RE.findall(read(os.path.join(dirpath, name))))
    return total


def truth(project, explicit_lint=None):
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
    path = lint_report(project, explicit_lint)
    if path:
        text = read(path)
        counts = None
        if path.endswith('.txt'):
            found = LINT_SUMMARY_RE.findall(text)
            if found:
                counts = found[-1]
            elif LINT_CLEAN_RE.search(text):
                counts = ('0', '0')
        else:
            counts = (str(len(re.findall(r'severity="Error"', text))),
                      str(len(re.findall(r'severity="Warning"', text))))
        if counts:
            lint = {'lint error': counts[0], 'lint 告警': counts[1]}
            values.update(lint)
            values['_lint_source'] = path
        else:
            # 报告在、却读不出数：这是解析器跟不上 lint 的句式，不是「没有报告」。
            # 与缺失分开标记，让 main 判红，否则 lint 比对会被静默跳过。
            values['_lint_unparsed'] = path
    if lint is None:
        values['lint error'] = None
        values['lint 告警'] = None
    return values


def lint_report(project, explicit=None):
    """找 lint 报告：release.txt → debug.txt → release.xml → debug.xml，取第一个存在的。

    数字比对与基线陈旧判定共用这一个入口，免得两条判据看的是两份不同的报告。
    explicit 是给负向自测开的口（同 SettingsBackup.PrefsSource 的思路）：
    自测要能喂「有陈旧条目」和「干净」两份夹具，而不是被真实基线的状态绑死。
    """
    if explicit:
        return explicit if os.path.isfile(explicit) else None
    base = os.path.join(project, 'app', 'build', 'reports')
    for pattern in ('lint-results-%s.txt', 'lint-results-%s.xml'):
        for variant in ('release', 'debug'):
            path = os.path.join(base, pattern % variant)
            if os.path.isfile(path):
                return path
    return None


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


def ledger_latest_deliverable(path):
    """台账最后一条「可交付」数据行 → (行号, androidTest 复跑单元格)；没有则 None。

    表头与分隔行跳过；判定列含「不可交付」的行跳过（历史 .devtest 包就躺在那儿）。
    """
    found = None
    for number, line in enumerate(read(path).split('\n'), 1):
        stripped = line.strip()
        if not stripped.startswith('|'):
            continue
        cells = [c.strip() for c in stripped.strip('|').split('|')]
        if len(cells) <= LEDGER_VERDICT_COL:
            continue
        if cells[0] == u'日期' or all(set(c) <= set('-: ') for c in cells):
            continue
        verdict = cells[LEDGER_VERDICT_COL]
        if u'可交付' not in verdict or u'不可交付' in verdict:
            continue
        found = (number, cells[LEDGER_RERUN_COL])
    return found


def overview_rerun_claim(path):
    """总览里「androidTest 共 N 条，<日期> 已在 <设备> 整套复跑」→ (行号, 条数, 日期, 设备)。"""
    for number, line in enumerate(read(path).split('\n'), 1):
        match = RERUN_CLAIM_RE.search(line)
        if match:
            return (number,) + match.groups()
    return None


def cross_doc_conflict(docs):
    """台账写「未复跑」而总览声明已整套复跑 = 互相矛盾，返回冲突说明（空列表 = 不矛盾）。"""
    ledger = next((d for d in docs if os.path.basename(d) == LEDGER_NAME), None)
    overview = next((d for d in docs if os.path.basename(d) == OVERVIEW_NAME), None)
    if not (ledger and overview and os.path.isfile(ledger) and os.path.isfile(overview)):
        print(u'\n· 跨文档判定跳过：台账与总览没有同时出现在比对口径里')
        return []
    row, claim = ledger_latest_deliverable(ledger), overview_rerun_claim(overview)
    if row is None or claim is None:
        print(u'\n· 跨文档判定跳过：台账可交付行 %s，总览整套复跑声明 %s'
              % (u'有' if row else u'无', u'有' if claim else u'无'))
        return []
    number, cell = row
    if u'未复跑' not in cell:
        print(u'\n· 跨文档判定：台账行%d 复跑列「%s…」与总览行%d 的整套复跑声明不矛盾'
              % (number, cell[:36], claim[0]))
        return []
    return [u'台账 %s 行%d（最新可交付行）androidTest 复跑列写「%s」，而总览 %s 行%d 声明'
            u'「androidTest 共 %s 条，%s 已在 %s 整套复跑」——两份文档对同一件事给出相反结论，'
            u'交付判定失去单一真值。按实测读数回填台账（跑在 devtest/debug 构建上要写明），'
            u'或改掉总览的声明。'
            % (LEDGER_NAME, number, cell, OVERVIEW_NAME, claim[0], claim[1], claim[2], claim[3])]


def delivery_claims(path):
    """总览里「谁是交付物」的当前态断言 → (debug 行列表, release 行列表)。

    按 。；; 换行 切成小句再判：一行里同时写「曾以 debug 交付；v4.23 起 release 为唯一交付物」
    是合法沿革，不能因为整行里两个词都出现就判矛盾。
    """
    debug, release = [], []
    for number, line in enumerate(read(path).split('\n'), 1):
        for clause in CLAUSE_SPLIT_RE.split(line):
            if any(mark in clause for mark in DELIVERY_PAST):
                continue
            if DEBUG_DELIVERY_RE.search(clause):
                debug.append((number, clause.strip()))
            if RELEASE_SOLE_RE.search(clause):
                release.append((number, clause.strip()))
    return debug, release


def delivery_conflict(docs):
    """交付口径唯一性：debug 与 release 不许同时被说成当前交付物。空跑与缺扫描根都算失败。"""
    overview = next((d for d in docs if os.path.basename(d) == OVERVIEW_NAME), None)
    if not (overview and os.path.isfile(overview)):
        return [u'交付口径唯一性判定的扫描根缺失：%s 不在比对口径里或文件不存在，'
                u'判不了交付物口径是否互斥——空跑不算绿' % OVERVIEW_NAME]
    debug, release = delivery_claims(overview)
    if not debug and not release:
        return [u'交付口径唯一性判定：总览里一句「谁是交付物」的当前态断言都没比到（0 比对）——'
                u'空跑不算绿，总览必须写明当前交付物是 release 还是 debug']
    if debug and release:
        return [u'总览同时把两种构建说成当前交付物，交付判定失去单一真值：'
                u'行%d「%s」说 debug 是交付物，行%d「%s」说 release 是唯一交付物。'
                u'当前态只能留一个（v4.23 起是 release），旧口径改写成带「曾」的沿革句。'
                % (debug[0][0], debug[0][1][:60], release[0][0], release[0][1][:60])]
    print(u'\n· 交付口径唯一性：debug 当前态断言 %d 处、release 当前态断言 %d 处，不互斥'
          % (len(debug), len(release)))
    return []


def baseline_stale(project, explicit=None):
    """基线陈旧条目：lint 报「基线里登记了、项目里找不到」就是存量陈旧，必须有人处理。"""
    path = lint_report(project, explicit)
    if not path:
        where = explicit or os.path.join(project, 'app', 'build', 'reports')
        return [u'基线陈旧条判定的扫描根缺失：%s 下找不到 lint 报告'
                u'（先跑 :app:lintRelease，或用 --lint-report 指定）——空跑不算绿' % where]
    text = read(path)
    match = BASELINE_STALE_RE.search(text)
    if not match:
        print(u'\n· 基线陈旧条判定：%s 里没有「listed in baseline but not found」，基线无陈旧条目'
              % os.path.basename(path))
        return []
    types = BASELINE_TYPES_RE.search(text)
    return [u'lint 基线有陈旧条目：%s 报「%s errors/warnings were listed in the baseline file '
            u'but not found in the project」，涉及 issue 类型 %s。'
            u'这些条目在 app/baseline.xml 里登记着、项目里已经不存在，'
            u'基线因此不再是「存量冻结」而是「存量 + 已修好的幽灵」。'
            u'要么重跑 :app:lintRelease 让它自己出基线，要么手工删掉对应条目。'
            % (os.path.basename(path), match.group(1),
               types.group(1).strip() if types else u'（报告里没写类型）')]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    parser.add_argument('--doc', action='append', default=None)
    parser.add_argument('--lint-report', default=None,
                        help='基线陈旧条判定用哪份 lint 报告（默认自动找 release.txt→debug.txt→xml）')
    args = parser.parse_args()

    project = os.path.abspath(args.project)
    docs = args.doc or ([os.path.join(project, 'README.md')] +
                        [os.path.join(project, 'docs', name) for name in
                         (u'小书房-项目总览.md', u'小书房-功能清单.md', u'发版记录.md')])
    values = truth(project, args.lint_report)
    if values is None:
        print('[FAIL] 读不到代码真值（--project 指对了吗）：%s' % project)
        return 2

    print('代码真值：versionName %s / versionCode %s / DB v%s / 单测 %s / androidTest %s / %s'
          % (values['versionName'], values['versionCode'], values['DB'], values['单测'],
             values['androidTest'],
             'lint %s error / %s 告警（%s）' % (values['lint error'], values['lint 告警'],
                                            os.path.relpath(values['_lint_source'], project))
             if values.get('lint error') else 'lint 报告缺失，本轮不比对 lint 数字'))
    if values.get('_lint_unparsed'):
        sys.stdout.flush()
        print('[FAIL] lint 报告读不出尾行数字：%s\n'
              '报告存在但句式不认识（有告警时写「N errors, M warnings」；全清时控制台写'
              '「Lint found no new issues」、文本报告写「No errors or warnings」）。'
              '这时继续跑等于把 lint 比对静默跳过，所以判红：要么按新句式补 '
              'LINT_SUMMARY_RE / LINT_CLEAN_RE，要么确认这份报告是不是本次构建产出的。'
              % os.path.relpath(values['_lint_unparsed'], project), file=sys.stderr)
        return 2

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
    conflicts = cross_doc_conflict(docs)
    conflicts += delivery_conflict(docs)
    conflicts += baseline_stale(project, args.lint_report)
    if conflicts:
        sys.stdout.flush()
        for message in conflicts:
            print('[FAIL] %s' % message, file=sys.stderr)
        return 2
    if drift:
        print('[FAIL] 文档与代码有差异，按上面的行号改文档（或确认真值是否该变）')
        return 1
    print('[OK] 文档与代码零差异')
    return 0


if __name__ == '__main__':
    sys.exit(main())
