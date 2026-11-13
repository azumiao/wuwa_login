#!/bin/bash
set -euo pipefail
cd -- "$(dirname -- "$0")"

# Keep the build result visible when opened from Finder.
finish() {
    result=$?
    if [ "$result" -ne 0 ]; then
        printf '\nBuild failed. Check the error above and run mvn -version (JDK 25 required).\n'
    fi
    if [ -t 0 ]; then
        read -r -p 'Press Enter to close...' || true
    fi
    exit "$result"
}
trap finish EXIT

if [ -z "${JAVA_HOME:-}" ] && [ "$(uname -s)" = Darwin ]; then
    export JAVA_HOME
    JAVA_HOME="$(/usr/libexec/java_home -v 25)"
fi
if [ -n "${JAVA_HOME:-}" ]; then
    export PATH="$JAVA_HOME/bin:$PATH"
fi
if ! command -v mvn >/dev/null 2>&1; then
    printf 'Maven not found. Install Maven 3.9 and add its bin directory to PATH.\n' >&2
    exit 1
fi

mvn -Papp clean package "$@"
printf '\nBuild complete: %s/target/dist\n' "$PWD"
if [ "$(uname -s)" = Darwin ]; then
    printf 'Double-click WuwaLogin.app to start.\n'
else
    printf 'Run target/dist/WuwaLogin/bin/WuwaLogin to start.\n'
fi
