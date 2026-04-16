$ErrorActionPreference = "Stop"
$root = "C:\Users\Administrator\Desktop\文档\project\nexus\tools\loadtest"
$outRoot = Join-Path $root "output"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$diagDir = Join-Path $outRoot ("bottleneck-live-" + $stamp)
New-Item -ItemType Directory -Path $diagDir -Force | Out-Null

$tokenFile = Join-Path $env:TEMP "nexus-loadtest-token.txt"
if (!(Test-Path $tokenFile)) { throw "token file missing: $tokenFile" }
$token = (Get-Content $tokenFile -Raw).Trim()
if ([string]::IsNullOrWhiteSpace($token)) { throw "token empty" }

$env:BASE_URL = "http://localhost:8080"
$env:TOKEN = $token
$env:POST_ID = "1"
$env:TARGET_USER_ID = "2"

# counter-system focused: disable relation traffic
$env:REL_START_RATE = "0"
$env:REL_RATE_1 = "0"
$env:REL_RATE_2 = "0"
$env:REL_PRE_VUS = "1"
$env:REL_MAX_VUS = "1"

# high pressure for feed/reaction/search
$env:FEED_START_RATE = "120"
$env:FEED_RATE_1 = "650"
$env:FEED_RATE_2 = "950"
$env:FEED_PRE_VUS = "400"
$env:FEED_MAX_VUS = "3000"

$env:REACTION_START_RATE = "120"
$env:REACTION_RATE_1 = "650"
$env:REACTION_RATE_2 = "950"
$env:REACTION_PRE_VUS = "400"
$env:REACTION_MAX_VUS = "3000"

$env:SEARCH_START_RATE = "120"
$env:SEARCH_RATE_1 = "650"
$env:SEARCH_RATE_2 = "950"
$env:SEARCH_PRE_VUS = "400"
$env:SEARCH_MAX_VUS = "3000"

$backendPid = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
  Where-Object { $_.CommandLine -match 'cn\.nexus\.Application' } |
  Select-Object -First 1 -ExpandProperty ProcessId
if (-not $backendPid) { throw "backend process not found: cn.nexus.Application" }

$loadLog = Join-Path $diagDir "loadtest-run.log"
$loadErr = Join-Path $diagDir "loadtest-run.err.log"
$runPs1 = Join-Path $root "run-loadtest.ps1"

$redisBefore = wsl.exe -d Ubuntu-22.04 -- docker exec project-redis-1 redis-cli INFO commandstats
$redisBefore | Out-File -FilePath (Join-Path $diagDir "redis-before.txt") -Encoding utf8

$p = Start-Process -FilePath "powershell" -ArgumentList @(
  "-NoProfile","-ExecutionPolicy","Bypass","-File",$runPs1,
  "-BaseUrl","http://localhost:8080","-PostId","1","-TargetUserId","2","-SkipLogin"
) -RedirectStandardOutput $loadLog -RedirectStandardError $loadErr -PassThru

$procOut = Join-Path $diagDir "mysql-processlist.log"
$statOut = Join-Path $diagDir "mysql-status.log"
$redisOut = Join-Path $diagDir "redis-commandstats-during.log"

$sec = 0
while (-not $p.HasExited) {
  if (($sec % 10) -eq 0) {
    Add-Content -Path $procOut -Value ("===== t=" + $sec + "s =====")
    $q1 = "SELECT NOW(),ID,USER,DB,COMMAND,TIME,STATE,LEFT(REPLACE(IFNULL(INFO,''),CHAR(10),' '),180) FROM information_schema.PROCESSLIST WHERE COMMAND<>'Sleep' ORDER BY TIME DESC LIMIT 30;"
    (wsl.exe -d Ubuntu-22.04 -- docker exec project-mysql-1 mysql -N -uroot -proot -e $q1) | Add-Content -Path $procOut

    Add-Content -Path $statOut -Value ("===== t=" + $sec + "s =====")
    $q2 = "SHOW GLOBAL STATUS WHERE Variable_name IN ('Threads_running','Threads_connected','Queries','Questions','Innodb_row_lock_current_waits','Innodb_row_lock_time');"
    (wsl.exe -d Ubuntu-22.04 -- docker exec project-mysql-1 mysql -N -uroot -proot -e $q2) | Add-Content -Path $statOut

    Add-Content -Path $redisOut -Value ("===== t=" + $sec + "s =====")
    (wsl.exe -d Ubuntu-22.04 -- docker exec project-redis-1 redis-cli INFO commandstats) | Add-Content -Path $redisOut
  }

  if ($sec -eq 30)  { & jstack $backendPid *> (Join-Path $diagDir "jstack-1.txt") }
  if ($sec -eq 90)  { & jstack $backendPid *> (Join-Path $diagDir "jstack-2.txt") }
  if ($sec -eq 150) { & jstack $backendPid *> (Join-Path $diagDir "jstack-3.txt") }

  Start-Sleep -Seconds 1
  $sec++
}

$p.WaitForExit()
$exit = $p.ExitCode
$redisAfter = wsl.exe -d Ubuntu-22.04 -- docker exec project-redis-1 redis-cli INFO commandstats
$redisAfter | Out-File -FilePath (Join-Path $diagDir "redis-after.txt") -Encoding utf8

"DIAG_DIR=$diagDir"
"LOADTEST_EXIT=$exit"
