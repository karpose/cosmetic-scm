package com.cosmetic.scm.common;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 写单工作表的 .xlsx（OOXML 就是 zip 包里的几个 XML）。只需要导出表格，不值得为此引入 POI。
 * 文本一律写成内联字符串：单元格内容不会被 Excel 当成公式执行，防止“= 开头”的数据注入。
 */
public final class XlsxWriter {

    private XlsxWriter() {
    }

    public static byte[] write(String sheetName, List<String> headers, List<List<Object>> rows) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            entry(zip, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                    <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                    <Default Extension="xml" ContentType="application/xml"/>
                    <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                    <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                    <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
                    </Types>""");
            entry(zip, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                    </Relationships>""");
            entry(zip, "xl/workbook.xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                    <sheets><sheet name="%s" sheetId="1" r:id="rId1"/></sheets>
                    </workbook>""".formatted(escape(sheetTitle(sheetName))));
            entry(zip, "xl/_rels/workbook.xml.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                    <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
                    </Relationships>""");
            // 样式 0 普通，样式 1 表头加粗
            entry(zip, "xl/styles.xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                    <fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>
                    <fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>
                    <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
                    <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
                    <cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs>
                    <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
                    </styleSheet>""");
            entry(zip, "xl/worksheets/sheet1.xml", sheet(headers, rows));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static String sheet(List<String> headers, List<List<Object>> rows) {
        StringBuilder s = new StringBuilder(4096);
        s.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
                .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
                .append("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>")
                .append("<cols>");
        for (int c = 0; c < headers.size(); c++) {
            s.append("<col min=\"").append(c + 1).append("\" max=\"").append(c + 1)
                    .append("\" width=\"").append(width(headers.get(c), rows, c)).append("\" customWidth=\"1\"/>");
        }
        s.append("</cols><sheetData><row r=\"1\">");
        for (int c = 0; c < headers.size(); c++) {
            textCell(s, ref(c, 1), headers.get(c), 1);
        }
        s.append("</row>");
        int r = 2;
        for (List<Object> row : rows) {
            s.append("<row r=\"").append(r).append("\">");
            for (int c = 0; c < row.size(); c++) {
                Object v = row.get(c);
                if (v instanceof BigDecimal d) {
                    s.append("<c r=\"").append(ref(c, r)).append("\"><v>").append(d.toPlainString()).append("</v></c>");
                } else if (v instanceof Number n) {
                    s.append("<c r=\"").append(ref(c, r)).append("\"><v>").append(n).append("</v></c>");
                } else if (v != null) {
                    textCell(s, ref(c, r), v.toString(), 0);
                }
            }
            s.append("</row>");
            r++;
        }
        return s.append("</sheetData></worksheet>").toString();
    }

    private static void textCell(StringBuilder s, String ref, String text, int style) {
        s.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"").append(style > 0 ? " s=\"" + style + "\"" : "")
                .append("><is><t xml:space=\"preserve\">").append(escape(text)).append("</t></is></c>");
    }

    /** 列宽按内容估算：中文按两个字符宽，封顶 60。 */
    private static int width(String header, List<List<Object>> rows, int col) {
        int w = display(header);
        for (List<Object> row : rows.subList(0, Math.min(rows.size(), 200))) {
            if (col < row.size() && row.get(col) != null) {
                w = Math.max(w, display(row.get(col).toString()));
            }
        }
        return Math.min(60, w + 2);
    }

    private static int display(String s) {
        return s.codePoints().map(c -> c > 0x2E80 ? 2 : 1).sum();
    }

    /** 列序号 + 行号 → A1 形式的单元格地址。 */
    static String ref(int col, int row) {
        StringBuilder name = new StringBuilder();
        for (int c = col; c >= 0; c = c / 26 - 1) {
            name.insert(0, (char) ('A' + c % 26));
        }
        return name.append(row).toString();
    }

    /** 工作表名不能超过 31 个字符，不能含这几个符号。 */
    private static String sheetTitle(String s) {
        String t = s.replaceAll("[\\\\/?*\\[\\]:]", " ");
        return t.length() > 31 ? t.substring(0, 31) : t;
    }

    /** XML 转义，并去掉 XML 1.0 不允许的控制字符（否则 Excel 打不开整个文件）。 */
    private static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        s.codePoints().forEach(c -> {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                default -> {
                    if (c == '\t' || c == '\n' || c == '\r' || c >= 0x20 && c != 0xFFFE && c != 0xFFFF) {
                        out.appendCodePoint(c);
                    }
                }
            }
        });
        return out.toString();
    }

    private static void entry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
