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
# CALENDAR_JAR to point at a different jar if needed, and CALENDAR_JAVA_OPTS
# to replace the JVM options below.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="${CALENDAR_JAR:-$SCRIPT_DIR/calendar-1.jar}"

if [ ! -f "$JAR" ]; then
  echo "ERROR: calendar jar not found at $JAR (set CALENDAR_JAR to override)" >&2
  exit 1
fi

# This runs on the same small box as the web app and MySQL, and a JVM with no
# -Xmx defaults its maximum heap to a QUARTER OF SYSTEM RAM - 230MB on a 1GB
# t3.micro, the same again for the app. Two unbounded JVMs plus MySQL do not
# fit, and the kernel OOM killer resolves that by killing whichever process is
# largest: the running web app or MySQL, never this one. The victim dies on
# SIGKILL and logs nothing, so the outage looks like it came from nowhere.
#
# So cap it. This is a plain main() that opens a database connection per resort
# and sends mail - there is no Spring context here - and 128MB is generous for
# that. SerialGC keeps the collector's own footprint down; nothing in a batch of
# emails needs concurrent collection, and G1 would switch itself on and start
# charging native memory for pause times this job does not care about.
JAVA_OPTS="${CALENDAR_JAVA_OPTS:--Xmx128m -XX:+UseSerialGC}"

# Unquoted on purpose: JAVA_OPTS holds several arguments that must word-split.
# shellcheck disable=SC2086
java $JAVA_OPTS -cp "$JAR" -Dloader.main=com.gledhills.calendar.DailyReminder \
  org.springframework.boot.loader.launch.PropertiesLauncher
