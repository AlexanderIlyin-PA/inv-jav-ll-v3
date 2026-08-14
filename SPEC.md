# PriceAggregator — behaviour specification

This is what `PriceAggregator` is *supposed* to do. `SpecChecks` asserts exactly
these rules, one check per rule. The implementation currently violates most of
them.

Throughout:

- **"live"** means a quote that has not expired under rule 2.
- All timing is **event time** — the `tsNanos` carried by each quote — never the
  wall clock. Replaying a recorded session must produce identical behaviour.
- **"published"** means delivered to registered `QuoteListener`s. The getters and
  the published stream must agree: a rule about top of book is a rule about both.
- If any of the market-data vocabulary is unfamiliar, `GLOSSARY.md` is one page and
  covers everything used below.

---

## Core rules

### 1. The published top of book is correct

`getBestBid(symbol)` returns the highest live bid from any LP, `getBestAsk(symbol)`
the lowest live ask, and `NaN` if no LP has a live quote for that symbol.

**The two sides are computed independently and will routinely come from different
LPs.** Taking both from one LP is wrong.

Every update delivered to a listener carries that same pair. State is per symbol:
quotes for one symbol never affect another.

### 2. Quotes that must not count are excluded

Two ways a quote stops counting:

- **Out of order.** A quote whose `tsNanos` is older than the newest quote already
  accepted **from that same (symbol, LP)** must be discarded — it must not
  overwrite newer data. The comparison is per (symbol, LP), not per symbol: one
  LP's clock says nothing about another's. A quote with an *equal* timestamp is
  accepted, and ties resolve in arrival order.
- **Stale.** Let `newest` be the newest event timestamp seen for a symbol. An LP
  whose last quote for that symbol is more than `QUOTE_TTL_NANOS` (2 seconds)
  older than `newest` is excluded from top of book, until it quotes again.

The TTL is measured against the newest event timestamp for the symbol, not against
the arriving quote and not against the wall clock. So if every LP for a symbol goes
quiet, its clock stops and nothing expires; and a single future-dated quote will
expire the rest of that symbol's book.

### 3. An update is published exactly when top of book changes

- A change to **either** side — best bid or best ask — publishes exactly one
  update, carrying the new pair.
- A quote that leaves both sides unchanged publishes nothing.

An improving ask with an unchanged bid is a change. Publishing on every quote and
publishing only on bid moves are both wrong.

### 4. It is safe under concurrent producers and consumers, and it stops

- `onQuote` is called concurrently from several LP session threads. **No quote may
  be lost**: every quote accepted by `onQuote` reaches processing.
- Listeners may be registered at any time, including while quotes are flowing.
  Registration must not disturb the publishing path, and a newly registered
  listener starts receiving updates.
- The getters are called from other threads throughout. Once a symbol has a live
  price, a getter on another thread must never report `NaN` for it.
- `isRunning()` is `true` after `start()` and before `stop()`.
- `stop()` must **return within 250 ms** — signal the worker and return; do not
  `join()` it — and `isRunning()` must become `false` within one second of
  `stop()` returning. No non-daemon thread may keep the JVM alive.

### 5. The hot path barely allocates

Steady-state processing must stay **under 40 bytes per quote**, measured over
200,000 quotes after warm-up, counting bytes allocated by the feeding thread plus
every thread the aggregator creates. Nothing may be dropped to get there.

Allocation on the hot path is what produces the GC jitter this system is being
fixed for. `./gradlew harness` prints the number, and prints how it moved since
your last run.

---

## Stretch rules

### 6. A crossed book is never published

The aggregate is crossed when `bestBid >= bestAsk` — which happens when one LP's
side goes stale while the other side moves. Then `isCrossed(symbol)` must return
`true` and **no update may be published**. The getters still report the raw
aggregate; publishing a crossed book downstream as tradeable is what is forbidden.

When the book un-crosses, the next genuine change publishes normally.

### 7. The hot path allocates almost nothing

Under **16 bytes per quote**. Rule 5 asks for "little"; this asks for near zero.

---

## Constraints

- Preserve the `PriceAggregatorApi` contract — other components depend on it.
- Target load: 200,000 quotes/sec steady state, bursting over 1,000,000/sec.
- `SpecChecks` and `SpecTests` are the spec. **Do not edit them** — if you think a
  check is wrong, say so, and this file is the arbiter.

---

# What each check feeds in, and what it expects

Everything the checks do is below, so you should not need to open them. Times are
event time — the `tsNanos` on each quote — not wall clock. "Published" means what
arrives at a registered `QuoteListener`.

Every rule starts from a **fresh aggregator**, so nothing carries over between
rules.

### Rule 1 — the published top of book is correct

| Quote in | Expected getters | Expected published |
|---|---|---|
| `EURUSD  LP-A  1.10000 / 1.10050  @ T0` | bid 1.10000, ask 1.10050 | `EURUSD 1.10000 / 1.10050` |
| `EURUSD  LP-B  1.10020 / 1.10150  @ T0` | bid **1.10020** (LP-B), ask **1.10050** (LP-A) | `EURUSD 1.10020 / 1.10050` |
| `USDJPY  LP-A  150.100 / 150.150  @ T0` | `EURUSD` unchanged at 1.10020 / 1.10050 | `USDJPY 150.100 / 150.150` |

Three updates in total. The second row is the point: the two sides come from
different LPs, and the pair delivered to the listener must be the same pair the
getters report.

### Rule 2 — quotes that must not count are excluded

Out of order:

| Quote in | Expected |
|---|---|
| `EURUSD  LP-A  1.10000 / 1.10050  @ T0` | bid 1.10000 |
| `EURUSD  LP-A  1.09000 / 1.09050  @ T0 − 0.5 s` | **discarded** — bid stays 1.10000 |
| `EURUSD  LP-B  1.10030 / 1.10080  @ T0 − 0.25 s` | **accepted** — it is LP-B's newest, so bid becomes 1.10030 |

Stale, on a fresh aggregator:

| Quote in | Expected |
|---|---|
| `EURUSD  LP-A  1.10000 / 1.10050  @ T0` | |
| `EURUSD  LP-B  1.09980 / 1.10030  @ T0` | bid 1.10000 (LP-A), ask 1.10030 (LP-B) |
| `EURUSD  LP-B  1.09980 / 1.10030  @ T0 + 3 s` | LP-A is now beyond the 2 s TTL: bid **1.09980**, ask **1.10030** |

### Rule 3 — published exactly when top of book changes

| Quote in | Expected |
|---|---|
| `EURUSD  LP-A  1.10000 / 1.10050  @ T0` | 1 update published |
| `EURUSD  LP-A  1.10000 / 1.10020  @ T0 + 1 ms` | 2 updates; the second carries `1.10000 / 1.10020` |
| `EURUSD  LP-A  1.10000 / 1.10020  @ T0 + 2 ms` | still 2 updates — nothing moved |

The ask improved with the bid unchanged, and that is a change.

### Rule 4 — safe under concurrent producers and consumers, and it stops

Not a table — this one runs a load:

- `isRunning()` must be `true` immediately after `start()`.
- **4 producer threads**, `LP-A` … `LP-D`, **25,000 quotes each** (100,000 total),
  across 8 symbols that come into play progressively, timestamps increasing per
  (symbol, LP), prices never crossed.
- **32 listeners are registered while those quotes are in flight**, 2 ms apart.
- A **reader thread** calls `getBestBid` and `getBestAsk` in a loop throughout.

Expected: `getQuotesProcessed()` is **exactly 100,000** — nothing lost; no getter
ever throws; no symbol reports a live price and then `NaN` again; at least one
update was published; at least one of the 32 late listeners received something.

Then a listener registered *after* the feed, plus one quote for a brand new
symbol (`EURGBP 0.85000 / 0.85040`), must reach that listener.

Finally `stop()` must return **within 250 ms**, and `isRunning()` must be `false`
within a further second.

### Rules 5 and 7 — allocation

One measurement, two thresholds. 50,000 quotes of warm-up, then **200,000
measured** quotes from a synthetic feed across 8 symbols and 6 LPs.

Expected: all 250,000 reach processing (dropping quotes to allocate less does not
count), something was published, and bytes allocated per quote is **under 40** for
rule 5 and **under 16** for rule 7. Bytes are counted for the feeding thread plus
every thread the aggregator creates, and nothing else. `./gradlew harness` reports
the same number, split the same way.

### Rule 6 — a crossed book is never published

| Quote in | Expected |
|---|---|
| `EURUSD  LP-A  1.09900 / 1.10050  @ T0` | 1 update published |
| `EURUSD  LP-B  1.10200 / 1.10250  @ T0` | aggregate is bid 1.10200 / ask 1.10050 — crossed. `isCrossed` is `true`, and the publish count **stays at 1** |
| `EURUSD  LP-A  1.09900 / 1.10300  @ T0 + 1 ms` | no longer crossed. `isCrossed` is `false`, 2 updates, the second carrying `1.10200 / 1.10250` |

Note the middle row moves the best bid, so suppressing the update is real work —
conflation alone will not do it.
