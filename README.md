# Market data aggregator

`PriceAggregator` consolidates quotes from several liquidity providers into a
top-of-book per symbol and publishes it downstream. It runs at ~200,000 quotes/sec
in production and it is not behaving.

**Start by taking a measurement:**

```bash
./gradlew harness      # latency percentiles, bytes allocated per quote, GC counts
./gradlew spec         # the spec checks: 5 core, 2 stretch
```

Every later harness run prints how the numbers moved since the previous one.

## The work

| Path | What it is |
|---|---|
| `SPEC.md` | The behaviour specification. **Read this first.** |
| `src/main/java/etrading/PriceAggregator.java` | **The class under review. This is where the work is.** |
| `src/main/java/etrading/PriceAggregatorApi.java` | The contract other components depend on — please keep it. |
| `src/main/java/etrading/SpecChecks.java` | The spec as executable checks. Please do not edit. |
| `src/main/java/etrading/MarketDataHarness.java` | The load generator behind `./gradlew harness`. |
| `DISRUPTOR_REVIEW.md` | A separate snippet, for discussion. Nothing to run. |

Three of the seven rules are behavioural, one is about concurrency and shutdown,
and two are about allocation. Some fixes are small and local; at least one is a
design change. **Get as many green as you can**, and please **think out loud** —
how you decide what to do first matters more than how much you finish.

## Other commands

```bash
./gradlew test                          # same checks through JUnit, red/green per rule
./gradlew harness --args="200000 10"    # rate per second, seconds
./gradlew harness --args="200000 10 slow"   # plus a deliberately slow listener
```

The `slow` token (or `--slow-listener=<microseconds>`, default 200) registers a
second listener that burns that long in every callback, and reports queue depth,
what the *fast* listener still sees, and published-versus-offered counts. It only
reports: nothing there can fail a run.

Each run's headline numbers go into `.harness-last` (git-ignored) so the next run
can print the movement. Deleting that file only costs you one comparison.

The checks have no test-framework dependency, so if Gradle cannot reach the network
this always works:

```bash
javac -d out $(find src/main -name '*.java')
java -cp out etrading.SpecChecks
```

JDK 17 or newer for the code itself; the Gradle wrapper here is 8.14.3, which needs
a JDK of 24 or below to *run* — on JDK 25+ either point `JAVA_HOME` at an older JDK
or use the `javac` route above.

## A note on the numbers

On a laptop, VM or container the far latency tail (p99.9 and beyond) is dominated
by OS scheduling and JIT rather than by this code. **Bytes allocated per quote and
GC counts are the reliable signals**, which is why the spec is written against
allocation rather than latency.
