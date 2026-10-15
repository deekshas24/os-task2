#!/usr/bin/env sh
# Compiles every Java source into out/ and runs all four programs, regenerating
# the four HTML files in generated_html/. Exits non-zero if any program reports a violation.
set -e
cd "$(dirname "$0")"
rm -rf out
mkdir -p out generated_html
javac -d out $(find common readers_writers dining_philosophers -name '*.java')
java -cp out readers_writers.semaphore.ReadersWritersSemaphore
java -cp out readers_writers.monitor.ReadersWritersMonitor
java -cp out dining_philosophers.semaphore.DiningPhilosophersSemaphore
java -cp out dining_philosophers.monitor.DiningPhilosophersMonitor
echo "All programs finished with no violations. Open the files in generated_html/ in a browser."
