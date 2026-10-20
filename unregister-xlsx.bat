@echo off
rem Removes the SheetWorks registration created by register-xlsx.bat.
reg delete "HKCU\Software\Classes\SheetWorks.xlsx" /f >nul 2>nul
reg delete "HKCU\Software\Classes\.xlsx\OpenWithProgids" /v SheetWorks.xlsx /f >nul 2>nul
echo SheetWorks registration removed. If it was your default for .xlsx, pick another program in Settings ^> Apps ^> Default apps.
