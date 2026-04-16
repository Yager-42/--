@echo off
setlocal

set SCRIPT_DIR=%~dp0
set PS_SCRIPT=%SCRIPT_DIR%run-loadtest-diagnose.ps1

rem Default built-in parameters (can be overridden by command-line args)
set BASE_URL=http://localhost:8080
set PHONE=13800000001
set PASSWORD=Pwd@123456
set POST_ID=1
set TARGET_USER_ID=2
set SAMPLE_INTERVAL_SEC=2
set BACKEND_PID=0

if not "%~1"=="" set BASE_URL=%~1
if not "%~2"=="" set PHONE=%~2
if not "%~3"=="" set PASSWORD=%~3
if not "%~4"=="" set POST_ID=%~4
if not "%~5"=="" set TARGET_USER_ID=%~5
if not "%~6"=="" set SAMPLE_INTERVAL_SEC=%~6
if not "%~7"=="" set BACKEND_PID=%~7

echo Running diagnose with:
echo   BASE_URL=%BASE_URL%
echo   PHONE=%PHONE%
echo   POST_ID=%POST_ID%
echo   TARGET_USER_ID=%TARGET_USER_ID%
echo   SAMPLE_INTERVAL_SEC=%SAMPLE_INTERVAL_SEC%
echo   BACKEND_PID=%BACKEND_PID%

powershell -NoProfile -ExecutionPolicy Bypass -File "%PS_SCRIPT%" ^
  -BaseUrl "%BASE_URL%" ^
  -Phone "%PHONE%" ^
  -Password "%PASSWORD%" ^
  -PostId "%POST_ID%" ^
  -TargetUserId "%TARGET_USER_ID%" ^
  -SampleIntervalSec %SAMPLE_INTERVAL_SEC% ^
  -BackendPid %BACKEND_PID%

set EXIT_CODE=%ERRORLEVEL%
if not "%EXIT_CODE%"=="0" (
  echo Diagnose loadtest failed. exit code=%EXIT_CODE%
  exit /b %EXIT_CODE%
)

echo Diagnose loadtest finished.
exit /b 0
