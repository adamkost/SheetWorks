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

/** A spreadsheet error value. It is both a value stored in cells and an exception used to propagate errors. */
public final class XlErr extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public static final XlErr DIV0 = new XlErr("#DIV/0!");
    public static final XlErr VALUE = new XlErr("#VALUE!");
    public static final XlErr REF = new XlErr("#REF!");
    public static final XlErr NAME = new XlErr("#NAME?");
    public static final XlErr NUM = new XlErr("#NUM!");
    public static final XlErr NA = new XlErr("#N/A");
    public static final XlErr CIRC = new XlErr("#CIRC!");
    public static final XlErr ERR = new XlErr("#ERR!");

    public final String code;

    private XlErr(String code) {
        super(code, null, false, false);
        this.code = code;
    }

    public static XlErr of(String code) {
        for (XlErr e : new XlErr[] {DIV0, VALUE, REF, NAME, NUM, NA, CIRC}) {
            if (e.code.equalsIgnoreCase(code)) return e;
        }
        return ERR;
    }

    @Override public String toString() { return code; }
}
