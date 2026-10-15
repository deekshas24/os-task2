@echo off
rem Compiles every Java source into out\ and runs all four programs, regenerating
rem the four HTML files in generated_html\. Exits non-zero if any program reports a violation.
setlocal
cd /d "%~dp0"
if exist out rmdir /s /q out
mkdir out
if not exist generated_html mkdir generated_html
dir /s /b common\*.java readers_writers\*.java dining_philosophers\*.java > out\sources.txt
javac -d out @out\sources.txt || exit /b 1
java -cp out readers_writers.semaphore.ReadersWritersSemaphore || exit /b 1
java -cp out readers_writers.monitor.ReadersWritersMonitor || exit /b 1
java -cp out dining_philosophers.semaphore.DiningPhilosophersSemaphore || exit /b 1
java -cp out dining_philosophers.monitor.DiningPhilosophersMonitor || exit /b 1
echo All programs finished with no violations. Open the files in generated_html\ in a browser.
