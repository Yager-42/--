@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop-local-all.ps1"
exit /b %errorlevel%
