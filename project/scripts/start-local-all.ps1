[CmdletBinding()]
param()

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

function Wait-ForHttp200 {
    param(
        [string]$Name,
        [string]$Url,
        [int]$TimeoutSeconds = 180
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 5
            if ($response.StatusCode -eq 200) {
                return
            }
        } catch {
        }
        Start-Sleep -Seconds 2
    }
    throw "$Name did not return HTTP 200 within $TimeoutSeconds seconds: $Url"
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
        wsl.exe -e bash -lc $command *> $null
        if ($LASTEXITCODE -eq 0) {
            return
        }
        Start-Sleep -Seconds 2
    }
    throw "$ServiceName is not running in WSL docker compose within $TimeoutSeconds seconds."
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
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $healthUrl -TimeoutSec 5
        if ($response.StatusCode -eq 200) {
            Write-Step 'Backend already healthy on port 8080.'
            return
        }
    } catch {
    }

    $existingProcessId = Get-ListeningPid -Port 8080
    if ($null -ne $existingProcessId) {
        throw "Port 8080 is already in use by PID $existingProcessId, but the Nexus backend health check is not healthy."
    }

    if (-not (Test-Path $mavenCmd)) {
        $command = "Set-Location '$backendDir'; `$env:SPRING_PROFILES_ACTIVE='wsl'; mvn -pl nexus-app -am -DskipTests install; if (`$LASTEXITCODE -ne 0) { exit `$LASTEXITCODE }; mvn -f nexus-app\pom.xml spring-boot:run"
    } else {
        $command = "Set-Location '$backendDir'; `$env:SPRING_PROFILES_ACTIVE='wsl'; & '$mavenCmd' -pl nexus-app -am -DskipTests install; if (`$LASTEXITCODE -ne 0) { exit `$LASTEXITCODE }; & '$mavenCmd' -f nexus-app\pom.xml spring-boot:run"
    }

    Write-Step 'Starting backend on port 8080.'
    Start-Process -FilePath 'powershell.exe' `
        -ArgumentList '-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', $command `
        -WorkingDirectory $backendDir `
        -WindowStyle Hidden `
        -RedirectStandardOutput $backendStdOutLog `
        -RedirectStandardError $backendStdErrLog | Out-Null

    Wait-ForHttp200 -Name 'Backend health check' -Url $healthUrl
}

function Start-FrontendIfNeeded {
    $frontendUrl = 'http://127.0.0.1:3000'
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $frontendUrl -TimeoutSec 5
        if ($response.StatusCode -eq 200) {
            Write-Step 'Frontend already available on port 3000.'
            return
        }
    } catch {
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
wsl.exe -e bash -lc "cd '$wslProjectDir' && bash scripts/up-wsl-middleware.sh"

foreach ($service in @(
    @{ Name = 'MySQL'; Port = 3306 },
    @{ Name = 'Redis'; Port = 6379 },
    @{ Name = 'RabbitMQ'; Port = 5672 },
    @{ Name = 'Zookeeper'; Port = 2181 },
    @{ Name = 'Kafka'; Port = 9092 },
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
