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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Minimal CSV reader and writer. */
public final class Csv {
    private Csv() {}

    public static Workbook read(Path p) throws IOException {
        String t = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        if (t.startsWith("\uFEFF")) t = t.substring(1);
        Workbook wb = Workbook.blank();
        Sheet sh = wb.sheets.get(0);
        List<String> row = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inq = false;
        int r = 0;
        for (int i = 0; i <= t.length(); i++) {
            boolean end = i == t.length();
            char ch = end ? '\n' : t.charAt(i);
            if (!end && inq) {
                if (ch == '"') { if (i + 1 < t.length() && t.charAt(i + 1) == '"') { cur.append('"'); i++; } else inq = false; }
                else cur.append(ch);
            } else if (!end && ch == '"') {
                inq = true;
            } else if (!end && ch == ',') {
                row.add(cur.toString()); cur.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < t.length() && t.charAt(i + 1) == '\n') i++;
                if (end && cur.length() == 0 && row.isEmpty()) break;
                row.add(cur.toString()); cur.setLength(0);
                for (int c = 0; c < row.size() && c < Sheet.MAX_COLS && r < Sheet.MAX_ROWS; c++) {
                    String v = row.get(c);
                    if (!v.isEmpty()) wb.set(sh, r, c, new Cell(v, Style.DEFAULT));
                }
                row.clear();
                r++;
            } else {
                cur.append(ch);
            }
        }
        wb.invalidate();
        return wb;
    }

    public static void write(Workbook wb, Sheet sh, Path p) throws IOException {
        StringBuilder out = new StringBuilder();
        for (int r = 0; r <= sh.maxRow; r++) {
            for (int c = 0; c <= sh.maxCol; c++) {
                if (c > 0) out.append(',');
                String v = wb.display(sh, r, c);
                if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0) v = "\"" + v.replace("\"", "\"\"") + "\"";
                out.append(v);
            }
            out.append("\r\n");
        }
        Files.write(p, out.toString().getBytes(StandardCharsets.UTF_8));
    }
}
