@echo off
setlocal
cd /d "%~dp0"
if not exist ".venv\Scripts\python.exe" (
  if exist "C:\Python313\python.exe" (
    "C:\Python313\python.exe" -m venv .venv
  ) else (
    py -3.13 -m venv .venv
  )
)
if errorlevel 1 exit /b 1
".venv\Scripts\python.exe" -m pip install --upgrade pip
if errorlevel 1 exit /b 1
".venv\Scripts\python.exe" -m pip install -r requirements-dev.txt
if errorlevel 1 exit /b 1
echo Setup complete. Start with run.bat.
