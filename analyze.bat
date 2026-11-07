@echo off
setlocal
cd /d "%~dp0"

where py.exe >nul 2>nul
if not errorlevel 1 (
    py -3 "%~dp0tools\analyze_reports.py" %*
    exit /b %ERRORLEVEL%
)

where python.exe >nul 2>nul
if not errorlevel 1 (
    python "%~dp0tools\analyze_reports.py" %*
    exit /b %ERRORLEVEL%
)

echo [ERROR] Python 3 was not found.
echo Install Python 3 from https://www.python.org/downloads/ or run the analyzer with another Python 3 installation.
exit /b 1
