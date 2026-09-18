# 编译 McBot：javac -> jar。产物版本号从 plugin.yml 读。
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$jdk = 'C:\Program Files\Java\jdk-21\bin'
$lib = Join-Path $root 'lib\compile'
$classes = Join-Path $root 'build\classes'
$dist = Join-Path $root 'dist'

if (-not (Test-Path (Join-Path $lib 'paper-api.jar'))) {
    throw "缺少编译依赖，请先运行: node tools\fetch-libs.cjs"
}

Remove-Item -LiteralPath $classes -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $classes, $dist | Out-Null

$classpath = (Get-ChildItem "$lib\*.jar" | ForEach-Object { $_.FullName }) -join ';'
$sources = Get-ChildItem (Join-Path $root 'src') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName }

& "$jdk\javac.exe" -encoding UTF-8 --release 21 -cp $classpath -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw '编译失败' }

Copy-Item (Join-Path $root 'plugin.yml') $classes -Force
Copy-Item (Join-Path $root 'config.yml') $classes -Force

$version = (Select-String -Path (Join-Path $root 'plugin.yml') -Pattern '^version:\s*(\S+)\s*$').Matches[0].Groups[1].Value
$jar = Join-Path $dist ("mcbot-" + $version + ".jar")
& "$jdk\jar.exe" --create --file $jar -C $classes .
Write-Output ("已生成: " + $jar + "  (" + [math]::Round((Get-Item $jar).Length / 1KB, 1) + " KB)")
