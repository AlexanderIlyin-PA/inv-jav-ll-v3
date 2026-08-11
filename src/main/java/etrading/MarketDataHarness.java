package etrading;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Replays a synthetic market data feed through a PriceAggregatorApi and reports
 * latency percentiles, allocation and GC behaviour.
 *
 * Usage:
 *   ./gradlew harness
 *   ./gradlew harness --args="200000 10"
 *   ./gradlew harness --args="200000 10 slow"
 *   ./gradlew harness --args="200000 10 --slow-listener=200"
 *
 * Arguments are [quotesPerSecond] [seconds] [slow | --slow-listener=&lt;micros&gt;].
 * Both positional arguments are optional and default to 200000 and 10. The slow
 * token is optional and may appear in any position, so older invocations keep
 * working unchanged.
 *
 * Latency measured here is: (time the listener sees the update) minus
 * (time the quote was handed to onQuote). That is the aggregator's contribution
 * to end-to-end latency, which is what the SLA is written against.
 *
 * Every run writes its headline numbers to a small state file in the project
 * root (see STATE_FILE) and the next run prints how each number moved, keyed by a
 * monotonic run counter rather than by the clock. The state file is best-effort:
 * if it is missing, empty, corrupt, from an older format or unwritable the run
 * still completes, it simply reports that no comparison is available.
 *
 * Output is deliberately plain ASCII inside 80 columns: this is read on Windows
 * consoles where anything else prints as '?'.
 */
public final class MarketDataHarness {

    private static final int MAX_SAMPLES = 8_000_000;

    private static final long[] SAMPLES = new long[MAX_SAMPLES];

    /**
     * Written only by the publishing thread. Volatile so the main thread reads a
     * current value; the main thread never writes it, it records the index the
     * measured window started at instead, so no increment can be lost.
     */
    private static volatile int sampleCount = 0;

    /** Consumes published values so the JIT cannot optimise the listener away. */
    private static double sink;

    // --- run-to-run comparison ------------------------------------------------

    /** Where the previous run's numbers live. Git-ignored; safe to delete. */
    private static final String STATE_FILE = ".harness-last";

    /** Bumped only if the meaning of existing keys changes. */
    private static final String STATE_FORMAT = "1";

    /** A state file with none of these in it is not harness output. */
    private static final String[] KNOWN_KEYS = {
            "run", "bytesPerQuote", "allocBytes", "gcCount", "gcMillis",
            "p50Micros", "p90Micros", "p99Micros", "published", "processed", "offered"
    };

    // --- slow-listener mode ---------------------------------------------------

    /** Default burn per callback for the slow listener, in microseconds. */
    private static final int DEFAULT_SLOW_MICROS = 200;

    private static boolean slowMode = false;

    private static long slowBurnNanos = DEFAULT_SLOW_MICROS * 1_000L;

    /**
     * Written by the publishing thread only, read by the main thread after the
     * run. Volatile so the read is not a stale cache line; the main thread never
     * writes it, it subtracts a baseline instead, so there is no lost update.
     */
    private static volatile long slowCallbacks = 0;

    /** Value of slowCallbacks when the measured replay started. */
    private static long slowCallbacksBefore = 0;

    /** Keeps the slow listener's burn loop from being optimised away. */
    private static long slowSink;

    /**
     * Inbound-queue depth samples, taken from the feeding loop itself so that no
     * extra thread pollutes the allocation figure. Pre-allocated: writing a
     * sample is a store into this array and nothing else.
     */
    private static final int MAX_DEPTH_SAMPLES = 1 << 16;

    private static final long[] DEPTH_SAMPLES = new long[MAX_DEPTH_SAMPLES];

    /** Sample every 4096th quote: (i & DEPTH_MASK) == 0. */
    private static final int DEPTH_MASK = 4095;

    private static int depthSampleCount = 0;

    /** True while the measured replay should record queue depth. */
    private static boolean sampleDepth = false;

    /** Baseline for getQuotesProcessed() during the measured replay. */
    private static long processedBaseline = 0L;

    /** Quotes still queued when the measured replay starts. */
    private static long depthOffset = 0L;

    /**
     * Feeding stops if the API-visible backlog passes this, so that an
     * aggregator which cannot keep up gets reported rather than killing the run
     * with an OutOfMemoryError. Sized from the heap in main(), before measuring.
     */
    private static long backlogCeiling = Long.MAX_VALUE;

    /** Quote index at which feeding gave up, or -1. */
    private static long stoppedEarlyAt = -1L;

    private static long stoppedEarlyDepth = 0L;

    /** Rough heap cost of one queued quote: object, queue node and references. */
    private static final long BYTES_PER_QUEUED_QUOTE = 96L;

    public static void main(String[] args) throws Exception {
        int rate = 200_000;
        int seconds = 10;

        // Argument parsing. Unrecognised tokens are reported and ignored rather
        // than thrown, so a stale command line never costs a measurement.
        int positional = 0;
        for (String raw : args) {
            String arg = raw == null ? "" : raw.trim();
            if (arg.isEmpty()) {
                continue;
            }
            if (arg.equalsIgnoreCase("slow") || arg.toLowerCase().startsWith("--slow-listener")) {
                slowMode = true;
                int eq = arg.indexOf('=');
                if (eq >= 0) {
                    try {
                        int micros = Integer.parseInt(arg.substring(eq + 1).trim());
                        if (micros > 0) {
                            slowBurnNanos = micros * 1_000L;
                        } else {
                            System.out.println("harness: --slow-listener needs a positive "
                                    + "number of microseconds; using "
                                    + DEFAULT_SLOW_MICROS + ".");
                        }
                    } catch (NumberFormatException e) {
                        System.out.println("harness: could not read the burn time from '"
                                + arg + "'; using " + DEFAULT_SLOW_MICROS + " microseconds.");
                    }
                }
                continue;
            }
            try {
                int value = Integer.parseInt(arg);
                if (positional == 0) {
                    rate = value;
                } else if (positional == 1) {
                    seconds = value;
                } else {
                    System.out.println("harness: ignoring extra argument '" + arg + "'.");
                    continue;
                }
                positional++;
            } catch (NumberFormatException e) {
                System.out.println("harness: ignoring unrecognised argument '" + arg + "'.");
                System.out.println("         usage: [quotesPerSecond] [seconds] "
                        + "[slow|--slow-listener=<micros>]");
            }
        }
        if (rate <= 0) {
            System.out.println("harness: rate must be positive; using 200000.");
            rate = 200_000;
        }
        if (seconds <= 0) {
            System.out.println("harness: duration must be positive; using 10.");
            seconds = 10;
        }

        String argLine = describeArgs(args);
        Path stateFile = Paths.get(STATE_FILE).toAbsolutePath();

        // Read the previous run before anything is measured: this allocates, and
        // the allocation counters are only started after warm-up, below.
        PreviousRun previous = PreviousRun.load(stateFile);
        long runNumber = previous.nextRunNumber();
        String startedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();

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

        if (slowMode) {
            // Registered after the fast listener, so the fast listener is called
            // first on each update: what it sees is the queue backing up, not the
            // burn inside its own callback.
            aggregator.addListener(new QuoteListener() {
                @Override
                public void onBest(String symbol, double bid, double ask, long eventTsNanos) {
                    slowCallbacks++;
                    long until = System.nanoTime() + slowBurnNanos;
                    long spins = 0;
                    while (System.nanoTime() < until) {
                        spins++;
                        Thread.onSpinWait();
                    }
                    slowSink += spins;
                }
            });
        }

        System.out.println("run #" + runNumber + "  args=\"" + argLine + "\"  " + startedAt);
        System.out.println("rate=" + rate + "/s  duration=" + seconds + "s");
        System.out.println("jvm=" + System.getProperty("java.vm.name")
                + " " + System.getProperty("java.version"));
        if (slowMode) {
            System.out.println("slow-listener mode ON: extra listener burns "
                    + (slowBurnNanos / 1000L) + "us per callback");
            System.out.println("NOTE: a slow consumer at full rate backs the inbound queue up "
                    + "fast.");
            System.out.println("      This harness stops feeding before the heap is gone and "
                    + "says so,");
            System.out.println("      so start with a short run and read the depth numbers.");
        }
        System.out.println();

        // Sized once, before anything is measured: half of the heap that is
        // still free, at a rough 96 bytes per queued quote.
        Runtime runtime = Runtime.getRuntime();
        long headroom = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
        backlogCeiling = Math.max(50_000L, headroom / 2 / BYTES_PER_QUEUED_QUOTE);

        aggregator.start();

        // Warm up so we measure steady state, not the interpreter.
        long warmUpSent = replay(aggregator, Math.min(rate, 50_000), 2);
        depthSampleCount = 0;
        if (stoppedEarlyAt >= 0) {
            System.out.println("NOTE: warm-up stopped feeding after " + warmUpSent
                    + " quotes: the backlog");
            System.out.println("      reached " + stoppedEarlyDepth
                    + ", the safety ceiling for this heap. The aggregator");
            System.out.println("      is not keeping up even at warm-up rate.");
            System.out.println();
            stoppedEarlyAt = -1L;
        }

        long processedBefore = aggregator.getQuotesProcessed();
        long publishedBefore = aggregator.getQuotesPublished();
        int sampleBase = sampleCount;
        slowCallbacksBefore = slowCallbacks;
        long gcCountBefore = gcCount();
        long gcTimeBefore = gcTimeMillis();
        long allocBefore = allocatedBytes();
        long wallBefore = System.nanoTime();

        processedBaseline = processedBefore;
        // A slow listener can leave warm-up quotes still queued. Counting them
        // keeps the reported depth absolute rather than relative to this run.
        depthOffset = Math.max(0L, warmUpSent - processedBefore);
        sampleDepth = slowMode;

        long sent = replay(aggregator, rate, seconds);
        sampleDepth = false;

        long wallAfter = System.nanoTime();
        // Give the consumer a moment to drain any backlog.
        Thread.sleep(1000);
        long allocAfter = allocatedBytes();
        long gcCountAfter = gcCount();
        long gcTimeAfter = gcTimeMillis();

        long processed = aggregator.getQuotesProcessed() - processedBefore;
        long published = aggregator.getQuotesPublished() - publishedBefore;
        int sampleEnd = Math.max(sampleBase, sampleCount);
        // Read now, not while reporting: with a backlog the publishing thread is
        // still running and every printed line would let the count drift.
        long slowDeliveries = slowCallbacks - slowCallbacksBefore;

        // Everything from here on is reporting: it allocates freely, but every
        // counter it reads was captured above, before this line.
        if (stoppedEarlyAt >= 0) {
            printStoppedEarly((long) rate * seconds, sent);
        }
        long[] sorted = report(sampleBase, sampleEnd, sent, wallAfter - wallBefore,
                processed, published,
                allocAfter - allocBefore,
                gcCountAfter - gcCountBefore,
                gcTimeAfter - gcTimeBefore);

        if (slowMode) {
            reportSlowListener(sorted, sent, processed, published, slowDeliveries);
        }

        Map<String, String> state = buildState(runNumber, startedAt, argLine, rate, seconds,
                sent, processed, published, allocAfter - allocBefore,
                gcCountAfter - gcCountBefore, gcTimeAfter - gcTimeBefore, sorted);

        printComparison(previous, state, stateFile);
        save(stateFile, state);

        aggregator.stop();
        System.out.println("(sink=" + (long) sink
                + (slowMode ? " slowSink=" + slowSink : "") + ")");
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
            // Allocation-free: one getter call every 4096 quotes, plus a store
            // into a pre-allocated array when depth sampling is on.
            if ((i & DEPTH_MASK) == 0) {
                long depth = depthOffset + i
                        - (aggregator.getQuotesProcessed() - processedBaseline);
                if (depth < 0) {
                    depth = 0;
                }
                if (sampleDepth && depthSampleCount < MAX_DEPTH_SAMPLES) {
                    DEPTH_SAMPLES[depthSampleCount++] = depth;
                }
                if (depth > backlogCeiling) {
                    // An aggregator with an unbounded queue that cannot keep up
                    // will fill the heap, and an OutOfMemoryError kills the run
                    // before it can report anything. Stop feeding and report.
                    stoppedEarlyAt = i;
                    stoppedEarlyDepth = depth;
                    return i;
                }
            }
            SyntheticFeed.send(aggregator, i, System.nanoTime());
        }
        return total;
    }

    /**
     * Explains a short run. The alternative is an OutOfMemoryError, which kills
     * the JVM before any of the numbers below can be printed.
     */
    private static void printStoppedEarly(long intended, long sent) {
        System.out.println(heading("stopped feeding early"));
        System.out.printf("The inbound backlog reached %,d quotes after %,d of %,d%n",
                stoppedEarlyDepth, sent, intended);
        System.out.printf("offered, which is this harness's safety ceiling of %,d for a%n",
                backlogCeiling);
        System.out.printf("%,d MB heap. The aggregator is not keeping up and its queue is%n",
                Runtime.getRuntime().maxMemory() / (1024L * 1024L));
        System.out.println("unbounded, so feeding on would have exhausted the heap. Every");
        System.out.println("number below is over the quotes that were actually offered.");
        System.out.println();
    }

    /** Prints the standard report and returns the sorted latency samples. */
    private static long[] report(int sampleBase, int sampleEnd, long sent, long wallNanos,
                              long processed, long published,
                              long allocBytes, long gcCount, long gcMillis) {
        // Only the samples the measured window produced: warm-up deliveries sit
        // below sampleBase, and deliveries that happen while this report prints
        // sit above sampleEnd.
        long[] used = Arrays.copyOfRange(SAMPLES, sampleBase, sampleEnd);
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
        if (used.length == 0) {
            System.out.println("NO LATENCY SAMPLES: nothing was published, so the figures below");
            System.out.println("are zero for lack of data, not because anything was fast.");
        }
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
        return used;
    }

    // -------------------------------------------------------------------------
    // Slow-listener mode. Reports only: it never asserts and never fails a run.
    // -------------------------------------------------------------------------

    private static void reportSlowListener(long[] sorted, long sent, long processed,
                                           long published, long slowDeliveries) {
        System.out.println(heading("slow listener (" + (slowBurnNanos / 1000L)
                + "us per callback)"));
        System.out.printf("slow callbacks      %,d%n", slowDeliveries);
        System.out.printf("quotes offered      %,d%n", sent);
        System.out.printf("quotes processed    %,d%n", processed);
        System.out.printf("updates published   %,d%n", published);
        System.out.printf("offered - published %,d%n", sent - published);
        if (sent > 0) {
            System.out.printf("published / offered %.1f%%%n", 100.0d * published / sent);
        }
        if (published == 0) {
            System.out.println("nothing was published, so the slow listener was never called and");
            System.out.println("the delivery figures below have no data behind them.");
        } else if (published > sent) {
            System.out.println("more was published than offered in this window: the consumer was");
            System.out.println("still draining the backlog warm-up left behind. Not a miscount.");
        }
        System.out.println();

        System.out.println("inbound queue depth over time");
        System.out.println("  PriceAggregatorApi exposes no queue-depth accessor, so this is the");
        System.out.println("  API-visible proxy: quotes offered minus getQuotesProcessed(),");
        System.out.println("  sampled from the feeding thread every " + (DEPTH_MASK + 1)
                + " quotes. It is an upper");
        System.out.println("  bound on the real inbound queue (it also counts the quote in "
                + "flight)");
        System.out.printf("  and it includes the %,d quotes still queued when warm-up "
                + "ended.%n", depthOffset);
        if (depthSampleCount == 0) {
            System.out.println("  NO DEPTH SAMPLES: the run was too short to take one.");
        } else if (processed == 0) {
            System.out.println("  getQuotesProcessed() never advanced, so depth is "
                    + "indistinguishable");
            System.out.println("  from the total offered: the numbers below say nothing about "
                    + "queueing.");
        }
        if (depthSampleCount > 0) {
            long[] depths = Arrays.copyOf(DEPTH_SAMPLES, depthSampleCount);
            long first = depths[0];
            long last = depths[depthSampleCount - 1];
            Arrays.sort(depths);
            System.out.printf("  samples           %,d%n", depths.length);
            System.out.printf("  depth at start    %,d%n", first);
            System.out.printf("  depth at end      %,d%n", last);
            System.out.printf("  median depth      %,d%n", percentile(depths, 50.0));
            System.out.printf("  p90 depth         %,d%n", percentile(depths, 90.0));
            System.out.printf("  max depth         %,d%n", depths[depths.length - 1]);
            printDepthTimeline();
        }
        System.out.println();

        System.out.println("fast listener end-to-end delivery latency (microseconds)");
        if (sorted.length == 0) {
            System.out.println("  NO SAMPLES: the fast listener was never called, so there is");
            System.out.println("  nothing to report - not even a fast zero.");
        } else {
            System.out.printf("  samples           %,d%n", sorted.length);
            System.out.printf("  p50               %,.1f%n", micros(percentile(sorted, 50.0)));
            System.out.printf("  p90               %,.1f%n", micros(percentile(sorted, 90.0)));
            System.out.printf("  p99               %,.1f%n", micros(percentile(sorted, 99.0)));
            System.out.printf("  max               %,.1f%n",
                    micros(sorted[sorted.length - 1]));
            System.out.println("  The fast listener is registered first, so this is what a "
                    + "well-behaved");
            System.out.println("  consumer sees while a badly behaved one shares the "
                    + "publishing thread.");
        }
        System.out.println();
        System.out.println("This models a slow downstream consumer. The right response is "
                + "a design");
        System.out.println("decision - conflation (publish the latest price per symbol and drop");
        System.out.println("intermediate ticks) or backpressure - not a tuning flag.");
        System.out.println();
    }

    /** Compact "percent of run = depth" timeline, wrapped inside 80 columns. */
    private static void printDepthTimeline() {
        int points = Math.min(8, depthSampleCount);
        StringBuilder line = new StringBuilder("  timeline          ");
        int indent = line.length();
        for (int k = 0; k < points; k++) {
            int idx = points == 1 ? 0 : (int) ((long) k * (depthSampleCount - 1) / (points - 1));
            int pct = depthSampleCount == 1 ? 0 : (int) (100L * idx / (depthSampleCount - 1));
            String piece = pct + "%=" + DEPTH_SAMPLES[idx];
            if (line.length() + piece.length() + 1 > 78) {
                System.out.println(line);
                line.setLength(0);
                for (int s = 0; s < indent; s++) {
                    line.append(' ');
                }
            } else if (line.length() > indent) {
                line.append(' ');
            }
            line.append(piece);
        }
        if (line.toString().trim().length() > 0) {
            System.out.println(line);
        }
    }

    // -------------------------------------------------------------------------
    // Run-to-run comparison
    // -------------------------------------------------------------------------

    private static Map<String, String> buildState(long runNumber, String startedAt, String argLine,
                                                  int rate, int seconds, long sent, long processed,
                                                  long published, long allocBytes, long gcCount,
                                                  long gcMillis, long[] sorted) {
        Map<String, String> state = new LinkedHashMap<>();
        state.put("format", STATE_FORMAT);
        state.put("run", Long.toString(runNumber));
        state.put("timestampUtc", startedAt);
        state.put("args", argLine);
        state.put("rate", Integer.toString(rate));
        state.put("seconds", Integer.toString(seconds));
        state.put("slowListenerMicros", slowMode ? Long.toString(slowBurnNanos / 1000L) : "0");
        state.put("offered", Long.toString(sent));
        state.put("processed", Long.toString(processed));
        state.put("published", Long.toString(published));
        state.put("allocBytes", Long.toString(allocBytes));
        state.put("bytesPerQuote", String.format("%.1f", sent == 0
                ? 0.0d : (double) allocBytes / sent));
        state.put("gcCount", Long.toString(gcCount));
        state.put("gcMillis", Long.toString(gcMillis));
        state.put("samples", Integer.toString(sorted.length));
        state.put("p50Micros", String.format("%.1f", micros(percentile(sorted, 50.0))));
        state.put("p90Micros", String.format("%.1f", micros(percentile(sorted, 90.0))));
        state.put("p99Micros", String.format("%.1f", micros(percentile(sorted, 99.0))));
        return state;
    }

    private static void printComparison(PreviousRun previous, Map<String, String> now,
                                        Path stateFile) {
        System.out.println(heading("change since the previous run"));
        if (!previous.usable()) {
            printWrapped(previous.explanation());
            System.out.println("This is run #" + now.get("run") + ", so the numbers above are the");
            System.out.println("baseline. Change something and run the harness again to see how");
            System.out.println("each number moved.");
            System.out.println("state file: " + stateFile);
            System.out.println();
            return;
        }

        String prevRun = previous.text("run", "?");
        String prevArgs = previous.text("args", "(unknown)");
        System.out.println("previous  run #" + prevRun + "  args=\"" + prevArgs + "\"  "
                + previous.text("timestampUtc", "(no timestamp)"));
        System.out.println("current   run #" + now.get("run") + "  args=\"" + now.get("args")
                + "\"  " + now.get("timestampUtc"));
        System.out.println("(run numbers order the runs; the timestamps are only labels)");
        if (previous.formatMismatch()) {
            System.out.println("NOTE: that run was written by a different harness version, so");
            System.out.println("      only the fields it does contain are compared.");
        }
        if (!prevArgs.equals(now.get("args"))) {
            System.out.println("NOTE: the arguments differ, so these two runs are not directly");
            System.out.println("      comparable. Re-run with the previous arguments to compare.");
        }
        System.out.println();

        deltaLine("bytes/quote", previous, now, "bytesPerQuote", 1);
        deltaLine("GC count", previous, now, "gcCount", 0);
        deltaLine("GC time (ms)", previous, now, "gcMillis", 0);
        deltaLine("p50 (us)", previous, now, "p50Micros", 1);
        deltaLine("p90 (us)", previous, now, "p90Micros", 1);
        deltaLine("p99 (us)", previous, now, "p99Micros", 1);
        deltaLine("published", previous, now, "published", 0);
        deltaLine("processed", previous, now, "processed", 0);
        System.out.println();
        System.out.println("Lower is better for every line except published and processed.");
        System.out.println("state file: " + stateFile);
        System.out.println();
    }

    private static void deltaLine(String label, PreviousRun previous, Map<String, String> now,
                                  String key, int decimals) {
        Double before = previous.number(key);
        Double after = parseNumber(now.get(key));
        String afterText = after == null ? "n/a" : format(after, decimals);
        if (before == null || after == null) {
            System.out.printf("%-16s %13s -> %13s  (%s)%n", label,
                    before == null ? "n/a" : format(before, decimals), afterText,
                    "no comparison");
            return;
        }
        double delta = after - before;
        String deltaText = Math.abs(delta) < 1e-9
                ? "unchanged"
                : (delta > 0 ? "+" : "-") + format(Math.abs(delta), decimals);
        System.out.printf("%-16s %13s -> %13s  (%s)%n", label,
                format(before, decimals), afterText, deltaText);
    }

    private static String format(double value, int decimals) {
        if (decimals <= 0) {
            return String.format("%,d", Math.round(value));
        }
        return String.format("%,." + decimals + "f", value);
    }

    private static Double parseNumber(String text) {
        if (text == null) {
            return null;
        }
        try {
            double d = Double.parseDouble(text.trim());
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                return null;
            }
            return d;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Writes the state file. A failure here is reported, never fatal. */
    private static void save(Path stateFile, Map<String, String> state) {
        StringBuilder out = new StringBuilder();
        out.append("# MarketDataHarness: numbers from the last run. Git-ignored,\n");
        out.append("# machine-written, safe to delete.\n");
        for (Map.Entry<String, String> e : state.entrySet()) {
            out.append(e.getKey()).append('=').append(oneLine(e.getValue())).append('\n');
        }
        byte[] bytes = out.toString().getBytes(StandardCharsets.US_ASCII);
        Path temp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
        try {
            try {
                Files.write(temp, bytes);
                Files.move(temp, stateFile, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException | UnsupportedOperationException e) {
                // Atomic-ish write not possible here; a plain write is fine, a
                // torn file is handled as "corrupt" on the next run.
                Files.write(stateFile, bytes);
            }
        } catch (IOException | RuntimeException e) {
            printWrapped("NOTE: could not write the state file (" + reason(e) + ").");
            System.out.println("      This run still measured fine; the next run just will not");
            System.out.println("      have anything to compare against.");
            System.out.println();
        }
    }

    /** "--- title ------" padded to the same 52 columns as the other headings. */
    private static String heading(String title) {
        StringBuilder sb = new StringBuilder("--- ").append(title).append(' ');
        while (sb.length() < 52) {
            sb.append('-');
        }
        return sb.toString();
    }

    /** Prints prose inside 78 columns; long unbreakable tokens still overflow. */
    private static void printWrapped(String text) {
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() > 0 && line.length() + 1 + word.length() > 78) {
                System.out.println(line);
                line.setLength(0);
            } else if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word);
        }
        if (line.length() > 0) {
            System.out.println(line);
        }
    }

    private static String oneLine(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\r', ' ').replace('\n', ' ');
    }

    private static String describeArgs(String[] args) {
        if (args == null || args.length == 0) {
            return "(defaults)";
        }
        StringBuilder sb = new StringBuilder();
        for (String a : args) {
            if (a == null || a.trim().isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(oneLine(a.trim()));
        }
        return sb.length() == 0 ? "(defaults)" : sb.toString();
    }

    private static String reason(Throwable t) {
        String message = t.getMessage();
        String type = t.getClass().getSimpleName();
        return message == null || message.isEmpty() ? type : type + ": " + message;
    }

    /**
     * The previous run's numbers, or an explanation of why there are none. Never
     * throws: every failure mode collapses into "no comparison available".
     */
    private static final class PreviousRun {

        private final Map<String, String> values;
        private final String explanation;

        private PreviousRun(Map<String, String> values, String explanation) {
            this.values = values;
            this.explanation = explanation;
        }

        static PreviousRun load(Path stateFile) {
            List<String> lines;
            try {
                if (!Files.exists(stateFile)) {
                    return new PreviousRun(null,
                            "No previous run recorded (no " + STATE_FILE + " yet).");
                }
                if (!Files.isReadable(stateFile) || Files.isDirectory(stateFile)) {
                    return new PreviousRun(null, "No comparison available: " + stateFile
                            + " is not a readable file.");
                }
                lines = readLines(stateFile);
            } catch (Exception e) {
                // Includes IOException, security managers and odd file types.
                return new PreviousRun(null,
                        "No comparison available: could not read " + STATE_FILE
                                + " (" + reason(e) + ").");
            }

            Map<String, String> parsed = new LinkedHashMap<>();
            for (String line : lines) {
                if (line == null) {
                    continue;
                }
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = trimmed.substring(0, eq).trim();
                if (!key.isEmpty()) {
                    parsed.put(key, trimmed.substring(eq + 1).trim());
                }
            }
            if (parsed.isEmpty()) {
                return new PreviousRun(null, "No comparison available: " + STATE_FILE
                        + " is empty or has no readable key=value lines.");
            }
            if (!recognisable(parsed)) {
                // Binary or unrelated content that happened to contain a '='.
                return new PreviousRun(null, "No comparison available: " + STATE_FILE
                        + " does not look like harness output.");
            }
            return new PreviousRun(parsed, null);
        }

        /** True if at least one field this harness knows about is present. */
        private static boolean recognisable(Map<String, String> parsed) {
            for (String key : KNOWN_KEYS) {
                if (parsed.containsKey(key)) {
                    return true;
                }
            }
            return false;
        }

        private static List<String> readLines(Path stateFile) throws IOException {
            // Read as bytes and decode leniently: a truncated or binary file must
            // not throw, it must simply fail to parse.
            byte[] raw = Files.readAllBytes(stateFile);
            String text = new String(raw, StandardCharsets.ISO_8859_1);
            List<String> lines = new ArrayList<>();
            for (String line : text.split("\n", -1)) {
                lines.add(line);
            }
            return lines;
        }

        boolean usable() {
            return values != null;
        }

        String explanation() {
            return explanation == null ? "No comparison available." : explanation;
        }

        boolean formatMismatch() {
            return values != null && !STATE_FORMAT.equals(values.get("format"));
        }

        String text(String key, String fallback) {
            if (values == null) {
                return fallback;
            }
            String value = values.get(key);
            return value == null || value.isEmpty() ? fallback : value;
        }

        Double number(String key) {
            return values == null ? null : parseNumber(values.get(key));
        }

        /** Monotonic counter: wall-clock ordering of runs is not trustworthy. */
        long nextRunNumber() {
            Double previous = number("run");
            if (previous == null || previous < 0 || previous > 4e18) {
                return 1L;
            }
            return (long) (double) previous + 1L;
        }
    }

    // -------------------------------------------------------------------------

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
