@echo off
setlocal

set SCRIPT_DIR=%~dp0
set PS_SCRIPT=%SCRIPT_DIR%run-loadtest.ps1

rem Default built-in parameters (can be overridden by command-line args)
set BASE_URL=http://localhost:8080
set PHONE=13800000001
set PASSWORD=Pwd@123456
set POST_ID=1
set TARGET_USER_ID=2

if not "%~1"=="" set BASE_URL=%~1
if not "%~2"=="" set PHONE=%~2
if not "%~3"=="" set PASSWORD=%~3
if not "%~4"=="" set POST_ID=%~4
if not "%~5"=="" set TARGET_USER_ID=%~5

if "%POST_ID%"=="" set POST_ID=1
if "%TARGET_USER_ID%"=="" set TARGET_USER_ID=2

echo Running with:
echo   BASE_URL=%BASE_URL%
echo   PHONE=%PHONE%
echo   POST_ID=%POST_ID%
echo   TARGET_USER_ID=%TARGET_USER_ID%

powershell -NoProfile -ExecutionPolicy Bypass -File "%PS_SCRIPT%" ^
  -BaseUrl "%BASE_URL%" ^
  -Phone "%PHONE%" ^
  -Password "%PASSWORD%" ^
  -PostId "%POST_ID%" ^
  -TargetUserId "%TARGET_USER_ID%"

set EXIT_CODE=%ERRORLEVEL%
if not "%EXIT_CODE%"=="0" (
  echo Loadtest failed. exit code=%EXIT_CODE%
  exit /b %EXIT_CODE%
)

echo Loadtest finished.
exit /b 0
