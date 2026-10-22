# SheetWorks

A small desktop spreadsheet written in Java (Swing). It opens and saves Excel `.xlsx` files, has no runtime
dependencies, and is built with Apache Maven. It runs anywhere Java runs: Windows, Linux and macOS.

Copyright (C) 2026 Adam Kost. Licensed under the GNU General Public License version 3 (see `LICENSE`).

## Contents
- [Requirements](#requirements)
- [Install a JDK and Maven](#install-a-jdk-and-maven)
- [Build, test and run](#build-test-and-run)
- [Opening .xlsx files by double-click](#opening-xlsx-files-by-double-click)
- [Features](#features)
- [Recent files](#recent-files)
- [Changes made to the open file by other programs](#changes-made-to-the-open-file-by-other-programs)
- [What is preserved in .xlsx files](#what-is-preserved-in-xlsx-files)
- [Working on the code](#working-on-the-code)
- [Troubleshooting](#troubleshooting)
- [Project layout](#project-layout)

## Requirements
- A JDK, version 11 or newer (to build; `mvn -version` must report Java 11+).
- Apache Maven 3.6.3 or newer.
- Internet access for the first build, so Maven can download its plugins and the test library. After that
  `mvn -o package` builds offline.
- To run the finished program: a Java 11+ runtime with graphics support and a graphical desktop. Some Linux packages ship
  a "headless" runtime (for example `openjdk-21-jre-headless`) that cannot open windows; install the full JRE or JDK.

## Install a JDK and Maven
| System | Commands |
| --- | --- |
| Debian / Ubuntu | `sudo apt install openjdk-21-jdk maven` |
| Fedora / RHEL | `sudo dnf install java-21-openjdk-devel maven` |
| Arch | `sudo pacman -S jdk-openjdk maven` |
| openSUSE | `sudo zypper install java-21-openjdk-devel maven` |
| macOS (Homebrew) | `brew install openjdk@21 maven`, then follow the `ln -sfn ...` line Homebrew prints so `java` is found |
| Windows | Install a JDK from https://adoptium.net, then Maven: `choco install maven` or `scoop install maven`, or unzip the binary archive from https://maven.apache.org/download.cgi and add its `bin` folder to the PATH |

Check with `java -version` and `mvn -version`. Maven uses the JDK named by `JAVA_HOME` when it is set, so make sure it
points at JDK 11 or newer.

## Build, test and run
The same commands work on Windows (command prompt), Linux and macOS, from the project folder:
```
mvn package
java -jar target/sheetworks.jar
java -jar target/sheetworks.jar /path/to/book.xlsx
```
On Windows use backslashes in paths, for example `java -jar target\sheetworks.jar C:\path\to\book.xlsx`.

| Goal | Command |
| --- | --- |
| Compile, run the tests, build `target/sheetworks.jar` | `mvn package` |
| Build without running the tests | `mvn package -DskipTests` |
| Run only the tests | `mvn test` |
| Delete all build output | `mvn clean` |
| Run straight from Maven, without building the jar | `mvn compile exec:java` (open a file with `-Dexec.args="/path/to/book.xlsx"`) |
| Build offline once dependencies are downloaded | `mvn -o package` |

The jar is self-contained: copy `target/sheetworks.jar` anywhere and run it with `java -jar`.

Windows only: `run.bat [file.xlsx]` starts the built jar without keeping a console window open.
On Linux and macOS, `java -jar target/sheetworks.jar file.xlsx &` leaves your terminal free. Several files can be given
at once; each opens in its own window.

## Opening .xlsx files by double-click

### Windows
1. Run `mvn package`.
2. Run `register-xlsx.bat`. It adds SheetWorks to the "Open with" list for `.xlsx` files (current user only, no admin rights).
3. Right-click an `.xlsx` file, choose Open with > Choose another app > SheetWorks, and tick "Always use this app".

Step 3 is required: Windows 10 and 11 do not allow a program to make itself the default. Excel stays installed; choose it
again in Settings > Apps > Default apps to switch back. `unregister-xlsx.bat` removes the registration. The registration
stores the full path of `target\sheetworks.jar`, so run `register-xlsx.bat` again if you move the folder or rebuild it
somewhere else (`mvn clean` deletes the jar until you build again).

### Linux (freedesktop desktops: GNOME, KDE, Xfce and others)
Create a launcher entry. Run this from the project folder after building; the entry stores the path, so keep the project
(or copy the jar to a permanent place and edit `Exec=`):
```
mkdir -p ~/.local/share/applications
cat > ~/.local/share/applications/sheetworks.desktop <<DESKTOP
[Desktop Entry]
Type=Application
Name=SheetWorks
Comment=Small spreadsheet
Exec=java -jar "$PWD/target/sheetworks.jar" %f
Terminal=false
Categories=Office;Spreadsheet;
MimeType=application/vnd.openxmlformats-officedocument.spreadsheetml.sheet;text/csv;
DESKTOP
update-desktop-database ~/.local/share/applications
```
Then either:
- right-click an `.xlsx` file in your file manager, choose Open With > SheetWorks, and (in most file managers) tick the
  "always" or "set as default" option; or
- set it from the terminal:
  `xdg-mime default sheetworks.desktop application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`

This replaces your current default for `.xlsx` (for example LibreOffice). To undo it, delete the `.desktop` file and
choose another default in your file manager. If `java` is not on the PATH used by your desktop session, put its full path
in the `Exec=` line.

### macOS
A `.jar` cannot be chosen directly as the program for a file type, so wrap it as an application with `jpackage`
(included in JDK 14 and newer). From the project folder, after `mvn package`:
```
mkdir -p dist && cp target/sheetworks.jar dist/
printf 'extension=xlsx\nmime-type=application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\ndescription=Excel workbook\n' > xlsx-assoc.properties
jpackage --type app-image --name SheetWorks --input dist --main-jar sheetworks.jar \
         --main-class me.kost.adam.dev.sheetworks.Main --file-associations xlsx-assoc.properties
```
This creates `SheetWorks.app` in the current folder. Move it to `/Applications`, then in Finder select an `.xlsx` file, press
Cmd+I (Get Info), choose SheetWorks under "Open with", and click "Change All...". SheetWorks handles files opened from
Finder through the standard macOS open-file event. Without jpackage you can still open files with
`java -jar target/sheetworks.jar /path/to/book.xlsx` or File > Open inside the application.

> The Linux and macOS double-click steps follow standard desktop conventions but have not been tried on real Linux or macOS
> desktops. If one does not work on your system, launching from the command line always does.

## Features
- Workbooks with multiple sheets (tabs along the bottom; double-click a tab to rename it), 1,000+ rows and 52+ columns
  (grows to fit imported data, up to Excel's limits).
- Formulas with cell and range references (`A1`, `$A$1`, `A1:B5`, `A:A`, `Sheet2!A1`, `'My Sheet'!A1:B2`),
  operators `+ - * / ^ & % = <> < > <= >=`, and these functions: SUM, PRODUCT, AVERAGE, MEDIAN, MIN, MAX, COUNT, COUNTA,
  IF, IFERROR, ROUND, ROUNDUP, ROUNDDOWN, ABS, INT, SQRT, POWER, MOD, PI, TODAY, NOW, AND, OR, NOT, CONCAT, CONCATENATE,
  UPPER, LOWER, LEN, TRIM, LEFT, RIGHT, MID, SUMIF, COUNTIF, VLOOKUP. Circular references show `#CIRC!`.
- Formatting: bold, italic, alignment, fill and text colors, number formats (number, currency, percent, date, or any
  Excel format code), column widths.
- Copy, cut and paste (formulas adjust like Excel), fill down and right, sort, undo and redo.
- Open and save `.xlsx`; open CSV and export the current sheet to CSV.
- File > Open Recent lists the ten most recently used files.
- Notices when another program changes the open file and offers to reload it.
- Closing a file (File > Close Window, Ctrl/Cmd+W, or the window's close button) leaves SheetWorks running: when the last
  window is closed it switches to a new empty workbook. Use File > Exit (or Cmd+Q on macOS) to quit.

Keyboard: type to replace a cell, F2 or double-click to edit it, Enter or Tab to move, Delete to clear.
Shortcuts use Ctrl on Windows and Linux and Cmd on macOS: N, O, S, Shift+S, W, Z, Y, D (fill down), R (fill right), B, I.
Start text with `'` to keep it as text (for example `'00123`).

## Recent files
File > Open Recent lists the ten files you used most recently, newest first, and opens one when you pick it. Items 1 to 9
can also be chosen with their number key while the menu is open. Hover over an item to see the full path.

- A file is added (or moved to the top) when you open it, including from the command line or by double-click, and when you
  save it with Save or Save As. CSV files you import are listed too.
- If you pick a file that no longer exists, SheetWorks says so and removes it from the list.
- File > Open Recent > Clear Recent Files empties the list.
- The list is shared by all SheetWorks windows and by separate runs of the program. It is stored as a plain text file, one
  path per line, in your user configuration folder; delete the file to reset it:

  | System | Location |
  | --- | --- |
  | Windows | `%APPDATA%\SheetWorks\recent-files.txt` |
  | macOS | `~/Library/Application Support/SheetWorks/recent-files.txt` |
  | Linux and others | `$XDG_CONFIG_HOME/sheetworks/recent-files.txt`, or `~/.config/sheetworks/recent-files.txt` |

## Changes made to the open file by other programs
SheetWorks watches the `.xlsx` file each window has open (once a second, by modification time and size). When the file
changes on disk, for example because someone saved it from Excel or a sync tool replaced it, SheetWorks asks whether to
reload it:

- **Reload** closes the current contents and reopens the file from disk. Any unsaved edits in the window, and its undo
  history, are discarded. The window returns to the same sheet and cell where possible.
- **Keep My Version** leaves the window as it is and does not ask again until the file changes once more. If you had
  unsaved edits and then save, you overwrite the other program's changes.

Details:
- The question appears only while the SheetWorks window is in front. If the change happens while you are in another
  program, SheetWorks asks when you switch back to it.
- A file that is still being written is not read half-finished: the change is reported once the file has stopped changing.
- If the file is deleted nothing happens, and saving recreates it. If the reload fails (for example the other program left
  a broken file), you get an error message and your current version is kept.
- Saving from SheetWorks itself does not trigger the question. CSV files are imported rather than edited in place, so they
  are not watched.
- Timestamps on some network drives are coarse or delayed, so detection there can lag.

## What is preserved in .xlsx files
Saved: values, formulas (with their calculated results), sheet names and order, number formats, bold/italic, font and fill
colors, horizontal alignment, column widths.

Not preserved, and lost if you overwrite an existing file: charts, images, borders, merged cells, conditional formatting,
data validation, comments, defined names, tables, pivot tables, macros, row heights, frozen panes. SheetWorks asks before
overwriting a file it did not create, and offers Save As instead. For formulas that use functions SheetWorks lacks, it
shows the result stored in the file until you edit the cell.

Other limits: sorting moves formulas as written without rewriting their references, and renaming a sheet does not update
formulas that use the old name.

## Working on the code
- **IDEs:** IntelliJ IDEA, Eclipse, NetBeans and VS Code (with the Java extension pack) all open the folder as a Maven
  project; use File > Open / Import and pick `pom.xml`.
- **Tests:** JUnit 5, in `src/test/java`. They never open a window (the build passes `-Djava.awt.headless=true`), so they
  also run on servers and CI. They cover the formula engine, number and date formats, the `.xlsx` and CSV round trips,
  the grid component, the change detection for the open file, and the recent files list.
- **Coordinates:** `groupId` is `me.kost.adam.dev` and the version is `1.0.0` in `pom.xml`; change them there if you publish
  the project. All code is in the Java package `me.kost.adam.dev.sheetworks`, and the program starts at
  `me.kost.adam.dev.sheetworks.Main`. The jar name stays `target/sheetworks.jar` whatever the version (`<finalName>` in the pom).
- **Maven Wrapper (optional):** `mvn wrapper:wrapper` adds `mvnw` / `mvnw.cmd`, so others can build with `./mvnw package`
  without installing Maven.
- **Runtime dependencies:** none. `.xlsx` files are read and written with the standard Java library only, so keep it that
  way unless there is a good reason, and add any dependency to `pom.xml`.

## Troubleshooting
| Symptom | Cause and fix |
| --- | --- |
| `mvn: command not found` / `'mvn' is not recognized` | Maven is not installed or not on the PATH (see above); reopen the terminal afterwards. |
| `release version 11 not supported` or `invalid target release: 11` | Maven is running on a JDK older than 11. Check `mvn -version`; set `JAVA_HOME` to a JDK 11+ and try again. |
| `No compiler is provided in this environment` | Maven found a JRE, not a JDK. Install a JDK and point `JAVA_HOME` at it. |
| Plugin or dependency download fails | The first build needs internet access. Behind a proxy, configure it in `~/.m2/settings.xml`. |
| `HeadlessException` or "No X11 DISPLAY variable" when starting | A headless Java runtime, or no graphical session (for example plain SSH). Install the full JRE/JDK and run from the desktop. |
| `UnsupportedClassVersionError` | The runtime is older than the JDK that built the jar. Run with the same or a newer Java, or rebuild with an older JDK (11+). |
| A file will not open | It must be a real `.xlsx` (not the older `.xls`) or a `.csv`. An error dialog gives the reason. |
| Fonts look blurry or tiny on a high-resolution screen | Use a recent Java (17+), which scales automatically; on Linux you can try `java -Dsun.java2d.uiScale=2 -jar target/sheetworks.jar`. |

## Project layout
```
pom.xml                                       Maven project file (build, tests, jar manifest)
src/main/java/me/kost/adam/dev/sheetworks/    All application code (package me.kost.adam.dev.sheetworks):
    Main                                      entry point
    AppFrame, SheetPanel, SheetHost           window, grid, and the interface between them
    Workbook, Sheet, Cell, Style, XlErr       document model
    Formula, NumFmt                           formula parser and functions, number and date formats
    XlsxIO, Csv                               file formats
    FileWatcher                               detects outside changes to the open file
    RecentFiles                               the most recently used files list
src/test/java/me/kost/adam/dev/sheetworks/    FormulaTest, NumFmtTest, FileRoundTripTest, GridTest, FileWatcherTest, RecentFilesTest
run.bat, register-xlsx.bat, unregister-xlsx.bat   Windows helpers
target/                                       Build output (created by Maven, not committed)
```
