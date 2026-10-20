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

/** One cell: the text the user typed (or "=formula"), its style, and for imported formulas the cached result. */
public final class Cell {
    public final String raw;
    public final Style style;
    /** Result stored in the file for a formula cell; used only when SheetWorks cannot evaluate the formula. */
    public final Object cached;
    Formula.Node node; // lazily parsed formula

    public Cell(String raw, Style style, Object cached) {
        this.raw = raw == null ? "" : raw;
        this.style = style == null ? Style.DEFAULT : style;
        this.cached = cached;
    }

    public Cell(String raw, Style style) { this(raw, style, null); }
    public Cell withStyle(Style s) { return new Cell(raw, s, cached); }
    public boolean isFormula() { return raw.length() > 1 && raw.charAt(0) == '='; }
}
