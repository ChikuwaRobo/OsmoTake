@echo off
setlocal
cd /d "%~dp0"
where uv >nul 2>nul
if errorlevel 1 (
  echo uv is not installed or is not on PATH. Run setup.bat after installing uv.
  pause
  exit /b 1
)
uv run osmo-ble-ctrl
