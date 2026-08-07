# Market data aggregator

A Gradle project. JDK 17 or newer is the only prerequisite.

```bash
./gradlew test
```

You should see **1 of 7 core tests passing**. Getting the rest green is the exercise.

You can also open the folder in IntelliJ or VS Code — it imports as a standard
Gradle project, and you can run the tests from the gutter.

---

## The task

`PriceAggregator` consolidates quotes from several liquidity providers into a
top-of-book per symbol and publishes it downstream. It has been in production for
six years. Three tickets are open against it:

- **PLAT-4412** — latency spikes to 40 ms+ several times a minute under load
- **PLAT-4587** — best bid occasionally goes backwards, or shows a withdrawn price
- **PLAT-4601** — the service sometimes will not shut down and has to be `kill -9`'d

**[`SPEC.md`](SPEC.md) says what the correct behaviour is.** The test suite checks
each rule, one test per rule. Most of them fail.

**Get as many core tests green as you can, without breaking the one that already
passes.** Two stretch rules wait beyond the core seven.

Some fixes are small and local. Some of them are the same fix — one change to the
design turns several tests green at once, and spotting that is worth more than
patching them one at a time. Rule 7 is a performance rule rather than a
behavioural one, and it will not go green by accident.

Please **think out loud**: how you decide what to do first matters more here than
how much you finish. You are welcome to use your usual AI tooling — if you do,
narrate what you are asking it and how you are checking what comes back.

## Layout

| Path | What it is |
|---|---|
| `SPEC.md` | The behaviour specification. Read this first. |
| `src/main/java/etrading/PriceAggregator.java` | **The class under review. This is where the work is.** |
| `src/main/java/etrading/PriceAggregatorApi.java` | The contract other components depend on — please keep it. |
| `src/main/java/etrading/Quote.java`, `QuoteListener.java` | Supporting types. |
| `src/main/java/etrading/SpecChecks.java` | The spec assertions, with no test-framework dependency. |
| `src/test/java/etrading/SpecTests.java` | JUnit wrapper around those assertions — you should not need to touch it. |
| `src/main/java/etrading/MarketDataHarness.java` | Optional load generator: latency percentiles, allocation, GC. |
| `DISRUPTOR_REVIEW.md` | A separate snippet we will discuss at the end. Nothing to run. |

The assertions live in `SpecChecks` rather than in the test class so the spec can
be run with nothing but a JDK — no Gradle, no network, no JUnit. `SpecTests` only
names, orders and tags them, so both routes check exactly the same thing.

## Other commands

```bash
./gradlew spec       # same checks, no JUnit and no downloads at all
./gradlew harness --args="200000 8"    # load generator: latency, allocation, GC
```

If Gradle cannot reach the network on your machine, `./gradlew spec` still works
once the Gradle distribution itself is present, and this always works:

```bash
javac -d out $(find src/main -name '*.java')
java -cp out etrading.SpecChecks
```

## A note on the numbers

The harness reports latency percentiles, but on a laptop, VM or container the far
tail (p99.9 and beyond) is dominated by OS scheduling and JIT rather than by this
code. **Bytes allocated per quote and GC counts are the reliable signals**, which
is why the spec is written against allocation rather than latency.

The allocation measurement attributes bytes to the feeding thread plus any thread
the aggregator creates, and to nothing else, so it is not disturbed by whatever
else the JVM happens to be doing.
