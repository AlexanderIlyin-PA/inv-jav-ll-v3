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
