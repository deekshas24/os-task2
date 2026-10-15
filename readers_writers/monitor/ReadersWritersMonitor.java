package readers_writers.monitor;

import common.EventLog;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Readers-Writers problem solved with a monitor ({@link ReentrantLock} plus two {@link Condition}s).
 *
 * <p>Rules enforced by the monitor:</p>
 * <ul>
 *   <li>A reader may enter when no writer is writing <i>and no writer is waiting</i>,
 *       so a stream of readers cannot starve writers.</li>
 *   <li>A writer may enter when nobody is reading or writing.</li>
 *   <li>When a writer leaves, all waiting readers are woken first, so readers queued during
 *       a burst of writers get in between writes and are not starved either.</li>
 * </ul>
 *
 * <p>Usage: {@code java readers_writers.monitor.ReadersWritersMonitor [readers] [writers] [iterations] [maxDelayMs]}
 * (defaults 4, 2, 3, 300). Run from the project root so the HTML lands in {@code generated_html/}.</p>
 */
public class ReadersWritersMonitor {

    /** THE SHARED RESOURCE: an integer that writers increment and readers read. */
    private static int sharedValue = 0;

    /** Shared event log rendered to HTML at the end. */
    private static final EventLog log = new EventLog();

    /** Iterations each thread performs. */
    private static int iterations;
    /** Upper bound (ms) for random sleeps simulating work. */
    private static int maxDelay;

    /** The monitor guarding access to {@link #sharedValue}. */
    private static final class ReadWriteMonitor {
        /** Monitor lock: mutual exclusion for the fields below. */
        private final ReentrantLock lock = new ReentrantLock(true);
        /** Readers wait here while a writer is writing or waiting. */
        private final Condition okToRead = lock.newCondition();
        /** Writers wait here while anyone is reading or writing. */
        private final Condition okToWrite = lock.newCondition();

        // ===== MONITOR STATE (only accessed while holding lock) =====
        private int activeReaders = 0;
        private boolean writerActive = false;
        private int waitingWriters = 0;
        /** Number of safety violations detected (should always stay 0). */
        private int violations = 0;

        void startRead() throws InterruptedException {
            lock.lock();
            try {
                record("Reader", "WAITING", "wants to read");
                while (writerActive || waitingWriters > 0) okToRead.await();
                activeReaders++;
                record("Reader", "READING", "reads value " + sharedValue);
            } finally {
                lock.unlock();
            }
        }

        void endRead() {
            lock.lock();
            try {
                activeReaders--;
                record("Reader", "IDLE", "finished reading");
                if (activeReaders == 0) okToWrite.signal();   // last reader out lets a writer in
            } finally {
                lock.unlock();
            }
        }

        void startWrite() throws InterruptedException {
            lock.lock();
            try {
                record("Writer", "WAITING", "wants to write");
                waitingWriters++;
                while (writerActive || activeReaders > 0) okToWrite.await();
                waitingWriters--;
                writerActive = true;
                record("Writer", "WRITING", "writes value " + (sharedValue + 1));
            } finally {
                lock.unlock();
            }
        }

        void endWrite() {
            lock.lock();
            try {
                writerActive = false;
                record("Writer", "IDLE", "finished writing " + sharedValue);
                okToRead.signalAll();   // let queued readers in first
                okToWrite.signal();     // and a writer, which re-checks its condition
            } finally {
                lock.unlock();
            }
        }

        /** Checks the safety rules and logs an event; called with the lock held, so the snapshot is consistent. */
        private void record(String actor, String state, String desc) {
            if (writerActive && activeReaders > 0) {
                violations++;
                System.err.println("VIOLATION: writer active alongside " + activeReaders + " reader(s)");
            }
            log.log(actor, state, desc, EventLog.Snapshot.readersWriters(activeReaders, writerActive));
        }

        /** Logs an event that happens outside start/end calls. */
        void note(String actor, String state, String desc) {
            lock.lock();
            try { record(actor, state, desc); } finally { lock.unlock(); }
        }
    }

    /** Counts writers inside the critical section, to detect two writers at once. */
    private static final AtomicInteger writersInside = new AtomicInteger();
    /** Two-writer violations, counted separately because they are detected outside the monitor. */
    private static final AtomicInteger writerViolations = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        int readers = args.length > 0 ? Integer.parseInt(args[0]) : 4;
        int writers = args.length > 1 ? Integer.parseInt(args[1]) : 2;
        iterations = args.length > 2 ? Integer.parseInt(args[2]) : 3;
        maxDelay = args.length > 3 ? Integer.parseInt(args[3]) : 300;
        ReadWriteMonitor monitor = new ReadWriteMonitor();

        // Create real threads for every reader and writer.
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < readers; i++) threads.add(new Thread(() -> reader(monitor), "Reader-" + i));
        for (int i = 0; i < writers; i++) threads.add(new Thread(() -> writer(monitor), "Writer-" + i));
        threads.forEach(Thread::start);
        for (Thread t : threads) t.join();

        int violations = monitor.violations + writerViolations.get();
        String out = "generated_html/readers_writers_monitor.html";
        log.writeHtml(out, "Readers-Writers (Monitor)");
        System.out.printf("Done. Final value=%d, events=%d, violations=%d. HTML: %s%n",
                sharedValue, log.events().size(), violations, out);
        if (violations > 0) System.exit(1);
    }

    /** Reader thread body. */
    private static void reader(ReadWriteMonitor m) {
        try {
            for (int i = 0; i < iterations; i++) {
                m.startRead();
                // ===== CRITICAL SECTION (reader): read the shared resource =====
                int seen = sharedValue;
                pause();
                // ===== END CRITICAL SECTION =====
                m.endRead();
                pause();
            }
            m.note("Reader", "DONE", "finished all iterations");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Writer thread body. */
    private static void writer(ReadWriteMonitor m) {
        try {
            for (int i = 0; i < iterations; i++) {
                m.startWrite();
                // ===== CRITICAL SECTION (writer): exclusive update of the shared resource =====
                if (writersInside.incrementAndGet() > 1) {
                    writerViolations.incrementAndGet();
                    System.err.println("VIOLATION: two writers active at once");
                }
                sharedValue++;
                pause();
                writersInside.decrementAndGet();
                // ===== END CRITICAL SECTION =====
                m.endWrite();
                pause();
            }
            m.note("Writer", "DONE", "finished all iterations");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Sleeps a random time up to {@code maxDelay} ms to simulate work. */
    private static void pause() throws InterruptedException {
        if (maxDelay > 0) Thread.sleep(ThreadLocalRandom.current().nextInt(maxDelay + 1));
    }
}
