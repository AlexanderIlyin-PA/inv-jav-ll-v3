# Part 2 — account service (`part2.accounts`)

An in-memory account service. Dozens of request threads call `getOrCreate`,
`credit`, `transfer` and the getters at the same time, on the same accounts. One
background thread recomputes a cached total for the dashboard.

Reported from production: totals that do not add up, accounts that go missing, the
occasional duplicate account, request threads that stop responding under load, and
deploys that time out.

Everything you need to change is in `AccountService` — the rest of the package can
stay as it is.

The failure messages carry the evidence: what was observed against what was
required, and for rule 4 the two threads' stacks plus the JVM's own view of which
monitor each one waits on and who holds it.

---

### 1. Every credit lands

Two threads each call `credit(acc, 1)` 50 000 times on the same account, meeting on
a spin rendezvous before every call so both are inside `credit` together.

Expected: balance **exactly 100 000**, `getOperationCount()` **exactly 100 000**.

### 2. Every account created is kept

Two threads each create 10 000 distinct accounts, meeting before every creation.

Expected: `getAccountCount()` **exactly 20 000**, every id comes back from
`getOrCreate`, and both threads finish.

### 3. `getOrCreate` constructs one account per id

Eight threads call `getOrCreate` with the same new id. The first thread to reach the
constructor is held there until another arrives, so the check does not depend on
winning a race.

Expected: **exactly one** `Account` constructed, and every caller gets **the same
instance**.

### 4. Transfers run in both directions

Two threads, one moving `ACC-A` → `ACC-B` and the other `ACC-B` → `ACC-A`, 20 000
times each, meeting before every transfer.

Expected: the run finishes **within 10 seconds**.

### 5. `stop()` returns promptly and the background thread finishes

The service runs for half a second, then stops.

Expected: `isRunning()` is true while it runs, `stop()` returns **within 250 ms**,
and `isRunning()` becomes false **within a further second**. The background thread
is signalled and `stop()` returns; it does not wait for the thread. No non-daemon
thread outlives it.

---

`Part2Checks` and `Part2Tests` are this file in executable form. If you think a
check is wrong, say so — this file is the arbiter.
