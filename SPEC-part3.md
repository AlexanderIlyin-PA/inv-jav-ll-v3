# Part 3 — allocation (`part3.etrading`)

`PriceAggregator` consolidates quotes from several liquidity providers into a top of
book per symbol and publishes it downstream. In production it runs at ~200 000
quotes/sec with bursts over a million, and the pauses it causes are GC pauses.

**Part 3 grades one thing: bytes allocated per quote on the hot path.** The
aggregation behaviour is part 1's subject, so leave it be. Two rules, one
measurement, two thresholds.

`GLOSSARY.md` covers the market-data vocabulary this part uses.

---

### 1. Under 40 bytes per quote

### 2. Under 16 bytes per quote

**The measurement**, run once and shared by both rules: 50 000 quotes of warm-up,
then **200 000 measured** quotes from a synthetic feed across 8 symbols and 6 LPs.

Bytes are counted for the feeding thread plus every thread the aggregator creates,
and nothing else, so the figure holds whatever else the JVM is doing. All 250 000
quotes reach processing and at least one update is published, so the number always
describes real work.

The failure message splits the total between the feeding thread and the aggregator's
own threads. That split is the first thing worth reading: it tells you which side of
the hot path the bytes are on.

### Constraints

- Preserve the `PriceAggregatorApi` contract.
- Target load: 200 000 quotes/sec steady state, bursting over 1 000 000/sec.

---

`Part3Checks` and `Part3Tests` are this file in executable form. If you think a
check is wrong, say so — this file is the arbiter.
