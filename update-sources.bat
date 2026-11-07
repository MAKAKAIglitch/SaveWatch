@echo off
setlocal
cd /d "%~dp0"

where powershell.exe >nul 2>nul
if errorlevel 1 (
    echo [ERROR] Windows PowerShell was not found.
    exit /b 1
)

powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\update-sources.ps1"
set "UPDATE_EXIT=%ERRORLEVEL%"

echo.
if "%UPDATE_EXIT%"=="0" (
    echo [SUCCESS] SaveWatch sources are up to date.
) else (
    echo [FAILED] Source update failed with exit code %UPDATE_EXIT%.
)
echo.
pause
exit /b %UPDATE_EXIT%
