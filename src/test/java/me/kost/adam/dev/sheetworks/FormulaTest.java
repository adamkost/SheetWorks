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

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Formula parsing, evaluation and rewriting. */
class FormulaTest {
    private Workbook wb;
    private Sheet s;

    @BeforeEach
    void setUp() {
        wb = Workbook.blank();
        s = wb.sheets.get(0);
        String[][] d = {{"Item", "Qty", "Price"}, {"Pen", "10", "1.5"}, {"Cap", "3", "4"}, {"Pad", "7", "2.25"}};
        for (int r = 0; r < d.length; r++) for (int c = 0; c < 3; c++) wb.set(s, r, c, new Cell(d[r][c], Style.DEFAULT));
        wb.invalidate();
    }

    /** Puts a formula in a scratch cell and returns what the grid would show. */
    private String f(String formula) {
        wb.set(s, 99, 0, new Cell(formula, Style.DEFAULT));
        wb.invalidate();
        return wb.display(s, 99, 0);
    }

    @Test
    void referencesAndChains() {
        wb.set(s, 0, 4, new Cell("=SUM(B2:B4)", Style.DEFAULT));
        wb.set(s, 1, 4, new Cell("=E1*2", Style.DEFAULT));
        wb.set(s, 2, 4, new Cell("=F3+1", Style.DEFAULT));
        wb.set(s, 3, 4, new Cell("=E4", Style.DEFAULT));
        wb.invalidate();
        assertEquals("20", wb.display(s, 0, 4), "sum range");
        assertEquals("40", wb.display(s, 1, 4), "chained reference");
        assertEquals("1", wb.display(s, 2, 4), "empty reference counts as zero");
        assertEquals("#CIRC!", wb.display(s, 3, 4), "self reference");
    }

    @Test
    void operators() {
        assertEquals("7", f("=1+2*3"), "precedence");
        assertEquals("4", f("=-2^2"), "unary minus binds tighter than ^");
        assertEquals("9", f("=(1+2)*3"), "parentheses");
        assertEquals("0.5", f("=50%"), "percent");
        assertEquals("#DIV/0!", f("=1/0"), "division by zero");
        assertEquals("a1", f("=\"a\"&1"), "concatenation");
        assertEquals("TRUE", f("=B2>B3"), "comparison");
    }

    @Test
    void functions() {
        assertEquals("yes", f("=IF(B2>5,\"yes\",\"no\")"), "IF");
        assertEquals("0", f("=IFERROR(1/0,0)"), "IFERROR");
        assertEquals("6.666666667", f("=AVERAGE(B2:B4)"), "AVERAGE");
        assertEquals("3,10", f("=MIN(B2:B4)&\",\"&MAX(B2:B4)"), "MIN and MAX");
        assertEquals("3", f("=COUNT(B1:B4)"), "COUNT ignores text");
        assertEquals("4", f("=COUNTA(A1:A4)"), "COUNTA");
        assertEquals("17", f("=SUMIF(B2:B4,\">5\")"), "SUMIF");
        assertEquals("3.75", f("=SUMIF(A2:A4,\"Pen\",C2:C4)*2.5"), "SUMIF with separate sum range");
        assertEquals("2", f("=COUNTIF(B2:B4,\">=7\")"), "COUNTIF");
        assertEquals("4", f("=VLOOKUP(\"Cap\",A2:C4,3,FALSE)"), "VLOOKUP exact");
        assertEquals("#N/A", f("=VLOOKUP(\"Zip\",A2:C4,3,FALSE)"), "VLOOKUP missing");
        assertEquals("2.35", f("=ROUND(2.345,2)"), "ROUND half away from zero");
        assertEquals("2", f("=MOD(-1,3)"), "MOD takes the sign of the divisor");
        assertEquals("AB|2|bc", f("=UPPER(\"ab\")&\"|\"&LEN(\"xy\")&\"|\"&MID(\"abcd\",2,2)"), "text functions");
        assertEquals("20", f("=sum(b2:b4)"), "lower-case input");
        assertEquals("#NAME?", f("=FOO(1)"), "unknown function");
        assertEquals("20", f("=SUM(B:B)"), "whole column");
    }

    @Test
    void crossSheetReferences() {
        Sheet other = new Sheet("My Data");
        wb.sheets.add(other);
        wb.set(other, 0, 0, new Cell("5", Style.DEFAULT));
        assertEquals("25", f("='My Data'!A1*5"));
        assertEquals("#REF!", f("=Missing!A1"));
    }

    @Test
    void rewritingFormulas() {
        assertEquals("=SUM(C2:C4)+$A$1+Sheet2!D5", Formula.shift("=SUM(B1:B3)+$A$1+Sheet2!C4", 1, 1), "shift keeps absolute refs");
        assertEquals("=\"B1\"&C2", Formula.shift("=\"B1\"&B1", 1, 1), "shift skips string literals");
        assertEquals("=SUM(A1:B2)&\"sum(a1)\"", Formula.normalize("=sum(a1:b2)&\"sum(a1)\""), "normalize upper-cases outside strings");
    }
}
