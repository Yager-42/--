param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Phone = "",
    [string]$Password = "",
    [string]$PostId = "1",
    [string]$TargetUserId = "2",
    [switch]$SkipLogin
)

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$K6Exe = Join-Path $Root "..\..\.toolcache\k6\k6-v1.7.0-windows-amd64\k6.exe"
$Scenario = Join-Path $Root "k6\scenarios\nexus-high-concurrency.js"
$OutDir = Join-Path $Root "output"
$SummaryJson = Join-Path $OutDir ("summary-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".json")

if (!(Test-Path $OutDir)) {
    New-Item -Path $OutDir -ItemType Directory | Out-Null
}

if (!(Test-Path $K6Exe)) {
    throw "k6 not found: $K6Exe"
}
if (!(Test-Path $Scenario)) {
    throw "scenario not found: $Scenario"
}

$token = $env:TOKEN

if (-not $SkipLogin) {
    if ([string]::IsNullOrWhiteSpace($Phone) -or [string]::IsNullOrWhiteSpace($Password)) {
        throw "Phone and Password are required unless -SkipLogin is set."
    }

    $loginBody = @{
        phone    = $Phone
        password = $Password
    } | ConvertTo-Json

    Write-Host "[INFO] Login: $BaseUrl/api/v1/auth/login/password"
    $loginResp = Invoke-RestMethod -Method Post `
        -Uri "$BaseUrl/api/v1/auth/login/password" `
        -ContentType "application/json" `
        -Body $loginBody

    if ($null -eq $loginResp -or $loginResp.code -ne "0000") {
        throw "Login failed. code=$($loginResp.code), info=$($loginResp.info)"
    }

    $prefix = [string]$loginResp.data.tokenPrefix
    $rawToken = [string]$loginResp.data.token
    if ([string]::IsNullOrWhiteSpace($rawToken)) {
        throw "Login succeeded but token is empty."
    }
    if ([string]::IsNullOrWhiteSpace($prefix)) {
        $token = $rawToken
    } else {
        $token = "$prefix $rawToken"
    }
}

if ([string]::IsNullOrWhiteSpace($token)) {
    throw "TOKEN is empty. Use login params or set TOKEN env."
}

$env:BASE_URL = $BaseUrl
$env:TOKEN = $token
$env:POST_ID = $PostId
$env:TARGET_USER_ID = $TargetUserId

Write-Host "[INFO] Running k6 scenario: $Scenario"
& $K6Exe run $Scenario --summary-export $SummaryJson --summary-trend-stats "avg,min,med,max,p(90),p(95),p(99)"
$k6ExitCode = $LASTEXITCODE
if ($k6ExitCode -ne 0 -and $k6ExitCode -ne 99) {
    throw "k6 run failed with exit code $k6ExitCode"
}
if ($k6ExitCode -eq 99) {
    Write-Warning "k6 thresholds were crossed (exit code 99). Exported summary will still be analyzed."
}

$summary = Get-Content -Raw -Path $SummaryJson | ConvertFrom-Json

function Get-MetricValue($obj, $path, $default) {
    try {
        $parts = $path.Split(".")
        $cursor = $obj
        foreach ($part in $parts) {
            $cursor = $cursor.$part
            if ($null -eq $cursor) { return $default }
        }
        return $cursor
    } catch {
        return $default
    }
}

function Get-MetricNumber($summaryObj, $metricName, $fieldName, $default = 0) {
    $metricObj = Get-MetricValue $summaryObj "metrics.$metricName" $null
    if ($null -eq $metricObj) { return [double]$default }

    # k6 (new): metrics.<name>.<field>
    $direct = Get-MetricValue $summaryObj "metrics.$metricName.$fieldName" $null
    if ($null -ne $direct -and "$direct" -ne "") { return [double]$direct }

    # k6 (old): metrics.<name>.values.<field>
    $legacy = Get-MetricValue $summaryObj "metrics.$metricName.values.$fieldName" $null
    if ($null -ne $legacy -and "$legacy" -ne "") { return [double]$legacy }

    return [double]$default
}

function Get-EndpointStats($summaryObj, $testDurationSec, $name, $totalMetric, $successMetric) {
    $total = [double](Get-MetricNumber $summaryObj $totalMetric "count" 0)
    $success = [double](Get-MetricNumber $summaryObj $successMetric "count" 0)
    $fail = [math]::Max(0, $total - $success)
    $qps = [math]::Round($total / $testDurationSec, 2)
    $tps = [math]::Round($success / $testDurationSec, 2)
    $successRate = if ($total -gt 0) { [math]::Round(($success / $total) * 100, 2) } else { 0 }
    return [ordered]@{
        name = $name
        total = [int64]$total
        success = [int64]$success
        fail = [int64]$fail
        qps = $qps
        tps = $tps
        successRatePct = $successRate
    }
}

$testDurationSec = [double](Get-MetricValue $summary "state.testRunDurationMs" 0) / 1000.0

$httpReqs = [double](Get-MetricNumber $summary "http_reqs" "count" 0)
$httpReqRate = [double](Get-MetricNumber $summary "http_reqs" "rate" 0)
$bizSuccessCount = [double](Get-MetricNumber $summary "biz_success_count" "count" 0)
$bizFailureCount = [double](Get-MetricNumber $summary "biz_failure_count" "count" 0)

if ($testDurationSec -le 0 -and $httpReqs -gt 0 -and $httpReqRate -gt 0) {
    $testDurationSec = $httpReqs / $httpReqRate
}
if ($testDurationSec -le 0) { $testDurationSec = 1 }

$qps = [math]::Round($httpReqs / $testDurationSec, 2)
$tps = [math]::Round($bizSuccessCount / $testDurationSec, 2)
$errorRate = [math]::Round((([double](Get-MetricNumber $summary "http_req_failed" "value" 0)) * 100), 2)
$bizSuccessRate = [math]::Round((([double](Get-MetricNumber $summary "biz_success_rate" "value" 0)) * 100), 2)
$p95 = [math]::Round([double](Get-MetricNumber $summary "http_req_duration" "p(95)" 0), 2)
$p99 = [math]::Round([double](Get-MetricNumber $summary "http_req_duration" "p(99)" 0), 2)
$waitingP95 = [math]::Round([double](Get-MetricNumber $summary "http_req_waiting" "p(95)" 0), 2)
$waitingP99 = [math]::Round([double](Get-MetricNumber $summary "http_req_waiting" "p(99)" 0), 2)
$droppedIterations = [int64](Get-MetricNumber $summary "dropped_iterations" "count" 0)

$endpointStats = @(
    Get-EndpointStats $summary $testDurationSec "feed_timeline" "endpoint_feed_total_count" "endpoint_feed_success_count"
    Get-EndpointStats $summary $testDurationSec "interact_reaction" "endpoint_reaction_total_count" "endpoint_reaction_success_count"
    Get-EndpointStats $summary $testDurationSec "relation_follow" "endpoint_follow_total_count" "endpoint_follow_success_count"
    Get-EndpointStats $summary $testDurationSec "relation_unfollow" "endpoint_unfollow_total_count" "endpoint_unfollow_success_count"
    Get-EndpointStats $summary $testDurationSec "search" "endpoint_search_total_count" "endpoint_search_success_count"
)

$result = [ordered]@{
    baseUrl         = $BaseUrl
    summaryFile     = $SummaryJson
    durationSec     = [math]::Round($testDurationSec, 2)
    totalHttpReqs   = [int64]$httpReqs
    totalBizSuccess = [int64]$bizSuccessCount
    totalBizFailure = [int64]$bizFailureCount
    qps             = $qps
    tps             = $tps
    k6ExitCode      = $k6ExitCode
    httpErrorRatePct = $errorRate
    bizSuccessRatePct = $bizSuccessRate
    p95Ms           = $p95
    p99Ms           = $p99
    waitingP95Ms    = $waitingP95
    waitingP99Ms    = $waitingP99
    droppedIterations = $droppedIterations
    endpointBreakdown = $endpointStats
}

$resultJson = ($result | ConvertTo-Json -Depth 5)
$resultFile = [System.IO.Path]::ChangeExtension($SummaryJson, ".report.json")
$resultJson | Out-File -Encoding utf8 -FilePath $resultFile

Write-Host ""
Write-Host "========== Load Test Summary =========="
Write-Host "Base URL:           $($result.baseUrl)"
Write-Host "Duration(s):        $($result.durationSec)"
Write-Host "Total HTTP Reqs:    $($result.totalHttpReqs)"
Write-Host "Business Success:   $($result.totalBizSuccess)"
Write-Host "Business Failure:   $($result.totalBizFailure)"
Write-Host "QPS(req/s):         $($result.qps)"
Write-Host "TPS(success tx/s):  $($result.tps)"
Write-Host "k6 Exit Code:       $($result.k6ExitCode)"
Write-Host "HTTP Error Rate(%): $($result.httpErrorRatePct)"
Write-Host "Biz Success Rate(%):$($result.bizSuccessRatePct)"
Write-Host "P95(ms):            $($result.p95Ms)"
Write-Host "P99(ms):            $($result.p99Ms)"
Write-Host "Waiting P95(ms):    $($result.waitingP95Ms)"
Write-Host "Waiting P99(ms):    $($result.waitingP99Ms)"
Write-Host "Dropped Iterations: $($result.droppedIterations)"
Write-Host "Summary JSON:       $SummaryJson"
Write-Host "Report JSON:        $resultFile"
Write-Host ""
Write-Host "----- Endpoint Breakdown (QPS/TPS) -----"
Write-Host "name                total    success  fail   qps     tps     successRate%"
foreach ($s in $endpointStats) {
    $line = "{0,-18} {1,8} {2,8} {3,6} {4,7} {5,7} {6,12}" -f `
        $s.name, $s.total, $s.success, $s.fail, $s.qps, $s.tps, $s.successRatePct
    Write-Host $line
}
Write-Host "----------------------------------------"
Write-Host "======================================="
