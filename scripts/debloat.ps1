# Disables (or with -Undo, enables again) the apps listed in scripts\debloat\<brand>.txt on the TV adb is
# connected to. Usage: scripts\debloat.ps1 <brand> [-Undo] [-Skip group,group] [-Serial host:port]
param(
    [Parameter(Mandatory = $true)][string]$Brand,
    [switch]$Undo,
    [string]$Skip = "",
    [string]$Serial = ""
)
$list = Join-Path $PSScriptRoot "debloat\$Brand.txt"
if (-not (Test-Path $list)) {
    $brands = (Get-ChildItem (Join-Path $PSScriptRoot "debloat") -Filter *.txt | ForEach-Object { $_.BaseName }) -join "|"
    Write-Error "usage: debloat.ps1 <$brands> [-Undo] [-Skip group,group] [-Serial host:port]"
    exit 1
}
$skipped = $Skip -split ","
$adb = @()
if ($Serial) { $adb = @("-s", $Serial) }
$group = ""
foreach ($raw in Get-Content $list) {
    $line = $raw.Trim()
    if ($line -eq "" -or $line.StartsWith("#")) { continue }
    if ($line -match '^\[(.+)\]$') { $group = $Matches[1]; continue }
    if ($skipped -contains $group) { continue }
    if ($Undo) {
        $out = & adb @adb shell pm enable $line 2>&1 | Select-Object -Last 1
    } else {
        $out = & adb @adb shell pm disable-user --user 0 $line 2>&1 | Select-Object -Last 1
    }
    "{0,-20} {1,-45} {2}" -f $group, $line, $out
}
