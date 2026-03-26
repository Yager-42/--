[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectDir = Split-Path -Parent $scriptDir

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

function Stop-ProcessIfMatch {
    param(
        [int]$Port,
        [string[]]$Patterns,
        [string]$Name
    )

    $listeningPid = Get-ListeningPid -Port $Port
    if ($null -eq $listeningPid) {
        Write-Step "$Name is already stopped."
        return
    }

    $commandLine = Get-CommandLine -ProcessId $listeningPid
    foreach ($pattern in $Patterns) {
        if ($commandLine -like $pattern) {
            Write-Step "Stopping $Name (PID $listeningPid)."
            Stop-Process -Id $listeningPid -Force -ErrorAction SilentlyContinue
            return
        }
    }

    Write-Warning "$Name is listening on port $Port with PID $listeningPid, but it does not look like this project. Skipped."
}

function Stop-ProcessesByPattern {
    param(
        [string]$Pattern,
        [string]$Name
    )

    $processes = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like $Pattern }
    foreach ($process in $processes) {
        if ($null -ne $process.ProcessId) {
            Write-Step "Stopping $Name launcher/helper PID $($process.ProcessId)."
            Stop-Process -Id ([int]$process.ProcessId) -Force -ErrorAction SilentlyContinue
        }
    }
}

Stop-ProcessIfMatch -Port 3000 -Patterns @('*vite.js*', '*nexus-frontend*') -Name 'Frontend'
Stop-ProcessIfMatch -Port 8080 -Patterns @('*cn.nexus.Application*', '*nexus-app*') -Name 'Backend'
Stop-ProcessesByPattern -Pattern '*HotKeyBridgeServer*' -Name 'HotKey helper'
Stop-ProcessesByPattern -Pattern '*nexus-app\pom.xml*spring-boot:run*' -Name 'Backend launcher'

Write-Step 'Stopping WSL middleware.'
$wslProjectDir = Convert-ToWslPath -WindowsPath $projectDir
wsl.exe -e bash -lc "cd '$wslProjectDir' && docker compose -f docker-compose.middleware.yml down"

Write-Host ''
Write-Host 'All local services have been stopped.'
