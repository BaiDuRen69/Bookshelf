"""打印 uiautomator dump 里指定 resource-id（默认 message）的完整文本。

用法：python -X utf8 tools/node_text.py <xml 路径> [resource-id 片段]
"""
import re
import sys

ATTR = re.compile(r'(\w[\w-]*)="((?:[^"\\]|\\.)*)"')


def main():
    xml = open(sys.argv[1], encoding="utf-8").read()
    want = sys.argv[2] if len(sys.argv) > 2 else "message"
    for node in re.findall(r"<node\b[^>]*?/>", xml):
        a = dict(ATTR.findall(node))
        if want in a.get("resource-id", "") and a.get("text"):
            print(a["text"].replace("&#10;", "\n").replace("\n", "\\n"))
            return
    print("NOT_FOUND")


main()
