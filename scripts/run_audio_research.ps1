param(
    [Parameter(Mandatory=$true)][string]$ClientJar,
    [Parameter(Mandatory=$true)][string]$Ffmpeg,
    [string]$MinecraftDirectory = "$env:APPDATA/.minecraft",
    [string]$OutputDirectory = '.runtime/audio-probe'
)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$client=(Resolve-Path -LiteralPath $ClientJar).Path
if((Get-FileHash -LiteralPath $client).Hash -ne 'EA74E9C5E92D01F95F3D39196DDB734E19A077A58B4C433C34109487D600276F') {
    throw 'This probe requires the original Minecraft Java 1.21.8 client JAR.'
}
$metadata=Get-Content -Raw -LiteralPath (Join-Path $MinecraftDirectory 'versions/1.21.8/1.21.8.json') | ConvertFrom-Json
$libraries=@($metadata.libraries | Where-Object { $_.name -notmatch ':natives-' -or $_.name -match ':natives-windows$' } |
    ForEach-Object { Join-Path $MinecraftDirectory ('libraries/'+$_.downloads.artifact.path) } | Where-Object { Test-Path -LiteralPath $_ })
$classpath=(@($client)+$libraries) -join ';'
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$out=(Resolve-Path -LiteralPath $OutputDirectory).Path
& $Ffmpeg -hide_banner -loglevel error -y -f lavfi -i 'aevalsrc=0.25*sin(2*PI*(300*t+50*t*t)):s=48000:d=8' -ac 1 -c:a libvorbis -q:a 4 "$out/mono.ogg"
if($LASTEXITCODE -ne 0){throw 'Mono sample generation failed'}
& $Ffmpeg -hide_banner -loglevel error -y -i "$out/mono.ogg" -ac 2 -c:a libvorbis -q:a 4 "$out/stereo.ogg"
if($LASTEXITCODE -ne 0){throw 'Stereo sample generation failed'}
& $Ffmpeg -hide_banner -loglevel error -y -i "$out/mono.ogg" -c:a copy -metadata LOOPSTART=144000 -metadata START=3 -metadata offset=3 -metadata start_offset=3 "$out/tagged.ogg"
if($LASTEXITCODE -ne 0){throw 'Metadata sample generation failed'}
& javac -cp $classpath -d "$out/classes" "$root/benchmarks/audio-research/SilentPlaybackProbe.java" "$root/benchmarks/audio-research/AudioMetadataProbe.java"
if($LASTEXITCODE -ne 0){throw 'Audio probe compilation failed'}
Push-Location -LiteralPath $out
try {
    & java --enable-native-access=ALL-UNNAMED -cp "$out/classes;$classpath" probe.SilentPlaybackProbe "$out/mono.ogg" "$out/stereo.ogg" "$out/silent-playback-result.json"
    if($LASTEXITCODE -ne 0){throw 'Silent playback probe failed'}
    & java --enable-native-access=ALL-UNNAMED -cp "$out/classes;$classpath" probe.AudioMetadataProbe "$out/mono.ogg" "$out/tagged.ogg"
    if($LASTEXITCODE -ne 0){throw 'Metadata probe failed'}
} finally {Pop-Location}
