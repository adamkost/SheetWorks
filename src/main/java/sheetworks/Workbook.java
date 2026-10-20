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

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** A set of sheets, the formula evaluator entry points, and the undo history. */
public final class Workbook {
    public final List<Sheet> sheets = new ArrayList<>();
    public Path path;
    public boolean dirty;
    /** True when loaded from an .xlsx file written by another program (saving may drop unsupported features). */
    public boolean foreignXlsx;

    private static final class Change {
        final Sheet sheet; final int r, c; final Cell before, after;
        Change(Sheet s, int r, int c, Cell b, Cell a) { sheet = s; this.r = r; this.c = c; before = b; after = a; }
    }

    private final Deque<List<Change>> undoStack = new ArrayDeque<>(), redoStack = new ArrayDeque<>();
    private List<Change> tx;

    public Workbook() {}

    public static Workbook blank() {
        Workbook wb = new Workbook();
        wb.sheets.add(new Sheet("Sheet1"));
        return wb;
    }

    public Sheet find(String name) {
        for (Sheet s : sheets) if (s.name.equalsIgnoreCase(name)) return s;
        return null;
    }

    // ---- editing with history ----

    public void begin() { tx = new ArrayList<>(); }

    public void set(Sheet s, int r, int c, Cell n) {
        Cell o = s.get(r, c);
        if (o == n) return;
        if (tx != null) tx.add(new Change(s, r, c, o, n));
        s.putRaw(r, c, n);
    }

    /** Sets the text of a cell, keeping its style. */
    public void setRaw(Sheet s, int r, int c, String raw) {
        Cell o = s.get(r, c);
        Style st = o == null ? Style.DEFAULT : o.style;
        if (raw == null || raw.isEmpty()) {
            set(s, r, c, st.isDefault() ? null : new Cell("", st));
        } else {
            set(s, r, c, new Cell(raw, st));
        }
    }

    public void setStyle(Sheet s, int r, int c, Style st) {
        Cell o = s.get(r, c);
        if (o == null) {
            if (!st.isDefault()) set(s, r, c, new Cell("", st));
        } else if (st.isDefault() && o.raw.isEmpty()) {
            set(s, r, c, null);
        } else {
            set(s, r, c, o.withStyle(st));
        }
    }

    public void commit() {
        if (tx != null && !tx.isEmpty()) {
            undoStack.push(tx);
            if (undoStack.size() > 200) undoStack.removeLast();
            redoStack.clear();
            dirty = true;
        }
        tx = null;
        invalidate();
    }

    public boolean canUndo() { return !undoStack.isEmpty(); }
    public boolean canRedo() { return !redoStack.isEmpty(); }

    public boolean undo() {
        if (undoStack.isEmpty()) return false;
        List<Change> l = undoStack.pop();
        for (int i = l.size() - 1; i >= 0; i--) { Change c = l.get(i); c.sheet.putRaw(c.r, c.c, c.before); }
        redoStack.push(l);
        dirty = true;
        invalidate();
        return true;
    }

    public boolean redo() {
        if (redoStack.isEmpty()) return false;
        List<Change> l = redoStack.pop();
        for (Change c : l) c.sheet.putRaw(c.r, c.c, c.after);
        undoStack.push(l);
        dirty = true;
        invalidate();
        return true;
    }

    public void clearHistory() { undoStack.clear(); redoStack.clear(); }

    // ---- evaluation ----

    public void invalidate() {
        for (Sheet s : sheets) { s.cache.clear(); s.visiting.clear(); }
    }

    /** The computed value of a cell: null (empty), Double, String, Boolean, or XlErr. */
    public Object value(Sheet s, int r, int c) {
        long k = Sheet.key(r, c);
        Cell cell = s.cells.get(k);
        if (cell == null || cell.raw.isEmpty()) return null;
        Object v = s.cache.get(k);
        if (v != null) return v;
        if (!s.visiting.add(k)) return XlErr.CIRC;
        try {
            v = compute(s, cell);
        } finally {
            s.visiting.remove(k);
        }
        if (v != null) s.cache.put(k, v);
        return v;
    }

    private Object compute(Sheet s, Cell cell) {
        if (!cell.isFormula()) return Formula.parseLiteral(cell.raw);
        try {
            if (cell.node == null) cell.node = Formula.parse(cell.raw.substring(1));
            Object r = cell.node.eval(new Formula.Ctx(this, s));
            if (r == null) return 0.0;
            if (r instanceof Formula.Range) return XlErr.VALUE;
            if (r instanceof Double && (((Double) r).isNaN() || ((Double) r).isInfinite())) return XlErr.NUM;
            return r;
        } catch (XlErr e) {
            if ((e == XlErr.NAME || e == XlErr.ERR) && cell.cached != null) return cell.cached;
            return e;
        } catch (StackOverflowError e) {
            return XlErr.ERR;
        } catch (RuntimeException e) {
            return XlErr.ERR;
        }
    }

    /** The text shown in the grid for a cell. */
    public String display(Sheet s, int r, int c) {
        Cell cell = s.get(r, c);
        if (cell != null && cell.raw.startsWith("'") && !cell.isFormula()) return cell.raw.substring(1);
        return NumFmt.format(value(s, r, c), cell == null ? null : cell.style.numFmt);
    }
}
