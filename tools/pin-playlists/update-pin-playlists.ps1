param(
    [switch]$ApplyAndVerify,
    [switch]$PromoteBaseline,
    [switch]$SimulateRename,
    [switch]$ForceDecompile,
    [string]$Apk,
    [string]$OfficialMpp,
    [string]$PatcherJar,
    [string]$StateRoot = "C:\Projects\Patch Updaters\Pin Playlists",
    [string]$JadxRoot = "C:\Projects\JADX",
    [string]$DecompileRoot = "C:\Projects\YouTube Music\Decompiled"
)

$ErrorActionPreference = "Stop"

$python = Get-Command python -ErrorAction Stop
$detector = Join-Path $PSScriptRoot "ytmusic-pin-compat.py"

if (-not (Test-Path -LiteralPath $detector -PathType Leaf)) {
    throw "Compatibility detector is missing: $detector"
}

if ($ApplyAndVerify -and $SimulateRename) {
    throw "-ApplyAndVerify cannot be combined with -SimulateRename."
}

$arguments = [System.Collections.Generic.List[string]]::new()
$arguments.Add($detector)

$arguments.Add("--state-root")
$arguments.Add([IO.Path]::GetFullPath($StateRoot))

$arguments.Add("--jadx-root")
$arguments.Add([IO.Path]::GetFullPath($JadxRoot))

$arguments.Add("--decompile-root")
$arguments.Add([IO.Path]::GetFullPath($DecompileRoot))

if (-not [string]::IsNullOrWhiteSpace($Apk)) {
    $arguments.Add("--apk")
    $arguments.Add([IO.Path]::GetFullPath($Apk))
}

if ($SimulateRename) {
    $arguments.Add("--simulate-rename")
}

if ($ApplyAndVerify) {
    $arguments.Add("--apply")
    $arguments.Add("--verify")
}

if ($PromoteBaseline) {
    $arguments.Add("--promote-baseline")
}

if ($ForceDecompile) {
    $arguments.Add("--force-decompile")
}

if (-not [string]::IsNullOrWhiteSpace($OfficialMpp)) {
    $arguments.Add("--official-mpp")
    $arguments.Add([IO.Path]::GetFullPath($OfficialMpp))
}

if (-not [string]::IsNullOrWhiteSpace($PatcherJar)) {
    $arguments.Add("--patcher-jar")
    $arguments.Add([IO.Path]::GetFullPath($PatcherJar))
}

Write-Host ""
Write-Host "Running Pin playlists updater..."
& $python.Source @arguments
$exitCode = $LASTEXITCODE

if ($exitCode -ne 0) {
    throw "Updater exited with code $exitCode."
}