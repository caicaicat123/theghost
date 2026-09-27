# 离线自测：先编译插件，再编译并运行同包的 SelfTest（详见本目录 README.md）
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$jdk = 'C:\Program Files\Java\jdk-21\bin'
$out = Join-Path $root 'build\selftest'
$lib = Join-Path $root 'lib\compile'

if (-not (Test-Path (Join-Path $lib 'paper-api.jar'))) {
    throw "缺少编译依赖，请先运行: node tools\fetch-libs.cjs"
}

& (Join-Path $root 'build.ps1')

Remove-Item -LiteralPath $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $out | Out-Null

$classpath = ((Get-ChildItem "$lib\*.jar" | ForEach-Object { $_.FullName }) + (Join-Path $root 'build\classes')) -join ';'
& "$jdk\javac.exe" -encoding UTF-8 --release 21 -cp $classpath -d $out (Join-Path $PSScriptRoot 'SelfTest.java')
if ($LASTEXITCODE -ne 0) { throw '自测编译失败' }

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
& "$jdk\java.exe" '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' -cp "$out;$classpath" mcbot.SelfTest
if ($LASTEXITCODE -ne 0) { throw '自测未全部通过' }
