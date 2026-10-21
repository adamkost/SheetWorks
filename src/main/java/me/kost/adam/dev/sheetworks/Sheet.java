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

import java.util.HashMap;
import java.util.HashSet;

/** A worksheet: a sparse grid of cells plus column widths. */
public final class Sheet {
    public static final int MAX_ROWS = 1048576, MAX_COLS = 16384;

    public String name;
    final HashMap<Long, Cell> cells = new HashMap<>();
    public final HashMap<Integer, Integer> colWidths = new HashMap<>();
    public int maxRow = -1, maxCol = -1;
    final HashMap<Long, Object> cache = new HashMap<>();
    final HashSet<Long> visiting = new HashSet<>();

    public Sheet(String name) { this.name = name; }

    public static long key(int r, int c) { return ((long) r << 14) | c; }
    public static int rowOf(long k) { return (int) (k >> 14); }
    public static int colOf(long k) { return (int) (k & 16383); }

    public Cell get(int r, int c) { return cells.get(key(r, c)); }

    public java.util.Collection<Long> keys() { return cells.keySet(); }

    void putRaw(int r, int c, Cell cell) {
        long k = key(r, c);
        if (cell == null) {
            cells.remove(k);
        } else {
            cells.put(k, cell);
            if (r > maxRow) maxRow = r;
            if (c > maxCol) maxCol = c;
        }
    }

    public static String colName(int c) {
        StringBuilder sb = new StringBuilder();
        for (int n = c + 1; n > 0; n = (n - 1) / 26) sb.insert(0, (char) ('A' + (n - 1) % 26));
        return sb.toString();
    }

    /** Parses column letters ("A", "AB") to a zero-based index, or -1 if invalid. */
    public static int parseCol(String letters) {
        int n = 0;
        for (int i = 0; i < letters.length(); i++) {
            char ch = Character.toUpperCase(letters.charAt(i));
            if (ch < 'A' || ch > 'Z') return -1;
            n = n * 26 + (ch - 'A' + 1);
        }
        return n - 1;
    }

    public static String ref(int r, int c) { return colName(c) + (r + 1); }
}
