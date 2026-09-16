# Launch FHIRViewer directly with java.exe (no Maven).
# Uses --module-path for the JavaFX platform-specific win JARs (required for JavaFX 11+)
# plus a classpath for all other runtime dependencies.
$ErrorActionPreference = 'Stop'

$project = 'C:\Development\FHIRViewer'
$targetDir = Join-Path $project 'target\classes'
$libDir = Join-Path $project 'target\lib'

if (-not (Test-Path $targetDir)) {
    throw 'target\classes missing - build first with mvnw package -DskipTests.'
}

# JavaFX platform-specific win JARs (contain native DLLs).
$jfxModules = @(
    (Join-Path $libDir 'javafx-base-25.0.4-win.jar'),
    (Join-Path $libDir 'javafx-controls-25.0.4-win.jar'),
    (Join-Path $libDir 'javafx-graphics-25.0.4-win.jar')
)
$jfxModulePath = $jfxModules -join ';'

# Full classpath: target/classes + every runtime dep jar.
$cpParts = @($targetDir)
if (Test-Path $libDir) {
    $cpParts += (Get-ChildItem -Path $libDir -Filter '*.jar' -File).FullName
}
$cp = $cpParts -join ';'

$jdkHome = 'C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot\bin\java.exe'
$javaExe = if (Test-Path $jdkHome) { $jdkHome } else { 'java' }

# Each JVM arg must be a separate element so Java sees them correctly.
$jvmArgs = @(
    '--add-opens', 'java.base/java.lang=ALL-UNNAMED',
    '--add-opens', 'javafx.graphics/com.sun.javafx.application=ALL-UNNAMED'
)

Write-Host 'Launching FHIRViewer directly with java.exe (no Maven)...'
Write-Host "  java      = $javaExe"
Write-Host "  module-path = $jfxModulePath"
Write-Host "  classpath  = $cp"

& $javaExe @jvmArgs --module-path $jfxModulePath --add-modules javafx.controls,javafx.graphics,javafx.base -cp $cp com.example.fhirviewer.Main
