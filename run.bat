@echo off
rem Starts SheetWorks without leaving a console window open. Usage: run.bat [file.xlsx]
rem Build first with:  mvn package
if not exist "%~dp0target\sheetworks.jar" (echo target\sheetworks.jar not found. Run "mvn package" first. & exit /b 1)
start "" javaw -jar "%~dp0target\sheetworks.jar" %*
