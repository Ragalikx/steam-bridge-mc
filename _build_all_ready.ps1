$ErrorActionPreference = 'Continue'
$ready = 'C:\Users\ragal\Documents\ready'
New-Item -ItemType Directory -Force -Path $ready | Out-Null

$j8 = 'C:\Program Files\Eclipse Adoptium\jdk-8.0.492.9-hotspot'
$jModern = 'C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.2\jbr'

$builds = @(
  @{ branch='Forge-1.7.10';     java=$j8;      jarName='steambridge-1.2-Forge-1.7.10.jar';     task='jar' },
  @{ branch='Forge-1.8.9';      java=$j8;      jarName='steambridge-1.2-Forge-1.8.9.jar';      task='jar' },
  @{ branch='Forge-1.12.2';     java=$j8;      jarName='steambridge-1.2-Forge-1.12.2.jar';     task='jar' },
  @{ branch='Forge-1.16.5';     java=$jModern; jarName='steambridge-1.2-Forge-1.16.5.jar';     task='jar' },
  @{ branch='Forge-1.19.2';     java=$jModern; jarName='steambridge-1.2-Forge-1.19.2.jar';     task='jar' },
  @{ branch='Fabric-1.16.5';    java=$jModern; jarName='steambridge-1.2-Fabric-1.16.5.jar';    task='remapJar' },
  @{ branch='Fabric-1.19.2';    java=$jModern; jarName='steambridge-1.2-Fabric-1.19.2.jar';    task='remapJar' },
  @{ branch='Fabric-1.20.1';    java=$jModern; jarName='steambridge-1.2-Fabric-1.20.1.jar';    task='remapJar' },
  @{ branch='Fabric-1.21.1';    java=$jModern; jarName='steambridge-1.2-Fabric-1.21.1.jar';    task='remapJar' },
  @{ branch='NeoForge-1.20.1';  java=$jModern; jarName='steambridge-1.2-NeoForge-1.20.1.jar';  task='jar' },
  @{ branch='NeoForge-1.21.1';  java=$jModern; jarName='steambridge-1.2-NeoForge-1.21.1.jar';  task='jar' }
)

$results = @()
foreach ($b in $builds) {
  Write-Host "`n======== BUILD $($b.branch) ========"
  git checkout $b.branch 2>&1 | Out-Null
  $env:JAVA_HOME = $b.java
  $env:Path = "$($b.java)\bin;" + ($env:Path -replace [regex]::Escape($j8 + '\bin;'), '' -replace [regex]::Escape($jModern + '\bin;'), '')
  & "$($b.java)\bin\java.exe" -version 2>&1 | Select-Object -First 1

  # stop daemons between JDK switches
  .\gradlew.bat --stop 2>&1 | Out-Null

  $task = $b.task
  # try remapJar then jar for fabric
  $out = & .\gradlew.bat $task "-PmodVersion=1.2" --no-daemon 2>&1 | Out-String
  if ($LASTEXITCODE -ne 0 -and $task -eq 'remapJar') {
    Write-Host "remapJar failed, trying jar..."
    $out = & .\gradlew.bat jar "-PmodVersion=1.2" --no-daemon 2>&1 | Out-String
  }
  $ok = $out -match 'BUILD SUCCESSFUL'
  Write-Host ($out | Select-String -Pattern 'BUILD SUCCESSFUL|BUILD FAILED|error:|What went wrong' | Select-Object -Last 8)

  # find built jar
  $candidates = @()
  $candidates += Get-ChildItem -Path build\libs -Filter '*.jar' -ErrorAction SilentlyContinue
  $candidates += Get-ChildItem -Path build\libs -Filter '*steambridge*.jar' -ErrorAction SilentlyContinue
  $jar = $candidates | Where-Object { $_.Name -notmatch 'sources|dev|javadoc|slim' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if (-not $jar) {
    $jar = Get-ChildItem -Recurse build -Filter 'steambridge*.jar' -ErrorAction SilentlyContinue |
      Where-Object { $_.Name -notmatch 'sources|dev|javadoc' -and $_.FullName -match 'libs|remap' } |
      Sort-Object LastWriteTime -Descending | Select-Object -First 1
  }

  if ($ok -and $jar) {
    $dest = Join-Path $ready $b.jarName
    Copy-Item $jar.FullName $dest -Force
    $hash = (Get-FileHash $dest -Algorithm SHA256).Hash.ToLower()
    Write-Host "OK $($b.jarName) sha256=$hash size=$($jar.Length)"
    $results += [pscustomobject]@{ Branch=$b.branch; File=$b.jarName; SHA256=$hash; Bytes=$jar.Length; Status='OK' }
  } else {
    Write-Host "FAIL $($b.branch)"
    $results += [pscustomobject]@{ Branch=$b.branch; File=$b.jarName; SHA256=''; Bytes=0; Status='FAIL' }
  }
}

# SHA256SUMS
$sums = Join-Path $ready 'SHA256SUMS.txt'
$lines = @()
foreach ($r in $results) {
  if ($r.Status -eq 'OK') {
    $lines += "$($r.SHA256)  $($r.File)"
  }
}
$lines | Set-Content -Path $sums -Encoding UTF8

# manifest
$man = Join-Path $ready 'manifest.txt'
@(
  "Steam Bridge build artifacts",
  "Date: $(Get-Date -Format o)",
  "Mod version: 1.2",
  ""
) + ($results | ForEach-Object { "$($_.Status)  $($_.File)  $($_.SHA256)  $($_.Bytes)" }) |
  Set-Content -Path $man -Encoding UTF8

Write-Host "`n==== SUMMARY ===="
$results | Format-Table -AutoSize
Write-Host "Written: $sums"
git checkout NeoForge-1.21.1 2>&1 | Out-Null
