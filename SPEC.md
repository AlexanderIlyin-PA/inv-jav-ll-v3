# PriceAggregator — behaviour specification

This is what `PriceAggregator` is *supposed* to do. `SpecTests` asserts exactly
these rules, one test per rule. The implementation currently violates most of
them.

Throughout: "live" means a quote that has not expired under rule 4, and all
timing is **event time** — the `tsNanos` value carried by each quote — never the
wall clock. The system must behave identically when a recorded session is
replayed.

---

### 1. Best bid is the highest bid across all liquidity providers

`getBestBid(symbol)` returns the highest live bid from any LP, or `NaN` if no LP
has a live quote.

### 2. Best ask is the lowest ask across all liquidity providers

`getBestAsk(symbol)` returns the lowest live ask from any LP, or `NaN` if none.

**The best bid and the best ask are computed independently.** They will routinely
come from different LPs. Taking both sides from one LP is wrong.

### 3. Out-of-order quotes are ignored

Quotes can arrive late or be replayed. A quote whose `tsNanos` is older than the
newest quote already accepted from that same (symbol, LP) must be **discarded**.
It must not overwrite newer data.

### 4. Stale quotes expire

A quote is live for `QUOTE_TTL_NANOS` (2 seconds) of event time. Once the newest
event timestamp seen for a symbol is more than the TTL beyond a given LP's last
quote, that LP's quote must be **excluded** from top of book — otherwise a
disconnected LP keeps pricing forever and we get filled on a price nobody honours.

### 5. Updates are published only when top of book changes

Listeners are notified only when the best bid or the best ask actually changes
value. A quote that leaves top of book unchanged must produce no callback.

### 6. `stop()` terminates the worker thread

After `stop()` returns, `isRunning()` must become `false` within one second. No
non-daemon thread may keep the JVM alive.

### 7. The hot path must not allocate

Steady-state processing must allocate essentially nothing per quote. The target
is **under 64 bytes per quote**, and a correct implementation reaches
approximately zero. Allocation on the hot path is what produces the GC jitter
this system is being fixed for.

---

## Stretch rules

### 8. A crossed book is detected and not published

If the aggregated top of book is crossed — `bestBid >= bestAsk`, which happens
when one LP goes stale — then `isCrossed(symbol)` must return `true` and **no
update may be published**. The getters still report the raw aggregate; it is
publishing a crossed book downstream as tradeable that is forbidden.

### 9. The hot path allocates *nothing*

Under 2 bytes per quote. Rule 7 asks for "little"; this asks for none.

---

## Constraints

- Preserve the `PriceAggregatorApi` contract — other components depend on it.
- `onQuote` is called concurrently from several LP session threads.
- The getters are called from the admin endpoint and the pricing engine, on other
  threads.
- Target load: 200,000 quotes/sec steady state, bursting over 1,000,000/sec.
