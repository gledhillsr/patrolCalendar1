#!/bin/bash
# Sends daily shift reminder emails for every resort.
#
# Run this daily from cron. It reuses the same executable jar the app
# itself runs from (java -jar calendar-1.jar) and launches DailyReminder's
# main() instead of the Spring Boot application, via Spring Boot's
# PropertiesLauncher. That launcher sets up the exact same runtime
# classpath (BOOT-INF/classes + BOOT-INF/lib) the app uses, so there is
# no separate jar list here to keep in sync with pom.xml.
#
# By default this looks for calendar-1.jar next to this script. Set
# CALENDAR_JAR to point at a different jar if needed.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="${CALENDAR_JAR:-$SCRIPT_DIR/calendar-1.jar}"

if [ ! -f "$JAR" ]; then
  echo "ERROR: calendar jar not found at $JAR (set CALENDAR_JAR to override)" >&2
  exit 1
fi

java -cp "$JAR" -Dloader.main=com.gledhills.calendar.DailyReminder \
  org.springframework.boot.loader.launch.PropertiesLauncher
