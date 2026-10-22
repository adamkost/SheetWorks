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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Detection of changes made to a file by another program. */
class FileWatcherTest {
    private Path file;
    private FileWatcher w;

    @BeforeEach
    void setUp() throws IOException {
        file = Files.createTempFile("sheetworks-watch", ".xlsx");
        write("one");
        w = new FileWatcher(file);
    }

    @AfterEach
    void tearDown() throws IOException { Files.deleteIfExists(file); }

    private void write(String text) throws IOException { Files.write(file, text.getBytes(StandardCharsets.UTF_8)); }

    @Test
    void quietWhileUnchanged() {
        assertFalse(w.poll(), "first check");
        assertFalse(w.poll(), "second check");
    }

    @Test
    void reportsAChangeOnceItHasSettled() throws IOException {
        write("changed content");
        assertFalse(w.poll(), "first sight of the change waits in case the file is still being written");
        assertTrue(w.poll(), "the same state on the next check is reported");
        assertTrue(w.poll(), "and keeps being reported until it is handled");
    }

    @Test
    void keepsWaitingWhileTheFileIsStillBeingWritten() throws IOException {
        write("part");
        assertFalse(w.poll(), "first change");
        write("part and some more");
        assertFalse(w.poll(), "it changed again, so the wait starts over");
        assertTrue(w.poll(), "now it has settled");
    }

    @Test
    void rebaseAcceptsTheCurrentState() throws IOException {
        write("changed content");
        w.poll();
        assertTrue(w.poll());
        w.rebase();
        assertFalse(w.poll(), "after rebase the file counts as known");
        write("changed once more!");
        w.poll();
        assertTrue(w.poll(), "a later change is detected again");
    }

    @Test
    void detectsATimestampOnlyChange() throws IOException {
        FileTime old = Files.getLastModifiedTime(file);
        Files.setLastModifiedTime(file, FileTime.fromMillis(old.toMillis() + 5000));
        assertFalse(w.poll());
        assertTrue(w.poll(), "same size but a newer modification time");
    }

    @Test
    void ignoresAMissingFileUntilItReturnsDifferent() throws IOException {
        Files.delete(file);
        assertFalse(w.poll(), "a missing file is not reported");
        assertFalse(w.poll());
        write("brand new content here");
        assertFalse(w.poll());
        assertTrue(w.poll(), "the replacement is reported");
    }
}
