#!/bin/sh
# Gradle wrapper script for Unix/macOS/Linux
DIRNAME=$(dirname "$0")
exec "$DIRNAME/gradle/wrapper/gradle-wrapper.jar" "$@" 2>/dev/null || \
    gradle "$@"
