package etrading;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;

/**
 * Replays a synthetic market data feed through a PriceAggregatorApi and reports
 * latency percentiles, allocation and GC behaviour.
 *
 * Usage:
 *   ./gradlew harness --args="200000 10"
 *
 * Arguments are [quotesPerSecond] [seconds].
 *
 * Latency measured here is: (time the listener sees the update) minus
 * (time the quote was handed to onQuote). That is the aggregator's contribution
 * to end-to-end latency, which is what the SLA is written against.
 */
public final class MarketDataHarness {

    private static final String[] SYMBOLS = {
            "EURUSD", "GBPUSD", "USDJPY", "USDCHF",
            "AUDUSD", "USDCAD", "XAUUSD", "XAGUSD"
    };

    private static final String[] LPS = {
            "LP-ALPHA", "LP-BRAVO", "LP-CHARLIE",
            "LP-DELTA", "LP-ECHO", "LP-FOXTROT"
    };

    private static final int MAX_SAMPLES = 8_000_000;

    private static final long[] SAMPLES = new long[MAX_SAMPLES];

    private static int sampleCount = 0;

    /** Consumes published values so the JIT cannot optimise the listener away. */
    private static double sink;

    public static void main(String[] args) throws Exception {
        int rate = args.length > 0 ? Integer.parseInt(args[0]) : 200_000;
        int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 10;

        PriceAggregatorApi aggregator = new PriceAggregator();

        aggregator.addListener(new QuoteListener() {
            @Override
            public void onBest(String symbol, double bestBid, double bestAsk, long eventTsNanos) {
                long latency = System.nanoTime() - eventTsNanos;
                if (sampleCount < MAX_SAMPLES) {
                    SAMPLES[sampleCount++] = latency;
                }
                sink += bestBid;
            }
        });

        System.out.println("rate=" + rate + "/s  duration=" + seconds + "s");
        System.out.println("jvm=" + System.getProperty("java.vm.name")
                + " " + System.getProperty("java.version"));
        System.out.println();

        aggregator.start();

        // Warm up so we measure steady state, not the interpreter.
        replay(aggregator, Math.min(rate, 50_000), 2);
        sampleCount = 0;

        long processedBefore = aggregator.getQuotesProcessed();
        long publishedBefore = aggregator.getQuotesPublished();
        long gcCountBefore = gcCount();
        long gcTimeBefore = gcTimeMillis();
        long allocBefore = allocatedBytes();
        long wallBefore = System.nanoTime();

        long sent = replay(aggregator, rate, seconds);

        long wallAfter = System.nanoTime();
        // Give the consumer a moment to drain any backlog.
        Thread.sleep(1000);
        long allocAfter = allocatedBytes();
        long gcCountAfter = gcCount();
        long gcTimeAfter = gcTimeMillis();

        report(sent, wallAfter - wallBefore,
                aggregator.getQuotesProcessed() - processedBefore,
                aggregator.getQuotesPublished() - publishedBefore,
                allocAfter - allocBefore,
                gcCountAfter - gcCountBefore,
                gcTimeAfter - gcTimeBefore);

        aggregator.stop();
        System.out.println("(sink=" + (long) sink + ")");
        System.exit(0);
    }

    /** Paced replay: emits {@code rate} quotes per second for {@code seconds}. */
    private static long replay(PriceAggregatorApi aggregator, int rate, int seconds) {
        long total = (long) rate * seconds;
        long intervalNanos = 1_000_000_000L / rate;
        long start = System.nanoTime();

        for (long i = 0; i < total; i++) {
            long due = start + i * intervalNanos;
            while (System.nanoTime() < due) {
                Thread.onSpinWait();
            }
            String symbol = SYMBOLS[(int) (i % SYMBOLS.length)];
            String lp = LPS[(int) ((i / SYMBOLS.length) % LPS.length)];
            double mid = 1.08500d + ((i % 200) * 0.00001d);
            aggregator.onQuote(symbol, lp, mid - 0.00005d, mid + 0.00005d, System.nanoTime());
        }
        return total;
    }

    private static void report(long sent, long wallNanos, long processed, long published,
                              long allocBytes, long gcCount, long gcMillis) {
        long[] used = Arrays.copyOf(SAMPLES, sampleCount);
        Arrays.sort(used);

        double wallSeconds = wallNanos / 1e9d;

        System.out.println("--- throughput -------------------------------------");
        System.out.printf("quotes offered      %,d in %.2fs (%,.0f/s)%n",
                sent, wallSeconds, sent / wallSeconds);
        System.out.printf("quotes processed    %,d%n", processed);
        System.out.printf("updates published   %,d%n", published);
        System.out.printf("backlog at end      %,d%n", sent - processed);
        System.out.println();

        System.out.println("--- aggregator latency (microseconds) --------------");
        System.out.printf("samples             %,d%n", used.length);
        System.out.printf("p50                 %10.1f%n", micros(percentile(used, 50.0)));
        System.out.printf("p90                 %10.1f%n", micros(percentile(used, 90.0)));
        System.out.printf("p99                 %10.1f%n", micros(percentile(used, 99.0)));
        System.out.printf("p99.9               %10.1f   <-- SLA: under 50%n",
                micros(percentile(used, 99.9)));
        System.out.printf("p99.99              %10.1f%n", micros(percentile(used, 99.99)));
        System.out.printf("max                 %10.1f%n",
                micros(used.length == 0 ? 0 : used[used.length - 1]));
        System.out.println();

        System.out.println("--- memory / GC ------------------------------------");
        System.out.printf("allocated           %,d bytes (%.1f MB)%n",
                allocBytes, allocBytes / (1024.0 * 1024.0));
        System.out.printf("allocated per quote %,.1f bytes%n",
                sent == 0 ? 0 : (double) allocBytes / sent);
        System.out.printf("gc collections      %d%n", gcCount);
        System.out.printf("gc time             %d ms%n", gcMillis);
        System.out.println();
        System.out.println("NOTE: on a laptop, VM or shared/containerised box the far tail "
                + "(p99.9+)");
        System.out.println("      is dominated by OS scheduling and JIT, not by this code. "
                + "Compare");
        System.out.println("      p50/p90, allocation and GC counts between runs; treat the "
                + "far tail as");
        System.out.println("      valid only on a tuned, core-pinned host.");
        System.out.println();
    }

    private static double micros(long nanos) {
        return nanos / 1000.0d;
    }

    private static long percentile(long[] sorted, double p) {
        if (sorted.length == 0) {
            return 0L;
        }
        int index = (int) Math.ceil(p / 100.0d * sorted.length) - 1;
        if (index < 0) {
            index = 0;
        }
        if (index >= sorted.length) {
            index = sorted.length - 1;
        }
        return sorted[index];
    }

    private static long allocatedBytes() {
        java.lang.management.ThreadMXBean base = ManagementFactory.getThreadMXBean();
        if (!(base instanceof com.sun.management.ThreadMXBean)) {
            return 0L;
        }
        com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) base;
        long[] ids = base.getAllThreadIds();
        long[] bytes = bean.getThreadAllocatedBytes(ids);
        long total = 0L;
        for (long b : bytes) {
            if (b > 0) {
                total += b;
            }
        }
        return total;
    }

    private static long gcCount() {
        long total = 0L;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long c = bean.getCollectionCount();
            if (c > 0) {
                total += c;
            }
        }
        return total;
    }

    private static long gcTimeMillis() {
        long total = 0L;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long t = bean.getCollectionTime();
            if (t > 0) {
                total += t;
            }
        }
        return total;
    }
}
