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

import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EventObject;
import java.util.List;
import java.util.function.UnaryOperator;
import javax.swing.AbstractAction;
import javax.swing.AbstractListModel;
import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.DefaultListSelectionModel;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.TableColumnModelEvent;
import javax.swing.event.TableColumnModelListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableColumn;

/** The grid for one worksheet. */
final class SheetPanel extends JScrollPane {
    static final Color ACCENT = new Color(0x2F6B57), GRID = new Color(0xD9D6CC), HEAD = new Color(0xECEAE3),
            HEAD_SEL = new Color(0xCFE3DA), ERR_COLOR = new Color(0xB3402F);
    private static final int DEFAULT_COL_WIDTH = 90;
    private static String lastCopied;
    private static int lastR, lastC;

    final SheetHost frame;
    final Workbook wb;
    final Sheet sheet;
    final JTable table;
    final JList<String> rowHdr;
    final int rows, cols;
    private int rowAnchor;

    SheetPanel(SheetHost frame, Workbook wb, Sheet sheet) {
        this.frame = frame;
        this.wb = wb;
        this.sheet = sheet;
        rows = Math.min(Sheet.MAX_ROWS, Math.max(1000, sheet.maxRow + 101));
        cols = Math.min(Sheet.MAX_COLS, Math.max(52, sheet.maxCol + 11));

        final SheetPanel self = this;
        AbstractTableModel model = new AbstractTableModel() {
            @Override public int getRowCount() { return rows; }
            @Override public int getColumnCount() { return cols; }
            @Override public String getColumnName(int c) { return Sheet.colName(c); }
            @Override public Object getValueAt(int r, int c) { return self.wb.display(self.sheet, r, c); }
            @Override public boolean isCellEditable(int r, int c) { return true; }
            @Override public void setValueAt(Object v, int r, int c) { self.frame.commitEdit(self.sheet, r, c, String.valueOf(v)); }
        };

        final JTextField editorField = new JTextField();
        table = new JTable(model) {
            @Override public boolean editCellAt(int r, int c, EventObject e) {
                if (e instanceof KeyEvent) {
                    KeyEvent k = (KeyEvent) e;
                    char ch = k.getKeyChar();
                    boolean mod = (k.isControlDown() || k.isMetaDown()) && !(k.isControlDown() && k.isAltDown());
                    if (k.getID() != KeyEvent.KEY_PRESSED || ch == KeyEvent.CHAR_UNDEFINED || Character.isISOControl(ch) || mod) return false;
                }
                boolean ok = super.editCellAt(r, c, e);
                if (ok && e instanceof KeyEvent) editorField.setText(""); // typing over a cell replaces its content
                return ok;
            }
        };
        table.setCellSelectionEnabled(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setRowHeight(22);
        table.setGridColor(GRID);
        table.setShowGrid(true);
        table.setSurrendersFocusOnKeystroke(true);
        table.setFillsViewportHeight(true);
        table.setSelectionBackground(new Color(0xC6DDD2));
        table.setSelectionForeground(Color.BLACK);
        table.getTableHeader().setReorderingAllowed(false);
        for (int c = 0; c < cols; c++) {
            Integer w = sheet.colWidths.get(c);
            table.getColumnModel().getColumn(c).setPreferredWidth(w == null ? DEFAULT_COL_WIDTH : w);
            table.getColumnModel().getColumn(c).setWidth(w == null ? DEFAULT_COL_WIDTH : w);
        }

        final Font base = table.getFont();
        final Font[] fonts = {base, base.deriveFont(Font.BOLD), base.deriveFont(Font.ITALIC), base.deriveFont(Font.BOLD | Font.ITALIC)};
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int r, int c) {
                super.getTableCellRendererComponent(t, v, sel, false, r, c);
                Cell cell = self.sheet.get(r, c);
                Style st = cell == null ? Style.DEFAULT : cell.style;
                Object val = self.wb.value(self.sheet, r, c);
                int al = st.align;
                if (al == Style.GENERAL) al = val instanceof Double ? Style.RIGHT : (val instanceof Boolean || val instanceof XlErr) ? Style.CENTER : Style.LEFT;
                setHorizontalAlignment(al == Style.LEFT ? SwingConstants.LEFT : al == Style.CENTER ? SwingConstants.CENTER : SwingConstants.RIGHT);
                setFont(fonts[(st.bold ? 1 : 0) + (st.italic ? 2 : 0)]);
                if (!sel) {
                    setBackground(st.fill >= 0 ? new Color(st.fill) : Color.WHITE);
                    setForeground(val instanceof XlErr ? ERR_COLOR : st.fg >= 0 ? new Color(st.fg) : Color.BLACK);
                }
                setBorder(foc ? BorderFactory.createLineBorder(ACCENT, 2) : BorderFactory.createEmptyBorder(0, 4, 0, 4));
                return this;
            }
        });

        editorField.setFont(base);
        table.setDefaultEditor(Object.class, new DefaultCellEditor(editorField) {
            @Override public Component getTableCellEditorComponent(JTable t, Object v, boolean sel, int r, int c) {
                super.getTableCellEditorComponent(t, v, sel, r, c);
                Cell cell = self.sheet.get(r, c);
                editorField.setText(cell == null ? "" : cell.raw);
                return editorField;
            }
        });
        editorField.getDocument().addDocumentListener(new DocumentListener() {
            void changed() { if (self.table.isEditing()) self.frame.setFormulaText(editorField.getText()); }
            @Override public void insertUpdate(DocumentEvent e) { changed(); }
            @Override public void removeUpdate(DocumentEvent e) { changed(); }
            @Override public void changedUpdate(DocumentEvent e) { changed(); }
        });

        // column header
        JTableHeader header = table.getTableHeader();
        header.setDefaultRenderer(new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int r, int c) {
                super.getTableCellRendererComponent(t, v, false, false, r, c);
                setHorizontalAlignment(SwingConstants.CENTER);
                setBackground(t.isColumnSelected(c) ? HEAD_SEL : HEAD);
                setFont(base.deriveFont(t.isColumnSelected(c) ? Font.BOLD : Font.PLAIN));
                setBorder(BorderFactory.createMatteBorder(0, 0, 1, 1, GRID));
                return this;
            }
        });

        // row header
        rowHdr = new JList<String>(new AbstractListModel<String>() {
            @Override public int getSize() { return rows; }
            @Override public String getElementAt(int i) { return String.valueOf(i + 1); }
        });
        rowHdr.setFixedCellWidth(48);
        rowHdr.setFixedCellHeight(table.getRowHeight());
        rowHdr.setSelectionModel(new DefaultListSelectionModel() { @Override public void setSelectionInterval(int a, int b) { } });
        rowHdr.setCellRenderer((list, value, index, sel, foc) -> {
            JLabel l = new JLabel(value, SwingConstants.CENTER);
            l.setOpaque(true);
            boolean on = table.isRowSelected(index);
            l.setBackground(on ? HEAD_SEL : HEAD);
            l.setFont(base.deriveFont(on ? Font.BOLD : Font.PLAIN));
            l.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 1, GRID));
            return l;
        });
        MouseAdapter rowMouse = new MouseAdapter() {
            void select(MouseEvent e, boolean press) {
                int i = rowHdr.locationToIndex(e.getPoint());
                if (i < 0) return;
                table.setColumnSelectionInterval(0, cols - 1);
                if (press && !e.isShiftDown()) rowAnchor = i;
                table.setRowSelectionInterval(rowAnchor, i);
                table.requestFocusInWindow();
            }
            @Override public void mousePressed(MouseEvent e) { select(e, true); }
            @Override public void mouseDragged(MouseEvent e) { select(e, false); }
        };
        rowHdr.addMouseListener(rowMouse);
        rowHdr.addMouseMotionListener(rowMouse);
        setViewportView(table);
        setRowHeaderView(rowHdr);
        JLabel corner = new JLabel();
        corner.setOpaque(true);
        corner.setBackground(HEAD);
        setCorner(JScrollPane.UPPER_LEFT_CORNER, corner);
        getViewport().setBackground(Color.WHITE);

        // selection and column resize tracking
        table.getSelectionModel().addListSelectionListener((ListSelectionEvent e) -> { if (!e.getValueIsAdjusting()) selectionChanged(); });
        table.getColumnModel().addColumnModelListener(new TableColumnModelListener() {
            @Override public void columnAdded(TableColumnModelEvent e) { }
            @Override public void columnRemoved(TableColumnModelEvent e) { }
            @Override public void columnMoved(TableColumnModelEvent e) { }
            @Override public void columnSelectionChanged(ListSelectionEvent e) { if (!e.getValueIsAdjusting()) selectionChanged(); }
            @Override public void columnMarginChanged(javax.swing.event.ChangeEvent e) {
                TableColumn rc = table.getTableHeader().getResizingColumn();
                if (rc != null) {
                    self.sheet.colWidths.put(rc.getModelIndex(), rc.getWidth());
                    self.wb.dirty = true;
                    self.frame.updateTitle();
                }
            }
        });

        // key bindings (copy/cut/paste replace the table's defaults, which only handle display text)
        javax.swing.InputMap im = table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        javax.swing.ActionMap am = table.getActionMap();
        am.put("copy", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { copy(false); } });
        am.put("cut", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { copy(true); } });
        am.put("paste", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { paste(); } });
        AbstractAction clear = new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { clearSelection(); } };
        am.put("sw-clear", clear);
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "sw-clear");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "sw-clear");

        table.changeSelection(0, 0, false, false);
    }

    private void selectionChanged() {
        rowHdr.repaint();
        table.getTableHeader().repaint();
        frame.selectionChanged(this);
    }

    /** Selected rectangle as {row1, col1, row2, col2}. */
    int[] rect() {
        int[] rs = table.getSelectedRows(), cs = table.getSelectedColumns();
        if (rs.length == 0 || cs.length == 0) { int[] a = active(); return new int[] {a[0], a[1], a[0], a[1]}; }
        return new int[] {rs[0], cs[0], rs[rs.length - 1], cs[cs.length - 1]};
    }

    /** The active (cursor) cell as {row, col}. */
    int[] active() {
        int r = table.getSelectionModel().getLeadSelectionIndex(), c = table.getColumnModel().getSelectionModel().getLeadSelectionIndex();
        return new int[] {Math.max(0, r), Math.max(0, c)};
    }

    void stopEditing() { if (table.isEditing()) table.getCellEditor().stopCellEditing(); }

    // ---- operations ----

    void applyStyle(UnaryOperator<Style> fn) {
        stopEditing();
        int[] g = rect();
        wb.begin();
        for (int r = g[0]; r <= g[2]; r++) for (int c = g[1]; c <= g[3]; c++) {
            Cell cell = sheet.get(r, c);
            wb.setStyle(sheet, r, c, fn.apply(cell == null ? Style.DEFAULT : cell.style));
        }
        wb.commit();
        frame.refresh();
    }

    Style activeStyle() {
        int[] a = active();
        Cell c = sheet.get(a[0], a[1]);
        return c == null ? Style.DEFAULT : c.style;
    }

    void clearSelection() {
        stopEditing();
        int[] g = rect();
        wb.begin();
        for (int r = g[0]; r <= g[2]; r++) for (int c = g[1]; c <= g[3]; c++) if (sheet.get(r, c) != null) wb.setRaw(sheet, r, c, "");
        wb.commit();
        frame.refresh();
    }

    private String rawText(int[] g) {
        StringBuilder sb = new StringBuilder();
        for (int r = g[0]; r <= g[2]; r++) {
            for (int c = g[1]; c <= g[3]; c++) {
                if (c > g[1]) sb.append('\t');
                Cell cell = sheet.get(r, c);
                if (cell != null) sb.append(cell.raw.replace('\t', ' ').replace('\n', ' ').replace('\r', ' '));
            }
            if (r < g[2]) sb.append('\n');
        }
        return sb.toString();
    }

    void copy(boolean cut) {
        stopEditing();
        int[] g = rect();
        if ((long) (g[2] - g[0] + 1) * (g[3] - g[1] + 1) > 2_000_000L) return;
        String t = rawText(g);
        lastCopied = t; lastR = g[0]; lastC = g[1];
        try { Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(t), null); } catch (IllegalStateException | java.awt.HeadlessException e) { /* clipboard unavailable */ }
        if (cut) clearSelection();
    }

    void paste() {
        stopEditing();
        String t;
        try {
            Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
            if (!cb.isDataFlavorAvailable(DataFlavor.stringFlavor)) return;
            t = (String) cb.getData(DataFlavor.stringFlavor);
        } catch (Exception e) {
            return;
        }
        if (t == null || t.isEmpty()) return;
        boolean same = t.equals(lastCopied);
        String[] lines = t.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int n = lines.length;
        if (n > 0 && lines[n - 1].isEmpty()) n--;
        int[] g = rect();
        wb.begin();
        for (int i = 0; i < n; i++) {
            String[] f = lines[i].split("\t", -1);
            for (int j = 0; j < f.length; j++) {
                int r = g[0] + i, c = g[1] + j;
                if (r >= rows || c >= cols) continue;
                String v = f[j];
                if (!same && v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length() - 1).replace("\"\"", "\"");
                if (same) v = Formula.shift(v, g[0] - lastR, g[1] - lastC);
                if (v.startsWith("=")) v = Formula.normalize(v);
                wb.setRaw(sheet, r, c, v);
            }
        }
        wb.commit();
        frame.refresh();
    }

    void fill(boolean down) {
        stopEditing();
        int[] g = rect();
        wb.begin();
        if (down) {
            for (int c = g[1]; c <= g[3]; c++) {
                Cell src = sheet.get(g[0], c);
                for (int r = g[0] + 1; r <= g[2]; r++) wb.setRaw(sheet, r, c, src == null ? "" : Formula.shift(src.raw, r - g[0], 0));
            }
        } else {
            for (int r = g[0]; r <= g[2]; r++) {
                Cell src = sheet.get(r, g[1]);
                for (int c = g[1] + 1; c <= g[3]; c++) wb.setRaw(sheet, r, c, src == null ? "" : Formula.shift(src.raw, 0, c - g[1]));
            }
        }
        wb.commit();
        frame.refresh();
    }

    /** Sorts the selected rows by the column where the selection started. Formulas are moved as written, not rewritten. */
    void sort(boolean ascending) {
        stopEditing();
        int[] g = rect();
        if (g[0] == g[2]) return;
        int key = Math.min(Math.max(table.getColumnModel().getSelectionModel().getAnchorSelectionIndex(), g[1]), g[3]);
        final List<Object[]> rowsList = new ArrayList<>(); // {sortValue, Cell[]}
        for (int r = g[0]; r <= g[2]; r++) {
            Cell[] cs = new Cell[g[3] - g[1] + 1];
            for (int c = g[1]; c <= g[3]; c++) cs[c - g[1]] = sheet.get(r, c);
            rowsList.add(new Object[] {wb.value(sheet, r, key), cs});
        }
        Collections.sort(rowsList, (a, b) -> {
            Object x = a[0], y = b[0];
            if (x == null || y == null) return x == null ? (y == null ? 0 : 1) : -1; // empty cells always last
            int k = Formula.compare(x, y);
            return ascending ? k : -k;
        });
        wb.begin();
        for (int i = 0; i < rowsList.size(); i++) {
            Cell[] cs = (Cell[]) rowsList.get(i)[1];
            for (int c = g[1]; c <= g[3]; c++) wb.set(sheet, g[0] + i, c, cs[c - g[1]]);
        }
        wb.commit();
        frame.refresh();
    }

    /** Sum / average / count of the selected numbers, as text for the status bar. */
    String stats() {
        int[] g = rect();
        long area = (long) (g[2] - g[0] + 1) * (g[3] - g[1] + 1);
        double sum = 0; int n = 0;
        if (area <= 200000) {
            for (int r = g[0]; r <= g[2]; r++) for (int c = g[1]; c <= g[3]; c++) {
                Object v = wb.value(sheet, r, c);
                if (v instanceof Double) { sum += (Double) v; n++; }
            }
        } else {
            for (long k : new ArrayList<>(sheet.keys())) {
                int r = Sheet.rowOf(k), c = Sheet.colOf(k);
                if (r < g[0] || r > g[2] || c < g[1] || c > g[3]) continue;
                Object v = wb.value(sheet, r, c);
                if (v instanceof Double) { sum += (Double) v; n++; }
            }
        }
        if (n < 2) return "";
        return "Sum " + NumFmt.general(sum) + "    Average " + NumFmt.general(sum / n) + "    Count " + n;
    }
}
