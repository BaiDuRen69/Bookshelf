#!/usr/bin/env python3
"""按统计页四栏逐屏抓取可见文本 + 截图，用于改前/改后对比。

用法：python -X utf8 tools/capture_stats_tabs.py <输出目录前缀>

只连已授权的测试机 de5090fb（换机要改 SER，且属于未授权设备，别动）。
产物是 <前缀>/<栏名>_NN.png 与 <前缀>/<栏名>_all.txt；txt 用来对文案，png 用来看排版。
这是**审计工具不是闸门**：抓不到标记时打印 MISS 并退 0，由人判读。
前身是本轮的一次性脚本 tmp/capture_stats_tabs.py。
"""
import os
import re
import subprocess
import sys
import time

SER = ["-s", "de5090fb"]
PKG = "com.bai.bookshelf.devtest"
DOCK_STATS = (410, 2206)
TAB_CENTERS = {
    "books": (176, 311),
    "reading": (418, 311),
    "purchase": (660, 311),
    "lend": (903, 311),
}
# 每栏用来确认「这一栏确实切过去了」的独有标记
TAB_MARKER = {
    "books": "已读率",
    "reading": "阅读时长",
    "purchase": "每页均价",
    "lend": "在借与逾期",
}


def adb(*args):
    cmd = ["adb"] + SER + list(args)
    return subprocess.run(cmd, capture_output=True, text=True,
                          encoding="utf-8", errors="replace")


def awake():
    return "mWakefulness=Awake" in adb("shell", "dumpsys", "power").stdout


def wake():
    """只在息屏时唤醒；亮屏时再划一下会变成页面里的滚动。"""
    if awake():
        return
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    time.sleep(1.5)
    adb("shell", "input", "swipe", "540", "2200", "540", "900", "300")
    time.sleep(1.0)


def focus():
    """只看 mCurrentFocus：dumpsys window 全文里有后台窗口，会误判成前台。"""
    for line in adb("shell", "dumpsys", "window").stdout.splitlines():
        if "mCurrentFocus" in line:
            return line
    return ""


def in_app():
    return PKG in focus()


def ensure_app():
    for _ in range(4):
        if in_app():
            return True
        wake()
        adb("shell", "am", "start", "-n", f"{PKG}/com.bai.bookshelf.ui.MainActivity")
        time.sleep(4)
    print(f"  !! 前台始终不是被测包：{focus().strip()}")
    return False


def dump():
    """MIUI 上 uiautomator 偶发 null root node，失败会留着旧 /sdcard/ui.xml，必须重试而不是照读。"""
    for _ in range(5):
        r = adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
        if "ERROR" in r.stdout or "ERROR" in r.stderr:
            wake()
            ensure_app()
            continue
        out = adb("shell", "cat", "/sdcard/ui.xml").stdout
        if "<hierarchy" in out:
            return out
    raise RuntimeError("uiautomator dump kept failing")


def rows(xml):
    out = []
    for m in re.finditer(r'<node[^>]*?text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',
                         xml):
        t = m.group(1).strip()
        if not t:
            continue
        out.append(((int(m.group(3)) + int(m.group(5))) // 2,
                    (int(m.group(2)) + int(m.group(4))) // 2, t))
    return out


def to_top():
    for _ in range(6):
        adb("shell", "input", "swipe", "540", "900", "540", "1950", "250")
        time.sleep(0.3)
    time.sleep(0.8)


def main():
    prefix = sys.argv[1] if len(sys.argv) > 1 else "tmp/stats-capture"
    os.makedirs(prefix, exist_ok=True)
    wake()
    if not ensure_app():
        print("app not in foreground，未抓到任何东西")
        return 0
    adb("shell", "input", "tap", str(DOCK_STATS[0]), str(DOCK_STATS[1]))
    time.sleep(2.5)
    for name, (cx, cy) in TAB_CENTERS.items():
        if not ensure_app():
            print(f"中断：{name} 栏无法回到被测 App 前台")
            return 0
        adb("shell", "input", "tap", str(cx), str(cy))
        time.sleep(2.0)
        to_top()
        lines, seen = [], set()
        for i in range(12):
            if not in_app():
                print(f"  !! 前台被抢，{name} 在第 {i} 屏中断")
                break
            xml = dump()
            sig = "|".join(r[2] for r in rows(xml))
            if sig in seen:
                break
            seen.add(sig)
            for _, _, t in rows(xml):
                lines.append(t)
            adb("shell", "screencap", "-p", f"/sdcard/{name}_{i:02d}.png")
            adb("pull", f"/sdcard/{name}_{i:02d}.png", f"{prefix}/{name}_{i:02d}.png")
            adb("shell", "input", "swipe", "540", "1900", "540", "560", "450")
            time.sleep(0.9)
        blob = "\n".join(lines)
        marker = TAB_MARKER[name]
        print(f"{name}: screens={len(seen)} lines={len(lines)} "
              f"marker<{marker}>={'HIT' if marker in blob else 'MISS'}")
        with open(f"{prefix}/{name}_all.txt", "w", encoding="utf-8") as f:
            f.write(blob)
    return 0


if __name__ == "__main__":
    sys.exit(main())
