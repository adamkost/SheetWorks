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

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import javax.swing.filechooser.FileNameExtensionFilter;

/** A document window: menus, toolbar, formula bar, one tab per sheet, and a status bar. */
final class AppFrame extends JFrame implements SheetHost {
    private static final List<AppFrame> FRAMES = new ArrayList<>();
    private static File lastDir;
    private static final String[] FMT_LABELS = {"General", "Number", "Currency", "Percent", "Date"};
    private static final String[] FMT_CODES = {null, "#,##0.00", "$#,##0.00", "0.00%", "yyyy-mm-dd"};

    private Workbook wb;
    private final JTabbedPane tabs = new JTabbedPane(JTabbedPane.BOTTOM);
    private final JTextField fx = new JTextField();
    private final JLabel nameBox = new JLabel("A1", JLabel.CENTER);
    private final JLabel status = new JLabel("Ready");
    private final JLabel stats = new JLabel("");
    private final JComboBox<String> fmtBox = new JComboBox<>(FMT_LABELS);
    private boolean updating;
    private FileWatcher watcher;
    private Timer watchTimer;
    private boolean changePending, prompting;
    private final int mask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

    // ------------------------------------------------------------------ opening

    static AppFrame openNew() { return new AppFrame(Workbook.blank()); }

    /** Opens a file in a window (reusing the window if it is already open). Returns false on failure. */
    static boolean openPath(Path p) {
        for (AppFrame f : FRAMES) {
            if (f.wb.path != null && sameFile(f.wb.path, p)) { f.setState(NORMAL); f.toFront(); RecentFiles.shared().add(p); return true; }
        }
        try {
            String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
            Workbook wb;
            if (n.endsWith(".csv")) {
                wb = Csv.read(p);
                wb.path = null; // a CSV is imported, not edited in place
            } else {
                wb = XlsxIO.read(p);
            }
            AppFrame cur = FRAMES.isEmpty() ? null : FRAMES.get(FRAMES.size() - 1);
            if (cur != null && cur.isPristine()) cur.setWorkbook(wb); else new AppFrame(wb);
            RecentFiles.shared().add(p);
            return true;
        } catch (IOException | RuntimeException e) {
            boolean gone = !Files.exists(p);
            if (gone) RecentFiles.shared().remove(p);
            JOptionPane.showMessageDialog(null, "Could not open " + p + "\n\n" + e.getMessage()
                    + (gone ? "\n\nThe file no longer exists, so it was removed from the recent files list." : ""),
                    "SheetWorks", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    private static boolean sameFile(Path a, Path b) {
        try { return Files.isSameFile(a, b); } catch (IOException e) { return a.toAbsolutePath().equals(b.toAbsolutePath()); }
    }

    private boolean isPristine() {
        return wb.path == null && !wb.dirty && wb.sheets.size() == 1 && wb.sheets.get(0).cells.isEmpty();
    }

    AppFrame(Workbook wb) {
        super("SheetWorks");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { closeWindow(); }
            @Override public void windowActivated(WindowEvent e) { checkFileChanged(); }
        });

        JToolBar tb = new JToolBar();
        tb.setFloatable(false);
        tb.add(button("Undo", e -> undo()));
        tb.add(button("Redo", e -> redo()));
        tb.addSeparator();
        tb.add(button("B", e -> toggleBold()));
        tb.add(button("I", e -> toggleItalic()));
        tb.add(button("Left", e -> align(Style.LEFT)));
        tb.add(button("Center", e -> align(Style.CENTER)));
        tb.add(button("Right", e -> align(Style.RIGHT)));
        tb.addSeparator();
        fmtBox.setMaximumSize(new Dimension(110, 28));
        fmtBox.addActionListener(e -> { if (!updating) setNumFmt(FMT_CODES[fmtBox.getSelectedIndex()]); });
        tb.add(fmtBox);
        tb.addSeparator();
        tb.add(button("Fill…", e -> pickColor(true)));
        tb.add(button("Text color…", e -> pickColor(false)));
        tb.addSeparator();
        tb.add(button("Sort A→Z", e -> { if (cur() != null) cur().sort(true); }));

        nameBox.setPreferredSize(new Dimension(64, 24));
        nameBox.setFont(nameBox.getFont().deriveFont(java.awt.Font.BOLD));
        nameBox.setForeground(SheetPanel.ACCENT);
        fx.addActionListener(e -> commitFromBar());
        fx.addKeyListener(new java.awt.event.KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) { updateInfo(); if (cur() != null) cur().table.requestFocusInWindow(); }
            }
        });
        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        bar.add(nameBox, BorderLayout.WEST);
        JLabel fxl = new JLabel("fx");
        fxl.setForeground(Color.GRAY);
        JPanel mid = new JPanel(new BorderLayout(6, 0));
        mid.add(fxl, BorderLayout.WEST);
        mid.add(fx, BorderLayout.CENTER);
        bar.add(mid, BorderLayout.CENTER);

        JPanel top = new JPanel(new BorderLayout());
        top.add(tb, BorderLayout.NORTH);
        top.add(bar, BorderLayout.SOUTH);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        bottom.add(status, BorderLayout.WEST);
        bottom.add(stats, BorderLayout.EAST);

        setLayout(new BorderLayout());
        add(top, BorderLayout.NORTH);
        add(tabs, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);
        setJMenuBar(buildMenu());

        tabs.addChangeListener(e -> updateInfo());
        tabs.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2 && tabs.indexAtLocation(e.getX(), e.getY()) >= 0) renameSheet(); }
        });

        FRAMES.add(this);
        setSize(1100, 700);
        setLocationByPlatform(true);
        setWorkbook(wb);
        setVisible(true);
        if (cur() != null) cur().table.requestFocusInWindow();
    }

    private JButton button(String label, java.awt.event.ActionListener l) {
        JButton b = new JButton(label);
        b.setFocusable(false);
        b.addActionListener(l);
        return b;
    }

    private void setWorkbook(Workbook w) { setWorkbook(w, null); }

    private void setWorkbook(Workbook w, FileWatcher preset) {
        this.wb = w;
        tabs.removeAll();
        for (Sheet s : w.sheets) tabs.addTab(s.name, new SheetPanel(this, w, s));
        tabs.setSelectedIndex(0);
        updateTitle();
        updateInfo();
        if (w.foreignXlsx) status.setText("Opened " + w.path.getFileName());
        startWatching(preset);
    }

    private SheetPanel cur() { return (SheetPanel) tabs.getSelectedComponent(); }

    // ------------------------------------------------------------------ menus

    private JMenuItem item(String text, int key, int mods, Runnable r) {
        JMenuItem m = new JMenuItem(new AbstractAction(text) { @Override public void actionPerformed(ActionEvent e) { r.run(); } });
        if (key != 0) m.setAccelerator(KeyStroke.getKeyStroke(key, mods));
        return m;
    }

    private JMenuBar buildMenu() {
        JMenuBar mb = new JMenuBar();
        JMenu file = new JMenu("File");
        file.add(item("New", KeyEvent.VK_N, mask, () -> openNew()));
        file.add(item("Open…", KeyEvent.VK_O, mask, this::openDialog));
        file.add(recentMenu());
        file.addSeparator();
        file.add(item("Save", KeyEvent.VK_S, mask, () -> save()));
        file.add(item("Save As…", KeyEvent.VK_S, mask | KeyEvent.SHIFT_DOWN_MASK, () -> saveAs()));
        file.add(item("Export Sheet as CSV…", 0, 0, this::exportCsv));
        file.addSeparator();
        file.add(item("Close Window", KeyEvent.VK_W, mask, this::closeWindow));
        file.add(item("Exit", 0, 0, AppFrame::exitAll));
        mb.add(file);

        JMenu edit = new JMenu("Edit");
        edit.add(item("Undo", KeyEvent.VK_Z, mask, this::undo));
        edit.add(item("Redo", KeyEvent.VK_Y, mask, this::redo));
        edit.addSeparator();
        edit.add(item("Cut", 0, 0, () -> { if (cur() != null) cur().copy(true); }));
        edit.add(item("Copy", 0, 0, () -> { if (cur() != null) cur().copy(false); }));
        edit.add(item("Paste", 0, 0, () -> { if (cur() != null) cur().paste(); }));
        edit.add(item("Delete Contents", 0, 0, () -> { if (cur() != null) cur().clearSelection(); }));
        edit.addSeparator();
        edit.add(item("Fill Down", KeyEvent.VK_D, mask, () -> { if (cur() != null) cur().fill(true); }));
        edit.add(item("Fill Right", KeyEvent.VK_R, mask, () -> { if (cur() != null) cur().fill(false); }));
        mb.add(edit);

        JMenu fmt = new JMenu("Format");
        fmt.add(item("Bold", KeyEvent.VK_B, mask, this::toggleBold));
        fmt.add(item("Italic", KeyEvent.VK_I, mask, this::toggleItalic));
        fmt.addSeparator();
        fmt.add(item("Align Left", 0, 0, () -> align(Style.LEFT)));
        fmt.add(item("Align Center", 0, 0, () -> align(Style.CENTER)));
        fmt.add(item("Align Right", 0, 0, () -> align(Style.RIGHT)));
        fmt.add(item("Align Automatically", 0, 0, () -> align(Style.GENERAL)));
        fmt.addSeparator();
        JMenu nf = new JMenu("Number Format");
        for (int i = 0; i < FMT_LABELS.length; i++) { final String code = FMT_CODES[i]; nf.add(item(FMT_LABELS[i], 0, 0, () -> setNumFmt(code))); }
        fmt.add(nf);
        fmt.add(item("Custom Number Format…", 0, 0, this::customFormat));
        fmt.addSeparator();
        fmt.add(item("Fill Color…", 0, 0, () -> pickColor(true)));
        fmt.add(item("Text Color…", 0, 0, () -> pickColor(false)));
        fmt.add(item("Clear Colors", 0, 0, () -> { if (cur() != null) cur().applyStyle(s -> s.withFill(-1).withFg(-1)); }));
        mb.add(fmt);

        JMenu data = new JMenu("Data");
        data.add(item("Sort A to Z (by first selected column)", 0, 0, () -> { if (cur() != null) cur().sort(true); }));
        data.add(item("Sort Z to A (by first selected column)", 0, 0, () -> { if (cur() != null) cur().sort(false); }));
        mb.add(data);

        JMenu sheet = new JMenu("Sheet");
        sheet.add(item("Add Sheet", 0, 0, this::addSheet));
        sheet.add(item("Rename Sheet…", 0, 0, this::renameSheet));
        sheet.add(item("Delete Sheet", 0, 0, this::deleteSheet));
        mb.add(sheet);

        JMenu help = new JMenu("Help");
        help.add(item("Functions", 0, 0, () -> JOptionPane.showMessageDialog(this,
                "Operators: + - * / ^ & % = <> < > <= >=\n\nFunctions:\n" + wrap(String.join(", ", Formula.functionNames()), 70)
                        + "\n\nReferences: A1, $A$1, A1:B5, A:A, Sheet2!A1, 'My Sheet'!A1:B2\nStart text with ' to force text (for example '00123).",
                "Formulas", JOptionPane.INFORMATION_MESSAGE)));
        help.add(item("About SheetWorks", 0, 0, () -> JOptionPane.showMessageDialog(this,
                "SheetWorks\nCopyright (C) 2026 Adam Kost\n\nFree software under the GNU General Public License version 3.\n"
                        + "This program comes with ABSOLUTELY NO WARRANTY.\nSee https://www.gnu.org/licenses/gpl-3.0.html",
                "About SheetWorks", JOptionPane.INFORMATION_MESSAGE)));
        mb.add(help);
        return mb;
    }

    /** "Open Recent" submenu; its items are rebuilt from the shared list each time the menu is opened. */
    private JMenu recentMenu() {
        final JMenu m = new JMenu("Open Recent");
        m.addMenuListener(new MenuListener() {
            @Override public void menuSelected(MenuEvent e) { fillRecent(m); }
            @Override public void menuDeselected(MenuEvent e) { }
            @Override public void menuCanceled(MenuEvent e) { }
        });
        fillRecent(m);
        return m;
    }

    private void fillRecent(JMenu m) {
        m.removeAll();
        List<Path> files = RecentFiles.shared().list();
        if (files.isEmpty()) {
            JMenuItem none = new JMenuItem("(No recent files)");
            none.setEnabled(false);
            m.add(none);
            return;
        }
        for (int i = 0; i < files.size(); i++) {
            final Path p = files.get(i);
            JMenuItem it = item(recentLabel(i, p), 0, 0, () -> openPath(p));
            it.setToolTipText(p.toString());
            if (i < 9) it.setMnemonic('1' + i);
            m.add(it);
        }
        m.addSeparator();
        m.add(item("Clear Recent Files", 0, 0, () -> RecentFiles.shared().clear()));
    }

    private static String recentLabel(int i, Path p) {
        Path name = p.getFileName();
        String dir = p.getParent() == null ? "" : p.getParent().toString();
        if (dir.length() > 50) dir = "\u2026" + dir.substring(dir.length() - 49);
        return (i + 1) + "  " + (name == null ? p.toString() : name.toString()) + (dir.isEmpty() ? "" : "   -   " + dir);
    }

    private static String wrap(String s, int width) {
        StringBuilder out = new StringBuilder();
        int line = 0;
        for (String w : s.split(" ")) {
            if (line + w.length() > width) { out.append('\n'); line = 0; }
            out.append(w).append(' ');
            line += w.length() + 1;
        }
        return out.toString().trim();
    }

    // ------------------------------------------------------------------ editing and display

    @Override public void commitEdit(Sheet s, int r, int c, String text) {
        String t = text.startsWith("=") ? Formula.normalize(text) : text;
        Cell o = s.get(r, c);
        if ((o == null ? "" : o.raw).equals(t)) return;
        wb.begin();
        wb.setRaw(s, r, c, t);
        wb.commit();
        refresh();
    }

    private void commitFromBar() {
        SheetPanel p = cur();
        if (p == null) return;
        int[] a = p.active();
        if (p.table.isEditing()) p.table.getCellEditor().cancelCellEditing();
        commitEdit(p.sheet, a[0], a[1], fx.getText());
        p.table.changeSelection(Math.min(a[0] + 1, p.rows - 1), a[1], false, false);
        p.table.requestFocusInWindow();
    }

    @Override public void setFormulaText(String t) { fx.setText(t); }

    @Override public void selectionChanged(SheetPanel p) { if (p == cur()) updateInfo(); }

    @Override public void refresh() {
        for (int i = 0; i < tabs.getTabCount(); i++) {
            SheetPanel p = (SheetPanel) tabs.getComponentAt(i);
            p.table.repaint();
        }
        updateTitle();
        updateInfo();
    }

    @Override public void updateTitle() {
        String n = wb.path == null ? "Untitled" : wb.path.getFileName().toString();
        setTitle((wb.dirty ? "* " : "") + n + " - SheetWorks");
    }

    private void updateInfo() {
        SheetPanel p = cur();
        if (p == null) return;
        int[] a = p.active();
        nameBox.setText(Sheet.ref(a[0], a[1]));
        Cell c = p.sheet.get(a[0], a[1]);
        if (!fx.hasFocus() && !p.table.isEditing()) fx.setText(c == null ? "" : c.raw);
        String code = c == null ? null : c.style.numFmt;
        int idx = 0;
        for (int i = 1; i < FMT_CODES.length; i++) if (FMT_CODES[i].equals(code)) idx = i;
        updating = true;
        fmtBox.setSelectedIndex(idx);
        updating = false;
        stats.setText(p.stats());
    }

    private void undo() { SheetPanel p = cur(); if (p != null) p.stopEditing(); if (wb.undo()) refresh(); }
    private void redo() { SheetPanel p = cur(); if (p != null) p.stopEditing(); if (wb.redo()) refresh(); }

    private void toggleBold() { SheetPanel p = cur(); if (p != null) { boolean v = !p.activeStyle().bold; p.applyStyle(s -> s.withBold(v)); } }
    private void toggleItalic() { SheetPanel p = cur(); if (p != null) { boolean v = !p.activeStyle().italic; p.applyStyle(s -> s.withItalic(v)); } }
    private void align(int a) { if (cur() != null) cur().applyStyle(s -> s.withAlign(a)); }
    private void setNumFmt(String code) { if (cur() != null) cur().applyStyle(s -> s.withNumFmt(code)); }

    private void customFormat() {
        String cur = JOptionPane.showInputDialog(this, "Excel number format code (for example 0.000, #,##0, 0.0%, dd/mm/yyyy):", "0.00");
        if (cur != null && !cur.trim().isEmpty()) setNumFmt(cur.trim());
    }

    private void pickColor(boolean fill) {
        if (cur() == null) return;
        Color c = JColorChooser.showDialog(this, fill ? "Fill color" : "Text color", fill ? new Color(0xFFF2B3) : new Color(0xB3402F));
        if (c == null) return;
        final int rgb = c.getRGB() & 0xFFFFFF;
        UnaryOperator<Style> fn = fill ? s -> s.withFill(rgb) : s -> s.withFg(rgb);
        cur().applyStyle(fn);
    }

    // ------------------------------------------------------------------ sheets

    private void addSheet() {
        int n = wb.sheets.size() + 1;
        String name;
        do { name = "Sheet" + n++; } while (wb.find(name) != null);
        Sheet s = new Sheet(name);
        wb.sheets.add(s);
        tabs.addTab(name, new SheetPanel(this, wb, s));
        tabs.setSelectedIndex(tabs.getTabCount() - 1);
        wb.dirty = true;
        updateTitle();
    }

    private void renameSheet() {
        SheetPanel p = cur();
        if (p == null) return;
        String n = (String) JOptionPane.showInputDialog(this, "Sheet name (formulas that refer to the old name are not updated):", "Rename Sheet",
                JOptionPane.PLAIN_MESSAGE, null, null, p.sheet.name);
        if (n == null) return;
        n = n.trim();
        Sheet clash = wb.find(n);
        if (n.isEmpty() || n.length() > 31 || n.matches(".*[\\[\\]:*?/\\\\].*") || (clash != null && clash != p.sheet)) {
            JOptionPane.showMessageDialog(this, "Sheet names must be 1-31 characters, unique, and cannot contain [ ] : * ? / \\", "SheetWorks", JOptionPane.WARNING_MESSAGE);
            return;
        }
        p.sheet.name = n;
        tabs.setTitleAt(tabs.getSelectedIndex(), n);
        wb.invalidate();
        wb.dirty = true;
        refresh();
    }

    private void deleteSheet() {
        SheetPanel p = cur();
        if (p == null) return;
        if (wb.sheets.size() == 1) {
            JOptionPane.showMessageDialog(this, "A workbook needs at least one sheet.", "SheetWorks", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (JOptionPane.showConfirmDialog(this, "Delete sheet \"" + p.sheet.name + "\"? This cannot be undone.", "Delete Sheet",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
        wb.sheets.remove(p.sheet);
        tabs.remove(p);
        wb.invalidate();
        wb.dirty = true;
        wb.clearHistory();
        refresh();
    }

    // ------------------------------------------------------------------ files

    private JFileChooser chooser() {
        JFileChooser fc = new JFileChooser(lastDir);
        fc.setAcceptAllFileFilterUsed(true);
        return fc;
    }

    private void openDialog() {
        JFileChooser fc = chooser();
        fc.setFileFilter(new FileNameExtensionFilter("Spreadsheets (*.xlsx, *.csv)", "xlsx", "csv"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        lastDir = fc.getCurrentDirectory();
        openPath(fc.getSelectedFile().toPath());
    }

    boolean save() {
        if (wb.path == null) return saveAs();
        return saveTo(wb.path);
    }

    boolean saveAs() {
        JFileChooser fc = chooser();
        fc.setFileFilter(new FileNameExtensionFilter("Excel workbook (*.xlsx)", "xlsx"));
        if (wb.path != null) fc.setSelectedFile(wb.path.toFile());
        else fc.setSelectedFile(new File(lastDir, "Book1.xlsx"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return false;
        lastDir = fc.getCurrentDirectory();
        File f = fc.getSelectedFile();
        if (!f.getName().toLowerCase(Locale.ROOT).endsWith(".xlsx")) f = new File(f.getPath() + ".xlsx");
        if (f.exists() && JOptionPane.showConfirmDialog(this, f.getName() + " already exists. Replace it?", "Save As",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return false;
        return saveTo(f.toPath());
    }

    private boolean saveTo(Path p) {
        SheetPanel sp = cur();
        if (sp != null) sp.stopEditing();
        if (wb.foreignXlsx && wb.path != null && sameFile(wb.path, p)) {
            Object[] opts = {"Overwrite", "Save As…", "Cancel"};
            int ch = JOptionPane.showOptionDialog(this,
                    "SheetWorks saves values, formulas, number formats, bold/italic, colors, alignment and column widths.\n"
                            + "Other features in this file (charts, images, borders, merged cells, conditional formatting, etc.)\n"
                            + "will be lost if you overwrite it.",
                    "Overwrite the original file?", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, opts, opts[1]);
            if (ch == 1) return saveAs();
            if (ch != 0) return false;
        }
        try {
            XlsxIO.write(wb, p);
        } catch (IOException | RuntimeException e) {
            JOptionPane.showMessageDialog(this, "Could not save " + p + "\n\n" + e.getMessage(), "SheetWorks", JOptionPane.ERROR_MESSAGE);
            return false;
        }
        wb.path = p;
        RecentFiles.shared().add(p);
        wb.foreignXlsx = false;
        wb.dirty = false;
        startWatching(null); // our own write must not look like an outside change
        status.setText("Saved " + p.getFileName());
        updateTitle();
        return true;
    }

    private void exportCsv() {
        SheetPanel sp = cur();
        if (sp == null) return;
        JFileChooser fc = chooser();
        fc.setFileFilter(new FileNameExtensionFilter("CSV (*.csv)", "csv"));
        fc.setSelectedFile(new File(lastDir, sp.sheet.name + ".csv"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        lastDir = fc.getCurrentDirectory();
        File f = fc.getSelectedFile();
        if (!f.getName().toLowerCase(Locale.ROOT).endsWith(".csv")) f = new File(f.getPath() + ".csv");
        try {
            Csv.write(wb, sp.sheet, f.toPath());
            status.setText("Exported " + f.getName());
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Could not export: " + e.getMessage(), "SheetWorks", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ------------------------------------------------------------------ outside changes

    /** Starts (or restarts) watching the file this window has open. Windows without a file on disk are not watched. */
    private void startWatching(FileWatcher preset) {
        if (watchTimer != null) { watchTimer.stop(); watchTimer = null; }
        watcher = null;
        changePending = false;
        if (wb.path == null) return;
        watcher = preset != null ? preset : new FileWatcher(wb.path);
        watchTimer = new Timer(1000, e -> checkFileChanged());
        watchTimer.start();
    }

    private void checkFileChanged() {
        if (watcher == null || prompting) return;
        if (!changePending && watcher.poll()) changePending = true;
        // Only interrupt the user while this window is in front; otherwise ask when they come back to it.
        if (changePending && isActive()) promptReload();
    }

    private void promptReload() {
        prompting = true;
        try {
            boolean dirty = wb.dirty;
            String msg = "\"" + wb.path.getFileName() + "\" was changed by another program.\n\n"
                    + (dirty ? "You also have unsaved changes in SheetWorks. Reloading discards them;\n"
                            + "if you keep your version, saving will overwrite the other changes.\n\n" : "")
                    + "Reload it from disk?";
            Object[] opts = {"Reload", "Keep My Version"};
            int ch = JOptionPane.showOptionDialog(this, msg, "File Changed", JOptionPane.DEFAULT_OPTION,
                    JOptionPane.WARNING_MESSAGE, null, opts, dirty ? opts[1] : opts[0]);
            changePending = false;
            if (ch == 0) reloadFromDisk();
            else if (watcher != null) watcher.rebase(); // do not ask again until the file changes again
        } finally {
            prompting = false;
        }
    }

    /** Closes the current contents and reopens the file from disk, discarding any unsaved edits and the undo history. */
    private void reloadFromDisk() {
        Path p = wb.path;
        int tab = tabs.getSelectedIndex();
        int[] cell = cur() == null ? new int[] {0, 0} : cur().active();
        FileWatcher fresh = new FileWatcher(p); // taken before reading, so a change during the read is noticed
        try {
            setWorkbook(XlsxIO.read(p), fresh);
        } catch (IOException | RuntimeException e) {
            if (watcher != null) watcher.rebase();
            JOptionPane.showMessageDialog(this, "Could not reload " + p.getFileName() + "\n\n" + e.getMessage()
                    + "\n\nYour current version is unchanged.", "SheetWorks", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (tab >= 0 && tab < tabs.getTabCount()) tabs.setSelectedIndex(tab);
        SheetPanel sp = cur();
        if (sp != null) {
            sp.table.changeSelection(Math.min(cell[0], sp.rows - 1), Math.min(cell[1], sp.cols - 1), false, false);
            sp.table.requestFocusInWindow();
        }
        status.setText("Reloaded " + p.getFileName());
    }

    @Override public void dispose() {
        if (watchTimer != null) { watchTimer.stop(); watchTimer = null; }
        super.dispose();
    }

    // ------------------------------------------------------------------ closing

    private boolean confirmDiscard() {
        if (!wb.dirty) return true;
        SheetPanel sp = cur();
        if (sp != null) sp.stopEditing();
        String n = wb.path == null ? "Untitled" : wb.path.getFileName().toString();
        int ch = JOptionPane.showConfirmDialog(this, "Save changes to " + n + "?", "SheetWorks", JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ch == JOptionPane.YES_OPTION) return save();
        return ch == JOptionPane.NO_OPTION;
    }

    /**
     * Closes this window's file. The last window is not closed: it switches to a new empty workbook, so closing a
     * file leaves the application running. Use File > Exit to quit.
     */
    private void closeWindow() {
        if (!confirmDiscard()) return;
        if (FRAMES.size() == 1) {
            setWorkbook(Workbook.blank());
            status.setText("Ready");
            if (cur() != null) cur().table.requestFocusInWindow();
            return;
        }
        FRAMES.remove(this);
        dispose();
    }

    /** Asks about unsaved changes in every window, then closes them all. Returns false if the user cancelled. */
    static boolean closeAll() {
        List<AppFrame> all = new ArrayList<>(FRAMES);
        for (AppFrame f : all) {
            f.toFront();
            if (!f.confirmDiscard()) return false;
        }
        for (AppFrame f : all) {
            FRAMES.remove(f);
            f.dispose();
        }
        return true;
    }

    static void exitAll() {
        if (closeAll()) System.exit(0);
    }
}
