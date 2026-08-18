# Vocabulary — part 3 only

One page of market-data terms, for **part 3** (`part3.etrading`). Parts 1 and 2 need
no domain knowledge and none of this applies to them. Skip it if the domain is
already familiar.

| Term | What it means here |
|---|---|
| **Symbol** / instrument | What is being priced, e.g. `EURUSD` — the price of 1 euro in US dollars. |
| **Liquidity provider (LP)** | A bank or venue streaming us prices. Several quote the same symbol at once, and they disagree. |
| **Quote** | One LP's current price for one symbol: a bid, an ask, and a timestamp. |
| **Bid** | The price that LP will **buy** at. We can sell to them at this price. |
| **Ask** (also *offer*) | The price that LP will **sell** at. We can buy from them at this price. |
| **Spread** | `ask − bid`. Always positive within a single healthy LP's quote. |
| **Mid** | `(bid + ask) / 2`. Not a tradeable price, just a reference point. |
| **Pip** | The conventional smallest quoted move for most FX pairs, `0.0001`. The feed here quotes to `0.00001` — a tenth of a pip, often called a point — with a one-pip spread. |
| **Tick** | One market data update. "200,000 ticks a second" means 200,000 quotes arriving. |
| **Top of book** | The best price available right now: the **highest** bid and the **lowest** ask. Sometimes called the BBO, best bid and offer. |
| **The book** | In this exercise, per symbol, the most recent quote from each LP. Note it is only one price level per LP — a full order book with depth is a different, bigger thing. |
| **Aggregation** | Building one consolidated top of book out of several LPs' quotes. This is what `PriceAggregator` does. |
| **Conflation** | Publishing only the latest state and dropping the intermediate updates. Legitimate for prices, where only the current one matters. Never legitimate for order flow or fills, where every message is an event. |
| **Event time** | Time as carried on the message — the `tsNanos` on each quote — rather than the clock on the machine processing it. Using event time is what lets a recorded session be replayed and produce exactly the same result. |
| **Downstream** | Whatever consumes our output: pricing engine, client API, risk. Here they are `QuoteListener`s. |
| **Backpressure** | Making the producer wait when the consumer is behind, instead of buffering without limit or throwing messages away. What a bounded inbound queue gives you. |
| **Allocation rate** | Bytes of heap the code asks for per unit of work. On a hot path this is what feeds the garbage collector, and the collector is what produces the pauses. |

## Why allocation is the subject

Two LPs quoting `EURUSD`:

```
LP-A    1.10000 / 1.10050
LP-B    1.10020 / 1.10150
```

The aggregated top of book is **bid 1.10020** (LP-B's, the highest) and
**ask 1.10050** (LP-A's, the lowest).