param(
    [ValidateSet('Auto','Required','Off')][string]$Policy = 'Auto',
    [ValidateRange(0,63)][int]$ServerCcd = 0,
    [ValidateRange(0,63)][int]$ClientCcd = 1
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'cpu-isolation.ps1')
New-SmokeCpuPlan -Policy $Policy -ServerCcd $ServerCcd -ClientCcd $ClientCcd | ConvertTo-Json -Depth 6 -Compress
