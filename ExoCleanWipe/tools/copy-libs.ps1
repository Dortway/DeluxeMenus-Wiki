# copy-libs.ps1 - copies the server's plugin jars that ExoCleanWipe compiles against into libs\ (read-only on the server).
param([string]$Server = "C:\Users\dortw\OneDrive\Dators\dogo")

$ErrorActionPreference = "Stop"
$libs = Join-Path (Split-Path $PSScriptRoot -Parent) "libs"
New-Item -ItemType Directory -Force -Path $libs | Out-Null
$plugins = Join-Path $Server "plugins"

$wanted = @{
    "ExcellentEconomy-*.jar" = "ExcellentEconomy-2.8.0.jar"
    "nightcore-*.jar"        = "nightcore-2.16.4.jar"
    "EssentialsX-2*.jar"     = "EssentialsX-2.22.1.jar"
    "ESkyblock-*.jar"        = "ESkyblock-2.0.0.jar"
    "ExoVaults-*.jar"        = "ExoVaults-1.0.0.jar"
}
foreach ($pattern in $wanted.Keys) {
    $jar = Get-ChildItem $plugins -Filter $pattern -File | Select-Object -First 1
    if (-not $jar) { throw "No $pattern in $plugins" }
    Copy-Item -LiteralPath $jar.FullName -Destination (Join-Path $libs $wanted[$pattern]) -Force
    Write-Host "copied $($jar.Name)"
}
Write-Host "Done. Now run: mvn package"
