# AI Prompts

Tool: **Claude Code** (Anthropic). The prompts below were given in this order.

## 1. Project structure

> Create a Java 17+ project named OS-Synchronization-Assignment with this structure:
> README.md, readers_writers/{semaphore,monitor}/, dining_philosophers/{semaphore,monitor}/,
> common/, generated_html/, screenshots/, ai_prompts/prompts.md, references/references.md, report/.
> Add a .gitignore for *.class and out/. Initialise git, commit "Initial project structure".

## 2. Event log and HTML generator

> In common/, write EventLog.java: a thread-safe recorder. Each event has a timestamp (ms since start),
> thread name, actor type, state (e.g. WAITING, READING, WRITING, THINKING, HUNGRY, EATING),
> a description, and a snapshot of shared state (active readers, writer active, fork owners).
> Add writeHtml(path, title): writes ONE self-contained HTML file with the events embedded as JSON,
> showing actor cards with live state colours, a shared-resource / fork panel, and a timeline table,
> plus Play/Pause/Step/Reset and a speed slider. No external libraries. Comment everything.
> Commit "Add EventLog and HTML generator".

## 3. Readers–Writers with semaphores

> In readers_writers/semaphore/, write ReadersWritersSemaphore.java using java.util.concurrent.Semaphore:
> mutex (1) protecting readCount, rwMutex (1) for the shared resource, and a fair turnstile semaphore
> so writers don't starve. Readers and writers are real threads; counts, iterations and max delay come
> from command-line args (defaults 4 readers, 2 writers). Clearly comment the CRITICAL SECTION and the
> shared resource (a shared integer). Log every state change through EventLog, then generate
> generated_html/readers_writers_semaphore.html. Assert at runtime: never a writer alongside readers,
> never two writers. Commit.

## 4. Dining Philosophers with semaphores

> In dining_philosophers/semaphore/, write DiningPhilosophersSemaphore.java for exactly 4 philosophers:
> one Semaphore(1) per fork, plus a room semaphore Semaphore(3) so at most 3 sit at once (deadlock
> prevention). Use fair semaphores to limit starvation. States: THINKING, HUNGRY, ACQUIRING_FORKS,
> EATING, RELEASING_FORKS. Track fork ownership in the snapshot. Assert that no fork is held by two
> philosophers. Output generated_html/dining_philosophers_semaphore.html with a round table
> showing 4 philosophers and 4 forks. Commit.

## 5. Dining Philosophers with a monitor

> In dining_philosophers/monitor/, write DiningPhilosophersMonitor.java using the classic monitor
> solution: a state array and one Condition per philosopher, with test(i) letting i eat only if both
> neighbours aren't eating. Add an aging/eat-count check to discuss starvation. Same states, assertions
> and HTML visual. Output generated_html/dining_philosophers_monitor.html. Commit.

## 6. Run scripts

> Add run.sh and run.bat that compile everything into out/ and run all 4 programs to regenerate the
> 4 HTML files. Run them, confirm no assertion fails, and fix any bugs found. Commit the generated HTML.

_(The Readers–Writers monitor version was written at this step so that four programs existed to run.)_

## 7. Documentation

> Write README.md with the sections from the brief: name/USN placeholders, a description of all 4
> implementations, Java version, build, run and HTML-generation commands, how to open the HTML,
> expected output, screenshot links, limitations, AI tools used (link ai_prompts/prompts.md), and
> references. Fill prompts.md with the prompts I actually used, and references.md with Silberschatz OS
> Concepts, the Java Semaphore and Condition docs, and the Downey Little Book of Semaphores. Commit.

## 8. Publishing

> Add remote … and push main.
>
> push it to os-task2 repo not the other one
