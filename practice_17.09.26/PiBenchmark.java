import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Monte Carlo Pi benchmark: race condition -> synchronization trap -> reduction.
 * Run:  java PiBenchmark.java          (Java 11+, no separate compile step needed)
 */
public class PiBenchmark {

    static final long N_RACE = 50_000_000L;      // Parts 1 and 2
    static final long N_REDUCE = 100_000_000L;   // Part 3

    // ---------------- helpers ----------------
    static boolean hit() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double x = r.nextDouble(), y = r.nextDouble();
        return x * x + y * y <= 1.0;
    }

    static void runThreads(int t, long total, java.util.function.IntFunction<Runnable> job) throws InterruptedException {
        Thread[] ts = new Thread[t];
        for (int i = 0; i < t; i++) ts[i] = new Thread(job.apply(i));
        for (Thread th : ts) th.start();
        for (Thread th : ts) th.join();
    }

    static double ms(long startNs) { return (System.nanoTime() - startNs) / 1e6; }

    // ---------------- Part 1: phantom bug ----------------
    static long totalHits = 0;                   // shared, unprotected

    static double part1Run() throws InterruptedException {
        totalHits = 0;
        final int T = 4;
        final long per = N_RACE / T;
        runThreads(T, N_RACE, i -> () -> {
            for (long k = 0; k < per; k++) {
                if (hit()) totalHits++;          // DATA RACE
            }
        });
        return 4.0 * totalHits / N_RACE;
    }

    // ---------------- Part 2: synchronization trap ----------------
    static long syncHits = 0;
    static synchronized void syncInc() { syncHits++; }
    static final AtomicLong atomicHits = new AtomicLong();

    static double[] part2Synchronized() throws InterruptedException {
        syncHits = 0;
        final int T = 4; final long per = N_RACE / T;
        long s = System.nanoTime();
        runThreads(T, N_RACE, i -> () -> {
            for (long k = 0; k < per; k++) if (hit()) syncInc();
        });
        return new double[]{4.0 * syncHits / N_RACE, ms(s)};
    }

    static double[] part2Atomic() throws InterruptedException {
        atomicHits.set(0);
        final int T = 4; final long per = N_RACE / T;
        long s = System.nanoTime();
        runThreads(T, N_RACE, i -> () -> {
            for (long k = 0; k < per; k++) if (hit()) atomicHits.incrementAndGet();
        });
        return new double[]{4.0 * atomicHits.get() / N_RACE, ms(s)};
    }

    static double[] singleThread(long n) {
        long hits = 0;
        long s = System.nanoTime();
        for (long k = 0; k < n; k++) if (hit()) hits++;
        return new double[]{4.0 * hits / n, ms(s)};
    }

    // ---------------- Part 3: reduction ----------------
    static double[] part3(int T) throws InterruptedException {
        final long per = N_REDUCE / T;
        final long[] partial = new long[T];      // written once per thread, at the end
        long s = System.nanoTime();
        runThreads(T, N_REDUCE, i -> () -> {
            long local = 0;                      // private counter
            long iters = (i == T - 1) ? N_REDUCE - per * (T - 1) : per;
            for (long k = 0; k < iters; k++) if (hit()) local++;
            partial[i] = local;
        });
        long sum = 0;
        for (long p : partial) sum += p;         // reduction(+:totalHits)
        return new double[]{4.0 * sum / N_REDUCE, ms(s)};
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== ENVIRONMENT ===");
        System.out.println("OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " (" + System.getProperty("os.arch") + ")");
        System.out.println("Java: " + System.getProperty("java.version") + " " + System.getProperty("java.vm.name"));
        System.out.println("Logical processors: " + Runtime.getRuntime().availableProcessors());

        System.out.println("\n[warm-up JIT]");
        singleThread(20_000_000L); part3(4);

        System.out.println("\n=== PART 1: Phantom Bug (4 threads, 50,000,000 points, shared totalHits++) ===");
        for (int run = 1; run <= 5; run++) {
            long s = System.nanoTime();
            double pi = part1Run();
            System.out.printf("Run %d: pi = %.6f   (time %.1f ms)%n", run, pi, ms(s));
        }

        System.out.println("\n=== PART 2: Synchronization Trap (50,000,000 points) ===");
        double[] st = singleThread(N_RACE);
        double[] sy = part2Synchronized();
        double[] at = part2Atomic();
        System.out.printf("Single-thread plain loop : pi = %.6f   time = %.1f ms   (1.00x)%n", st[0], st[1]);
        System.out.printf("4 threads synchronized   : pi = %.6f   time = %.1f ms   (%.2fx slower)%n", sy[0], sy[1], sy[1] / st[1]);
        System.out.printf("4 threads AtomicLong     : pi = %.6f   time = %.1f ms   (%.2fx slower)%n", at[0], at[1], at[1] / st[1]);

        System.out.println("\n=== PART 3: Reduction (100,000,000 points) ===");
        System.out.println("| Threads (T) | Runtime (ms) | Speedup (T1/TN) | Efficiency (Speedup/T) | pi |");
        System.out.println("|---|---|---|---|---|");
        double t1 = 0;
        for (int T : new int[]{1, 2, 4, 8, 16, 32}) {
            double best = Double.MAX_VALUE, pi = 0;
            for (int rep = 0; rep < 3; rep++) {          // best of 3 to reduce noise
                double[] r = part3(T);
                if (r[1] < best) { best = r[1]; pi = r[0]; }
            }
            if (T == 1) t1 = best;
            double sp = t1 / best;
            System.out.printf("| %d%s | %.1f | %.2fx | %.1f%% | %.5f |%n",
                    T, T == 1 ? " (baseline)" : "", best, sp, sp / T * 100, pi);
        }
    }
}
