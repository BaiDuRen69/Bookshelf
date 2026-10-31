package com.bai.bookshelf.util;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 最小 XLSX 包的 XML 片段生成（v5.6 从 ExcelExporter 抽出）：
 * 工作表 / 工作簿 / 关系 / 绘图 XML，以及列名、数值判断与转义。
 */
public final class XlsxParts {

    /** 数字列的下标（从 0 开始）：页数、定价、购买价格、评分、拥有数量、阅读顺序 */
    private static final Set<Integer> NUMERIC_COLS = new HashSet<>();

    static {
        NUMERIC_COLS.add(8);   // 页数
        NUMERIC_COLS.add(10);  // 定价
        NUMERIC_COLS.add(21);  // 购买价格
        NUMERIC_COLS.add(22);  // 评分
        NUMERIC_COLS.add(25);  // 拥有数量
        NUMERIC_COLS.add(28);  // 阅读顺序
    }

    private XlsxParts() {
    }

    /** 封面图片（已压缩的 JPEG 字节 + 原始宽高，用于按比例计算展示尺寸） */
    public static final class CoverImage {
        public final byte[] bytes;
        public final int pxW;
        public final int pxH;

        public CoverImage(byte[] bytes, int pxW, int pxH) {
            this.bytes = bytes;
            this.pxW = pxW;
            this.pxH = pxH;
        }
    }

    public static String contentTypesXml(boolean hasImages) {
        String imageDefaults = hasImages
                ? "<Default Extension=\"jpg\" ContentType=\"image/jpeg\"/>"
                + "<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>"
                : "";
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + imageDefaults
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                + (hasImages ? "<Override PartName=\"/xl/drawings/drawing1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawing+xml\"/>" : "")
                + "</Types>";
    }

    public static String relsXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                + "</Relationships>";
    }

    public static String workbookXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                + "<sheets><sheet name=\"藏书\" sheetId=\"1\" r:id=\"rId1\"/></sheets>"
                + "</workbook>";
    }

    public static String workbookRelsXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                + "</Relationships>";
    }

    /** 生成 sheet1.xml：逐行逐列写单元格；有封面图片时数据行加高并挂 drawing */
    public static String sheetXml(List<String[]> rows, boolean hasImages) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheetData>");
        for (int r = 0; r < rows.size(); r++) {
            String[] row = rows.get(r);
            // 有嵌入图片时数据行加高到 56pt，给封面留展示空间（表头行保持默认）
            if (hasImages && r > 0) {
                sb.append("<row r=\"").append(r + 1).append("\" ht=\"56\" customHeight=\"1\">");
            } else {
                sb.append("<row r=\"").append(r + 1).append("\">");
            }
            for (int c = 0; c < row.length; c++) {
                String cellRef = colName(c) + (r + 1); // 如 B12
                String value = row[c] == null ? "" : row[c];
                if (r > 0 && NUMERIC_COLS.contains(c) && isNumber(value)) {
                    // 数值单元格
                    sb.append("<c r=\"").append(cellRef).append("\"><v>").append(value).append("</v></c>");
                } else {
                    // 文本单元格（内联字符串）
                    sb.append("<c r=\"").append(cellRef).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                            .append(escape(value)).append("</t></is></c>");
                }
            }
            sb.append("</row>");
        }
        sb.append("</sheetData>");
        if (hasImages) {
            sb.append("<drawing r:id=\"rId1\"/>");
        }
        sb.append("</worksheet>");
        return sb.toString();
    }

    /** sheet1 的关系文件：挂到 drawing1.xml */
    public static String sheetRelsXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing\" Target=\"../drawings/drawing1.xml\"/>"
                + "</Relationships>";
    }

    /**
     * drawing1.xml：每张封面一个 oneCellAnchor，钉在「封面」列（第 14 列 = O）对应数据行。
     * 展示尺寸按原图宽高比缩放到 48x64 框内（EMU = px * 9525）。
     */
    public static String drawingXml(List<CoverImage> covers) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        sb.append("<xdr:wsDr xmlns:xdr=\"http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing\" "
                + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">");
        int picId = 1;
        for (int i = 0; i < covers.size(); i++) {
            CoverImage img = covers.get(i);
            if (img == null) {
                continue;
            }
            float scale = Math.min(48f / img.pxW, 64f / img.pxH);
            long cx = Math.round(img.pxW * scale * 9525);
            long cy = Math.round(img.pxH * scale * 9525);
            int row = i + 1; // 数据行（0-based，表头占第 0 行）
            sb.append("<xdr:oneCellAnchor>")
                    .append("<xdr:from><xdr:col>14</xdr:col><xdr:colOff>0</xdr:colOff>")
                    .append("<xdr:row>").append(row).append("</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from>")
                    .append("<xdr:ext cx=\"").append(cx).append("\" cy=\"").append(cy).append("\"/>")
                    .append("<xdr:pic>")
                    .append("<xdr:nvPicPr><xdr:cNvPr id=\"").append(picId).append("\" name=\"封面").append(picId).append("\"/>")
                    .append("<xdr:cNvPicPr/></xdr:nvPicPr>")
                    .append("<xdr:blipFill><a:blip r:embed=\"rId").append(picId).append("\"/>")
                    .append("<a:stretch><a:fillRect/></a:stretch></xdr:blipFill>")
                    .append("<xdr:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/>")
                    .append("<a:ext cx=\"").append(cx).append("\" cy=\"").append(cy).append("\"/></a:xfrm>")
                    .append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></xdr:spPr>")
                    .append("</xdr:pic>")
                    .append("<xdr:clientData/>")
                    .append("</xdr:oneCellAnchor>");
            picId++;
        }
        sb.append("</xdr:wsDr>");
        return sb.toString();
    }

    /**
     * drawing1 的关系文件：rIdN → ../media/imageN.jpg。
     *
     * @param imageCount 实际嵌入的图片数；序号必须与 media 里的文件序号一一对应
     */
    public static String drawingRelsXml(int imageCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        sb.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (int i = 1; i <= imageCount; i++) {
            sb.append("<Relationship Id=\"rId").append(i).append("\" ")
                    .append("Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" ")
                    .append("Target=\"../media/image").append(i).append(".jpg\"/>");
        }
        sb.append("</Relationships>");
        return sb.toString();
    }

    /** 列下标转 Excel 列名：0→A, 25→Z, 26→AA */
    public static String colName(int index) {
        StringBuilder sb = new StringBuilder();
        int i = index + 1;
        while (i > 0) {
            int mod = (i - 1) % 26;
            sb.insert(0, (char) ('A' + mod));
            i = (i - 1) / 26;
        }
        return sb.toString();
    }

    public static boolean isNumber(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** XML 特殊字符转义 */
    public static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
