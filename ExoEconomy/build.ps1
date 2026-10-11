# Builds ExoEconomy against your real server jars (same approach as ExoSkills' build.ps1),
# runs the unit tests and prints the jar hash.
#   .\build.ps1 -ServerDirectory C:\path\to\server
param(
  [string]$ServerDirectory = 'C:\Users\dortw\OneDrive\Dators\dogo',
  [string]$JavaHome = 'C:\Program Files\Java\jdk-25.0.2',
  [string]$ExoSkillsJar = (Join-Path $PSScriptRoot '..\ExoSkills\ExoSkills-1.0.1.jar'),
  [string]$Version = '1.0.0'
)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
Remove-Item -Recurse -Force "$root\build" -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$root\build\classes", "$root\build\test-classes" | Out-Null
# Paper, Adventure, SQLite JDBC and other server libraries.
$deps = @(Get-ChildItem -LiteralPath (Join-Path $ServerDirectory 'libraries') -Recurse -Filter '*.jar' | ForEach-Object FullName)
# ExcellentEconomy 2.8.x and NightCore (ExcellentEconomy's API exposes NightCore types), PlaceholderAPI if installed.
foreach ($pattern in 'ExcellentEconomy*.jar', 'nightcore*.jar', 'NightCore*.jar', 'PlaceholderAPI*.jar') {
  $deps += @(Get-ChildItem -LiteralPath (Join-Path $ServerDirectory 'plugins') -Filter $pattern | ForEach-Object FullName)
}
if (-not ($deps | Where-Object { $_ -like '*ExcellentEconomy*' })) { throw 'ExcellentEconomy jar not found in plugins' }
$cp = $deps -join ';'
$sources = @(Get-ChildItem "$root\src\main\java" -Recurse -Filter '*.java' | ForEach-Object FullName)
& "$JavaHome\bin\javac.exe" --release 25 -encoding UTF-8 -Xlint:all,-serial,-processing,-try -classpath $cp -d "$root\build\classes" @sources
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
Copy-Item "$root\src\main\resources\*" "$root\build\classes" -Force
(Get-Content "$root\build\classes\plugin.yml" -Raw).Replace('${version}', $Version) | Set-Content -NoNewline -Encoding UTF8 "$root\build\classes\plugin.yml"
& "$JavaHome\bin\jar.exe" --create --file "$root\ExoEconomy-$Version.jar" -C "$root\build\classes" .
if ($LASTEXITCODE -ne 0) { throw 'Packaging failed' }
$tests = @(Get-ChildItem "$root\src\test\java" -Recurse -Filter '*.java' | ForEach-Object FullName)
& "$JavaHome\bin\javac.exe" --release 25 -encoding UTF-8 -classpath "$root\build\classes;$cp" -d "$root\build\test-classes" @tests
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
& "$JavaHome\bin\java.exe" -ea --enable-native-access=ALL-UNNAMED -classpath "$root\build\classes;$root\build\test-classes;$cp" net.exoeconomy.CoreTests "$root\src\main\resources\prices.yml" $ExoSkillsJar
if ($LASTEXITCODE -ne 0) { throw 'Tests failed' }
Get-FileHash "$root\ExoEconomy-$Version.jar" -Algorithm SHA256
