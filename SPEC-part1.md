# Part 1 — fuel-price board (`part1.fuel`)

A fuel-price board: stations report their petrol and diesel prices for an area, and
`PriceBoard` keeps the cheapest of each and notifies listeners.

- Timing is **event time**: the `timestamp` on each report, epoch millis.
- State is **per area**.
- **"Live"** means a report that has not expired under rule 3.
- The getters and the published stream report the same thing.

The contract to preserve:

```java
public interface PriceBoardApi {
    void addListener(PriceListener listener);
    /** A station's current prices for an area. */
    void onReport(String area, String station, double petrol, double diesel, long timestamp);
    /** Cheapest live petrol price in the area, or NaN if none. */
    double getCheapestPetrol(String area);
    /** Cheapest live diesel price in the area, or NaN if none. */
    double getCheapestDiesel(String area);
    /** Reports accepted under rule 2. */
    long getReportsAccepted();
    /** Updates delivered to listeners. */
    long getUpdatesPublished();
}
```

---

### 1. The cheapest of each is correct

`getCheapestPetrol` returns the lowest live petrol price and `getCheapestDiesel` the
lowest live diesel price. Each is found on its own, so the two often come from
different stations. Listeners receive that same pair.

| Report in | Getters | Published |
|---|---|---|
| `Camden  Shell  1.459 / 1.539  @ T0` | 1.459 / 1.539 | `Camden 1.459 / 1.539` |
| `Camden  BP     1.479 / 1.499  @ T0` | **1.459 / 1.499** | `Camden 1.459 / 1.499` |
| `Hackney Shell  1.429 / 1.509  @ T0` | `Camden` still 1.459 / 1.499 | `Hackney 1.429 / 1.509` |

Three updates. Shell is cheapest on petrol and BP is cheapest on diesel, so the pair
is 1.459 / 1.499.

### 2. The newest report from each station wins

Each (area, station) has its own clock. A report is accepted when its timestamp is at
least as new as the last one accepted from that same station in that area, and equal
timestamps resolve in arrival order. `getReportsAccepted()` counts the accepted ones.

| Report in | Expected |
|---|---|
| `Camden Shell 1.459 / 1.539 @ T0` | 1.459 / 1.539, accepted 1 |
| `Camden Shell 1.699 / 1.799 @ T0 − 5 min` | still 1.459 / 1.539, accepted 1 |
| `Camden BP    1.439 / 1.549 @ T0 − 30 s` | 1.439 / 1.539, accepted 2 — BP's newest |

### 3. A report stays live for 60 minutes of event time

`REPORT_TTL_MILLIS` is 3 600 000. Take `newest` to be the newest timestamp seen for an
area: a station counts towards both sides while its last report for that area is
within the TTL of `newest`, and counts again as soon as it reports.

The reference point is `newest` for the area, so if every station goes quiet the
area's clock stops with them.

| Report in | Expected |
|---|---|
| `Camden Shell 1.459 / 1.539 @ T0` | |
| `Camden BP    1.479 / 1.549 @ T0` | 1.459 / 1.539, both Shell's |
| `Camden BP    1.479 / 1.549 @ T0 + 90 min` | **1.479 / 1.549** — only BP is live |
| `Camden Shell 1.459 / 1.539 @ T0 + 90 min` | 1.459 / 1.539 — Shell is live again |

### 4. An update goes out when the cheapest changes

A change to either side sends exactly one update carrying the new pair. A report that
leaves both sides where they were is simply stored.
`getUpdatesPublished()` matches the number of listener callbacks.

| Report in | Expected |
|---|---|
| `Camden Shell 1.459 / 1.539 @ T0` | 1 update |
| `Camden Shell 1.459 / 1.519 @ T0 + 1 min` | 2 updates; the second carries `1.459 / 1.519` |
| `Camden Shell 1.459 / 1.519 @ T0 + 2 min` | 2 updates |

Accepted 3, published 2.

---

`Part1Checks` and `Part1Tests` are this file in executable form. If you think a
check is wrong, say so — this file is the arbiter.
