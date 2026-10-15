package dining_philosophers.semaphore;

import common.EventLog;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Dining Philosophers with exactly 4 philosophers, solved with {@link Semaphore}s.
 *
 * <p>Semaphores used:</p>
 * <ul>
 *   <li>{@code forks[i]} (1, fair): fork i. Philosopher i uses fork i (left) and fork (i+1)%4 (right).</li>
 *   <li>{@code room} (3, fair): at most 3 of the 4 philosophers may sit at the table at once.
 *       With 3 philosophers sharing 4 forks, at least one of them can always get both forks,
 *       so the circular wait needed for deadlock cannot form.</li>
 * </ul>
 * <p>Fair semaphores serve waiting threads in FIFO order, which limits starvation.</p>
 *
 * <p>Usage: {@code java dining_philosophers.semaphore.DiningPhilosophersSemaphore [iterations] [maxDelayMs]}
 * (defaults 3, 300). Run from the project root so the HTML lands in {@code generated_html/}.</p>
 */
public class DiningPhilosophersSemaphore {

    /** Number of philosophers (and forks). Fixed at 4 by the assignment. */
    private static final int N = 4;

    /** THE SHARED RESOURCES: one binary fair semaphore per fork. */
    private static final Semaphore[] forks = new Semaphore[N];
    static {
        for (int i = 0; i < N; i++) forks[i] = new Semaphore(1, true);
    }

    /** Lets at most N-1 philosophers try to pick up forks at the same time. */
    private static final Semaphore room = new Semaphore(N - 1, true);

    // ---- Monitoring only (not part of the algorithm) --------------------------------

    /** Lock that makes fork-owner updates, safety checks and logging one atomic step. */
    private static final Object monitorLock = new Object();
    /** Name of the philosopher holding each fork, or null if free. Guarded by {@code monitorLock}. */
    private static final String[] forkOwners = new String[N];
    /** Number of safety violations detected (should always stay 0). */
    private static int violations = 0;

    /** Shared event log rendered to HTML at the end. */
    private static final EventLog log = new EventLog();

    /** Meals each philosopher eats. */
    private static int iterations;
    /** Upper bound (ms) for random sleeps simulating thinking/eating. */
    private static int maxDelay;

    public static void main(String[] args) throws Exception {
        iterations = args.length > 0 ? Integer.parseInt(args[0]) : 3;
        maxDelay = args.length > 1 ? Integer.parseInt(args[1]) : 300;

        // One real thread per philosopher.
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            final int id = i;
            threads.add(new Thread(() -> philosopher(id), "Philosopher-" + i));
        }
        threads.forEach(Thread::start);
        for (Thread t : threads) t.join();

        String out = "generated_html/dining_philosophers_semaphore.html";
        log.writeHtml(out, "Dining Philosophers (Semaphore)");
        System.out.printf("Done. Events=%d, violations=%d. HTML: %s%n", log.events().size(), violations, out);
        if (violations > 0) System.exit(1);
    }

    /** Philosopher thread body. */
    private static void philosopher(int id) {
        int left = id;
        int right = (id + 1) % N;
        try {
            for (int meal = 1; meal <= iterations; meal++) {
                record("THINKING", "thinks", -1, false);
                pause();

                record("HUNGRY", "wants meal " + meal, -1, false);
                room.acquire();                         // sit down: at most 3 at the table

                record("ACQUIRING_FORKS", "picking up forks " + left + " and " + right, -1, false);
                forks[left].acquire();
                record("ACQUIRING_FORKS", "picked up left fork " + left, left, true);
                forks[right].acquire();
                record("ACQUIRING_FORKS", "picked up right fork " + right, right, true);

                // ===== CRITICAL SECTION: eating while holding both forks =====
                record("EATING", "eats meal " + meal + " with forks " + left + " and " + right, -1, false);
                pause();
                // ===== END CRITICAL SECTION =====

                record("RELEASING_FORKS", "puts down right fork " + right, right, false);
                forks[right].release();
                record("RELEASING_FORKS", "puts down left fork " + left, left, false);
                forks[left].release();
                room.release();                         // leave the table
            }
            record("DONE", "finished all meals", -1, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Updates fork ownership (if {@code fork >= 0}), checks the safety rule and logs the
     * event, all as one atomic step so the snapshot matches the state change.
     *
     * <p>Pickups are recorded right after the fork semaphore is acquired, and put-downs
     * right before it is released, so the owners array never lags behind reality.</p>
     *
     * @param fork    the fork that changed hands, or -1 for none
     * @param pickUp  true if the calling thread just picked {@code fork} up, false if it is putting it down
     */
    private static void record(String state, String desc, int fork, boolean pickUp) {
        String me = Thread.currentThread().getName();
        synchronized (monitorLock) {
            if (fork >= 0) {
                if (pickUp) {
                    // Runtime safety check: a fork must never be held by two philosophers.
                    if (forkOwners[fork] != null) {
                        violations++;
                        System.err.println("VIOLATION: fork " + fork + " taken by " + me
                                + " while held by " + forkOwners[fork]);
                    }
                    forkOwners[fork] = me;
                } else {
                    forkOwners[fork] = null;
                }
            }
            log.log("Philosopher", state, desc, EventLog.Snapshot.forks(forkOwners));
        }
    }

    /** Sleeps a random time up to {@code maxDelay} ms. */
    private static void pause() throws InterruptedException {
        if (maxDelay > 0) Thread.sleep(ThreadLocalRandom.current().nextInt(maxDelay + 1));
    }
}
