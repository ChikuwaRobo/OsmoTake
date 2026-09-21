@echo off
setlocal
cd /d "%~dp0"
if not exist ".venv\Scripts\python.exe" (
  echo .venv is not found. Run setup.bat first.
  pause
  exit /b 1
)
".venv\Scripts\python.exe" -m osmo_ble_ctrl

