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
package sheetworks;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Saving and re-reading .xlsx and .csv files, and undo history. */
class FileRoundTripTest {
    @Test
    void xlsxKeepsValuesFormulasStylesAndSheets() throws Exception {
        Workbook wb = Workbook.blank();
        Sheet s = wb.sheets.get(0);
        String[][] d = {{"Item", "Qty", "Price"}, {"Pen", "10", "1.5"}, {"Cap", "3", "4"}, {"Pad", "7", "2.25"}};
        for (int r = 0; r < d.length; r++) for (int c = 0; c < 3; c++) wb.set(s, r, c, new Cell(d[r][c], Style.DEFAULT));
        wb.set(s, 0, 4, new Cell("=SUM(B2:B4)", Style.DEFAULT));
        wb.set(s, 5, 0, new Cell("'00123", Style.DEFAULT));
        wb.set(s, 5, 1, new Cell("hello & <world>", Style.DEFAULT));
        Style fancy = Style.DEFAULT.withBold(true).withItalic(true).withAlign(Style.CENTER)
                .withFill(0xFFEEAA).withFg(0xB3402F).withNumFmt("$#,##0.00");
        wb.set(s, 6, 0, new Cell("1234.5", fancy));
        s.colWidths.put(0, 140);
        Sheet second = new Sheet("My Data");
        wb.sheets.add(second);
        wb.set(second, 0, 0, new Cell("5", Style.DEFAULT));

        Path tmp = Files.createTempFile("sheetworks", ".xlsx");
        try {
            XlsxIO.write(wb, tmp);
            Workbook back = XlsxIO.read(tmp);
            Sheet b = back.sheets.get(0);
            assertEquals("2", String.valueOf(back.sheets.size()), "sheet count");
            assertEquals("My Data", back.sheets.get(1).name, "sheet name");
            assertEquals("20", back.display(b, 0, 4), "formula result");
            assertEquals("=SUM(B2:B4)", b.get(0, 4).raw, "formula text");
            assertEquals("00123", back.display(b, 5, 0), "text that looks like a number stays text");
            assertEquals("hello & <world>", back.display(b, 5, 1), "XML special characters");
            assertEquals("$1,234.50", back.display(b, 6, 0), "number format");
            Style st = b.get(6, 0).style;
            assertEquals("true,true," + Style.CENTER + ",16772778,11747375",
                    st.bold + "," + st.italic + "," + st.align + "," + st.fill + "," + st.fg, "style");
            assertEquals("140", String.valueOf(back.sheets.get(0).colWidths.get(0)), "column width");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void csvRoundTrip() throws Exception {
        Workbook wb = Workbook.blank();
        Sheet s = wb.sheets.get(0);
        wb.set(s, 0, 0, new Cell("Pen", Style.DEFAULT));
        wb.set(s, 0, 1, new Cell("a, \"quoted\" value", Style.DEFAULT));
        wb.set(s, 1, 0, new Cell("=1+2", Style.DEFAULT));
        wb.invalidate();
        Path tmp = Files.createTempFile("sheetworks", ".csv");
        try {
            Csv.write(wb, s, tmp);
            Workbook back = Csv.read(tmp);
            Sheet b = back.sheets.get(0);
            assertEquals("Pen", back.display(b, 0, 0));
            assertEquals("a, \"quoted\" value", back.display(b, 0, 1), "quoted field");
            assertEquals("3", back.display(b, 1, 0), "formulas are exported as their values");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void undoAndRedo() {
        Workbook wb = Workbook.blank();
        Sheet s = wb.sheets.get(0);
        wb.begin(); wb.setRaw(s, 0, 0, "1"); wb.commit();
        wb.begin(); wb.setRaw(s, 0, 0, "2"); wb.commit();
        wb.undo();
        assertEquals("1", wb.display(s, 0, 0), "undo");
        wb.redo();
        assertEquals("2", wb.display(s, 0, 0), "redo");
    }
}
