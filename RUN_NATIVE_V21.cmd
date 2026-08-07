@echo off
setlocal
cd /d "%~dp0"
echo ROVER QNN DETECTOR - NATIVE V21
echo =================================
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\build_native.ps1"
if errorlevel 1 (
  echo.
  echo BUILD OR DEPLOYMENT FAILED. Read NATIVE_V21_BUILD_LOG.txt.
)
pause
