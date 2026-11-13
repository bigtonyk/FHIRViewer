#!/usr/bin/env bash
# Launches FHIRViewer from a Maven-built project tree (macOS / Linux).
#
# Prerequisites:
#   - Build the runtime artifacts first, for example:
#       ./mvnw package -DskipTests
#   - This script expects target/classes and target/lib to exist.
#
# Any arguments are passed through to the application, for example:
#   ./launch-app.sh /path/to/patient.json

set -euo pipefail

PROJECT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# --- Find java ----------------------------------------------------------
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
    JAVA_EXE="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
    JAVA_EXE="java"
else
    echo "ERROR: no java found on JAVA_HOME or PATH." >&2
    exit 1
fi

# --- Verify runtime artifacts -------------------------------------------
if [[ ! -d "$PROJECT/target/classes" ]]; then
    echo "ERROR: target/classes not found - run './mvnw package -DskipTests' first." >&2
    exit 1
fi
if ! compgen -G "$PROJECT/target/lib/javafx-graphics-*.jar" >/dev/null; then
    echo "ERROR: Maven JavaFX runtime not found in target/lib." >&2
    echo "       Run './mvnw package -DskipTests' first." >&2
    exit 1
fi

# --- Launch ---------------------------------------------------------------
# JavaFX is on the class path here, so start Launcher rather than Main
# (Main extends Application and would fail with "JavaFX runtime components are missing").
CP="$PROJECT/target/classes:$PROJECT/target/lib/*"

echo "Launching FHIRViewer (Maven runtime)..."
echo "  java = $JAVA_EXE"

exec "$JAVA_EXE" --add-opens java.base/java.lang=ALL-UNNAMED \
    -cp "$CP" com.spiraleyes.fhirviewer.Launcher "$@"
