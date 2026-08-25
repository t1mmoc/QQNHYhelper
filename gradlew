#!/bin/sh
# gradlew - Gradle Wrapper script

# Use local Gradle installation
GRADLE_HOME="C:\\Users\\xiaod\\AppData\\Local\\gradle\\gradle-8.7"
APP_BASE_NAME=`basename "$0"`

exec "$GRADLE_HOME\\bin\\gradle.bat" "$@"
