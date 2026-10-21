/*
 * SheetWorks - a small spreadsheet application
 * Copyright (C) 2026 Adam Kost
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 3 as published by the
 * Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package me.kost.adam.dev.sheetworks;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.SAXParserFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

/** Reads and writes .xlsx files (a zip of XML parts) without any third-party library. */
public final class XlsxIO {
    private XlsxIO() {}

    private static final String NS_MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String NS_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final long MAX_PART = 512L * 1024 * 1024;

    private static final Map<Integer, String> BUILTIN = new HashMap<>();
    static {
        BUILTIN.put(1, "0"); BUILTIN.put(2, "0.00"); BUILTIN.put(3, "#,##0"); BUILTIN.put(4, "#,##0.00");
        BUILTIN.put(5, "$#,##0"); BUILTIN.put(6, "$#,##0"); BUILTIN.put(7, "$#,##0.00"); BUILTIN.put(8, "$#,##0.00");
        BUILTIN.put(9, "0%"); BUILTIN.put(10, "0.00%");
        BUILTIN.put(14, "m/d/yyyy"); BUILTIN.put(15, "d-mmm-yy"); BUILTIN.put(16, "d-mmm"); BUILTIN.put(17, "mmm-yy");
        BUILTIN.put(18, "h:mm AM/PM"); BUILTIN.put(19, "h:mm:ss AM/PM"); BUILTIN.put(20, "h:mm"); BUILTIN.put(21, "h:mm:ss");
        BUILTIN.put(22, "m/d/yyyy h:mm");
        BUILTIN.put(37, "#,##0"); BUILTIN.put(38, "#,##0"); BUILTIN.put(39, "#,##0.00"); BUILTIN.put(40, "#,##0.00");
        BUILTIN.put(41, "#,##0"); BUILTIN.put(42, "$#,##0"); BUILTIN.put(43, "#,##0.00"); BUILTIN.put(44, "$#,##0.00");
        BUILTIN.put(45, "mm:ss"); BUILTIN.put(46, "h:mm:ss");
    }

    // =================================================================== reading

    public static Workbook read(Path path) throws IOException {
        try (ZipFile z = new ZipFile(path.toFile())) {
            Document wbDoc = dom(part(z, "xl/workbook.xml", true));
            Map<String, String> rels = new HashMap<>();
            byte[] relBytes = part(z, "xl/_rels/workbook.xml.rels", true);
            for (Element e : all(dom(relBytes).getDocumentElement(), "Relationship")) {
                rels.put(e.getAttribute("Id"), e.getAttribute("Target"));
            }
            List<String> sst = sharedStrings(part(z, "xl/sharedStrings.xml", false));
            List<Style> xfs = styles(part(z, "xl/styles.xml", false));

            Workbook wb = new Workbook();
            Element sheetsEl = first(wbDoc.getDocumentElement(), "sheets");
            if (sheetsEl == null) throw new IOException("Not a valid workbook: no sheets found");
            for (Element se : kids(sheetsEl, "sheet")) {
                String name = se.getAttribute("name");
                String target = rels.get(se.getAttributeNS(NS_REL, "id"));
                if (target == null) continue;
                String partName = target.startsWith("/") ? target.substring(1) : "xl/" + target;
                byte[] data = part(z, partName, false);
                if (data == null) continue;
                Sheet sh = new Sheet(name);
                if (!parseSheet(data, sh, sst, xfs)) continue; // not a worksheet (e.g. a chart sheet)
                wb.sheets.add(sh);
            }
            if (wb.sheets.isEmpty()) wb.sheets.add(new Sheet("Sheet1"));
            wb.path = path;
            wb.foreignXlsx = true;
            return wb;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not read the workbook: " + e.getMessage(), e);
        }
    }

    private static byte[] part(ZipFile z, String name, boolean required) throws IOException {
        ZipEntry e = z.getEntry(name);
        if (e == null) {
            if (required) throw new IOException("Not an .xlsx workbook (missing " + name + ")");
            return null;
        }
        try (InputStream in = z.getInputStream(e)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_PART) throw new IOException("Part too large: " + name);
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static Document dom(byte[] data) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(data));
    }

    private static javax.xml.parsers.SAXParser sax() throws Exception {
        SAXParserFactory f = SAXParserFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return f.newSAXParser();
    }

    private static String local(Node n) { return n.getLocalName() != null ? n.getLocalName() : n.getNodeName(); }

    private static List<Element> kids(Element e, String name) {
        List<Element> out = new ArrayList<>();
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && local(n).equals(name)) out.add((Element) n);
        }
        return out;
    }

    private static Element first(Element e, String name) {
        List<Element> l = kids(e, name);
        return l.isEmpty() ? null : l.get(0);
    }

    private static List<Element> all(Element e, String name) {
        List<Element> out = new ArrayList<>();
        org.w3c.dom.NodeList nl = e.getElementsByTagNameNS("*", name);
        for (int i = 0; i < nl.getLength(); i++) out.add((Element) nl.item(i));
        return out;
    }

    private static List<String> sharedStrings(byte[] data) throws Exception {
        List<String> out = new ArrayList<>();
        if (data == null) return out;
        sax().parse(new InputSource(new ByteArrayInputStream(data)), new DefaultHandler() {
            StringBuilder cur; boolean inT; int skip; // skip: depth inside phonetic runs (rPh)
            @Override public void startElement(String u, String l, String q, Attributes a) {
                if (l.equals("si")) cur = new StringBuilder();
                else if (l.equals("rPh")) skip++;
                else if (l.equals("t") && cur != null && skip == 0) inT = true;
            }
            @Override public void characters(char[] ch, int s, int n) { if (inT) cur.append(ch, s, n); }
            @Override public void endElement(String u, String l, String q) {
                if (l.equals("t")) inT = false;
                else if (l.equals("rPh")) skip--;
                else if (l.equals("si")) { out.add(cur.toString()); cur = null; }
            }
        });
        return out;
    }

    private static int rgb(String argb) {
        if (argb == null || argb.length() < 6) return -1;
        try { return Integer.parseInt(argb.substring(argb.length() - 6), 16); } catch (NumberFormatException e) { return -1; }
    }

    private static boolean flag(Element parent, String child) {
        Element e = first(parent, child);
        if (e == null) return false;
        String v = e.getAttribute("val");
        return !(v.equals("0") || v.equalsIgnoreCase("false"));
    }

    private static List<Style> styles(byte[] data) throws Exception {
        List<Style> out = new ArrayList<>();
        if (data == null) return out;
        Element root = dom(data).getDocumentElement();
        Map<Integer, String> custom = new HashMap<>();
        Element nf = first(root, "numFmts");
        if (nf != null) for (Element e : kids(nf, "numFmt")) custom.put(Integer.parseInt(e.getAttribute("numFmtId")), e.getAttribute("formatCode"));
        List<boolean[]> fontFlags = new ArrayList<>();
        List<Integer> fontColors = new ArrayList<>();
        Element fonts = first(root, "fonts");
        if (fonts != null) for (Element f : kids(fonts, "font")) {
            fontFlags.add(new boolean[] {flag(f, "b"), flag(f, "i")});
            Element col = first(f, "color");
            fontColors.add(col == null ? -1 : rgb(col.hasAttribute("rgb") ? col.getAttribute("rgb") : null));
        }
        List<Integer> fills = new ArrayList<>();
        Element fe = first(root, "fills");
        if (fe != null) for (Element f : kids(fe, "fill")) {
            Element pf = first(f, "patternFill");
            int c = -1;
            if (pf != null && pf.getAttribute("patternType").equals("solid")) {
                Element fg = first(pf, "fgColor");
                if (fg != null && fg.hasAttribute("rgb")) c = rgb(fg.getAttribute("rgb"));
            }
            fills.add(c);
        }
        Element cx = first(root, "cellXfs");
        if (cx != null) for (Element x : kids(cx, "xf")) {
            int nid = intAttr(x, "numFmtId"), fid = intAttr(x, "fontId"), fill = intAttr(x, "fillId");
            String code = custom.containsKey(nid) ? custom.get(nid) : BUILTIN.get(nid);
            if (code != null) code = code.replace("\"$\"", "$");
            boolean b = false, i = false; int fg = -1;
            if (fid >= 0 && fid < fontFlags.size()) { b = fontFlags.get(fid)[0]; i = fontFlags.get(fid)[1]; fg = fontColors.get(fid); }
            int bg = fill >= 0 && fill < fills.size() ? fills.get(fill) : -1;
            int al = Style.GENERAL;
            Element ae = first(x, "alignment");
            if (ae != null) {
                String h = ae.getAttribute("horizontal");
                al = h.equals("left") ? Style.LEFT : h.equals("center") ? Style.CENTER : h.equals("right") ? Style.RIGHT : Style.GENERAL;
            }
            out.add(new Style(b, i, al, code, bg, fg));
        }
        return out;
    }

    private static int intAttr(Element e, String n) {
        try { return Integer.parseInt(e.getAttribute(n)); } catch (NumberFormatException ex) { return 0; }
    }

    private static boolean parseSheet(byte[] data, Sheet sh, List<String> sst, List<Style> xfs) throws Exception {
        SheetHandler h = new SheetHandler(sh, sst, xfs);
        sax().parse(new InputSource(new ByteArrayInputStream(data)), h);
        return h.isWorksheet;
    }

    private static final class SheetHandler extends DefaultHandler {
        final Sheet sh; final List<String> sst; final List<Style> xfs;
        boolean isWorksheet, first = true;
        int r, c; Style st; String t, ftype, fsi;
        final StringBuilder v = new StringBuilder(), f = new StringBuilder(), is = new StringBuilder();
        boolean inV, inF, inIs, inT, inC;
        final Map<String, Object[]> shared = new HashMap<>();

        SheetHandler(Sheet sh, List<String> sst, List<Style> xfs) { this.sh = sh; this.sst = sst; this.xfs = xfs; }

        @Override public void startElement(String u, String l, String q, Attributes a) {
            if (first) { first = false; isWorksheet = l.equals("worksheet"); }
            switch (l) {
                case "c": {
                    inC = true;
                    String ref = a.getValue("r");
                    r = -1; c = -1;
                    if (ref != null) {
                        int i = 0;
                        while (i < ref.length() && Character.isLetter(ref.charAt(i))) i++;
                        c = Sheet.parseCol(ref.substring(0, i));
                        try { r = Integer.parseInt(ref.substring(i)) - 1; } catch (NumberFormatException e) { r = -1; }
                    }
                    String s = a.getValue("s");
                    int si = -1;
                    if (s != null) { try { si = Integer.parseInt(s); } catch (NumberFormatException e) { si = -1; } }
                    st = si >= 0 && si < xfs.size() ? xfs.get(si) : Style.DEFAULT;
                    t = a.getValue("t");
                    v.setLength(0); f.setLength(0); is.setLength(0);
                    ftype = null; fsi = null;
                    break;
                }
                case "v": inV = true; break;
                case "f": inF = true; ftype = a.getValue("t"); fsi = a.getValue("si"); break;
                case "is": inIs = true; break;
                case "t": if (inIs) inT = true; break;
                case "col": {
                    try {
                        int min = Integer.parseInt(a.getValue("min")) - 1, max = Integer.parseInt(a.getValue("max")) - 1;
                        double w = Double.parseDouble(a.getValue("width"));
                        for (int i = Math.max(0, min); i <= Math.min(max, Sheet.MAX_COLS - 1) && i < min + 2000; i++) {
                            sh.colWidths.put(i, (int) Math.round(w * 7 + 5));
                        }
                    } catch (RuntimeException e) { /* ignore malformed width */ }
                    break;
                }
                default: break;
            }
        }

        @Override public void characters(char[] ch, int s, int n) {
            if (inV) v.append(ch, s, n);
            else if (inF) f.append(ch, s, n);
            else if (inT) is.append(ch, s, n);
        }

        @Override public void endElement(String u, String l, String q) {
            switch (l) {
                case "v": inV = false; break;
                case "f": inF = false; break;
                case "is": inIs = false; break;
                case "t": inT = false; break;
                case "c": inC = false; finishCell(); break;
                default: break;
            }
        }

        private void finishCell() {
            if (r < 0 || c < 0 || r >= Sheet.MAX_ROWS || c >= Sheet.MAX_COLS) return;
            String formula = null;
            String ft = f.toString();
            if ("shared".equals(ftype) && fsi != null) {
                if (!ft.isEmpty()) { shared.put(fsi, new Object[] {ft, r, c}); formula = ft; }
                else if (shared.containsKey(fsi)) {
                    Object[] m = shared.get(fsi);
                    formula = Formula.shift("=" + m[0], r - (Integer) m[1], c - (Integer) m[2]).substring(1);
                }
            } else if (!ft.isEmpty()) {
                formula = ft;
            }
            String vs = v.toString();
            String text = null; Object cached = null;
            if (t == null || t.equals("n")) {
                String x = vs.trim();
                if (!x.isEmpty()) { text = x; try { cached = Double.parseDouble(x); } catch (NumberFormatException e) { cached = null; } }
            } else if (t.equals("s")) {
                try { text = sst.get(Integer.parseInt(vs.trim())); } catch (RuntimeException e) { text = null; }
                cached = text;
            } else if (t.equals("str") || t.equals("d")) {
                text = vs; cached = vs;
            } else if (t.equals("inlineStr")) {
                text = is.toString(); cached = text;
            } else if (t.equals("b")) {
                boolean b = vs.trim().equals("1");
                text = b ? "TRUE" : "FALSE"; cached = b;
            } else if (t.equals("e")) {
                text = vs; cached = XlErr.of(vs.trim());
            }
            String raw;
            if (formula != null) {
                raw = "=" + formula;
            } else if (text == null || text.isEmpty()) {
                raw = "";
            } else if ((t != null && (t.equals("s") || t.equals("str") || t.equals("inlineStr") || t.equals("d") || t.equals("e")))
                    && Formula.needsQuote(text)) {
                raw = "'" + text;
            } else {
                raw = text;
            }
            if (raw.isEmpty() && st.isDefault()) return;
            sh.putRaw(r, c, new Cell(raw, st, formula != null ? cached : null));
        }
    }

    // =================================================================== writing

    public static void write(Workbook wb, Path path) throws IOException {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try (OutputStream os = Files.newOutputStream(tmp); ZipOutputStream zo = new ZipOutputStream(os, StandardCharsets.UTF_8)) {
            wb.invalidate();
            // Collect distinct styles first.
            LinkedHashMap<Style, Integer> xf = new LinkedHashMap<>();
            xf.put(Style.DEFAULT, 0);
            for (Sheet s : wb.sheets) for (Cell c : s.cells.values()) if (!xf.containsKey(c.style)) xf.put(c.style, xf.size());

            StringBuilder ct = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                    + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
            for (int i = 0; i < wb.sheets.size(); i++) {
                ct.append("<Override PartName=\"/xl/worksheets/sheet").append(i + 1)
                  .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
            }
            ct.append("</Types>");
            put(zo, "[Content_Types].xml", ct.toString());
            put(zo, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                    + "</Relationships>");

            StringBuilder w = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<workbook xmlns=\""
                    + NS_MAIN + "\" xmlns:r=\"" + NS_REL + "\"><bookViews><workbookView/></bookViews><sheets>");
            StringBuilder rel = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
            for (int i = 0; i < wb.sheets.size(); i++) {
                w.append("<sheet name=\"").append(esc(wb.sheets.get(i).name)).append("\" sheetId=\"").append(i + 1)
                 .append("\" r:id=\"rId").append(i + 1).append("\"/>");
                rel.append("<Relationship Id=\"rId").append(i + 1)
                   .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet")
                   .append(i + 1).append(".xml\"/>");
            }
            w.append("</sheets></workbook>");
            rel.append("<Relationship Id=\"rId").append(wb.sheets.size() + 1)
               .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>");
            put(zo, "xl/workbook.xml", w.toString());
            put(zo, "xl/_rels/workbook.xml.rels", rel.toString());
            put(zo, "xl/styles.xml", stylesXml(xf));
            for (int i = 0; i < wb.sheets.size(); i++) put(zo, "xl/worksheets/sheet" + (i + 1) + ".xml", sheetXml(wb, wb.sheets.get(i), xf));
        }
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void put(ZipOutputStream zo, String name, String content) throws IOException {
        zo.putNextEntry(new ZipEntry(name));
        zo.write(content.getBytes(StandardCharsets.UTF_8));
        zo.closeEntry();
    }

    static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                default:
                    if (ch >= 0x20 || ch == '\t' || ch == '\n' || ch == '\r') sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static String hex(int rgb) { return String.format("FF%06X", rgb & 0xFFFFFF); }

    private static String stylesXml(LinkedHashMap<Style, Integer> xf) {
        Map<String, Integer> builtin = new HashMap<>();
        builtin.put("0", 1); builtin.put("0.00", 2); builtin.put("#,##0", 3); builtin.put("#,##0.00", 4);
        builtin.put("0%", 9); builtin.put("0.00%", 10);
        LinkedHashMap<String, Integer> custom = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> fonts = new LinkedHashMap<>(), fills = new LinkedHashMap<>();
        fonts.put("false|false|-1", 0);
        fills.put("none", 0); fills.put("gray125", 1);
        StringBuilder xfs = new StringBuilder();
        for (Style s : xf.keySet()) {
            int nid = 0;
            if (s.numFmt != null) {
                // Excel writes currency symbols as quoted literals; some readers need that
                String code = (s.numFmt.indexOf('$') >= 0 && s.numFmt.indexOf('"') < 0) ? s.numFmt.replace("$", "\"$\"") : s.numFmt;
                if (builtin.containsKey(code)) nid = builtin.get(code);
                else { if (!custom.containsKey(code)) custom.put(code, 164 + custom.size()); nid = custom.get(code); }
            }
            String fk = s.bold + "|" + s.italic + "|" + s.fg;
            if (!fonts.containsKey(fk)) fonts.put(fk, fonts.size());
            int fid = fonts.get(fk), flid = 0;
            if (s.fill >= 0) {
                String k = "s" + s.fill;
                if (!fills.containsKey(k)) fills.put(k, fills.size());
                flid = fills.get(k);
            }
            xfs.append("<xf numFmtId=\"").append(nid).append("\" fontId=\"").append(fid).append("\" fillId=\"").append(flid)
               .append("\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\" applyFill=\"1\"");
            if (s.align != Style.GENERAL) {
                xfs.append(" applyAlignment=\"1\"><alignment horizontal=\"")
                   .append(s.align == Style.LEFT ? "left" : s.align == Style.CENTER ? "center" : "right").append("\"/></xf>");
            } else {
                xfs.append("/>");
            }
        }
        StringBuilder o = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<styleSheet xmlns=\"" + NS_MAIN + "\">");
        if (!custom.isEmpty()) {
            o.append("<numFmts count=\"").append(custom.size()).append("\">");
            for (Map.Entry<String, Integer> e : custom.entrySet()) {
                o.append("<numFmt numFmtId=\"").append(e.getValue()).append("\" formatCode=\"").append(esc(e.getKey())).append("\"/>");
            }
            o.append("</numFmts>");
        }
        o.append("<fonts count=\"").append(fonts.size()).append("\">");
        for (String k : fonts.keySet()) {
            String[] p = k.split("\\|");
            o.append("<font>");
            if (p[0].equals("true")) o.append("<b/>");
            if (p[1].equals("true")) o.append("<i/>");
            o.append("<sz val=\"11\"/>");
            if (!p[2].equals("-1")) o.append("<color rgb=\"").append(hex(Integer.parseInt(p[2]))).append("\"/>");
            o.append("<name val=\"Calibri\"/></font>");
        }
        o.append("</fonts><fills count=\"").append(fills.size()).append("\">");
        for (String k : fills.keySet()) {
            if (k.equals("none") || k.equals("gray125")) o.append("<fill><patternFill patternType=\"").append(k).append("\"/></fill>");
            else o.append("<fill><patternFill patternType=\"solid\"><fgColor rgb=\"").append(hex(Integer.parseInt(k.substring(1))))
                  .append("\"/><bgColor indexed=\"64\"/></patternFill></fill>");
        }
        o.append("</fills><borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
                + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                + "<cellXfs count=\"").append(xf.size()).append("\">").append(xfs)
         .append("</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>");
        return o.toString();
    }

    private static String sheetXml(Workbook wb, Sheet sh, Map<Style, Integer> xf) {
        StringBuilder o = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<worksheet xmlns=\"" + NS_MAIN + "\">");
        if (!sh.colWidths.isEmpty()) {
            o.append("<cols>");
            List<Integer> cols = new ArrayList<>(sh.colWidths.keySet());
            java.util.Collections.sort(cols);
            for (int c : cols) {
                o.append("<col min=\"").append(c + 1).append("\" max=\"").append(c + 1).append("\" width=\"")
                 .append(String.format(java.util.Locale.US, "%.2f", Math.max(0, (sh.colWidths.get(c) - 5) / 7.0))).append("\" customWidth=\"1\"/>");
            }
            o.append("</cols>");
        }
        o.append("<sheetData>");
        List<Long> keys = new ArrayList<>(sh.cells.keySet());
        java.util.Collections.sort(keys);
        int curRow = -1;
        for (long k : keys) {
            int r = Sheet.rowOf(k), c = Sheet.colOf(k);
            if (r != curRow) {
                if (curRow >= 0) o.append("</row>");
                o.append("<row r=\"").append(r + 1).append("\">");
                curRow = r;
            }
            cellXml(o, wb, sh, r, c, sh.cells.get(k), xf.get(sh.cells.get(k).style));
        }
        if (curRow >= 0) o.append("</row>");
        o.append("</sheetData></worksheet>");
        return o.toString();
    }

    private static void cellXml(StringBuilder o, Workbook wb, Sheet sh, int r, int c, Cell cell, int s) {
        String ref = Sheet.ref(r, c);
        String head = "<c r=\"" + ref + "\"" + (s != 0 ? " s=\"" + s + "\"" : "");
        String raw = cell.raw;
        if (raw.isEmpty()) { o.append(head).append("/>"); return; }
        if (cell.isFormula()) {
            Object v = wb.value(sh, r, c);
            String f = Formula.normalize(raw.substring(1));
            o.append(head);
            if (v instanceof Double) o.append("><f>").append(esc(f)).append("</f><v>").append(NumFmt.plain((Double) v)).append("</v></c>");
            else if (v instanceof Boolean) o.append(" t=\"b\"><f>").append(esc(f)).append("</f><v>").append(((Boolean) v) ? 1 : 0).append("</v></c>");
            else if (v instanceof XlErr) o.append(" t=\"e\"><f>").append(esc(f)).append("</f><v>").append(esc(((XlErr) v).code)).append("</v></c>");
            else o.append(" t=\"str\"><f>").append(esc(f)).append("</f><v>").append(esc(v == null ? "" : v.toString())).append("</v></c>");
            return;
        }
        Object lit = Formula.parseLiteral(raw);
        if (lit instanceof Double) {
            o.append(head).append("><v>").append(NumFmt.plain((Double) lit)).append("</v></c>");
        } else if (lit instanceof Boolean) {
            o.append(head).append(" t=\"b\"><v>").append(((Boolean) lit) ? 1 : 0).append("</v></c>");
        } else {
            o.append(head).append(" t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(esc(lit.toString())).append("</t></is></c>");
        }
    }
}
