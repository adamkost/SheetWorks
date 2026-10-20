@echo off
rem Makes SheetWorks available in Windows "Open with" for .xlsx files (current user only, no admin needed).
rem Run "mvn package" first. Nothing is overwritten: Excel or any other program stays installed.
setlocal
set "JAR=%~dp0target\sheetworks.jar"
if not exist "%JAR%" (echo target\sheetworks.jar not found. Run "mvn package" first. & exit /b 1)

set "JAVAW="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javaw.exe" set "JAVAW=%JAVA_HOME%\bin\javaw.exe"
if not defined JAVAW for %%I in (javaw.exe) do set "JAVAW=%%~$PATH:I"
if not defined JAVAW (echo javaw.exe not found. Install a Java runtime or set JAVA_HOME. & exit /b 1)

reg add "HKCU\Software\Classes\SheetWorks.xlsx" /ve /d "SheetWorks spreadsheet" /f >nul || exit /b 1
reg add "HKCU\Software\Classes\SheetWorks.xlsx\shell\open\command" /ve /d "\"%JAVAW%\" -jar \"%JAR%\" \"%%1\"" /f >nul || exit /b 1
reg add "HKCU\Software\Classes\.xlsx\OpenWithProgids" /v SheetWorks.xlsx /t REG_NONE /f >nul || exit /b 1

echo.
echo SheetWorks is now registered for .xlsx files using:
echo   %JAVAW% -jar "%JAR%"
echo.
echo One more step (Windows requires you to confirm it yourself):
echo   Right-click any .xlsx file ^> Open with ^> Choose another app ^> SheetWorks
echo   then tick "Always use this app" and press OK.
echo If SheetWorks is not listed, choose "More apps" or "Look for another app on this PC".
echo Run unregister-xlsx.bat to remove the registration.
