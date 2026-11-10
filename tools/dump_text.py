"""把 uiautomator dump 的 XML 里的 text / resource-id / 中心点坐标打出来。

用法：python -X utf8 tools/dump_text.py <xml 路径>
"""
import re
import sys

ATTR = re.compile(r'(\w[\w-]*)="((?:[^"\\]|\\.)*)"')


def main():
    xml = open(sys.argv[1], encoding="utf-8").read()
    for node in re.findall(r"<node\b[^>]*?/>", xml):
        a = dict(ATTR.findall(node))
        txt = a.get("text", "")
        desc = a.get("content-desc", "")
        if not txt and not desc:
            continue
        b = re.findall(r"\d+", a.get("bounds", ""))
        cx = cy = 0
        if len(b) == 4:
            cx = (int(b[0]) + int(b[2])) // 2
            cy = (int(b[1]) + int(b[3])) // 2
        rid = a.get("resource-id", "").split("/")[-1]
        print("%-70s | %-24s | (%d,%d)" % ((txt or desc)[:70], rid, cx, cy))


main()
