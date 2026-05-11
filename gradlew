#!/bin/bash

APP_BASE_NAME=${0##*/}
APP_HOME=$( cd "${BASH_SOURCE[0]%/*}" && pwd )

exec java -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
