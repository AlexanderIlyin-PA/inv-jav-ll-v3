package etrading;

import java.lang.management.ManagementFactory;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * The executable form of SPEC.md: one check per rule.
 *
 * <p>This class deliberately has <b>no test-framework dependency</b> and lives in
 * {@code src/main/java}, so the spec can be run with nothing but a JDK — no
 * Gradle, no network, no JUnit. {@code SpecTests} in {@code src/test/java} is a
 * thin JUnit wrapper around exactly these methods, so both routes assert the
 * same thing:
 *
 * <pre>
 *   ./gradlew test      run through JUnit (IDE-friendly, red/green per test)
 *   ./gradlew spec      run this class directly (no dependencies at all)
 * </pre>
 *
 * <p>Every check is deterministic. Timing is driven by the {@code tsNanos} values
 * supplied by the checks themselves, never by the wall clock, so results do not
 * depend on how fast the machine is.
 */
public final class SpecChecks {

    private static final double EPS = 1e-9;

    private static final String SYM = "EURUSD";

    private static final long T0 = 1_000_000_000L;

    private static final long SECOND = 1_000_000_000L;

    /** Cached so the allocation load runs once even though two checks read it. */
    private static double bytesPerQuote = -1;

    private SpecChecks() {
    }

    // =========================================================== the nine rules

    /** Rule 1. Passes against the starting code — included to catch regressions. */
    public static void bestBidIsHighestAcrossLps() throws Exception {
        try (Fixture f = new Fixture()) {
            f.quote("LP-A", 1.10000, 1.10050, T0);
            f.quote("LP-B", 1.10100, 1.10150, T0);
            f.awaitBestBid(1.10100);
            assertEquals(1.10100, f.agg.getBestBid(SYM), "best bid");
        }
    }

    /** Rule 2. */
    public static void bestAskIsLowestAcrossLps() throws Exception {
        try (Fixture f = new Fixture()) {
            f.quote("LP-A", 1.10000, 1.10050, T0);
            f.quote("LP-B", 1.10100, 1.10150, T0);
            f.awaitBestBid(1.10100);
            assertEquals(1.10050, f.agg.getBestAsk(SYM),
                    "best ask (LP-A quotes the lower ask, LP-B the higher bid: "
                            + "the two sides come from different LPs)");
        }
    }

    /** Rule 3. */
    public static void outOfOrderQuotesAreIgnored() throws Exception {
        try (Fixture f = new Fixture()) {
            f.quote("LP-A", 1.10000, 1.10050, T0);
            f.awaitBestBid(1.10000);
            // Same LP, older timestamp, worse price: a late or replayed message.
            f.quote("LP-A", 1.09000, 1.09050, T0 - SECOND / 2);
            f.settle();
            assertEquals(1.10000, f.agg.getBestBid(SYM),
                    "best bid after a stale out-of-order quote (it must be discarded, "
                            + "not applied)");
        }
    }

    /** Rule 4. */
    public static void staleQuotesExpire() throws Exception {
        try (Fixture f = new Fixture()) {
            f.quote("LP-A", 1.20000, 1.20050, T0);
            f.quote("LP-B", 1.10000, 1.10050, T0);
            f.awaitBestBid(1.20000);
            // Three seconds of event time later, only LP-B is still quoting, so
            // LP-A's quote is now beyond the 2s TTL and must drop out.
            f.quote("LP-B", 1.10000, 1.10050, T0 + 3 * SECOND);
            f.awaitBestBid(1.10000);
            assertEquals(1.10000, f.agg.getBestBid(SYM),
                    "best bid after LP-A's quote exceeded the 2s TTL");
            assertEquals(1.10050, f.agg.getBestAsk(SYM),
                    "best ask after LP-A's quote exceeded the 2s TTL");
        }
    }

    /** Rule 5. */
    public static void publishesOnlyWhenTopOfBookChanges() throws Exception {
        try (Fixture f = new Fixture()) {
            f.quote("LP-A", 1.10000, 1.10050, T0);
            f.awaitPublishes(1);
            int before = f.publishes();
            // Identical prices, newer timestamp: top of book has not moved.
            f.quote("LP-A", 1.10000, 1.10050, T0 + 1_000_000L);
            f.settle();
            assertEquals(before, f.publishes(),
                    "publish count after a quote that does not move top of book");
        }
    }

    /** Rule 6. */
    public static void stopTerminatesTheWorkerThread() throws Exception {
        Fixture f = new Fixture();
        try {
            f.quote("LP-A", 1.10000, 1.10050, T0);
            f.awaitBestBid(1.10000);
            f.agg.stop();
            if (!awaitTrue(() -> !f.agg.isRunning(), 1_000)) {
                throw new AssertionError(
                        "worker thread still alive one second after stop() returned");
            }
        } finally {
            f.close();
        }
    }

    /** Rule 7. */
    public static void hotPathAllocatesUnder64BytesPerQuote() throws Exception {
        double perQuote = measureAllocation();
        if (perQuote >= 64.0) {
            throw new AssertionError(String.format(
                    "hot path allocated %.1f bytes per quote (limit 64)", perQuote));
        }
    }

    /** Rule 8 (stretch). */
    public static void crossedBookIsDetectedAndNotPublished() throws Exception {
        try (Fixture f = new Fixture()) {
            // LP-A is stale-high. Neither LP is internally crossed, but the
            // aggregate is: best bid 1.10100 (A) sits above best ask 1.10050 (B).
            f.quote("LP-A", 1.10100, 1.10150, T0);
            f.awaitPublishes(1);
            int before = f.publishes();
            f.quote("LP-B", 1.09950, 1.10050, T0);
            f.settle();
            if (!f.agg.isCrossed(SYM)) {
                throw new AssertionError(String.format(
                        "LP-A quotes 1.10100/1.10150 and LP-B quotes 1.09950/1.10050, so the "
                        + "aggregate is bid 1.10100 / ask 1.10050, which is crossed. "
                        + "isCrossed() returned false. This implementation reports "
                        + "bid %.5f / ask %.5f.",
                        f.agg.getBestBid(SYM), f.agg.getBestAsk(SYM)));
            }
            assertEquals(before, f.publishes(),
                    "publish count after the book became crossed (a crossed book must "
                            + "not be published)");
        }
    }

    /** Rule 9 (stretch). */
    public static void hotPathAllocatesEssentiallyNothing() throws Exception {
        double perQuote = measureAllocation();
        if (perQuote >= 2.0) {
            throw new AssertionError(String.format(
                    "hot path allocated %.1f bytes per quote (limit 2)", perQuote));
        }
    }

    // ============================================================= measurement

    /**
     * Runs the load once and caches the result.
     *
     * <p>Allocation is attributed to the feeding thread plus every thread the
     * aggregator creates, and to nothing else — so the figure is unaffected by
     * whatever else the JVM is doing (Gradle's test worker, the IDE, JIT
     * threads). That is what makes this assertion safe to run anywhere.
     */
    static synchronized double measureAllocation() throws Exception {
        if (bytesPerQuote >= 0) {
            return bytesPerQuote;
        }
        final String[] symbols = {"EURUSD", "GBPUSD", "USDJPY", "USDCHF",
                                  "AUDUSD", "USDCAD", "XAUUSD", "XAGUSD"};
        final String[] lps = {"LP-A", "LP-B", "LP-C", "LP-D", "LP-E", "LP-F"};
        final int warmup = 50_000;
        final int measured = 200_000;

        Set<Long> preExisting = new HashSet<>();
        for (long id : ManagementFactory.getThreadMXBean().getAllThreadIds()) {
            preExisting.add(id);
        }

        PriceAggregatorApi agg = newAggregator();
        agg.start();
        try {
            feed(agg, symbols, lps, warmup, T0);
            Thread.sleep(1_000L);

            long[] ids = ownedThreadIds(preExisting);
            long before = allocatedBytes(ids);
            feed(agg, symbols, lps, measured, T0 + 10 * SECOND);
            Thread.sleep(1_500L);
            long after = allocatedBytes(ownedThreadIds(preExisting));

            bytesPerQuote = (double) (after - before) / measured;
            return bytesPerQuote;
        } finally {
            agg.stop();
        }
    }

    /** This thread, plus every thread that did not exist before the aggregator did. */
    private static long[] ownedThreadIds(Set<Long> preExisting) {
        long[] all = ManagementFactory.getThreadMXBean().getAllThreadIds();
        long self = Thread.currentThread().getId();
        long[] owned = new long[all.length + 1];
        int n = 0;
        owned[n++] = self;
        for (long id : all) {
            if (id != self && !preExisting.contains(id)) {
                owned[n++] = id;
            }
        }
        long[] result = new long[n];
        System.arraycopy(owned, 0, result, 0, n);
        return result;
    }

    /** Allocation-free feed loop, so we measure the aggregator and not the check. */
    private static void feed(PriceAggregatorApi agg, String[] symbols, String[] lps,
                             int count, long baseTs) {
        for (int i = 0; i < count; i++) {
            String symbol = symbols[i % symbols.length];
            String lp = lps[(i / symbols.length) % lps.length];
            double mid = 1.08500d + ((i % 200) * 0.00001d);
            agg.onQuote(symbol, lp, mid - 0.00005d, mid + 0.00005d, baseTs + i * 1_000L);
        }
    }

    private static long allocatedBytes(long[] ids) {
        java.lang.management.ThreadMXBean base = ManagementFactory.getThreadMXBean();
        if (!(base instanceof com.sun.management.ThreadMXBean)) {
            return 0L;
        }
        com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) base;
        long total = 0L;
        for (long b : bean.getThreadAllocatedBytes(ids)) {
            if (b > 0) {
                total += b;
            }
        }
        return total;
    }

    // ================================================================= support

    static PriceAggregatorApi newAggregator() {
        return new PriceAggregator();
    }

    static boolean awaitTrue(BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(2L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    static void assertEquals(double expected, double actual, String what) {
        if (Double.isNaN(expected) != Double.isNaN(actual)
                || (!Double.isNaN(expected) && Math.abs(expected - actual) > EPS)) {
            throw new AssertionError(String.format(
                    "%s: expected %.5f but was %.5f", what, expected, actual));
        }
    }

    static void assertEquals(int expected, int actual, String what) {
        if (expected != actual) {
            throw new AssertionError(String.format(
                    "%s: expected %d but was %d", what, expected, actual));
        }
    }

    /** One aggregator plus a recording listener, torn down after each check. */
    static final class Fixture implements AutoCloseable {

        final PriceAggregatorApi agg;
        private final int[] publishCount = new int[1];
        private final Object countLock = new Object();

        Fixture() throws Exception {
            agg = newAggregator();
            agg.addListener((symbol, bestBid, bestAsk, ts) -> {
                synchronized (countLock) {
                    publishCount[0]++;
                }
            });
            agg.start();
        }

        int publishes() {
            synchronized (countLock) {
                return publishCount[0];
            }
        }

        void quote(String lp, double bid, double ask, long tsNanos) {
            agg.onQuote(SYM, lp, bid, ask, tsNanos);
        }

        void awaitBestBid(double expected) {
            awaitTrue(() -> Math.abs(agg.getBestBid(SYM) - expected) <= EPS, 2_000);
        }

        void awaitPublishes(int atLeast) {
            awaitTrue(() -> publishes() >= atLeast, 2_000);
        }

        /** Long enough that any update would certainly have been observed. */
        void settle() {
            try {
                Thread.sleep(300L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void close() {
            agg.stop();
        }
    }

    // ============================================== standalone runner (no deps)

    private interface Check {
        void run() throws Exception;
    }

    public static void main(String[] args) {
        Map<String, Check> core = new LinkedHashMap<>();
        core.put("1. best bid is the highest bid across all LPs",
                SpecChecks::bestBidIsHighestAcrossLps);
        core.put("2. best ask is the lowest ask across all LPs",
                SpecChecks::bestAskIsLowestAcrossLps);
        core.put("3. out-of-order quotes are ignored",
                SpecChecks::outOfOrderQuotesAreIgnored);
        core.put("4. stale quotes expire out of top of book",
                SpecChecks::staleQuotesExpire);
        core.put("5. updates are published only when top of book changes",
                SpecChecks::publishesOnlyWhenTopOfBookChanges);
        core.put("6. stop() terminates the worker thread",
                SpecChecks::stopTerminatesTheWorkerThread);
        core.put("7. hot path allocates under 64 bytes per quote",
                SpecChecks::hotPathAllocatesUnder64BytesPerQuote);

        Map<String, Check> stretch = new LinkedHashMap<>();
        stretch.put("8. a crossed book is detected and not published",
                SpecChecks::crossedBookIsDetectedAndNotPublished);
        stretch.put("9. hot path allocates essentially nothing (under 2 bytes/quote)",
                SpecChecks::hotPathAllocatesEssentiallyNothing);

        System.out.println("Checking etrading.PriceAggregator against SPEC.md");
        System.out.println();
        int corePassed = runAll(core);
        int stretchPassed = runAll(stretch);

        System.out.println();
        System.out.println("----------------------------------------------------------------");
        System.out.printf("CORE     %d / %d passed%n", corePassed, core.size());
        System.out.printf("STRETCH  %d / %d passed%n", stretchPassed, stretch.size());
        System.out.println("----------------------------------------------------------------");
        if (corePassed < core.size()) {
            System.out.println("Core rules are still violated. SPEC.md says what each rule requires.");
        } else if (stretchPassed < stretch.size()) {
            System.out.println("All core rules satisfied. Stretch rules remain.");
        } else {
            System.out.println("All rules satisfied.");
        }
        System.exit(corePassed == core.size() ? 0 : 1);
    }

    private static int runAll(Map<String, Check> checks) {
        int passed = 0;
        for (Map.Entry<String, Check> e : checks.entrySet()) {
            try {
                e.getValue().run();
                System.out.printf("[PASS] %s%n", e.getKey());
                passed++;
            } catch (AssertionError err) {
                System.out.printf("[FAIL] %s%n       %s%n", e.getKey(), err.getMessage());
            } catch (Exception ex) {
                System.out.printf("[FAIL] %s%n       threw %s%n", e.getKey(), ex);
            }
        }
        return passed;
    }
}
