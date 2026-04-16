param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Phone = "",
    [string]$Password = "",
    [string]$PostId = "1",
    [string]$TargetUserId = "2",
    [int]$SampleIntervalSec = 2,
    [int]$BackendPid = 0,
    [switch]$SkipLogin
)

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$RunLoadtestScript = Join-Path $Root "run-loadtest.ps1"
$OutDir = Join-Path $Root "output"
$JstatExe = (Get-Command jstat -ErrorAction SilentlyContinue).Source

if (!(Test-Path $RunLoadtestScript)) {
    throw "run-loadtest.ps1 not found: $RunLoadtestScript"
}
if (!(Test-Path $OutDir)) {
    New-Item -Path $OutDir -ItemType Directory | Out-Null
}
if ($SampleIntervalSec -lt 1) {
    $SampleIntervalSec = 1
}

function Get-BaseUrlPort([string]$url) {
    try {
        $uri = [System.Uri]$url
        if ($uri.IsDefaultPort) {
            if ($uri.Scheme -eq "https") { return 443 }
            return 80
        }
        return $uri.Port
    } catch {
        return 8080
    }
}

function Resolve-BackendPid([int]$port) {
    try {
        $candidates = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction Stop
        if ($null -eq $candidates) { return $null }
        $conn = $candidates | Select-Object -First 1
        $pid = [int]$conn.OwningProcess
        if ($pid -gt 0) { return $pid }
    } catch {
        # ignore and fallback
    }

    # Fallback 1: netstat parsing (works in some environments where Get-NetTCPConnection is empty)
    try {
        $lines = netstat -ano -p tcp | Select-String -Pattern (":$port\s") | ForEach-Object { $_.ToString() }
        foreach ($line in $lines) {
            if ($line -match 'LISTENING\s+(\d+)\s*$') {
                $pid = [int]$matches[1]
                if ($pid -gt 0) { return $pid }
            }
        }
    } catch {
        # ignore and fallback
    }

    # Fallback 2: likely Java backend process by command line
    try {
        $javaCandidates = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
            Where-Object {
                ($_.CommandLine -match 'nexus' -or $_.CommandLine -match 'spring-boot' -or $_.CommandLine -match 'nexus-main')
            } |
            Select-Object -ExpandProperty ProcessId
        if ($javaCandidates -and $javaCandidates.Count -gt 0) {
            return [int]$javaCandidates[0]
        }
    } catch {
        # ignore
    }

    return $null
}

function Get-SystemSample {
    try {
        $cpuCounter = Get-Counter '\Processor(_Total)\% Processor Time' -ErrorAction Stop
        $memCounter = Get-Counter '\Memory\Available MBytes' -ErrorAction Stop
        $cpu = [double]$cpuCounter.CounterSamples[0].CookedValue
        $mem = [double]$memCounter.CounterSamples[0].CookedValue
        return @{
            CpuPct = [math]::Round($cpu, 2)
            AvailableMemMB = [math]::Round($mem, 2)
        }
    } catch {
        return @{
            CpuPct = $null
            AvailableMemMB = $null
        }
    }
}

function Get-ProcessSample {
    param(
        [int]$Pid,
        [datetime]$Now,
        [Nullable[double]]$PrevCpuSec,
        [Nullable[datetime]]$PrevTime,
        [int]$LogicalCpuCount
    )

    try {
        $proc = Get-Process -Id $Pid -ErrorAction Stop
        $cpuSec = [double]$proc.CPU
        $cpuPct = $null

        if ($PrevCpuSec.HasValue -and $PrevTime.HasValue) {
            $elapsedSec = ($Now - $PrevTime.Value).TotalSeconds
            if ($elapsedSec -gt 0 -and $LogicalCpuCount -gt 0) {
                $delta = $cpuSec - $PrevCpuSec.Value
                if ($delta -lt 0) { $delta = 0 }
                $cpuPct = [math]::Round(($delta / ($elapsedSec * $LogicalCpuCount)) * 100, 2)
            }
        }

        return @{
            Exists = $true
            CpuSec = $cpuSec
            ProcessCpuPct = $cpuPct
            WorkingSetMB = [math]::Round($proc.WorkingSet64 / 1MB, 2)
            ThreadCount = $proc.Threads.Count
            HandleCount = $proc.Handles
        }
    } catch {
        return @{
            Exists = $false
            CpuSec = $null
            ProcessCpuPct = $null
            WorkingSetMB = $null
            ThreadCount = $null
            HandleCount = $null
        }
    }
}

function Get-JstatGcSample {
    param(
        [string]$JstatPath,
        [int]$Pid
    )

    if ([string]::IsNullOrWhiteSpace($JstatPath)) { return $null }

    try {
        $out = & $JstatPath -gcutil $Pid 1 1 2>$null
        if ($LASTEXITCODE -ne 0 -or $null -eq $out -or $out.Count -lt 2) { return $null }
        $header = ($out[0] -replace '^\s+', '') -split '\s+'
        $value = ($out[1] -replace '^\s+', '') -split '\s+'
        if ($header.Count -ne $value.Count) { return $null }

        $map = @{}
        for ($i = 0; $i -lt $header.Count; $i++) {
            $k = $header[$i]
            $v = $value[$i]
            $num = 0.0
            if ([double]::TryParse($v, [ref]$num)) {
                $map[$k] = $num
            }
        }
        return $map
    } catch {
        return $null
    }
}

function Get-Avg($values) {
    if ($null -eq $values -or $values.Count -eq 0) { return $null }
    return [math]::Round((($values | Measure-Object -Average).Average), 2)
}

function Get-Max($values) {
    if ($null -eq $values -or $values.Count -eq 0) { return $null }
    return [math]::Round((($values | Measure-Object -Maximum).Maximum), 2)
}

function Get-Min($values) {
    if ($null -eq $values -or $values.Count -eq 0) { return $null }
    return [math]::Round((($values | Measure-Object -Minimum).Minimum), 2)
}

$port = Get-BaseUrlPort -url $BaseUrl
$logicalCpu = [Environment]::ProcessorCount
$startAt = Get-Date

$beforeLatestReport = Get-ChildItem -Path $OutDir -Filter "*.report.json" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

$runnerArgs = @(
    "-NoProfile",
    "-ExecutionPolicy", "Bypass",
    "-File", $RunLoadtestScript,
    "-BaseUrl", $BaseUrl,
    "-PostId", $PostId,
    "-TargetUserId", $TargetUserId
)

if ($SkipLogin) {
    $runnerArgs += "-SkipLogin"
} else {
    $runnerArgs += @("-Phone", $Phone, "-Password", $Password)
}

Write-Host "[INFO] Start diagnose run..."
Write-Host "[INFO] Sample interval: $SampleIntervalSec s"
Write-Host "[INFO] Target base URL: $BaseUrl (port $port)"

$child = Start-Process -FilePath "powershell" -ArgumentList $runnerArgs -PassThru -NoNewWindow

$samples = New-Object System.Collections.Generic.List[object]
$prevCpuSec = $null
$prevCpuTime = $null

while (-not $child.HasExited) {
    $now = Get-Date
    $backendPidCurrent = $BackendPid
    if ($backendPidCurrent -le 0) {
        $backendPidCurrent = Resolve-BackendPid -port $port
    }
    $sys = Get-SystemSample
    $proc = $null
    if ($null -ne $backendPidCurrent -and $backendPidCurrent -gt 0) {
        $proc = Get-ProcessSample -Pid $backendPidCurrent -Now $now -PrevCpuSec $prevCpuSec -PrevTime $prevCpuTime -LogicalCpuCount $logicalCpu
    } else {
        $proc = @{
            Exists = $false
            CpuSec = $null
            ProcessCpuPct = $null
            WorkingSetMB = $null
            ThreadCount = $null
            HandleCount = $null
        }
    }

    if ($proc.Exists -and $null -ne $proc.CpuSec) {
        $prevCpuSec = [double]$proc.CpuSec
        $prevCpuTime = $now
    }

    $gc = $null
    if ($proc.Exists) {
        $gc = Get-JstatGcSample -JstatPath $JstatExe -Pid $backendPidCurrent
    }

    $samples.Add([pscustomobject]@{
        timestamp = $now.ToString("o")
        backendPid = $backendPidCurrent
        systemCpuPct = $sys.CpuPct
        availableMemMB = $sys.AvailableMemMB
        processCpuPct = $proc.ProcessCpuPct
        workingSetMB = $proc.WorkingSetMB
        threadCount = $proc.ThreadCount
        handleCount = $proc.HandleCount
        ygc = if ($gc) { $gc["YGC"] } else { $null }
        ygct = if ($gc) { $gc["YGCT"] } else { $null }
        fgc = if ($gc) { $gc["FGC"] } else { $null }
        fgct = if ($gc) { $gc["FGCT"] } else { $null }
        gct = if ($gc) { $gc["GCT"] } else { $null }
        oldPct = if ($gc) { $gc["O"] } else { $null }
        edenPct = if ($gc) { $gc["E"] } else { $null }
    }) | Out-Null

    Start-Sleep -Seconds $SampleIntervalSec
}

$child.WaitForExit()
$childExitCode = $null
try {
    $childExitCode = [int]$child.ExitCode
} catch {
    $childExitCode = $null
}
if ($null -ne $childExitCode -and $childExitCode -ne 0) {
    throw "Loadtest process failed with exit code $childExitCode"
}

$latestReport = Get-ChildItem -Path $OutDir -Filter "*.report.json" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if ($null -eq $latestReport) {
    throw "No report json found in $OutDir"
}
if ($null -ne $beforeLatestReport -and $latestReport.FullName -eq $beforeLatestReport.FullName -and $latestReport.LastWriteTime -le $beforeLatestReport.LastWriteTime) {
    throw "No new report file generated."
}

$report = Get-Content -Raw -Path $latestReport.FullName | ConvertFrom-Json
$durationSec = [double]$report.durationSec
if ($durationSec -le 0) {
    $durationSec = [math]::Max(1, ((Get-Date) - $startAt).TotalSeconds)
}

$sysCpuSeries = @($samples | Where-Object { $null -ne $_.systemCpuPct } | ForEach-Object { [double]$_.systemCpuPct })
$procCpuSeries = @($samples | Where-Object { $null -ne $_.processCpuPct } | ForEach-Object { [double]$_.processCpuPct })
$procMemSeries = @($samples | Where-Object { $null -ne $_.workingSetMB } | ForEach-Object { [double]$_.workingSetMB })
$threadSeries = @($samples | Where-Object { $null -ne $_.threadCount } | ForEach-Object { [double]$_.threadCount })
$availMemSeries = @($samples | Where-Object { $null -ne $_.availableMemMB } | ForEach-Object { [double]$_.availableMemMB })

$gcSeries = @($samples | Where-Object { $null -ne $_.gct })
$gcOverheadPct = $null
$fgcDelta = $null
if ($gcSeries.Count -ge 2) {
    $firstGc = $gcSeries[0]
    $lastGc = $gcSeries[$gcSeries.Count - 1]
    $gctDelta = [double]$lastGc.gct - [double]$firstGc.gct
    $fgcDelta = [double]$lastGc.fgc - [double]$firstGc.fgc
    if ($durationSec -gt 0) {
        $gcOverheadPct = [math]::Round(($gctDelta / $durationSec) * 100, 2)
    }
}

$monitorSummary = [ordered]@{
    sampleCount = $samples.Count
    sampleIntervalSec = $SampleIntervalSec
    systemCpuAvgPct = Get-Avg $sysCpuSeries
    systemCpuMaxPct = Get-Max $sysCpuSeries
    processCpuAvgPct = Get-Avg $procCpuSeries
    processCpuMaxPct = Get-Max $procCpuSeries
    processMemMaxMB = Get-Max $procMemSeries
    processThreadMax = Get-Max $threadSeries
    availableMemMinMB = Get-Min $availMemSeries
    gcOverheadPct = $gcOverheadPct
    fullGcCountDelta = $fgcDelta
}

$findings = New-Object System.Collections.Generic.List[string]
$recommendations = New-Object System.Collections.Generic.List[string]

if ([double]$report.httpErrorRatePct -gt 1) {
    $findings.Add("HTTP error rate > 1%, possible downstream saturation or thread pool exhaustion.")
    $recommendations.Add("Check application error logs and rejected-execution counters during peak window.")
}

if ($null -ne $monitorSummary.processCpuMaxPct -and [double]$monitorSummary.processCpuMaxPct -ge 85) {
    $findings.Add("Application process CPU reached >= 85%.")
    $recommendations.Add("Profile hot endpoints (serialization, JSON parse, lock contention) and reduce CPU-heavy logic.")
}

if ($null -ne $monitorSummary.gcOverheadPct -and [double]$monitorSummary.gcOverheadPct -ge 10) {
    $findings.Add("GC overhead >= 10% of test duration.")
    $recommendations.Add("Tune heap sizing, object allocation hotspots, and GC configuration.")
}

if ($null -ne $monitorSummary.fullGcCountDelta -and [double]$monitorSummary.fullGcCountDelta -ge 1) {
    $findings.Add("Full GC occurred during load.")
    $recommendations.Add("Capture heap histogram and inspect large object retention / cache growth.")
}

if ($null -ne $monitorSummary.availableMemMinMB -and [double]$monitorSummary.availableMemMinMB -lt 512) {
    $findings.Add("Available system memory dropped below 512MB.")
    $recommendations.Add("Increase memory headroom or move dependent services to separate host.")
}

if ([double]$report.p99Ms -gt 500 -and [double]$report.httpErrorRatePct -le 1 -and ($null -eq $monitorSummary.processCpuMaxPct -or [double]$monitorSummary.processCpuMaxPct -lt 70)) {
    $findings.Add("High p99 without obvious app CPU pressure, likely I/O bottleneck (DB/Redis/network).")
    $recommendations.Add("Correlate with MySQL slow query log, Redis latency, and network RTT in the same timeframe.")
}

$overall = "No clear bottleneck in current run. Increase load rates until QPS plateaus and p95/p99 climb sharply."
if ($findings.Count -gt 0) {
    $overall = $findings[0]
}

$diagnosis = [ordered]@{
    overall = $overall
    findings = @($findings)
    recommendations = @($recommendations)
}

$sampleFile = [System.IO.Path]::ChangeExtension($latestReport.FullName, ".samples.json")
$diagFile = [System.IO.Path]::ChangeExtension($latestReport.FullName, ".diagnosis.json")

$samples | ConvertTo-Json -Depth 6 | Out-File -FilePath $sampleFile -Encoding utf8

$result = [ordered]@{
    baseUrl = $BaseUrl
    reportFile = $latestReport.FullName
    sampleFile = $sampleFile
    generatedAt = (Get-Date).ToString("s")
    loadtest = $report
    monitor = $monitorSummary
    diagnosis = $diagnosis
}
$result | ConvertTo-Json -Depth 8 | Out-File -FilePath $diagFile -Encoding utf8

Write-Host ""
Write-Host "========== Bottleneck Diagnose Summary =========="
Write-Host "Loadtest report:      $($latestReport.FullName)"
Write-Host "Monitor sample file:  $sampleFile"
Write-Host "Diagnosis file:       $diagFile"
Write-Host "QPS / TPS:            $($report.qps) / $($report.tps)"
Write-Host "p95 / p99(ms):        $($report.p95Ms) / $($report.p99Ms)"
Write-Host "HTTP Error Rate(%):   $($report.httpErrorRatePct)"
Write-Host "App CPU avg/max(%):   $($monitorSummary.processCpuAvgPct) / $($monitorSummary.processCpuMaxPct)"
Write-Host "System CPU avg/max(%):$($monitorSummary.systemCpuAvgPct) / $($monitorSummary.systemCpuMaxPct)"
Write-Host "Heap GC overhead(%):  $($monitorSummary.gcOverheadPct)"
Write-Host "Overall:              $overall"
if ($findings.Count -gt 0) {
    Write-Host "Findings:"
    foreach ($f in $findings) { Write-Host "  - $f" }
}
if ($recommendations.Count -gt 0) {
    Write-Host "Recommendations:"
    foreach ($r in $recommendations) { Write-Host "  - $r" }
}
Write-Host "==============================================="
