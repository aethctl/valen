#!/usr/bin/env bash
# Valen build environment. Everything lives inside the project; nothing is installed system-wide.
set -a
VALEN_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA_HOME="$VALEN_ROOT/.tools/jdk"
ANDROID_HOME="$VALEN_ROOT/.tools/android-sdk"
GRADLE_USER_HOME="$VALEN_ROOT/.tools/gradle-home"
PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
set +a
