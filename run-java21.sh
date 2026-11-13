#!/usr/bin/env bash
# Builds and runs FHIRViewer with JDK 21, regardless of the JAVA_HOME / PATH
# the calling shell has.
#
# Usage:
#   ./run-java21.sh                 clean build (skip tests) and launch the app
#   ./run-java21.sh test            run the Maven test suite
#   ./run-java21.sh launch [args]   launch without rebuilding
#   ./run-java21.sh build           clean build only (skip tests)
#
# Override the JDK with: JDK21_HOME=/path/to/jdk ./run-java21.sh

set -euo pipefail

PROJECT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT"

# --- Locate a JDK 21 ------------------------------------------------------
find_jdk21() {
    if [[ -n "${JDK21_HOME:-}" ]]; then
        echo "$JDK21_HOME"; return
    fi
    if [[ -x /usr/libexec/java_home ]] && /usr/libexec/java_home -v 21 >/dev/null 2>&1; then
        /usr/libexec/java_home -v 21; return
    fi
    local d
    for d in "$HOME"/.sdkman/candidates/java/21* \
             /usr/lib/jvm/*21* \
             /Library/Java/JavaVirtualMachines/*21*/Contents/Home; do
        [[ -x "$d/bin/java" ]] && { echo "$d"; return; }
    done
    return 1
}

if ! JAVA_HOME="$(find_jdk21)"; then
    echo "ERROR: no JDK 21 found. Install one or set JDK21_HOME." >&2
    exit 1
fi
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

if ! "$JAVA_HOME/bin/java" -version 2>&1 | grep -qE 'version "21[."]'; then
    echo "ERROR: $JAVA_HOME is not a JDK 21:" >&2
    "$JAVA_HOME/bin/java" -version >&2
    exit 1
fi

echo "Using JAVA_HOME=$JAVA_HOME"
"$JAVA_HOME/bin/java" -version 2>&1 | head -1

# --- Run ------------------------------------------------------------------
cmd="${1:-run}"
[[ $# -gt 0 ]] && shift

case "$cmd" in
    test)   exec ./mvnw test "$@" ;;
    build)  exec ./mvnw clean package -DskipTests "$@" ;;
    launch) exec ./launch-app.sh "$@" ;;
    run)    ./mvnw clean package -DskipTests && exec ./launch-app.sh "$@" ;;
    *)      echo "Unknown command: $cmd (use run | build | test | launch)" >&2; exit 2 ;;
esac
