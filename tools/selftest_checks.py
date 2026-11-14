#!/usr/bin/env python3
"""负向自测：闸门必须在「该红的时候真的红」（防空跑算绿 / 防护栏没通电）。

两类形状都覆盖：

1. 指错路径。A1 修掉的两条假绿靠这个守着：check_doc_drift 曾经一句当前态都没比到还 return 0，
   check_activities 曾经一个待判定 Activity 都没扫到照样打 [OK]。指错目录就是那两种空跑的
   形状，所以把上一级目录（本工程 = 仓库根，`..` 就是工作空间）当错误输入喂给每条检查；
   参数没被接受（argparse 的 usage 退出码也是 2）算无效，不算拦住。

2. 带阈值的护栏（卡 5 的 `count_cn_literals.py --max`）。护栏自己的失效形状不是空跑，而是
   「阈值写反方向 / 判据压根没参与退出码」，所以两个方向都要试：**调高必须仍绿、调低必须红**，
   外加一条「指定的文件不存在也要判定性非 0」。只看真实数据那一次绿灯，证明不了任何事。

3. 跨文档一致性（I1 · T2 的 `check_doc_drift.py` 台账 vs 总览）。这条判据比的是两份文档彼此，
   失效形状是「夹具造不出矛盾 / 矛盾了也不红」，所以用临时文档对跑红绿双向：
   台账写「未复跑」+ 总览声明已整套复跑 → 必须 exit 2 且点名矛盾；台账回填读数后 → 必须绿。
   夹具里特意留一条「不可交付」行，取错行（取第一条可交付 / 取最后一行）都会让红用例失效。
   夹具的当前态数字取自 `check_doc_drift.truth()`，不写死，免得版本一抬自测自己先红。

4. 交付口径唯一性（I2 · T6）。失效形状是「互斥了也不红」与「把沿革句当当前态误红」，
   所以四条都跑：debug+release 同时当前 → 红；只有 release → 绿；
   「曾以 debug 交付；v4.23 起 release 为唯一交付物」→ 绿（沿革不算当前态）；
   一句交付口径都没有 → 红（0 比对不许算绿）。外加扫描根缺失（不喂总览）→ 红。

5. 基线陈旧条目（I2 · T6）。失效形状是「正则把正常的基线过滤消息也当陈旧」，所以干净夹具里
   **特意保留**「989 warnings … listed in the baseline file [LintBaseline]」那一行：
   判据必须只认「but not found in the project」，否则干净用例就会假红。
   陈旧夹具 → exit 2 且点名 SetTextI18n；干净夹具 → 绿；报告路径不存在 → exit 2。
   用 `--lint-report` 喂夹具，不依赖真实基线的当前状态（真实基线此刻就有一条陈旧条目）。

秒级到十秒级，不编译、不装机（阈值那三条要扫全项目 Java，约两秒）。

用法：
    python -X utf8 tools/selftest_checks.py [--project <工程根，默认脚本上一级>]
退出码：0 = 每条都被拦住、阈值两个方向都判得对、跨文档/交付口径/基线陈旧红绿都对；
        1 = 有脚本空跑仍 return 0、阈值失灵、任一判定不成立，或负向参数根本没接上。
"""

import argparse
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
# 夹具要用同目录闸门的 truth() 取代码真值；按 -m 或从别处调用时脚本目录不一定在 sys.path 上
if HERE not in sys.path:
    sys.path.insert(0, HERE)
sys.dont_write_bytecode = True  # 否则 import 会在 tools/ 里留下 __pycache__，污染工作区
import check_doc_drift

CHECKS = ('check_activities.py', 'check_doc_drift.py', 'check_release_package.py')

# (脚本, 用例名, 参数, 期望) —— 期望 'green' = exit 0；'red' = 判定性非 0（且不能是 usage 退出）
THRESHOLD_CASES = (
    ('count_cn_literals.py', '阈值调高仍绿', ['--max', '999999'], 'green'),
    ('count_cn_literals.py', '阈值调低必须红', ['--max', '1'], 'red'),
    ('count_cn_literals.py', '指定的文件不存在', ['no/such/File.java'], 'red'),
)

# (用例名, 台账 androidTest 复跑单元格, 期望) —— 红必须 exit 2 且点名矛盾，绿必须 exit 0
CROSS_DOC_CASES = (
    (u'台账未复跑 vs 总览已整套复跑', u'未复跑（见 f86b473 提交说明）', 'red'),
    (u'台账已回填复跑读数', u'179/179，de5090fb，2026-11-11', 'green'),
)

LEDGER_FIXTURE = u"""# 发版记录（自测夹具）

| 日期 | 文件名 | versionCode | versionName | SHA-256 | 体积 | 产出时 HEAD | 工作区 | 正式槽位安装 | androidTest 复跑 | 判定 |
|---|---|---|---|---|---|---|---|---|---|---|
| 2026-11-08 | `XiaoShuFang-v5.9.4.apk` | 138 | 5.9.4 | `149dc520` | 76 867 328 B | 未记录 | 未记录 | 不适用 | 未记录 | **不可交付**：debug 包名 + 混入 x86 |
| 2026-11-09 | `XiaoShuFang-v5.9.5.apk` | 139 | 5.9.5 | `f1c00c68` | 38 919 971 B | `ed26e56` | 不干净 | 待填 | %s | 可交付，表内最新一条 |
"""

OVERVIEW_FIXTURE = (u'# 项目总览（自测夹具）\n\n'
                    u'**当前版本 %s（versionCode %s，DB v%s，单测 %s；'
                    u'androidTest 共 %s 条，2026-11-11 已在 de5090fb 整套复跑完）**\n')

# 交付口径用例：(用例名, 追加到总览夹具正文的行, 期望)
# 夹具正文自带当前态数字，所以 total>0，exit 2 只可能来自交付口径判定本身。
RELEASE_ONLY = u'\n- **构建类型（当前）**：`release` 是唯一交付物；`debug` 只用于抓包调试。\n'
DELIVERY_CASES = (
    (u'debug 与 release 同时是当前交付物',
     u'\n- `debug`＝交付物（功能全开、明文流量放行）。\n- `release` 是唯一交付物。\n', 'red'),
    (u'只有 release 是当前交付物', RELEASE_ONLY, 'green'),
    (u'沿革句不算当前态',
     u'\n- v4.1–v4.22 曾只交付一个 APK（debug 构建，功能全开）；v4.23 起 release 为唯一交付物。\n', 'green'),
    (u'一句交付口径都没有', u'\n- 本节只讲版本号与数据库。\n', 'red'),
)

# 基线陈旧用例：干净夹具特意留着正常的 [LintBaseline] 行，证明判据不是「提到基线就红」
LINT_CLEAN_FIXTURE = (
    u'D:\\proj\\app\\baseline.xml: Hint: 989 warnings were filtered out because they are '
    u'listed in the baseline file, baseline.xml [LintBaseline]\n\n'
    u'0 errors, 6 warnings (and 989 warnings filtered by baseline baseline.xml)\n')
LINT_STALE_FIXTURE = (
    u'D:\\proj\\app\\baseline.xml: Hint: 989 warnings were filtered out because they are '
    u'listed in the baseline file, baseline.xml [LintBaseline]\n'
    u'D:\\proj\\app\\baseline.xml: Hint: 1 errors/warnings were listed in the baseline file '
    u'(baseline.xml) but not found in the project; perhaps they have been fixed? '
    u'Unmatched issue types: SetTextI18n [LintBaselineFixed]\n\n'
    u'0 errors, 6 warnings (and 989 warnings filtered by baseline baseline.xml)\n')

# (用例名, 喂哪份报告, 期望)
BASELINE_CASES = (
    (u'基线有陈旧条目', 'stale', 'red'),
    (u'基线干净但仍含正常过滤消息', 'clean', 'green'),
    (u'报告路径不存在', 'missing', 'red'),
)

# lint 尾行句式用例：一条新问题都没有时 lint 不写「0 errors, 0 warnings」，
# 而且控制台与文本报告用的不是同一句（控制台「Lint found no new issues」、
# lint-results-*.txt「No errors or warnings」）。认不出就会落进「报告缺失」分支，
# 把 lint 比对整段静默跳过（假绿），所以四种句式都钉住。
LINT_NUMERIC_FIXTURE = (
    u'D:\\proj\\app\\build.gradle:92: Warning: A newer version of x than 1 is available: 2 '
    u'[NewerVersionAvailable]\n\n'
    u'0 errors, 6 warnings (and 989 warnings filtered by baseline baseline.xml)\n')
LINT_NOISSUE_REPORT_FIXTURE = (
    u'D:\\proj\\app\\baseline.xml: Hint: 989 warnings were filtered out because they are '
    u'listed in the baseline file, baseline.xml [LintBaseline]\n'
    u'No errors or warnings (and 989 warnings filtered by baseline baseline.xml)\n')
LINT_NOISSUE_CONSOLE_FIXTURE = (
    u'> Task :app:lintReportRelease\n'
    u'Lint found no new issues (and 989 warnings filtered by baseline baseline.xml)\n')
LINT_UNKNOWN_FIXTURE = u'lint 换了句式：这里既没有数字尾行，也没有全清那两句。\n'

# (用例名, 报告夹具, 期望 exit, 输出里必须出现的片段)
LINT_SUMMARY_CASES = (
    (u'数字句式读出 6 告警', LINT_NUMERIC_FIXTURE, 0, u'lint 0 error / 6 告警'),
    (u'报告全清句式读出 0 告警', LINT_NOISSUE_REPORT_FIXTURE, 0, u'lint 0 error / 0 告警'),
    (u'控制台全清句式读出 0 告警', LINT_NOISSUE_CONSOLE_FIXTURE, 0, u'lint 0 error / 0 告警'),
    (u'认不出的句式必判红', LINT_UNKNOWN_FIXTURE, 2, u'读不出尾行数字'),
)


def tail(text, limit=2):
    lines = [ln for ln in (text or '').splitlines() if ln.strip()]
    return ' / '.join(lines[-limit:]) if lines else '(无输出)'


def negative_args(name, wrong):
    """每条检查各自的「指错目录」写法；exit 必须是判定出来的非 0，不能是 argparse 用法错误。"""
    if name == 'check_release_package.py':
        # 该脚本的 --project 由自身位置推导，不接受外部工程根，所以喂一个不存在的 APK
        return ['--apk', os.path.join(wrong, 'no-such-release.apk')]
    return ['--project', wrong]


def run(name, args):
    cmd = [sys.executable, '-X', 'utf8', os.path.join(HERE, name)] + args
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            universal_newlines=True, encoding='utf-8', errors='replace')
    out, err = proc.communicate()
    return proc.returncode, out, err


def is_usage(err):
    return 'usage:' in (err or '').lower()


def write_cross_doc_fixture(tmpdir, project, rerun_cell, delivery=RELEASE_ONLY):
    """造一对临时文档：台账（含一条不可交付行 + 一条可交付行）与总览（当前态数字取代码真值）。

    总览默认带上「release 是唯一交付物」，好让交付口径判定在跨文档用例里保持绿——
    否则红用例的 exit 2 可能来自交付口径而不是台账矛盾，证明不了想证明的那条判据。
    """
    values = check_doc_drift.truth(project)
    if values is None:
        return None
    ledger = os.path.join(tmpdir, check_doc_drift.LEDGER_NAME)
    overview = os.path.join(tmpdir, check_doc_drift.OVERVIEW_NAME)
    with open(ledger, 'w', encoding='utf-8') as handle:
        handle.write(LEDGER_FIXTURE % rerun_cell)
    with open(overview, 'w', encoding='utf-8') as handle:
        handle.write((OVERVIEW_FIXTURE % (values['versionName'], values['versionCode'],
                                          values['DB'], values[u'单测'], values['androidTest']))
                     + delivery)
    return ledger, overview


def write_lint_fixture(tmpdir, name, text):
    path = os.path.join(tmpdir, name)
    with open(path, 'w', encoding='utf-8') as handle:
        handle.write(text)
    return path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    args = parser.parse_args()

    project = os.path.abspath(args.project)
    wrong = os.path.dirname(project)
    print('工程根：%s' % project)
    print('负向输入目录：%s（工作空间根，不是工程根）\n' % wrong)

    bad = []
    fail1 = fail2 = fail3 = 0
    print('一、闸门指错目录时必须给出判定性非 0')
    for name in CHECKS:
        if not os.path.isfile(os.path.join(HERE, name)):
            print('  MISS %-24s 脚本不存在' % name)
            fail1 += 1
            bad.append(name)
            continue
        code, out, err = run(name, negative_args(name, wrong))
        if is_usage(err):
            expect, why = '无效', '参数没被接受（argparse 就退出了，证明不了判定）'
        else:
            expect = 'PASS' if code != 0 else '假绿'
            why = 'stdout: %s ｜ stderr: %s' % (tail(out), tail(err))
        print('  %-4s %-24s exit=%-3s %s' % (expect, name, code, why))
        if expect != 'PASS':
            fail1 += 1
            bad.append('%s %s' % (name, why))

    print('\n二、阈值护栏两个方向都要判（防「护栏其实没通电」）')
    for name, case, cmd_args, expect in THRESHOLD_CASES:
        if not os.path.isfile(os.path.join(HERE, name)):
            print('  MISS %-24s %s 脚本不存在' % (name, case))
            fail2 += 1
            bad.append('%s %s' % (name, case))
            continue
        code, out, err = run(name, cmd_args)
        if is_usage(err):
            verdict, why = '无效', '参数没被接受（argparse 就退出了）'
        elif expect == 'green':
            verdict = 'PASS' if code == 0 else '失灵'
            why = tail(out, 1) if code == 0 else '本该仍绿却红了：' + tail(err, 1)
        else:
            verdict = 'PASS' if code != 0 else '失灵'
            why = tail(err, 1) if code != 0 else '压到 1 条照样 return 0 —— 判据没参与退出码'
        print('  %-4s %-24s exit=%-3s %-18s %s' % (verdict, case, code, ' '.join(cmd_args), why))
        if verdict != 'PASS':
            fail2 += 1
            bad.append('%s %s' % (name, case))

    # 夹具必须与工程同盘：check_doc_drift 用 os.path.relpath 打印文档名，跨盘（C: vs D:）会抛 ValueError
    fixture_root = os.path.join(project, 'tmp')
    os.makedirs(fixture_root, exist_ok=True)
    tmpdir = tempfile.mkdtemp(prefix='xsf_selftest_', dir=fixture_root)
    fail4 = fail5 = fail6 = 0
    try:
        clean_lint = write_lint_fixture(tmpdir, 'lint-clean.txt', LINT_CLEAN_FIXTURE)
        stale_lint = write_lint_fixture(tmpdir, 'lint-stale.txt', LINT_STALE_FIXTURE)

        print('\n三、跨文档一致性：台账 vs 总览（临时夹具，红绿双向）')
        for case, cell, expect in CROSS_DOC_CASES:
            fixture = write_cross_doc_fixture(tmpdir, project, cell)
            if fixture is None:
                print('  %-4s %-28s 读不到代码真值，夹具造不出来' % ('失灵', case))
                fail3 += 1
                bad.append(u'跨文档 %s' % case)
                continue
            ledger, overview = fixture
            code, out, err = run('check_doc_drift.py',
                                 ['--project', project, '--doc', ledger, '--doc', overview,
                                  '--lint-report', clean_lint])
            if expect == 'red':
                # exit 2 必须来自矛盾判定：夹具当前态数字取真值、交付口径只留 release、
                # 基线喂干净夹具，漂移/空跑/交付互斥/基线陈旧四条路都走不到
                ok = code == 2 and u'单一真值' in (err or '')
                why = (u'stderr 点名矛盾：%s' % tail(err, 1)) if ok else \
                      (u'本该 exit 2 且点名矛盾，实际 exit=%s：%s' % (code, tail(err, 1)))
            else:
                ok = code == 0
                why = tail(out, 1) if ok else (u'回填之后本该绿：' + tail(err, 1))
            print('  %-4s %-28s exit=%-3s %s' % ('PASS' if ok else '失灵', case, code, why))
            if not ok:
                fail3 += 1
                bad.append(u'跨文档 %s' % case)

        print('\n四、交付口径唯一性：debug 与 release 不许同时被说成当前交付物')
        for case, delivery, expect in DELIVERY_CASES:
            fixture = write_cross_doc_fixture(tmpdir, project, u'179/179，de5090fb，2026-11-11',
                                              delivery)
            if fixture is None:
                print('  %-4s %-30s 读不到代码真值，夹具造不出来' % ('失灵', case))
                fail4 += 1
                bad.append(u'交付口径 %s' % case)
                continue
            _, overview = fixture
            # 只喂总览：台账不在口径里，跨文档判定自己跳过，exit 2 只可能来自交付口径
            code, out, err = run('check_doc_drift.py',
                                 ['--project', project, '--doc', overview,
                                  '--lint-report', clean_lint])
            if expect == 'red':
                ok = code == 2 and (u'交付物' in (err or '') or u'交付口径' in (err or ''))
                why = (u'stderr：%s' % tail(err, 1)) if ok else \
                      (u'本该 exit 2 且点名交付口径，实际 exit=%s：%s' % (code, tail(err, 1)))
            else:
                ok = code == 0
                why = tail(out, 1) if ok else (u'本该绿却红了：' + tail(err, 1))
            print('  %-4s %-30s exit=%-3s %s' % ('PASS' if ok else '失灵', case, code, why))
            if not ok:
                fail4 += 1
                bad.append(u'交付口径 %s' % case)

        # 扫描根缺失：口径里有当前态数字、但没有任何一份叫「项目总览」→ 判不了互斥，必须 exit 2。
        # 不能只喂台账：台账夹具一句当前态都没有，会先被「0 比对」那条空跑护栏拦掉，
        # 那样 exit 2 证明的是空跑护栏而不是本判定。所以把总览正文改名成 README.md 喂进去。
        fixture = write_cross_doc_fixture(tmpdir, project, u'179/179，de5090fb，2026-11-11')
        renamed = os.path.join(tmpdir, 'README.md')
        with open(fixture[1], encoding='utf-8') as handle:
            body = handle.read()
        with open(renamed, 'w', encoding='utf-8') as handle:
            handle.write(body)
        code, out, err = run('check_doc_drift.py',
                             ['--project', project, '--doc', renamed,
                              '--lint-report', clean_lint])
        ok = code == 2 and u'扫描根缺失' in (err or '')
        print('  %-4s %-30s exit=%-3s %s' % ('PASS' if ok else '失灵', u'总览不在比对口径里', code,
                                            tail(err, 1) if ok else
                                            u'本该 exit 2 且点名扫描根缺失：%s ｜ %s'
                                            % (tail(err, 1), tail(out, 1))))
        if not ok:
            fail4 += 1
            bad.append(u'交付口径 扫描根缺失')

        print('\n五、基线陈旧条目：只认「but not found」，正常的基线过滤消息不算陈旧')
        for case, which, expect in BASELINE_CASES:
            fixture = write_cross_doc_fixture(tmpdir, project, u'179/179，de5090fb，2026-11-11')
            if fixture is None:
                print('  %-4s %-30s 读不到代码真值，夹具造不出来' % ('失灵', case))
                fail5 += 1
                bad.append(u'基线陈旧 %s' % case)
                continue
            _, overview = fixture
            report = {'stale': stale_lint, 'clean': clean_lint,
                      'missing': os.path.join(tmpdir, 'no-such-lint.txt')}[which]
            code, out, err = run('check_doc_drift.py',
                                 ['--project', project, '--doc', overview, '--lint-report', report])
            if expect == 'red':
                ok = code == 2 and (u'陈旧' in (err or '') or u'扫描根缺失' in (err or ''))
                why = (u'stderr：%s' % tail(err, 1)) if ok else \
                      (u'本该 exit 2 且点名陈旧/缺根，实际 exit=%s：%s' % (code, tail(err, 1)))
            else:
                ok = code == 0
                why = tail(out, 1) if ok else \
                      (u'干净基线本该绿（正常 [LintBaseline] 行被误判成陈旧？）：' + tail(err, 1))
            print('  %-4s %-30s exit=%-3s %s' % ('PASS' if ok else '失灵', case, code, why))
            if not ok:
                fail5 += 1
                bad.append(u'基线陈旧 %s' % case)

        print('\n六、lint 尾行句式：数字与全清都要读出数，认不出的句式不许静默跳过')
        for case, fixture_text, expect_code, fragment in LINT_SUMMARY_CASES:
            fixture = write_cross_doc_fixture(tmpdir, project, u'179/179，de5090fb，2026-11-11')
            if fixture is None:
                print('  %-4s %-30s 读不到代码真值，夹具造不出来' % ('失灵', case))
                fail6 += 1
                bad.append(u'lint 尾行 %s' % case)
                continue
            _, overview = fixture
            report = write_lint_fixture(tmpdir, 'lint-shape.txt', fixture_text)
            code, out, err = run('check_doc_drift.py',
                                 ['--project', project, '--doc', overview, '--lint-report', report])
            ok = code == expect_code and fragment in ((out or '') + (err or ''))
            why = tail(out, 1) if ok else \
                (u'本该 exit=%s 且输出含「%s」，实际 exit=%s：%s'
                 % (expect_code, fragment, code, tail((err or '') + (out or ''), 1)))
            print('  %-4s %-30s exit=%-3s %s' % ('PASS' if ok else '失灵', case, code, why))
            if not ok:
                fail6 += 1
                bad.append(u'lint 尾行 %s' % case)
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)

    delivery_total = len(DELIVERY_CASES) + 1
    total = (len(CHECKS) + len(THRESHOLD_CASES) + len(CROSS_DOC_CASES)
             + delivery_total + len(BASELINE_CASES) + len(LINT_SUMMARY_CASES))
    print('\n判定 %d 条：一、指错目录 %d/%d；二、阈值 %d/%d；三、跨文档 %d/%d；'
          '四、交付口径 %d/%d；五、基线陈旧 %d/%d；六、lint 尾行 %d/%d'
          % (total, len(CHECKS) - fail1, len(CHECKS),
             len(THRESHOLD_CASES) - fail2, len(THRESHOLD_CASES),
             len(CROSS_DOC_CASES) - fail3, len(CROSS_DOC_CASES),
             delivery_total - fail4, delivery_total,
             len(BASELINE_CASES) - fail5, len(BASELINE_CASES),
             len(LINT_SUMMARY_CASES) - fail6, len(LINT_SUMMARY_CASES)))
    if bad:
        print('[FAIL] %d 条失灵（空跑算绿 / 阈值不判 / 跨文档不判 / 交付口径不判 / 基线陈旧不判 / '
              'lint 尾行不判 / 负向参数没接上）：%s' % (len(bad), '；'.join(bad)))
        return 1
    print('[OK] 空跑一律非 0；阈值调高仍绿、调低必红；台账与总览矛盾必 exit 2、回填后必绿；'
          'debug/release 互斥必红、沿革句不误红；基线陈旧必红、正常过滤消息不误红；'
          'lint 数字句式与全清句式都读出数、认不出的句式必 exit 2')
    return 0


if __name__ == '__main__':
    sys.exit(main())
