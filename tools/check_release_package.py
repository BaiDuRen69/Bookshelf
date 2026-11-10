#!/usr/bin/env python3
"""交付包准入校验：这个 APK 能不能发给用户（上一轮 scripts/pack.ps1 里 aapt 那段的脚本化版本）。

只做机器可判定的三件事：
1. 包名必须等于正式版 applicationId —— com.bai.bookshelf.devtest（debug 构建）禁止进交付目录；
2. native-code 只能是 arm64-v8a / armeabi-v7a —— 混进 x86 说明 abiFilters 被注释掉了还没改回；
3. 包里的 versionName 必须等于 app/build.gradle 声明的版本 —— 防止拿旧产物交差。

aapt 打不开含中文的路径（静默无输出），所以校验前先把 APK 拷到系统临时目录的纯英文路径。

用法：
    python -X utf8 tools/check_release_package.py [--apk <路径>] [--aapt <路径>]
默认 --apk = app/build/outputs/apk/release/app-release.apk
退出码：0 = 可交付；1 = 判定不通过；2 = 环境缺 aapt / 找不到 APK。
"""

import argparse
import hashlib
import io
import os
import re
import shutil
import subprocess
import sys
import tempfile

DEFAULT_PACKAGE = 'com.bai.bookshelf'
ALLOWED_ABIS = ('arm64-v8a', 'armeabi-v7a')
PKG_RE = re.compile(r"^package:\s*name='([^']*)'\s+versionCode='([^']*)'\s+versionName='([^']*)'", re.M)
ABI_RE = re.compile(r'^native-code:\s*(.*)$', re.M)
QUOTED_RE = re.compile(r"'([^']+)'")
BUILD_GRADLE_VERSION_RE = re.compile(r'versionName\s+"([^"]+)"')


def find_aapt(explicit):
    if explicit:
        return explicit if os.path.isfile(explicit) else None
    roots = [os.environ.get('ANDROID_HOME'), os.environ.get('ANDROID_SDK_ROOT'),
             os.path.expandvars(r'%LOCALAPPDATA%\Android\Sdk')]
    for root in [r for r in roots if r]:
        build_tools = os.path.join(root, 'build-tools')
        if not os.path.isdir(build_tools):
            continue
        for version in sorted(os.listdir(build_tools), reverse=True):
            for name in ('aapt', 'aapt.exe'):
                candidate = os.path.join(build_tools, version, name)
                if os.path.isfile(candidate):
                    return candidate
    return shutil.which('aapt')


def declared_version(project):
    path = os.path.join(project, 'app', 'build.gradle')
    if not os.path.isfile(path):
        return None
    match = BUILD_GRADLE_VERSION_RE.search(io.open(path, encoding='utf-8', errors='replace').read())
    return match.group(1) if match else None


def badging(aapt, apk):
    """把 APK 拷到英文临时路径再喂给 aapt（中文路径它会静默返回空）。"""
    staged = os.path.join(tempfile.gettempdir(), 'xsf_check_%d.apk' % os.getpid())
    shutil.copyfile(apk, staged)
    try:
        result = subprocess.run([aapt, 'dump', 'badging', staged],
                                capture_output=True, text=True, encoding='utf-8', errors='replace')
        return result.stdout or '', result.returncode
    finally:
        os.remove(staged)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', default=None)
    parser.add_argument('--aapt', default=None)
    parser.add_argument('--package', default=DEFAULT_PACKAGE)
    parser.add_argument('--allow-x86', action='store_true', help='本地调试用：不为交付时准备')
    args = parser.parse_args()

    project = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    apk = os.path.abspath(args.apk or os.path.join(project, 'app', 'build', 'outputs', 'apk',
                                                   'release', 'app-release.apk'))
    if not os.path.isfile(apk):
        print('[SKIP/FAIL] 找不到 APK：%s（先跑 gradle assembleRelease）' % apk)
        return 2
    aapt = find_aapt(args.aapt)
    if not aapt:
        print('[SKIP/FAIL] 环境里找不到 aapt（设 ANDROID_HOME 或用 --aapt 指定）')
        return 2

    allowed = set(ALLOWED_ABIS) | ({'x86', 'x86_64'} if args.allow_x86 else set())
    with io.open(apk, 'rb') as handle:
        digest = hashlib.sha256(handle.read()).hexdigest()
    size = os.path.getsize(apk)

    text, code = badging(aapt, apk)
    if code != 0 or not text:
        print('[FAIL] aapt dump badging 失败（退出码 %s）：%s' % (code, apk))
        return 1
    pkg_match = PKG_RE.search(text)
    if not pkg_match:
        print('[FAIL] badging 里解析不到 package 行')
        return 1
    name, version_code, version_name = pkg_match.groups()
    abi_match = ABI_RE.search(text)
    abis = QUOTED_RE.findall(abi_match.group(0)) if abi_match else []

    problems = []
    if name != args.package:
        problems.append('包名 %s 不是 %s（.devtest 禁止进交付目录）' % (name, args.package))
    if not abis:
        problems.append('没有 native-code 行（包内无 so，不对劲）')
    stray = [abi for abi in abis if abi not in allowed]
    if stray:
        problems.append('含非交付 ABI：%s（只允许 %s）' % (', '.join(stray), ', '.join(ALLOWED_ABIS)))
    declared = declared_version(project)
    if declared and version_name != declared:
        problems.append('包内 versionName %s ≠ app/build.gradle 声明的 %s（产物是旧的）'
                        % (version_name, declared))

    print('APK     : %s' % apk)
    print('包名    : %s (versionCode %s / versionName %s)' % (name, version_code, version_name))
    print('ABI     : %s' % (', '.join(abis) if abis else '（无）'))
    print('SHA-256 : %s' % digest)
    print('体积    : %d 字节（%.2f MB）' % (size, size / 1048576.0))
    if problems:
        print('判定    : 不可交付')
        for problem in problems:
            print('  [FAIL] %s' % problem)
        return 1
    print('判定    : 可交付')
    return 0


if __name__ == '__main__':
    sys.exit(main())
