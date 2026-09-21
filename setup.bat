@echo off
setlocal
cd /d "%~dp0"
where uv >nul 2>nul
if errorlevel 1 (
  echo uv is not installed or is not on PATH.
  echo Install uv from https://docs.astral.sh/uv/getting-started/installation/
  pause
  exit /b 1
)
uv sync --extra test
if errorlevel 1 exit /b 1
echo Setup complete. Start with run.bat.
