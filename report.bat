@echo off
setlocal
cd /d "%~dp0"
where py >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Python 3 launcher ^(py^) was not found.
  echo Install Python 3 or run: python tools\report_html.py logs\savewatch --open
  pause
  exit /b 1
)
py -3 "%~dp0tools\report_html.py" logs\savewatch --open %*
set "RESULT=%ERRORLEVEL%"
if not "%RESULT%"=="0" pause
exit /b %RESULT%
