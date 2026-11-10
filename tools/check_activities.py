#!/usr/bin/env python3
"""每个 Activity 必须真的把内容视图设上（文档坑 #41 的直接防线）。

v4.46 用脚本批量改码时把 ScanActivity 的 onCreate / setContentView 整段删掉，Gradle 照常
编译通过、release 正常打包，页面全黑——只有装机才看得出来。这里做纯静态检查：
app/src/main/java 下每个 *Activity.java，要么自己调用 setContentView，要么继承链上有一个
已经设过内容的父类（如 DistributionListActivity）。基类不参与判定——被其它 Activity 继承的
类自动进白名单（本工程就是 BaseActivity，它只做主题/状态栏，不设内容视图）。秒级完成，不编译、不装机。

用法：
    python -X utf8 tools/check_activities.py [--project <工程根，默认脚本上一级>]
退出码：0 = 全部可达；1 = 有 Activity 找不到 setContentView；
2 = 源码目录不存在或一个待判定 Activity 都没扫到（空跑不算绿，否则 --project 指错就假绿）。
"""

import argparse
import io
import os
import re
import sys

SRC_REL = os.path.join('app', 'src', 'main', 'java')
CLASS_RE = re.compile(r'\bclass\s+([A-Za-z0-9_]+)\b[^{;]*?\bextends\s+([A-Za-z0-9_]+)', re.S)
SET_CONTENT_RE = re.compile(r'\bsetContentView\s*\(')
MAX_HOPS = 10


def java_files(root):
    for dirpath, _, names in os.walk(root):
        for name in sorted(names):
            if name.endswith('.java'):
                yield os.path.join(dirpath, name)


def scan(project):
    """simple class name -> (相对路径, 父类名, 是否自己调 setContentView)"""
    classes = {}
    for path in java_files(os.path.join(project, SRC_REL)):
        text = io.open(path, encoding='utf-8', errors='replace').read()
        match = CLASS_RE.search(text)
        if not match:
            continue
        rel = os.path.relpath(path, project).replace(os.sep, '/')
        classes[match.group(1)] = (rel, match.group(2), bool(SET_CONTENT_RE.search(text)))
    return classes


def reach(cls, classes):
    """返回 (是否可达, 命中它的类名链)"""
    chain = [cls]
    current = cls
    for _ in range(MAX_HOPS):
        entry = classes.get(current)
        if entry is None:
            return False, chain          # 父类不在本工程源码里（AppCompatActivity 等），到此为止
        if entry[2]:
            return True, chain           # 这一层自己设了内容
        parent = entry[1]
        if parent in chain:
            return False, chain          # 继承环，防御性退出
        chain.append(parent)
        current = parent
    return False, chain


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    args = parser.parse_args()
    project = os.path.abspath(args.project)
    src = os.path.join(project, SRC_REL)
    if not os.path.isdir(src):
        print('工程：%s' % project)
        sys.stdout.flush()
        print('[FAIL] nothing-to-check 找不到源码目录：%s（一个类都没扫到，不算绿）' % src,
              file=sys.stderr)
        return 2
    classes = scan(project)

    activities = sorted(name for name, (rel, _, _) in classes.items()
                        if rel.endswith('Activity.java') or name.endswith('Activity'))
    # 基类白名单：被其它 Activity 继承的类自己不负责内容视图（本工程 = BaseActivity）
    bases = {parent for _, parent, _ in classes.values() if parent in activities}
    concrete = [name for name in activities if name not in bases]
    if not concrete:
        print('工程：%s' % project)
        print('[FAIL] nothing-to-check 扫到 %d 个 Activity、0 个待判定：空跑不算绿（目录结构被改了吗）'
              % len(activities), file=sys.stderr)
        return 2

    direct = []
    inherited = []
    broken = []
    for name in concrete:
        ok, chain = reach(name, classes)
        if not ok:
            broken.append((name, classes[name][0]))
        elif classes[name][2]:
            direct.append(name)
        else:
            inherited.append((name, ' -> '.join(chain)))

    print('工程：%s' % project)
    print('Activity 类：%d 个｜基类（白名单）%d 个：%s' % (
        len(activities), len(bases), ', '.join(sorted(bases))))
    print('待判定 %d 个：自己调 setContentView %d 个，靠父类链可达 %d 个'
          % (len(concrete), len(direct), len(inherited)))
    for name, chain in inherited:
        print('  继承设内容  %-32s %s' % (name, chain))
    if broken:
        print('\n[FAIL] 以下 %d 个 Activity 的继承链上找不到 setContentView（坑 #41 的形状）：' % len(broken))
        for name, rel in broken:
            print('  %s  (%s)' % (name, rel))
        return 1
    print('[OK] 每个 Activity 都有可达的 setContentView')
    return 0


if __name__ == '__main__':
    sys.exit(main())
