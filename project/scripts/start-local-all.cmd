@echo off
setlocal EnableExtensions

set "PS_SCRIPT=%~dp0start-local-all.ps1"

if not exist "%PS_SCRIPT%" (
  echo [ERROR] Cannot find startup script:
  echo         %PS_SCRIPT%
  pause
  exit /b 1
)

where powershell.exe >nul 2>&1
if errorlevel 1 (
  echo [ERROR] powershell.exe was not found in PATH.
  pause
  exit /b 1
)

echo [INFO] Starting Nexus local stack...
echo [INFO] Using script: %PS_SCRIPT%
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%PS_SCRIPT%" %*
set "EXIT_CODE=%ERRORLEVEL%"

if not "%EXIT_CODE%"=="0" (
  echo.
  echo [ERROR] Startup failed with exit code %EXIT_CODE%.
  echo [INFO] Check logs under:
  echo        %%TEMP%%\nexus-local-dev
  echo [INFO] Last 80 lines from backend/frontend logs:
  powershell.exe -NoProfile -ExecutionPolicy Bypass -Command ^
    "$runtime = Join-Path $env:TEMP 'nexus-local-dev';" ^
    "$files = @(" ^
    "  (Join-Path $runtime 'backend.out.log')," ^
    "  (Join-Path $runtime 'backend.err.log')," ^
    "  (Join-Path $runtime 'frontend.out.log')," ^
    "  (Join-Path $runtime 'frontend.err.log')" ^
    ");" ^
    "foreach ($f in $files) {" ^
    "  Write-Host ('----- ' + $f + ' -----');" ^
    "  if (Test-Path $f) { Get-Content -Path $f -Tail 80 } else { Write-Host '[missing]' }" ^
    "}"
  pause
  exit /b %EXIT_CODE%
)

echo.
echo [INFO] Startup completed.
echo [INFO] Watching backend/frontend logs (stdout+stderr). Press Ctrl+C to stop watching.
echo [INFO] Log directory: %%TEMP%%\nexus-local-dev

powershell.exe -NoProfile -ExecutionPolicy Bypass -Command ^
  "$runtime = Join-Path $env:TEMP 'nexus-local-dev';" ^
  "$files = @(" ^
  "  (Join-Path $runtime 'backend.out.log')," ^
  "  (Join-Path $runtime 'backend.err.log')," ^
  "  (Join-Path $runtime 'frontend.out.log')," ^
  "  (Join-Path $runtime 'frontend.err.log')" ^
  ");" ^
  "foreach ($f in $files) { if (-not (Test-Path $f)) { New-Item -ItemType File -Path $f -Force | Out-Null } };" ^
  "Get-Content -Path $files -Tail 30 -Wait"

pause
exit /b 0
