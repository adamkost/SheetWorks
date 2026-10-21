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

import org.junit.jupiter.api.Test;

/** Number and date formatting. */
class NumFmtTest {
    @Test
    void numbers() {
        assertEquals("$1,234.50", NumFmt.format(1234.5, "$#,##0.00"), "currency");
        assertEquals("-$3.00", NumFmt.format(-3.0, "$#,##0.00"), "negative currency");
        assertEquals("12.5%", NumFmt.format(0.125, "0.0%"), "percent");
        assertEquals("1234.5", NumFmt.format(1234.5, null), "general");
        assertEquals("0.1", NumFmt.general(0.1 + 0.2 - 0.2), "general hides binary noise");
    }

    @Test
    void dates() {
        assertEquals("2024-02-29", NumFmt.format(45351.0, "yyyy-mm-dd"));
        assertEquals("29-Feb-24", NumFmt.format(45351.0, "d-mmm-yy"));
        assertEquals("13:05", NumFmt.format(45351.54514, "h:mm"), "m after h means minutes");
    }
}
