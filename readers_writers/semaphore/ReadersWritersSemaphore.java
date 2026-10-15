package readers_writers.semaphore;

import common.EventLog;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Readers-Writers problem solved with {@link Semaphore}s, without starving writers.
 *
 * <p>Semaphores used:</p>
 * <ul>
 *   <li>{@code mutex} (1) protects {@code readCount}.</li>
 *   <li>{@code rwMutex} (1) guards the shared resource: held by one writer, or by the
 *       group of readers as a whole (first reader in acquires, last reader out releases).</li>
 *   <li>{@code turnstile} (1, fair) every thread passes through it before entering.
 *       A waiting writer keeps holding it, so readers that arrive later queue
 *       behind that writer instead of overtaking it forever. This stops writers starving.</li>
 * </ul>
 *
 * <p>Usage: {@code java readers_writers.semaphore.ReadersWritersSemaphore [readers] [writers] [iterations] [maxDelayMs]}
 * (defaults 4, 2, 3, 300). Run from the project root so the HTML lands in {@code generated_html/}.</p>
 */
public class ReadersWritersSemaphore {

    /** THE SHARED RESOURCE: an integer that writers increment and readers read. */
    private static int sharedValue = 0;

    /** Number of readers currently inside the resource. Protected by {@code mutex}. */
    private static int readCount = 0;

    /** Protects {@code readCount}. */
    private static final Semaphore mutex = new Semaphore(1, true);
    /** Exclusive access to the shared resource (one writer, or all readers together). */
    private static final Semaphore rwMutex = new Semaphore(1, true);
    /** Fair turnstile that keeps new readers from overtaking a waiting writer. */
    private static final Semaphore turnstile = new Semaphore(1, true);

    // ---- Monitoring only (not part of the algorithm) --------------------------------
    // These counters record who is really inside the critical section, so we can check
    // the safety rules and log a snapshot that is consistent with each state change.

    /** Lock that makes counter updates, safety checks and logging one atomic step. */
    private static final Object monitorLock = new Object();
    /** Readers currently inside the critical section. Guarded by {@code monitorLock}. */
    private static int activeReaders = 0;
    /** Writers currently inside the critical section. Guarded by {@code monitorLock}. */
    private static int activeWriters = 0;
    /** Number of safety violations detected (should always stay 0). */
    private static int violations = 0;

    /** Shared event log rendered to HTML at the end. */
    private static final EventLog log = new EventLog();

    /** Iterations each thread performs. */
    private static int iterations;
    /** Upper bound (ms) for random sleeps simulating work. */
    private static int maxDelay;

    public static void main(String[] args) throws Exception {
        int readers = args.length > 0 ? Integer.parseInt(args[0]) : 4;
        int writers = args.length > 1 ? Integer.parseInt(args[1]) : 2;
        iterations = args.length > 2 ? Integer.parseInt(args[2]) : 3;
        maxDelay = args.length > 3 ? Integer.parseInt(args[3]) : 300;

        // Create real threads for every reader and writer.
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < readers; i++) threads.add(new Thread(ReadersWritersSemaphore::reader, "Reader-" + i));
        for (int i = 0; i < writers; i++) threads.add(new Thread(ReadersWritersSemaphore::writer, "Writer-" + i));
        threads.forEach(Thread::start);
        for (Thread t : threads) t.join();

        String out = "generated_html/readers_writers_semaphore.html";
        log.writeHtml(out, "Readers-Writers (Semaphore)");
        System.out.printf("Done. Final value=%d, events=%d, violations=%d. HTML: %s%n",
                sharedValue, log.events().size(), violations, out);
        if (violations > 0) System.exit(1);
    }

    /** Reader thread body. */
    private static void reader() {
        try {
            for (int i = 0; i < iterations; i++) {
                record("Reader", "WAITING", "wants to read", 0, 0);

                // Pass through the turnstile: blocks while a writer is waiting or writing.
                turnstile.acquire();
                turnstile.release();

                // Entry: the first reader locks writers out of the resource.
                mutex.acquire();
                readCount++;
                if (readCount == 1) rwMutex.acquire();
                mutex.release();

                // ===== CRITICAL SECTION (reader): read the shared resource =====
                int seen = sharedValue;
                record("Reader", "READING", "reads value " + seen, +1, 0);
                pause();
                record("Reader", "IDLE", "finished reading " + seen, -1, 0);
                // ===== END CRITICAL SECTION =====

                // Exit: the last reader out lets writers in again.
                mutex.acquire();
                readCount--;
                if (readCount == 0) rwMutex.release();
                mutex.release();

                pause();
            }
            record("Reader", "DONE", "finished all iterations", 0, 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Writer thread body. */
    private static void writer() {
        try {
            for (int i = 0; i < iterations; i++) {
                record("Writer", "WAITING", "wants to write", 0, 0);

                // Hold the turnstile while waiting for the resource so that new readers queue up behind us.
                turnstile.acquire();
                rwMutex.acquire();
                turnstile.release();

                // ===== CRITICAL SECTION (writer): exclusive update of the shared resource =====
                int newValue = ++sharedValue;
                record("Writer", "WRITING", "writes value " + newValue, 0, +1);
                pause();
                record("Writer", "IDLE", "finished writing " + newValue, 0, -1);
                // ===== END CRITICAL SECTION =====

                rwMutex.release();
                pause();
            }
            record("Writer", "DONE", "finished all iterations", 0, 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Updates the monitoring counters, checks the safety rules and logs the event, all
     * as one atomic step so the logged snapshot matches the state change.
     */
    private static void record(String actor, String state, String desc, int readerDelta, int writerDelta) {
        synchronized (monitorLock) {
            activeReaders += readerDelta;
            activeWriters += writerDelta;
            // Runtime safety checks: never a writer with readers, never two writers.
            if (activeWriters > 0 && activeReaders > 0) {
                violations++;
                System.err.println("VIOLATION: writer active alongside " + activeReaders + " reader(s)");
            }
            if (activeWriters > 1) {
                violations++;
                System.err.println("VIOLATION: " + activeWriters + " writers active at once");
            }
            log.log(actor, state, desc, EventLog.Snapshot.readersWriters(activeReaders, activeWriters > 0));
        }
    }

    /** Sleeps a random time up to {@code maxDelay} ms to simulate work. */
    private static void pause() throws InterruptedException {
        if (maxDelay > 0) Thread.sleep(ThreadLocalRandom.current().nextInt(maxDelay + 1));
    }
}
