# OS-Synchronization-Assignment

| | |
|---|---|
| **Name** | Deeksha |
| **USN** | NNM24IS063 |
| **Course** | Operating Systems |

Java implementations of two classic synchronization problems, each solved two ways (semaphores and
monitors). Every program runs real threads, checks its safety rules at runtime, and writes an
interactive HTML replay of the run.

## Implementations

| Problem | Technique | Source | How it works |
|---|---|---|---|
| Readers–Writers | Semaphores | [ReadersWritersSemaphore.java](readers_writers/semaphore/ReadersWritersSemaphore.java) | `mutex` protects `readCount`; `rwMutex` guards the shared integer (first reader in locks it, last reader out unlocks it); a fair `turnstile` that a waiting writer holds keeps new readers from overtaking it, so writers don't starve. |
| Readers–Writers | Monitor | [ReadersWritersMonitor.java](readers_writers/monitor/ReadersWritersMonitor.java) | `ReentrantLock` with `okToRead` / `okToWrite` conditions. Readers wait while a writer is writing **or waiting**; a finishing writer wakes all waiting readers first, so neither side starves. |
| Dining Philosophers (4) | Semaphores | [DiningPhilosophersSemaphore.java](dining_philosophers/semaphore/DiningPhilosophersSemaphore.java) | One fair `Semaphore(1)` per fork, plus a fair `room` `Semaphore(3)`: at most 3 philosophers sit at once, so at least one can always get both forks and they can never all wait on each other (no deadlock). |
| Dining Philosophers (4) | Monitor | [DiningPhilosophersMonitor.java](dining_philosophers/monitor/DiningPhilosophersMonitor.java) | Classic state array + one `Condition` per philosopher; `test(i)` lets *i* eat only if neither neighbour is eating. Adds **aging**: *i* gives way to a neighbour that has been hungry longer than a threshold. Prints meal counts and longest waits per philosopher for the starvation discussion. |

Shared code: [common/EventLog.java](common/EventLog.java) is a thread-safe event recorder that generates the
self-contained HTML replay (no external libraries).

**Runtime checks** (normal `if` statements, so they run without `-ea`): never a writer alongside readers,
never two writers, never a fork held by two philosophers, never neighbours eating together, and every
philosopher eats all its meals. Any violation prints `VIOLATION` and exits with code 1.

## Requirements

Java 17 or later (tested with Java 21).

## Build and run

Build everything and regenerate all four HTML files in one go, from the project root:

```sh
./run.sh        # Linux / macOS / Git Bash
run.bat         # Windows
```

Or by hand:

```sh
javac -d out common/*.java readers_writers/*/*.java dining_philosophers/*/*.java
java -cp out readers_writers.semaphore.ReadersWritersSemaphore [readers] [writers] [iterations] [maxDelayMs]
java -cp out readers_writers.monitor.ReadersWritersMonitor     [readers] [writers] [iterations] [maxDelayMs]
java -cp out dining_philosophers.semaphore.DiningPhilosophersSemaphore [iterations] [maxDelayMs]
java -cp out dining_philosophers.monitor.DiningPhilosophersMonitor     [iterations] [maxDelayMs] [agingMs]
```

Defaults: 4 readers, 2 writers, 3 iterations, 300 ms max delay; aging threshold 600 ms (`0` turns aging off).
Run from the project root so the HTML is written to `generated_html/`.

## HTML generation

Each program writes one file when it finishes:

| Program | Output |
|---|---|
| Readers–Writers (semaphore) | [generated_html/readers_writers_semaphore.html](generated_html/readers_writers_semaphore.html) |
| Readers–Writers (monitor) | [generated_html/readers_writers_monitor.html](generated_html/readers_writers_monitor.html) |
| Dining Philosophers (semaphore) | [generated_html/dining_philosophers_semaphore.html](generated_html/dining_philosophers_semaphore.html) |
| Dining Philosophers (monitor) | [generated_html/dining_philosophers_monitor.html](generated_html/dining_philosophers_monitor.html) |

### Opening the HTML

Double-click the file, or open it from a browser with *File → Open*. It works offline. The page shows:

- actor cards coloured by each thread's current state, with a legend
- the shared resource (active readers / writer active) or a round table with the 4 philosophers and 4 forks
- a timeline of every event
- **Play / Pause / Step / Reset** buttons and a speed slider

## Expected output

Timings are random, so numbers vary between runs; the violation count must always be 0.

```text
Done. Final value=6, events=60, violations=0. HTML: generated_html/readers_writers_semaphore.html
Done. Final value=6, events=60, violations=0. HTML: generated_html/readers_writers_monitor.html
Done. Events=100, violations=0. HTML: generated_html/dining_philosophers_semaphore.html
Starvation check (aging 600 ms):
  Philosopher-0: meals=3, longest wait=211 ms
  Philosopher-1: meals=3, longest wait=47 ms
  Philosopher-2: meals=3, longest wait=242 ms
  Philosopher-3: meals=3, longest wait=71 ms
Done. Events=68, violations=0. HTML: generated_html/dining_philosophers_monitor.html
All programs finished with no violations. Open the files in generated_html/ in a browser.
```

## Screenshots

_Add screenshots to [screenshots/](screenshots/) and they will appear here:_

- Readers–Writers (semaphore): `screenshots/readers_writers_semaphore.png`
- Readers–Writers (monitor): `screenshots/readers_writers_monitor.png`
- Dining Philosophers (semaphore): `screenshots/dining_philosophers_semaphore.png`
- Dining Philosophers (monitor): `screenshots/dining_philosophers_monitor.png`

## Limitations

- **Random timing.** Thread scheduling and sleeps are random, so each run (and each HTML replay) is different and a single run cannot prove correctness; the runtime checks only show that no violation happened in that run.
- **Monitoring adds locking.** The counters and fork-owner list used for checks and snapshots are updated under an extra lock. This doesn't change which thread may enter, but it does make threads slightly more synchronized than the bare algorithm.
- **Fairness isn't proven.** Fair semaphores/locks and aging reduce starvation; the meal counts and longest waits are evidence from a run, not a proof.
- **Fixed size.** Dining Philosophers is fixed at 4 philosophers, as the assignment asks.
- **Replay timing.** The HTML replays events one step at a time at the chosen speed, not at their real recorded intervals.

## AI tools used

Claude Code (Anthropic) was used to generate the project structure, code, scripts and documentation.
The prompts used are listed in [ai_prompts/prompts.md](ai_prompts/prompts.md). All code was compiled, run and checked.

## References

See [references/references.md](references/references.md).
