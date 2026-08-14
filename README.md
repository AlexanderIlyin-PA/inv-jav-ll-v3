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
| `GLOSSARY.md` | One page of market-data vocabulary. Skip it if the domain is familiar. |
| `src/main/java/etrading/PriceAggregator.java` | **The class under review. This is where the work is.** |
| `src/main/java/etrading/PriceAggregatorApi.java` | The contract other components depend on — please keep it. |
| `src/test/java/etrading/SpecChecks.java` | The spec as executable checks. You should not need to open it, and please do not edit it. |
| `src/main/java/etrading/MarketDataHarness.java` | The load generator behind `./gradlew harness`. |
| `DISRUPTOR_REVIEW.md` | A separate snippet, for discussion. Nothing to run. |

Four of the seven rules are behavioural, one is about concurrency and shutdown,
and two are about allocation. Some fixes are small and local; at least one is a
design change. **Get as many green as you can**, and please **think out loud** —
how you decide what to do first matters more than how much you finish.

## A note on the numbers

On a laptop, VM or container the far latency tail (p99.9 and beyond) is dominated
by OS scheduling and JIT rather than by this code. **Bytes allocated per quote and
GC counts are the reliable signals**, which is why the spec is written against
allocation rather than latency.
