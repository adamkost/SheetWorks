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

import java.util.Objects;

/** Immutable cell formatting. Colors are 0xRRGGBB or -1 for "none". */
public final class Style {
    public static final Style DEFAULT = new Style(false, false, 0, null, -1, -1);
    public static final int GENERAL = 0, LEFT = 1, CENTER = 2, RIGHT = 3;

    public final boolean bold, italic;
    public final int align;
    public final String numFmt;
    public final int fill, fg;

    public Style(boolean bold, boolean italic, int align, String numFmt, int fill, int fg) {
        this.bold = bold; this.italic = italic; this.align = align;
        this.numFmt = (numFmt == null || numFmt.equalsIgnoreCase("General")) ? null : numFmt;
        this.fill = fill; this.fg = fg;
    }

    public Style withBold(boolean v) { return new Style(v, italic, align, numFmt, fill, fg); }
    public Style withItalic(boolean v) { return new Style(bold, v, align, numFmt, fill, fg); }
    public Style withAlign(int v) { return new Style(bold, italic, v, numFmt, fill, fg); }
    public Style withNumFmt(String v) { return new Style(bold, italic, align, v, fill, fg); }
    public Style withFill(int v) { return new Style(bold, italic, align, numFmt, v, fg); }
    public Style withFg(int v) { return new Style(bold, italic, align, numFmt, fill, v); }
    public boolean isDefault() { return equals(DEFAULT); }

    @Override public boolean equals(Object o) {
        if (!(o instanceof Style)) return false;
        Style s = (Style) o;
        return bold == s.bold && italic == s.italic && align == s.align && fill == s.fill && fg == s.fg
                && Objects.equals(numFmt, s.numFmt);
    }

    @Override public int hashCode() { return Objects.hash(bold, italic, align, numFmt, fill, fg); }
}
