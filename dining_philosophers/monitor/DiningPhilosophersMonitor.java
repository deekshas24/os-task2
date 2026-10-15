package dining_philosophers.monitor;

import common.EventLog;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Dining Philosophers with exactly 4 philosophers, solved with the classic monitor
 * (Silberschatz/Tanenbaum): a state array plus one {@link Condition} per philosopher.
 *
 * <p>A philosopher may eat only if neither neighbour is eating; {@link DiningTable#test(int)}
 * checks that and, if it holds, lets the philosopher eat and wakes it. Because a philosopher
 * picks up both forks in one atomic step inside the monitor, the circular wait that causes
 * deadlock cannot happen.</p>
 *
 * <p><b>Starvation.</b> The plain classic solution is deadlock-free but <i>not</i> starvation-free:
 * two neighbours can take turns eating so that the philosopher between them never finds both
 * neighbours idle. To address this, {@code test(i)} adds <b>aging</b>: if a neighbour has been
 * hungry longer than {@code agingMs} and longer than {@code i}, then {@code i} gives way to that
 * neighbour. Hunger start times give a strict order, so two philosophers can never wait for each
 * other this way. At the end, eat counts and the longest hunger wait per philosopher are printed
 * (and logged) as evidence for the starvation discussion. Pass {@code agingMs = 0} to turn aging off.</p>
 *
 * <p>Usage: {@code java dining_philosophers.monitor.DiningPhilosophersMonitor [iterations] [maxDelayMs] [agingMs]}
 * (defaults 3, 300, 600). Run from the project root so the HTML lands in {@code generated_html/}.</p>
 */
public class DiningPhilosophersMonitor {

    /** Number of philosophers (and forks). Fixed at 4 by the assignment. */
    private static final int N = 4;

    /** Shared event log rendered to HTML at the end. */
    private static final EventLog log = new EventLog();

    /** Meals each philosopher eats. */
    private static int iterations;
    /** Upper bound (ms) for random sleeps simulating thinking/eating. */
    private static int maxDelay;

    /** Philosopher states held in the monitor's state array. */
    private enum State { THINKING, HUNGRY, EATING }

    /**
     * The monitor. Every method runs while holding {@code lock}, so only one thread is inside at a time.
     */
    private static final class DiningTable {
        /** Monitor lock: mutual exclusion for everything below. */
        private final ReentrantLock lock = new ReentrantLock(true);
        /** One condition per philosopher; philosopher i waits on self[i] until test(i) lets it eat. */
        private final Condition[] self = new Condition[N];

        // ===== SHARED STATE (only accessed while holding lock) =====
        /** The classic state array. */
        private final State[] state = new State[N];
        /** Holder of each fork (for the snapshot and safety check), or null if free. */
        private final String[] forkOwners = new String[N];
        /** Time (ms) each philosopher became hungry, used for aging. */
        private final long[] hungrySince = new long[N];
        /** Meals eaten by each philosopher. */
        private final int[] eatCount = new int[N];
        /** Longest time (ms) each philosopher waited while hungry. */
        private final long[] maxWait = new long[N];
        /** Aging threshold in ms (0 = disabled). */
        private final long agingMs;
        /** Number of safety violations detected (should always stay 0). */
        private int violations = 0;

        DiningTable(long agingMs) {
            this.agingMs = agingMs;
            for (int i = 0; i < N; i++) {
                self[i] = lock.newCondition();
                state[i] = State.THINKING;
            }
        }

        private static int left(int i) { return (i + N - 1) % N; }   // left neighbour
        private static int right(int i) { return (i + 1) % N; }      // right neighbour

        /** Philosopher i wants to eat: becomes HUNGRY and waits until both forks are free. */
        void pickup(int i) throws InterruptedException {
            lock.lock();
            try {
                // ===== CRITICAL SECTION (monitor entry) =====
                state[i] = State.HUNGRY;
                hungrySince[i] = System.currentTimeMillis();
                record("HUNGRY", "is hungry");
                record("ACQUIRING_FORKS", "asks for forks " + i + " and " + right(i));
                test(i);
                // Wait (releasing the lock) until a test() sets us to EATING.
                while (state[i] != State.EATING) self[i].await();
                long waited = System.currentTimeMillis() - hungrySince[i];
                maxWait[i] = Math.max(maxWait[i], waited);
                eatCount[i]++;
                record("EATING", "eats meal " + eatCount[i] + " after waiting " + waited + " ms");
            } finally {
                lock.unlock();
            }
        }

        /** Philosopher i has finished eating: puts both forks down and tests both neighbours. */
        void putdown(int i) {
            lock.lock();
            try {
                // ===== CRITICAL SECTION (monitor entry) =====
                record("RELEASING_FORKS", "puts down forks " + i + " and " + right(i));
                state[i] = State.THINKING;
                forkOwners[i] = null;
                forkOwners[right(i)] = null;
                // A neighbour may now be able to eat.
                test(left(i));
                test(right(i));
                record("THINKING", "thinks");
            } finally {
                lock.unlock();
            }
        }

        /**
         * Lets philosopher i eat if it is hungry and neither neighbour is eating, then signals it.
         * With aging on, i also gives way to a neighbour that has been hungry for longer
         * than {@code agingMs} and longer than i.
         */
        private void test(int i) {
            int l = left(i), r = right(i);
            if (state[i] != State.HUNGRY || state[l] == State.EATING || state[r] == State.EATING) return;
            if (agingMs > 0 && (mustYield(i, l) || mustYield(i, r))) return;

            state[i] = State.EATING;
            String me = "Philosopher-" + i;
            // Runtime safety checks: forks must be free, neighbours must not be eating.
            for (int f : new int[] { i, r }) {
                if (forkOwners[f] != null) {
                    violations++;
                    System.err.println("VIOLATION: fork " + f + " given to " + me + " while held by " + forkOwners[f]);
                }
                forkOwners[f] = me;
            }
            if (state[l] == State.EATING || state[r] == State.EATING) {
                violations++;
                System.err.println("VIOLATION: " + me + " eats next to an eating neighbour");
            }
            self[i].signal();
        }

        /** True if hungry neighbour n has aged past the threshold and has waited longer than i. */
        private boolean mustYield(int i, int n) {
            if (state[n] != State.HUNGRY) return false;
            long now = System.currentTimeMillis();
            boolean aged = now - hungrySince[n] >= agingMs;
            // Older hunger wins; ties are broken by id so the order is strict (no mutual yielding).
            boolean older = hungrySince[n] < hungrySince[i] || (hungrySince[n] == hungrySince[i] && n < i);
            return aged && older;
        }

        /** Logs an event with a fork snapshot. Called with the lock held, so the snapshot is consistent. */
        private void record(String st, String desc) {
            log.log("Philosopher", st, desc, EventLog.Snapshot.forks(forkOwners));
        }

        /** Logs a state change made outside pickup/putdown (start and finish). */
        void note(String st, String desc) {
            lock.lock();
            try { record(st, desc); } finally { lock.unlock(); }
        }
    }

    public static void main(String[] args) throws Exception {
        iterations = args.length > 0 ? Integer.parseInt(args[0]) : 3;
        maxDelay = args.length > 1 ? Integer.parseInt(args[1]) : 300;
        long agingMs = args.length > 2 ? Long.parseLong(args[2]) : 600;
        DiningTable table = new DiningTable(agingMs);

        // One real thread per philosopher.
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            final int id = i;
            threads.add(new Thread(() -> philosopher(table, id), "Philosopher-" + i));
        }
        threads.forEach(Thread::start);
        for (Thread t : threads) t.join();

        // Starvation report: every philosopher should eat the same number of meals, and the
        // longest wait shows whether anyone was held back much longer than the others.
        System.out.println("Starvation check (aging " + (agingMs > 0 ? agingMs + " ms" : "off") + "):");
        for (int i = 0; i < N; i++) {
            System.out.printf("  Philosopher-%d: meals=%d, longest wait=%d ms%n", i, table.eatCount[i], table.maxWait[i]);
            if (table.eatCount[i] != iterations) {
                table.violations++;
                System.err.println("VIOLATION: Philosopher-" + i + " ate " + table.eatCount[i] + " of " + iterations + " meals");
            }
        }

        String out = "generated_html/dining_philosophers_monitor.html";
        log.writeHtml(out, "Dining Philosophers (Monitor)");
        System.out.printf("Done. Events=%d, violations=%d. HTML: %s%n", log.events().size(), table.violations, out);
        if (table.violations > 0) System.exit(1);
    }

    /** Philosopher thread body: think, pick up, eat, put down. */
    private static void philosopher(DiningTable table, int id) {
        try {
            table.note("THINKING", "sits down and thinks");
            for (int meal = 1; meal <= iterations; meal++) {
                pause();                    // thinking
                table.pickup(id);
                pause();                    // eating (holds both forks)
                table.putdown(id);
            }
            table.note("DONE", "finished all meals, longest wait " + table.maxWait[id] + " ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Sleeps a random time up to {@code maxDelay} ms. */
    private static void pause() throws InterruptedException {
        if (maxDelay > 0) Thread.sleep(ThreadLocalRandom.current().nextInt(maxDelay + 1));
    }
}
