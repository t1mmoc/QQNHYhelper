#!/bin/sh
# gradlew - Gradle Wrapper script

# Use local Gradle installation
GRADLE_HOME="D:\\Users\\Timmoc\\.gradle\\wrapper\\dists\\gradle-8.10.2-bin\\e0thjr3we83usdufs66z371ne\\gradle-8.10.2"
APP_BASE_NAME=`basename "$0"`

exec "$GRADLE_HOME\\bin\\gradle.bat" "$@"
