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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Notices when a file on disk has been changed by someone else. It looks at the modification time and size, and
 * only reports a change once the file has stayed the same for two consecutive checks, so a file that another program
 * is still writing is not read half-finished. Call {@link #poll()} periodically; call {@link #rebase()} once the
 * change has been dealt with (or when this program has just written the file itself).
 */
final class FileWatcher {
    private static final class Stamp {
        final long time, size;
        Stamp(long time, long size) { this.time = time; this.size = size; }
        @Override public boolean equals(Object o) { return o instanceof Stamp && ((Stamp) o).time == time && ((Stamp) o).size == size; }
        @Override public int hashCode() { return Long.hashCode(time) * 31 + Long.hashCode(size); }
    }

    private final Path path;
    private Stamp baseline, pending;

    FileWatcher(Path path) {
        this.path = path;
        this.baseline = stamp();
    }

    /** Treats the file as it is right now as the known state. */
    void rebase() {
        baseline = stamp();
        pending = null;
    }

    /** True when the file differs from the known state and has stopped changing. Stays true until {@link #rebase()}. */
    boolean poll() {
        Stamp now = stamp();
        if (now == null) return false; // missing for the moment, for example while a program replaces it
        if (now.equals(baseline)) { pending = null; return false; }
        if (now.equals(pending)) return true;
        pending = now;
        return false;
    }

    private Stamp stamp() {
        try {
            return new Stamp(Files.getLastModifiedTime(path).toMillis(), Files.size(path));
        } catch (IOException e) {
            return null;
        }
    }
}
