# Three independent exercises

Three separate exercises in three separate packages, with **no shared code** — you
can work on them in any order, and finishing one has no bearing on the others.
You may have been asked for only one of them.

| Part | What it is about | Specification | Run it |
|---|---|---|---|
| 1 | A fuel-price board | `SPEC-part1.md` | `./gradlew part1` |
| 2 | Concurrency and diagnosis: an account service | `SPEC-part2.md` | `./gradlew part2` |
| 3 | Allocation on a hot path: a market-data aggregator | `SPEC-part3.md` | `./gradlew part3` |

Read the spec for the part you are working on — it is the arbiter, and each one is a
page. `./gradlew test` runs all three through JUnit.

## Where the work is

| Part | The class under review | The checks (please do not edit) |
|---|---|---|
| 1 | `src/main/java/part1/fuel/PriceBoard.java` | `src/test/java/part1/fuel/Part1Checks.java` |
| 2 | `src/main/java/part2/accounts/AccountService.java` | `src/test/java/part2/accounts/Part2Checks.java` |
| 3 | `src/main/java/part3/etrading/PriceAggregator.java` | `src/test/java/part3/etrading/Part3Checks.java` |

Each part also has a small API interface next to the class under review. **Please
keep those interfaces as they are** — other components depend on them.

`GLOSSARY.md` is one page of market-data vocabulary and applies to **part 3 only**.
Parts 1 and 2 need no domain knowledge.

## If Gradle cannot reach the network

The checks have no test-framework dependency, so a JDK on its own is enough. One
part at a time:

```bash
mkdir -p out
javac -d out $(find src/main/java/part1 src/test/java/part1 -name '*.java')
java -cp out part1.fuel.Part1Checks
```

and the same for `part2` / `part2.accounts.Part2Checks` and
`part3` / `part3.etrading.Part3Checks`. Nothing outside a part's own two
directories is needed to compile or run it.

(The `PartNTests.java` files are the JUnit wrappers and are the only files that
need JUnit; the `find` above picks them up too, so add `-not -name '*Tests.java'`
if you have no JUnit on the classpath.)

## How to approach it

**Get as many checks green as you can**, and please **think out loud** — how you
decide what to do first matters more than how much you finish. If you think a check
is wrong, say so; that is a legitimate thing to raise, and the spec is the arbiter.

The failure messages are meant to be read. Each one says what was expected, what
was actually observed, and usually why it matters — you should not need to open the
check files.
