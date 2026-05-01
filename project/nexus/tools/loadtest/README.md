# Nexus Loadtest Quick Start

## Installed k6 path

- `C:/Users/Administrator/Desktop/文档/project/nexus/.toolcache/k6/k6-v1.7.0-windows-amd64/k6.exe`

## One-click run

From PowerShell:

```powershell
cd C:\Users\Administrator\Desktop\文档\project\nexus
powershell -ExecutionPolicy Bypass -File .\tools\loadtest\run-loadtest.ps1 `
  -BaseUrl "http://localhost:8080" `
  -Phone "13800000001" `
  -Password "Pwd@123456" `
  -PostId "1" `
  -TargetUserId "2"
```

From CMD:

```cmd
cd /d C:\Users\Administrator\Desktop\文档\project\nexus\tools\loadtest
run-loadtest.cmd
```

`run-loadtest.cmd` now has built-in defaults:

- `BASE_URL=http://localhost:8080`
- `PHONE=13800000001`
- `PASSWORD=Pwd@123456`
- `POST_ID=1`
- `TARGET_USER_ID=2`

You can still override any value:

```cmd
run-loadtest.cmd http://localhost:8080 13800000001 Pwd@123456 1 2
```

## Output files

- k6 summary: `tools/loadtest/output/summary-*.json`
- final report: `tools/loadtest/output/summary-*.report.json`
- diagnose samples: `tools/loadtest/output/summary-*.samples.json`
- diagnose result: `tools/loadtest/output/summary-*.diagnosis.json`

The final report includes:

- `qps` (HTTP requests per second)
- `tps` (business-success transactions per second, `code == "0000"`)
- `httpErrorRatePct`
- `bizSuccessRatePct`
- `p95Ms` / `p99Ms`
- `endpointBreakdown` (separate stats per endpoint)

Endpoint breakdown currently includes:

- `feed_timeline`
- `interact_reaction`
- `relation_follow`
- `relation_unfollow`
- `search`

## Bottleneck diagnosis mode

Run with one command (includes loadtest + lightweight monitoring):

```cmd
cd /d C:\Users\Administrator\Desktop\鏂囨。\project\nexus\tools\loadtest
run-loadtest-diagnose.cmd
```

Parameters:

```cmd
run-loadtest-diagnose.cmd [baseUrl] [phone] [password] [postId] [targetUserId] [sampleIntervalSec] [backendPid]
```

Example:

```cmd
run-loadtest-diagnose.cmd http://localhost:8080 13800000001 Pwd@123456 1 2 2 12345
```

`backendPid` optional:
- `0` (default): auto-detect backend process
- `>0`: force use specified Java process PID for CPU/threads/GC sampling

Diagnosis output includes:

- loadtest metrics (QPS/TPS, p95/p99, error rate, endpoint breakdown)
- sampled host/app runtime metrics (CPU, memory, threads, GC)
- bottleneck findings and optimization suggestions

## Notes

- If your benchmark account is not ready, create/login data first.
- If you want to skip auto-login, set env `TOKEN` and run with `-SkipLogin`.
