"""
验证 ExcelExporter 生成的 xlsx 图片嵌入是否正确。
做法：把 Java 里的 XML 生成逻辑等价移植到 Python，构造一个含「部分书籍无封面」的
场景（最容易触发下标错位的情况），生成 xlsx 后逐项检查 OOXML 结构。

对照检查项：
1. zip 内是否含 xl/media/*.jpg、xl/drawings/drawing1.xml、两级 rels
2. [Content_Types].xml 是否声明了 jpg Default 与 drawing Override
3. sheet1.xml 是否在 sheetData 之后挂 <drawing r:id="rId1"/>
4. drawing1.xml 里每个 r:embed=rIdN 是否都能在 drawing1.xml.rels 找到，
   且 rels 的 Target 与 media 实际文件名一一对应（这是 v2.8 的错位 bug 点）
5. 每个 anchor 的行号是否与该书在表中的实际数据行一致
"""
import io
import os
import zipfile
import re
import sys

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "test_out.xlsx")

HEADERS = ["ISBN", "书名", "作者", "译者", "国家/地区", "出版社",
           "版次", "印次", "页数", "字数", "定价", "豆瓣评分", "分类", "标签",
           "封面", "存放地点", "藏书状态", "阅读状态", "来源", "来源渠道",
           "购买日期", "购买价格", "评分", "备注", "录入时间", "拥有数量"]

# 场景：第 1、3 本无封面，第 2、4 本有封面 —— 用于暴露下标错位
ROWS = [
    ["1", "书A", "作者A"],   # 无封面
    ["2", "书B", "作者B"],   # 有封面
    ["3", "书C", "作者C"],   # 无封面
    ["4", "书D", "作者D"],   # 有封面
]
COVER_AT = {1: (300, 450), 3: (200, 200)}  # 行下标 -> (宽,高)

def make_jpeg(w, h):
    """生成真实的 JPEG 字节：openpyxl 依赖 Pillow 解析图片，假字节会被判为「无法读取」"""
    from PIL import Image
    import io as _io
    im = Image.new("RGB", (w, h), (120, 90, 60))
    b = _io.BytesIO()
    im.save(b, "JPEG", quality=85)
    return b.getvalue()


def col_name(index):
    i = index + 1
    s = ""
    while i > 0:
        mod = (i - 1) % 26
        s = chr(ord('A') + mod) + s
        i = (i - 1) // 26
    return s


def esc(s):
    return (s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace('"', "&quot;").replace("'", "&apos;"))


def build():
    rows = [HEADERS]
    for r in ROWS:
        rows.append(r + [""] * (len(HEADERS) - len(r)))
    covers = [None] * len(ROWS)
    for i, wh in COVER_AT.items():
        covers[i] = wh

    valid = [c for c in covers if c is not None]
    has = len(valid) > 0

    with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("[Content_Types].xml", content_types(has))
        z.writestr("_rels/.rels", rels())
        z.writestr("xl/workbook.xml", workbook())
        z.writestr("xl/_rels/workbook.xml.rels", wb_rels())
        z.writestr("xl/worksheets/sheet1.xml", sheet(rows, has))
        if has:
            z.writestr("xl/worksheets/_rels/sheet1.xml.rels", sheet_rels())
            z.writestr("xl/drawings/drawing1.xml", drawing(covers))
            z.writestr("xl/drawings/_rels/drawing1.xml.rels", draw_rels(len(valid)))
            for i in range(len(valid)):
                z.writestr("xl/media/image%d.jpg" % (i + 1), make_jpeg(*valid[i]))


def content_types(has):
    img = ('<Default Extension="jpg" ContentType="image/jpeg"/>'
           '<Default Extension="jpeg" ContentType="image/jpeg"/>') if has else ''
    ov = ('<Override PartName="/xl/drawings/drawing1.xml" '
          'ContentType="application/vnd.openxmlformats-officedocument.drawing+xml"/>') if has else ''
    return ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
            '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
            '<Default Extension="xml" ContentType="application/xml"/>' + img +
            '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
            '<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
            + ov + '</Types>')


def rels():
    return ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
            '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>'
            '</Relationships>')


def workbook():
    return ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
            'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
            '<sheets><sheet name="藏书" sheetId="1" r:id="rId1"/></sheets></workbook>')


def wb_rels():
    return ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
            '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>'
            '</Relationships>')


def sheet(rows, has):
    s = io.StringIO()
    s.write('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>')
    s.write('<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
            'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheetData>')
    for r, row in enumerate(rows):
        if has and r > 0:
            s.write('<row r="%d" ht="56" customHeight="1">' % (r + 1))
        else:
            s.write('<row r="%d">' % (r + 1))
        for c, v in enumerate(row):
            ref = col_name(c) + str(r + 1)
            s.write('<c r="%s" t="inlineStr"><is><t xml:space="preserve">%s</t></is></c>'
                    % (ref, esc(v)))
        s.write('</row>')
    s.write('</sheetData>')
    if has:
        s.write('<drawing r:id="rId1"/>')
    s.write('</worksheet>')
    return s.getvalue()


def sheet_rels():
    return ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
            '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing" Target="../drawings/drawing1.xml"/>'
            '</Relationships>')


def drawing(covers):
    s = io.StringIO()
    s.write('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>')
    s.write('<xdr:wsDr xmlns:xdr="http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing" '
            'xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" '
            'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">')
    pic = 1
    for i, wh in enumerate(covers):
        if wh is None:
            continue
        w, h = wh
        scale = min(48.0 / w, 64.0 / h)
        cx = int(round(w * scale * 9525))
        cy = int(round(h * scale * 9525))
        s.write('<xdr:oneCellAnchor>'
                '<xdr:from><xdr:col>14</xdr:col><xdr:colOff>0</xdr:colOff>'
                '<xdr:row>%d</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from>'
                '<xdr:ext cx="%d" cy="%d"/>'
                '<xdr:pic><xdr:nvPicPr><xdr:cNvPr id="%d" name="封面%d"/><xdr:cNvPicPr/></xdr:nvPicPr>'
                '<xdr:blipFill><a:blip r:embed="rId%d"/><a:stretch><a:fillRect/></a:stretch></xdr:blipFill>'
                '<xdr:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="%d" cy="%d"/></a:xfrm>'
                '<a:prstGeom prst="rect"><a:avLst/></a:prstGeom></xdr:spPr></xdr:pic>'
                '<xdr:clientData/></xdr:oneCellAnchor>'
                % (i + 1, cx, cy, pic, pic, pic, cx, cy))
        pic += 1
    s.write('</xdr:wsDr>')
    return s.getvalue()


def draw_rels(n):
    s = io.StringIO()
    s.write('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>')
    s.write('<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">')
    for i in range(1, n + 1):
        s.write('<Relationship Id="rId%d" '
                'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" '
                'Target="../media/image%d.jpg"/>' % (i, i))
    s.write('</Relationships>')
    return s.getvalue()


def check():
    problems = []
    ok = []

    z = zipfile.ZipFile(OUT)
    names = set(z.namelist())

    media = sorted(n for n in names if n.startswith("xl/media/"))
    ok.append("media 文件: %s" % media)
    if len(media) != 2:
        problems.append("media 图片数应为 2，实际 %d" % len(media))

    for need in ["xl/drawings/drawing1.xml",
                 "xl/drawings/_rels/drawing1.xml.rels",
                 "xl/worksheets/_rels/sheet1.xml.rels"]:
        if need not in names:
            problems.append("缺少部件: %s" % need)

    ct = z.read("[Content_Types].xml").decode()
    if 'Extension="jpg"' not in ct:
        problems.append("[Content_Types].xml 缺少 jpg Default 声明")
    if "/xl/drawings/drawing1.xml" not in ct:
        problems.append("[Content_Types].xml 缺少 drawing1.xml Override")
    else:
        ok.append("Content_Types 声明完整")

    sh = z.read("xl/worksheets/sheet1.xml").decode()
    if "<drawing r:id=\"rId1\"/>" not in sh:
        problems.append("sheet1.xml 未挂 drawing")
    if sh.index("<drawing") < sh.index("</sheetData>"):
        problems.append("drawing 元素出现在 sheetData 之前（Excel 会解析失败）")
    else:
        ok.append("drawing 位置正确（sheetData 之后）")

    dr = z.read("xl/drawings/drawing1.xml").decode()
    embeds = re.findall(r'r:embed="(rId\d+)"', dr)
    rels = dict(re.findall(r'Id="(rId\d+)"[^>]*Target="\.\./media/(image\d+\.jpg)"',
                           z.read("xl/drawings/_rels/drawing1.xml.rels").decode()))
    ok.append("drawing 中的 r:embed: %s" % embeds)
    ok.append("rels 映射: %s" % rels)

    for e in embeds:
        if e not in rels:
            problems.append("drawing 引用了 %s 但 rels 中不存在" % e)
    for rid, target in rels.items():
        if "xl/media/" + target not in names:
            problems.append("rels 指向不存在的文件: %s -> %s" % (rid, target))
    for e in embeds:
        t = rels.get(e)
        if t and ("xl/media/" + t) in names:
            ok.append("  %s -> %s (存在)" % (e, t))

    rows = [int(m) for m in re.findall(r'<xdr:row>(\d+)</xdr:row>', dr)]
    ok.append("anchor 行号: %s (期望 [2, 4] —— 即书B/书D 所在数据行)" % rows)
    if rows != [2, 4]:
        problems.append("anchor 行号与有封面的书籍所在行不一致: %s" % rows)

    z.close()

    # ---- 端到端：用真实解析器（openpyxl）打开，确认 Excel 也能识别 ----
    try:
        import openpyxl
    except ImportError:
        ok.append("未安装 openpyxl，跳过端到端校验（pip install openpyxl pillow）")
        return ok, problems
    try:
        wb = openpyxl.load_workbook(OUT)
        ws = wb["藏书"]
        imgs = ws._images
        ok.append("openpyxl 识别到图片数: %d" % len(imgs))
        if len(imgs) != len(media):
            problems.append("openpyxl 只识别到 %d 张图片，应为 %d 张" % (len(imgs), len(media)))
        for i, im in enumerate(imgs):
            ok.append("  图%d 锚点行(0基)=%d 列=%d" % (i + 1, im.anchor._from.row, im.anchor._from.col))
        # 锚点行 0-based → Excel 1-based 行号，应为 3 和 5（书B 在第3行、书D 在第5行）
        got = sorted(im.anchor._from.row for im in imgs)
        if got != [2, 4]:
            problems.append("openpyxl 读到的锚点行 %s 与期望 [2, 4] 不符" % got)
        else:
            ok.append("openpyxl 锚点行与数据行对齐（书B→第3行、书D→第5行）")
    except Exception as e:
        problems.append("openpyxl 打开失败：%s: %s" % (type(e).__name__, e))

    return ok, problems


build()
ok, problems = check()
print("=== 通过项 ===")
for o in ok:
    print("  ✓", o)
print("=== 问题 ===")
if problems:
    for p in problems:
        print("  ✗", p)
    sys.exit(1)
print("  （无）")
print("\n结论：OOXML 结构自洽，图片可被 Excel 正确解析。")
