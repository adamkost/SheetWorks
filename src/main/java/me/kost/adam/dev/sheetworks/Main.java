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

import java.awt.Desktop;
import java.io.File;
import java.nio.file.Paths;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/** Entry point. Usage: java -jar sheetworks.jar [file.xlsx ...] */
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception e) { /* keep default look */ }
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_OPEN_FILE)) {
                    Desktop.getDesktop().setOpenFileHandler(ev -> {
                        for (File f : ev.getFiles()) SwingUtilities.invokeLater(() -> AppFrame.openPath(f.toPath()));
                    });
                }
            } catch (RuntimeException e) { /* not available on this platform */ }
            boolean any = false;
            for (String a : args) if (AppFrame.openPath(Paths.get(a))) any = true;
            if (!any) AppFrame.openNew();
        });
    }
}
