@echo off
rem Launches FHIRViewer from a Maven-built project tree.
rem
rem Prerequisites:
rem   - Run this from the project root.
rem   - Build the runtime artifacts first, for example:
rem       mvnw package -DskipTests
rem   - This script expects target/classes and target/lib to exist.

setlocal enabledelayedexpansion

set "PROJECT=%~dp0"

rem --- Find java/javaw --------------------------------------------------
set "JAVA_EXE="
where javaw >nul 2>&1 && set "JAVA_EXE=javaw" || set "JAVA_EXE=java"
if not defined JAVA_EXE (
    where java >nul 2>&1 && set "JAVA_EXE=java"
)
if not defined JAVA_EXE (
    set "JAVA_EXE=%JAVA_HOME%\bin\javaw.exe"
)
if not defined JAVA_EXE (
    set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
)
if not defined JAVA_EXE (
    echo ERROR: no java/javaw found on PATH or JAVA_HOME. >&2
    exit /b 1
)

rem --- Verify runtime artifacts -----------------------------------------
if not exist "%PROJECT%target\classes" (
    echo ERROR: target\classes not found - run 'mvnw package -DskipTests' first. >&2
    exit /b 1
)
if not exist "%PROJECT%target\lib\javafx-base-25.0.4-win.jar" (
    echo ERROR: Maven JavaFX runtime not found at target\lib\javafx-base-25.0.4-win.jar. >&2
    echo        Run 'mvnw package -DskipTests' first. >&2
    exit /b 1
)

rem --- Build classpath and JavaFX module path ---------------------------
set "CP=%PROJECT%target\classes"
for %%F in ("%PROJECT%target\lib\*.jar") do set "CP=!CP!;%%F"

set "JFX_MODULES=%PROJECT%target\lib\javafx-base-25.0.4-win.jar"
set "JFX_MODULES=!JFX_MODULES!;%PROJECT%target\lib\javafx-controls-25.0.4-win.jar"
set "JFX_MODULES=!JFX_MODULES!;%PROJECT%target\lib\javafx-graphics-25.0.4-win.jar"

set "JVM_OPTS=--add-opens java.base/java.lang=ALL-UNNAMED --add-opens javafx.graphics/com.sun.javafx.application=ALL-UNNAMED"

echo Launching FHIRViewer (Maven runtime)...
echo   java   = %JAVA_EXE%
echo   module-path = !JFX_MODULES!

start "FHIRViewer" "%JAVA_EXE%" %JVM_OPTS% --module-path "!JFX_MODULES!" --add-modules javafx.controls,javafx.graphics,javafx.base -cp "!CP!" com.example.fhirviewer.Main

exit /b 0
