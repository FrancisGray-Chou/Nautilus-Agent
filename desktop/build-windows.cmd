@echo off
setlocal
set "PYTHONUTF8=1"
set "PYTHONIOENCODING=utf-8"
where py >nul 2>nul
if errorlevel 1 (
  echo Python 3.11 or later is required on the BUILD MACHINE.
  echo See desktop\WINDOWS.md for prerequisites.
  pause
  exit /b 1
)
py -3 "%~dp0..\scripts\package_windows.py" %*
set "nautilus_result=%errorlevel%"
if not "%nautilus_result%"=="0" pause
exit /b %nautilus_result%
