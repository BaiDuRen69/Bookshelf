#!/usr/bin/env python3
"""负向自测：给三条 tools/check_*.py 喂错路径，必须拿到「判定性」的非 0 退出（防空跑算绿）。

A1 修掉的两条假绿靠这个守着：check_doc_drift 曾经一句当前态都没比到还 return 0，
check_activities 曾经一个待判定 Activity 都没扫到照样打 [OK]。指错目录就是那两种空跑
的形状，所以把上一级目录（本工程 = 仓库根，`..` 就是工作空间）当错误输入喂给每条检查；
参数没被接受（argparse 的 usage 退出码也是 2）算无效，不算拦住。秒级，不编译、不装机。

用法：
    python -X utf8 tools/selftest_checks.py [--project <工程根，默认脚本上一级>]
退出码：0 = 每条都被拦住；1 = 有脚本空跑仍 return 0，或负向参数根本没接上。
"""

import argparse
import os
import subprocess
import sys

CHECKS = ('check_activities.py', 'check_doc_drift.py', 'check_release_package.py')


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
    cmd = [sys.executable, '-X', 'utf8',
           os.path.join(os.path.dirname(os.path.abspath(__file__)), name)] + args
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            universal_newlines=True, encoding='utf-8', errors='replace')
    out, err = proc.communicate()
    return proc.returncode, out, err


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    args = parser.parse_args()

    project = os.path.abspath(args.project)
    wrong = os.path.dirname(project)
    here = os.path.dirname(os.path.abspath(__file__))
    print('工程根：%s' % project)
    print('负向输入目录：%s（工作空间根，不是工程根）\n' % wrong)

    bad = []
    for name in CHECKS:
        if not os.path.isfile(os.path.join(here, name)):
            print('  MISS %-26s 脚本不存在' % name)
            bad.append(name)
            continue
        code, out, err = run(name, negative_args(name, wrong))
        if 'usage:' in (err or '').lower():
            expect, why = '无效', '参数没被接受（argparse 就退出了，证明不了判定）'
        else:
            expect = 'PASS' if code != 0 else '假绿'
            why = 'stdout: %s ｜ stderr: %s' % (tail(out), tail(err))
        print('  %-4s %-24s exit=%-3s %s' % (expect, name, code, why))
        if expect != 'PASS':
            bad.append(name)

    print('\n%d/%d 条在指错目录时给出判定性非 0' % (len(CHECKS) - len(bad), len(CHECKS)))
    if bad:
        print('[FAIL] 这些脚本的空跑还没被拦住（假绿或参数没接上）：%s' % ', '.join(bad))
        return 1
    print('[OK] 空跑一律非 0')
    return 0


if __name__ == '__main__':
    sys.exit(main())
