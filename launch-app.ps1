function Get-LaunchClasspath {
    $targetDir = Join-Path $PSScriptRoot 'target\classes'
    if (-not (Test-Path $targetDir)) {
        throw 'target\classes not found. Build first with mvnw package -DskipTests.'
    }
    $libDir = Join-Path $PSScriptRoot 'target\lib'
    $entries = @($targetDir)
    if (Test-Path $libDir) {
        $entries += (Get-ChildItem -Path $libDir -Filter *.jar -File | ForEach-Object { $_.FullName })
    }
    return ($entries -join ';')
}

# Pick a java/javaw executable. Prefer javaw (no console) from PATH, then the
# Microsoft JDK path that this environment uses, then java from PATH.
$candidates = @(
    'javaw',
    'C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot\bin\javaw.exe',
    'java',
    'C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot\bin\java.exe'
)
$javaExe = $null
foreach ($c in $candidates) {
    if ([System.IO.File]::Exists($c)) {
        $javaExe = $c
        break
    }
    if ($null -eq $javaExe -and (Get-Command $c -ErrorAction SilentlyContinue)) {
        $javaExe = $c
        break
    }
}
if (-not $javaExe) {
    throw 'No java/javaw found on PATH and no known JDK path present.'
}

$cp = Get-LaunchClasspath
$appClass = 'com.example.fhirviewer.Main'
Write-Host "Launching FHIRViewer (Main=$appClass)"
Write-Host "  java/javaw = $javaExe"
Write-Host "  target/lib  = $((Get-ChildItem (Join-Path $PSScriptRoot target\lib) -Filter *.jar -File).Count) jars"

$env:FP_NO_CONSOLE_LAUNCH = '1'
Start-Process -FilePath $javaExe -ArgumentList ('-cp', $cp, $appClass) -NoNewWindow:$false -PassThru
