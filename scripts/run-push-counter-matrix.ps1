# Push counter matrix — missing cells only (pwsh 7)
$ErrorActionPreference = "Continue"
Set-Location $PSScriptRoot\..
$versions = @("1.20.1","1.21.1","1.21.2","1.21.3","1.21.4","1.21.5","1.21.6","1.21.7","1.21.8","1.21.9","1.21.10","1.21.11")
$loaders = @("fabric","forge","neoforge")
$resultsDir = "build\smoke-test\results"
foreach ($ver in $versions) {
  foreach ($loader in $loaders) {
    $sid = "${ver}_${loader}_I_push_counter"
    $rp = Join-Path $resultsDir "result_${sid}.json"
    if (Test-Path $rp) {
      try {
        $ro = Get-Content $rp -Raw | ConvertFrom-Json
        if ($ro.Result -eq "PASS" -or $ro.Result -eq "SKIP") { Write-Host "SKIP $($ro.Result): $sid"; continue }
      } catch {}
    }
    Write-Host "=== RUN $sid ===" -ForegroundColor Cyan
    & "$PSScriptRoot\runtime-smoke-test-batch.ps1" -Phase I -Versions $ver -Loaders $loader -SessionSuffix push_counter -MaxRetries 3 -DelayMs 20000 -ServerReadyTimeoutSec 300 -ClientTimeoutSec 600
  }
}
Write-Host "=== MATRIX DONE ===" -ForegroundColor Green
