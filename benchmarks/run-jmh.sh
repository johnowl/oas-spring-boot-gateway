#!/usr/bin/env sh
# Runs the routing benchmarks of spec 016. Usage: benchmarks/run-jmh.sh [JMH options]
# Example: benchmarks/run-jmh.sh -rf json -rff benchmarks/results/jmh-routing.json
set -eu
cd "$(dirname "$0")/.."
./mvnw -B -q -pl gateway -am -Pjmh test-compile -DskipTests
CLASSPATH="gateway/target/test-classes:gateway/target/classes:$(cat gateway/target/jmh-classpath.txt)"
exec java -cp "$CLASSPATH" org.openjdk.jmh.Main "$@"
