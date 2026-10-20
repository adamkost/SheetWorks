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

import java.awt.Component;
import java.awt.event.KeyEvent;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.JTextField;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The grid component, driven without a window. */
class GridTest {
    static { System.setProperty("java.awt.headless", "true"); }

    private Workbook wb;
    private Sheet s;
    private SheetPanel p;
    private JTable t;
    private int commits;

    @BeforeEach
    void setUp() {
        wb = Workbook.blank();
        s = wb.sheets.get(0);
        commits = 0;
        SheetHost host = new SheetHost() {
            @Override public void commitEdit(Sheet sh, int r, int c, String text) {
                commits++;
                wb.begin();
                wb.setRaw(sh, r, c, text.startsWith("=") ? Formula.normalize(text) : text);
                wb.commit();
            }
            @Override public void setFormulaText(String text) { }
            @Override public void selectionChanged(SheetPanel panel) { }
            @Override public void refresh() { }
            @Override public void updateTitle() { }
        };
        String[][] d = {{"b", "2", "x"}, {"a", "3", "y"}, {"c", "1", "z"}};
        for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) wb.set(s, r, c, new Cell(d[r][c], Style.DEFAULT));
        wb.invalidate();
        p = new SheetPanel(host, wb, s);
        t = p.table;
    }

    private String column(int c) { return wb.display(s, 0, c) + "," + wb.display(s, 1, c) + "," + wb.display(s, 2, c); }

    private String editorText(int r, int c) {
        t.editCellAt(r, c, null);
        String v = ((JTextField) t.getEditorComponent()).getText();
        t.getCellEditor().cancelCellEditing();
        return v;
    }

    @Test
    void modelBasics() {
        assertEquals("1000x52", t.getRowCount() + "x" + t.getColumnCount(), "default size");
        assertEquals("AA", t.getColumnName(26), "column names");
        assertEquals("3", String.valueOf(t.getValueAt(1, 1)), "displayed value");
    }

    @Test
    void typingReplacesAndF2Keeps() {
        t.changeSelection(0, 1, false, false);
        KeyEvent typed = new KeyEvent(t, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_7, '7');
        assertEquals("true", String.valueOf(t.editCellAt(0, 1, typed)), "typing starts editing");
        assertEquals("", ((JTextField) t.getEditorComponent()).getText(), "typing clears the old text");
        t.getCellEditor().cancelCellEditing();
        assertEquals("2", editorText(0, 1), "F2 or double-click keeps the text");
        KeyEvent ctrlC = new KeyEvent(t, KeyEvent.KEY_PRESSED, 0, KeyEvent.CTRL_DOWN_MASK, KeyEvent.VK_C, 'c');
        assertEquals("false", String.valueOf(t.editCellAt(0, 1, ctrlC)), "ctrl shortcuts do not start editing");
        KeyEvent f5 = new KeyEvent(t, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_F5, KeyEvent.CHAR_UNDEFINED);
        assertEquals("false", String.valueOf(t.editCellAt(0, 1, f5)), "function keys do not start editing");
    }

    @Test
    void editorCommitGoesThroughHost() {
        t.editCellAt(0, 1, null);
        ((JTextField) t.getEditorComponent()).setText("=sum(b2:b3)");
        t.getCellEditor().stopCellEditing();
        assertEquals("=SUM(B2:B3)", s.get(0, 1).raw, "formula is normalized");
        assertEquals("4", wb.display(s, 0, 1), "formula is evaluated");
        assertEquals("1", String.valueOf(commits), "host was told once");
    }

    @Test
    void alignmentFollowsValueType() {
        Component num = t.getCellRenderer(1, 1).getTableCellRendererComponent(t, "3", false, false, 1, 1);
        assertEquals("4", String.valueOf(((JLabel) num).getHorizontalAlignment()), "numbers are right-aligned");
        Component txt = t.getCellRenderer(1, 0).getTableCellRendererComponent(t, "a", false, false, 1, 0);
        assertEquals("2", String.valueOf(((JLabel) txt).getHorizontalAlignment()), "text is left-aligned");
    }

    @Test
    void sortKeepsRowsTogetherAndCanBeUndone() {
        t.changeSelection(0, 0, false, false);
        t.changeSelection(2, 2, false, true);
        p.sort(true);
        assertEquals("a,b,c", column(0), "ascending");
        assertEquals("3,2,1", column(1), "rows stay together");
        p.sort(false);
        assertEquals("c,b,a", column(0), "descending");
        wb.undo();
        assertEquals("a,b,c", column(0), "undo");
    }

    @Test
    void fillDownShiftsReferences() {
        wb.begin(); wb.setRaw(s, 0, 4, "=B1*2"); wb.commit();
        t.changeSelection(0, 4, false, false);
        t.setRowSelectionInterval(0, 2);
        p.fill(true);
        assertEquals("=B3*2", s.get(2, 4).raw);
        assertEquals("2", wb.display(s, 2, 4));
    }

    @Test
    void stylesAndClearing() {
        t.changeSelection(0, 0, false, false);
        p.applyStyle(st -> st.withBold(true).withFill(0xFFEEAA));
        assertEquals("true,16772778", s.get(0, 0).style.bold + "," + s.get(0, 0).style.fill, "style applied");
        assertEquals("true", String.valueOf(p.activeStyle().bold), "active style");
        p.clearSelection();
        assertEquals("true", String.valueOf(s.get(0, 0) != null && s.get(0, 0).style.bold), "clearing keeps the style");
        assertEquals("", wb.display(s, 0, 0), "clearing empties the text");
    }

    @Test
    void selectionAndStatistics() {
        t.setRowSelectionInterval(0, 2);
        t.setColumnSelectionInterval(1, 1);
        int[] g = p.rect();
        assertEquals("0,1,2,1", g[0] + "," + g[1] + "," + g[2] + "," + g[3], "selected rectangle");
        assertEquals("Sum 6    Average 2    Count 3", p.stats(), "status bar statistics");
    }
}
