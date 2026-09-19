param(
    [Parameter(Mandatory=$true)][string]$PluginJar,
    [Parameter(Mandatory=$true)][string]$PaperLibraries,
    [string]$OutputDirectory = ".runtime/benchmark-classes"
)
$ErrorActionPreference = 'Stop'
$libraries = Get-ChildItem -LiteralPath $PaperLibraries -Recurse -Filter '*.jar' | ForEach-Object FullName
$classpath = (@((Resolve-Path -LiteralPath $PluginJar).Path) + $libraries) -join ';'
New-Item -ItemType Directory -Force $OutputDirectory | Out-Null
javac -cp $classpath -d $OutputDirectory benchmarks/server/BenchmarkPlugin.java
if ($LASTEXITCODE -ne 0) { throw 'Benchmark compilation failed' }
Copy-Item -LiteralPath benchmarks/server/plugin.yml -Destination (Join-Path $OutputDirectory plugin.yml)
jar --create --file .runtime/mud-benchmark.jar -C $OutputDirectory .
if ($LASTEXITCODE -ne 0) { throw 'Benchmark packaging failed' }
