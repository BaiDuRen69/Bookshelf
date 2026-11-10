"""从 uiautomator dump 的 XML 里按文本片段找到节点中心点，打印 "x y"。

用法：python -X utf8 tools/tap_target.py <xml 路径> <文本片段>
找不到时打印 NOT_FOUND。
"""
import re
import sys

ATTR = re.compile(r'(\w[\w-]*)="((?:[^"\\]|\\.)*)"')
NUM = re.compile(r"\d+")


def main():
    xml = open(sys.argv[1], encoding="utf-8").read()
    needle = sys.argv[2]
    for node in re.findall(r"<node\b[^>]*?/>", xml):
        a = dict(ATTR.findall(node))
        if needle in (a.get("text") or a.get("content-desc") or ""):
            b = [int(x) for x in NUM.findall(a.get("bounds", ""))]
            if len(b) == 4:
                print("%d %d" % ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2))
                return
    print("NOT_FOUND")


main()
