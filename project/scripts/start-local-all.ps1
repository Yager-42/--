[CmdletBinding()]
param(
    [switch]$Rebuild,
    [switch]$CleanOrphans,
    [string]$WslDistro = 'Ubuntu-22.04'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectDir = Split-Path -Parent $scriptDir
$workspaceDir = Split-Path -Parent $projectDir
$backendDir = Join-Path $projectDir 'nexus'
$frontendDir = Join-Path $projectDir 'nexus-frontend'
$runtimeDir = Join-Path ([System.IO.Path]::GetTempPath()) 'nexus-local-dev'
$backendStdOutLog = Join-Path $runtimeDir 'backend.out.log'
$backendStdErrLog = Join-Path $runtimeDir 'backend.err.log'
$frontendStdOutLog = Join-Path $runtimeDir 'frontend.out.log'
$frontendStdErrLog = Join-Path $runtimeDir 'frontend.err.log'
$mavenCmd = Join-Path $workspaceDir '.codex\tools\apache-maven-3.9.6\bin\mvn.cmd'

function Write-Step {
    param([string]$Message)
    Write-Host "==> $Message"
}

function Invoke-WslBash {
    param(
        [string]$Command,
        [switch]$SuppressOutput
    )

    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        if ($SuppressOutput) {
            $output = & wsl.exe -d $WslDistro -e bash -lc $Command 1>$null 2>$null
        } else {
            $output = & wsl.exe -d $WslDistro -e bash -lc $Command 2>$null
        }
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }

    return [pscustomobject]@{
        ExitCode = $exitCode
        Output = $output
    }
}

function Convert-ToWslPath {
    param([string]$WindowsPath)

    $resolved = (Resolve-Path $WindowsPath).Path
    $drive = $resolved.Substring(0, 1).ToLowerInvariant()
    $rest = $resolved.Substring(2).Replace('\', '/')
    return "/mnt/$drive$rest"
}

function Test-TcpPort {
    param(
        [string]$HostName,
        [int]$Port
    )

    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $async = $client.BeginConnect($HostName, $Port, $null, $null)
        if (-not $async.AsyncWaitHandle.WaitOne(1000, $false)) {
            return $false
        }
        $client.EndConnect($async) | Out-Null
        return $true
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

function Wait-ForTcpPort {
    param(
        [string]$Name,
        [int]$Port,
        [int]$TimeoutSeconds = 180
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-TcpPort -Host '127.0.0.1' -Port $Port) {
            return
        }
        Start-Sleep -Seconds 2
    }
    throw "$Name is not ready on 127.0.0.1:$Port within $TimeoutSeconds seconds."
}

function Wait-ForWslTcpPort {
    param(
        [string]$Name,
        [int]$Port,
        [int]$TimeoutSeconds = 180
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $result = Invoke-WslBash -Command "python3 - <<'PY'
import socket, sys
s = socket.socket()
s.settimeout(1)
try:
    s.connect(('127.0.0.1', $Port))
    sys.exit(0)
except Exception:
    sys.exit(1)
finally:
    s.close()
PY" -SuppressOutput
        if ($result.ExitCode -eq 0) {
            return
        }
        Start-Sleep -Seconds 2
    }
    throw "$Name is not ready on WSL 127.0.0.1:$Port within $TimeoutSeconds seconds."
}

function Test-HttpEndpoint {
    param(
        [string]$Url,
        [int]$TimeoutSec = 5
    )

    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec $TimeoutSec
        return $response.StatusCode -eq 200
    } catch {
        return $false
    }
}

function Test-RedisWindowsEndpoint {
    param(
        [string]$HostName = '127.0.0.1',
        [int]$Port = 6379,
        [int]$TimeoutMilliseconds = 1000
    )

    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $async = $client.BeginConnect($HostName, $Port, $null, $null)
        if (-not $async.AsyncWaitHandle.WaitOne($TimeoutMilliseconds, $false)) {
            return $false
        }
        $client.EndConnect($async) | Out-Null
        $stream = $client.GetStream()
        $stream.ReadTimeout = $TimeoutMilliseconds
        $stream.WriteTimeout = $TimeoutMilliseconds
        $payload = [System.Text.Encoding]::ASCII.GetBytes("*1`r`n`$4`r`nPING`r`n")
        $stream.Write($payload, 0, $payload.Length)
        $stream.Flush()
        $buffer = New-Object byte[] 64
        $read = $stream.Read($buffer, 0, $buffer.Length)
        if ($read -le 0) {
            return $false
        }
        $reply = [System.Text.Encoding]::ASCII.GetString($buffer, 0, $read)
        return $reply.Contains('+PONG')
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

function Wait-ForHttp200 {
    param(
        [string]$Name,
        [string]$Url,
        [int]$TimeoutSeconds = 180
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-HttpEndpoint -Url $Url) {
            return
        }
        Start-Sleep -Seconds 2
    }
    throw "$Name did not return HTTP 200 within $TimeoutSeconds seconds: $Url"
}

function Wait-ForHttp200OrProcessExit {
    param(
        [string]$Name,
        [string]$Url,
        [int]$ProcessId,
        [int]$TimeoutSeconds = 180
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $process = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
        if ($null -eq $process) {
            throw "$Name failed because process PID $ProcessId exited before returning HTTP 200: $Url"
        }

        if (Test-HttpEndpoint -Url $Url) {
            return
        }
        Start-Sleep -Seconds 2
    }

    throw "$Name did not return HTTP 200 within $TimeoutSeconds seconds: $Url"
}

function Show-LogTail {
    param(
        [string]$Title,
        [string]$Path,
        [int]$Tail = 120
    )

    Write-Host "----- $Title ($Path) -----"
    if (Test-Path $Path) {
        Get-Content -Path $Path -Tail $Tail
    } else {
        Write-Host '[missing]'
    }
}

function Wait-ForWslComposeServiceRunning {
    param(
        [string]$WslProjectDir,
        [string]$ServiceName,
        [int]$TimeoutSeconds = 180
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $command = "cd '$WslProjectDir' && docker compose -f docker-compose.middleware.yml ps --status running --services $ServiceName | grep -qx $ServiceName"
        $result = Invoke-WslBash -Command $command -SuppressOutput
        if ($result.ExitCode -eq 0) {
            return
        }
        Start-Sleep -Seconds 2
    }
    throw "$ServiceName is not running in WSL docker compose within $TimeoutSeconds seconds."
}

function Test-CassandraReady {
    param(
        [string]$WslProjectDir,
        [int]$TimeoutSeconds = 180,
        [int]$RequiredStableChecks = 4
    )

    $stableSuccessCount = 0
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $healthCheck = "cd '$WslProjectDir' && docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}nohealth{{end}}' project-cassandra-1"
        $healthResult = Invoke-WslBash -Command $healthCheck
        $health = ($healthResult.Output | Out-String).Trim()
        if (($healthResult.ExitCode -eq 0) -and ($health -eq 'healthy')) {
            $cqlCheck = "cd '$WslProjectDir' && docker exec project-cassandra-1 cqlsh -e 'DESCRIBE KEYSPACES;' >/dev/null 2>&1"
            $cqlResult = Invoke-WslBash -Command $cqlCheck -SuppressOutput
            if ($cqlResult.ExitCode -eq 0) {
                $stableSuccessCount += 1
                if ($stableSuccessCount -ge $RequiredStableChecks) {
                    return $true
                }
                Start-Sleep -Seconds 3
                continue
            }
        }

        $stableSuccessCount = 0
        Start-Sleep -Seconds 3
    }
    return $false
}

function Test-RedisReady {
    param(
        [string]$WslProjectDir,
        [int]$TimeoutSeconds = 120,
        [int]$RequiredStableChecks = 4
    )

    $stableSuccessCount = 0
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $healthCheck = "cd '$WslProjectDir' && docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}nohealth{{end}}' project-redis-1"
        $healthResult = Invoke-WslBash -Command $healthCheck
        $health = ($healthResult.Output | Out-String).Trim()
        if (($healthResult.ExitCode -eq 0) -and ($health -eq 'healthy')) {
            $pingCheck = "cd '$WslProjectDir' && docker exec project-redis-1 redis-cli ping | grep -qx PONG"
            $pingResult = Invoke-WslBash -Command $pingCheck -SuppressOutput
            if ($pingResult.ExitCode -eq 0) {
                $stableSuccessCount += 1
                if ($stableSuccessCount -ge $RequiredStableChecks) {
                    return $true
                }
                Start-Sleep -Seconds 2
                continue
            }
        }

        $stableSuccessCount = 0
        Start-Sleep -Seconds 2
    }

    return $false
}
function Get-ListeningPid {
    param([int]$Port)

    $connection = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($null -eq $connection) {
        return $null
    }
    return [int]$connection.OwningProcess
}

function Get-CommandLine {
    param([int]$ProcessId)

    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$ProcessId" -ErrorAction SilentlyContinue
    if ($null -eq $process) {
        return ''
    }
    return [string]$process.CommandLine
}

function Ensure-Directory {
    param([string]$Path)

    if (-not (Test-Path $Path)) {
        New-Item -ItemType Directory -Path $Path | Out-Null
    }
}

function Start-BackendIfNeeded {
    $healthUrl = 'http://127.0.0.1:8080/api/v1/health'
    if (Test-HttpEndpoint -Url $healthUrl) {
        Write-Step 'Backend already healthy on port 8080.'
        return
    }

    $existingProcessId = Get-ListeningPid -Port 8080
    if ($null -ne $existingProcessId) {
        throw "Port 8080 is already in use by PID $existingProcessId, but the Nexus backend health check is not healthy."
    }

    if (-not (Test-Path $mavenCmd)) {
        if ($Rebuild) {
            $command = "Set-Location '$backendDir'; `$env:SPRING_PROFILES_ACTIVE='wsl'; mvn -pl nexus-app -am -DskipTests install; if (`$LASTEXITCODE -ne 0) { exit `$LASTEXITCODE }; mvn -f nexus-app\pom.xml spring-boot:run"
        } else {
            $command = "Set-Location '$backendDir'; `$env:SPRING_PROFILES_ACTIVE='wsl'; mvn -f nexus-app\pom.xml spring-boot:run"
        }
    } else {
        if ($Rebuild) {
            $command = "Set-Location '$backendDir'; `$env:SPRING_PROFILES_ACTIVE='wsl'; & '$mavenCmd' -pl nexus-app -am -DskipTests install; if (`$LASTEXITCODE -ne 0) { exit `$LASTEXITCODE }; & '$mavenCmd' -f nexus-app\pom.xml spring-boot:run"
        } else {
            $command = "Set-Location '$backendDir'; `$env:SPRING_PROFILES_ACTIVE='wsl'; & '$mavenCmd' -f nexus-app\pom.xml spring-boot:run"
        }
    }

    Write-Step 'Starting backend on port 8080.'
    $backendProcess = Start-Process -FilePath 'powershell.exe' `
        -ArgumentList '-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', $command `
        -WorkingDirectory $backendDir `
        -WindowStyle Hidden `
        -PassThru `
        -RedirectStandardOutput $backendStdOutLog `
        -RedirectStandardError $backendStdErrLog

    # First startup may spend several minutes on Maven install when -Rebuild is enabled.
    try {
        Wait-ForHttp200OrProcessExit -Name 'Backend health check' -Url $healthUrl -ProcessId $backendProcess.Id -TimeoutSeconds 600
    } catch {
        Write-Host ''
        Write-Host '[ERROR] Backend health check failed. Showing backend/frontend log tails:'
        Show-LogTail -Title 'backend.out.log' -Path $backendStdOutLog -Tail 120
        Show-LogTail -Title 'backend.err.log' -Path $backendStdErrLog -Tail 120
        Show-LogTail -Title 'frontend.out.log' -Path $frontendStdOutLog -Tail 80
        Show-LogTail -Title 'frontend.err.log' -Path $frontendStdErrLog -Tail 80
        throw
    }
}

function Start-FrontendIfNeeded {
    $frontendUrl = 'http://127.0.0.1:3000'
    if (Test-HttpEndpoint -Url $frontendUrl) {
        Write-Step 'Frontend already available on port 3000.'
        return
    }

    $existingProcessId = Get-ListeningPid -Port 3000
    if ($null -ne $existingProcessId) {
        throw "Port 3000 is already in use by PID $existingProcessId, but the frontend page is not healthy."
    }

    $command = "cd /d `"$frontendDir`" && npm run dev -- --host 0.0.0.0"
    Write-Step 'Starting frontend on port 3000.'
    Start-Process -FilePath 'cmd.exe' `
        -ArgumentList '/c', $command `
        -WorkingDirectory $frontendDir `
        -WindowStyle Hidden `
        -RedirectStandardOutput $frontendStdOutLog `
        -RedirectStandardError $frontendStdErrLog | Out-Null

    Wait-ForHttp200 -Name 'Frontend home page' -Url $frontendUrl
}

Ensure-Directory -Path $runtimeDir

Write-Step 'Starting WSL middleware.'
$wslProjectDir = Convert-ToWslPath -WindowsPath $projectDir
$middlewareFlags = @()
if ($Rebuild) {
    $middlewareFlags += '--build'
}
if ($CleanOrphans) {
    $middlewareFlags += '--remove-orphans'
}
$middlewareFlagText = if ($middlewareFlags.Count -gt 0) { ' ' + ($middlewareFlags -join ' ') } else { '' }
$middlewareResult = Invoke-WslBash -Command "cd '$wslProjectDir' && bash scripts/up-wsl-middleware.sh$middlewareFlagText" -SuppressOutput
if ($middlewareResult.ExitCode -ne 0) {
    throw "Failed to start WSL middleware (exit code: $($middlewareResult.ExitCode))."
}

foreach ($service in @(
    @{ Name = 'MySQL'; Port = 3306 },
    @{ Name = 'Redis'; Port = 6379 },
    @{ Name = 'RabbitMQ'; Port = 5672 },
    @{ Name = 'Zookeeper'; Port = 2181 },
    @{ Name = 'Cassandra'; Port = 9042 },
    @{ Name = 'Elasticsearch'; Port = 9200 },
    @{ Name = 'MinIO'; Port = 9000 },
    @{ Name = 'etcd'; Port = 2379 },
    @{ Name = 'Gorse API'; Port = 8087 },
    @{ Name = 'HotKey Dashboard'; Port = 9901 }
)) {
    Wait-ForWslTcpPort -Name $service.Name -Port $service.Port
}

foreach ($service in @(
    @{ Name = 'MySQL'; Port = 3306 },
    @{ Name = 'Redis'; Port = 6379 },
    @{ Name = 'RabbitMQ'; Port = 5672 },
    @{ Name = 'Zookeeper'; Port = 2181 },
    @{ Name = 'Cassandra'; Port = 9042 },
    @{ Name = 'Elasticsearch'; Port = 9200 },
    @{ Name = 'MinIO'; Port = 9000 },
    @{ Name = 'etcd'; Port = 2379 },
    @{ Name = 'Gorse API'; Port = 8087 },
    @{ Name = 'HotKey Dashboard'; Port = 9901 }
)) {
    Wait-ForTcpPort -Name $service.Name -Port $service.Port
}

Wait-ForWslComposeServiceRunning -WslProjectDir $wslProjectDir -ServiceName 'canal-server'

Write-Step 'Waiting Cassandra health and CQL readiness.'
if (-not (Test-CassandraReady -WslProjectDir $wslProjectDir -TimeoutSeconds 240)) {
    throw 'Cassandra did not become healthy and queryable in time.'
}

Write-Step 'Waiting Redis health and PING readiness.'
if (-not (Test-RedisReady -WslProjectDir $wslProjectDir -TimeoutSeconds 180)) {
    throw 'Redis did not become healthy and queryable in time.'
}

if (-not (Test-RedisWindowsEndpoint)) {
    throw 'Redis is healthy inside WSL, but Windows localhost forwarding for 127.0.0.1:6379 is not responding to PING.'
}

Start-BackendIfNeeded
Start-FrontendIfNeeded

Write-Host ''
Write-Host 'All services are ready.'
Write-Host 'Frontend:  http://localhost:3000'
Write-Host 'Backend:   http://localhost:8080/api/v1/health'
Write-Host 'Gorse:     http://localhost:8088'
Write-Host 'HotKey:    http://localhost:9901'
Write-Host "Backend logs:  $backendStdOutLog , $backendStdErrLog"
Write-Host "Frontend logs: $frontendStdOutLog , $frontendStdErrLog"


