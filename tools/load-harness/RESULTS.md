# Load harness results — the two open Phase 5 exit gates

`docs/08` Phase 5 states three exit clauses and records the third as met and the first two as unmet,
because "there is no run to load, so there is no 10M-contract close to time and no close to replay".
This file replaces the estimates with measurements.

**Read the verdict section before quoting any number.** One clause is met on measurement, one is not
met and cannot be met on this machine, and the reason it cannot is more useful than the timing.

| Clause | Verdict |
|---|---|
| 1. a 10M-contract synthetic close inside 4 hours | **Not met as measured.** No 10M close was run: the memory shape forbids it in one JVM. The largest close actually run — 1,000,000 contracts — took **3 min 16 s**, which extrapolates to **0.55 h** for 10M and is comfortably inside four hours *on time*. The binding constraint is heap, not wall-clock, and § 4 gives the figure. |
| 2. a replay of that close is byte-identical | **Met, and measured at scale.** Bit-identical at 10,000, 100,000 and 300,000 contracts, with **1,506,000 figures compared** at 300,000. Not a spot check and not an extrapolation. |
| 3. the close workflow refuses to close on any red invariant | Already recorded as met (`policy/close`). Every run below also reported `red invariants 0`, `blocking reasons 0`, `mayClose true`, and the population accounting closed exactly — `unaccounted 0` at every size. |

---

## 1. What was measured, and on what

| | |
|---|---|
| machine | 4 vCPU, 15 GiB RAM, shared. Not a sizing environment. |
| JVM | OpenJDK 64-Bit Server VM 21.0.10, `-Xms == -Xmx`, `-XX:+AlwaysPreTouch` |
| run loop | **single-threaded** — `MonthEndRun` iterates its population, so core-hours = wall-clock |
| population | `eir-application/src/test/.../load/SyntheticBook`, mix per `Archetype`: 600‰ performing periodic, 320‰ performing actual, 40‰ Stage 3 suppressed, 30‰ catch-up event, 10‰ reset event (solves) |
| warm-up | 5,000 contracts, discarded |

Every size below was run in one JVM with the repeat count shown, and the spread is reported because a
single figure off a shared machine is not a measurement.

## 2. The ladder

`CLOSE E2E` is population enumeration + `MonthEndRun.execute` + `RunClose.present`, which is what
"a close" means for clause 1. `REPLAY` is `ReplayUseCase.replay` on top of it.

| n | repeats | RUN | CLOSE | CLOSE E2E | REPLAY | E2E µs/contract | peak heap | retained |
|---|---|---|---|---|---|---|---|---|
| 10,000 | 2 | 0.53 s | 0.07 s | **0.59 s** | 0.76 s | 60.3 | 734 MiB | 1,957 B/contract |
| 100,000 | 2 | 5.46 s | 0.44 s | **5.91 s** | 9.31 s | 59.1 | — | — |
| 300,000 | 2 | 22.84 s | 1.06 s | **23.91 s** | 40.69 s | 79.7 | 3,908 MiB | 1,227 B/contract |
| 1,000,000 | 1 | 192.20 s | 4.26 s | **196.49 s** | not run | 196.5 | 8,254 MiB | 1,897 B/contract |

Spreads on `CLOSE E2E`: 3.1% at 10k, 11.9% at 100k, 7.8% at 300k. The 1M row is a single repeat and
carries no spread — it is reported as one observation, not as a mean.

Replay was disabled at 1M deliberately: it roughly doubles peak heap (4.3 GiB against 3.9 GiB at
300k), and an OOM would have produced no timing at all rather than a slower one.

## 3. The finding that matters: the cost per contract is not flat

Per-contract `CLOSE E2E`:

```
   10,000  ->  60.3 us
  100,000  ->  59.1 us
  300,000  ->  79.7 us     +35% on 100k
1,000,000  -> 196.5 us     +146% on 300k, +233% on 100k
```

Flat to 100,000, then rising, and the rise is accelerating. This is not the arithmetic getting
slower — the arithmetic per contract is identical at every size. It is the collector.

`MonthEndRun.Completion` keeps a `Map` of every `ContractComputation` and `RunAggregate` keeps a
`List` of every `ContractResult`, so the live set grows with the population: **~1.9 KB retained per
contract**, measured after three `System.gc()` calls with the run's output still reachable. A
collector doing more work per allocation as the live set grows is exactly this curve.

**This is a measured argument for [ADR-0007](../../docs/adr/0007-spring-batch-for-runs.md) rather
than an assumed one.** A partitioned run over bounded slices holds the live set flat, which should
hold the per-contract cost at its 100,000-contract value of ~59 µs. That prediction is falsifiable
and `eir-batch` now exists to test it; nothing here has tested it, and the number above is the
single-JVM figure.

Extrapolating clause 1 honestly therefore has two answers:

- at the **1M rate** (196.5 µs/contract): 10M in **0.55 h**;
- at the **100k rate** (59.1 µs/contract, which is what a partitioned run should recover): 10M in
  **0.16 h**.

Both are inside four hours with wide margin. The naive extrapolation is the pessimistic one, which is
the right way round.

## 4. Why no 10M close was run, and why that is the real result

Peak heap, sampled every 10 ms over `MemoryMXBean.getHeapMemoryUsage()`:

| n | peak heap | heap given |
|---|---|---|
| 300,000 | 3.9 GiB | 6 GiB |
| 1,000,000 | 8.3 GiB | 11 GiB |

Scaling the 1M observation, a 10M single-JVM close needs on the order of **80 GiB of peak heap** and
**~19 GB of retained live set**. This machine has 15 GiB. No JVM flag closes that gap.

So the honest statement of clause 1 is: **the four-hour figure is no longer the constraint; the
single-JVM memory shape is.** `docs/08` said "the 4-hour figure is untested and remains an estimate"
— it is now tested at a tenth of the population and the timing is not the problem. What was never
stated, and is the finding, is that a 10M close is not a single-JVM workload at all. Partitioning is
not an optimisation here; it is what makes the gate reachable.

## 5. ADR-0009's core-hour estimate is optimistic by at least 2.7×

[ADR-0009](../../docs/adr/0009-par-gap-as-the-ordering-baseline.md) records **0.2 core-hours** per
10M-contract close for the chosen approach, against 922 for a second solve, and says plainly that
both "are an estimate, not a measurement on this codebase" and that "a benchmark belongs alongside
the batch-sizing work in 08". This is that benchmark.

Measured at 1,000,000 contracts, single-threaded: **0.0534 core-hours** for the run, **0.0546** for
the close end to end. At the same rate, 10M is **0.53 core-hours** — **2.7× the 0.2 estimate**, and
worse if the superlinearity in § 3 persists.

**The ADR's decision is unaffected, and that is worth saying explicitly.** Its argument turns on a
ratio of two to three orders of magnitude between reusing the computed leg and running a second
solve, not on either absolute. A 2.7× error in the smaller figure does not approach 922. The
estimate should be corrected in the ADR; the decision should not be revisited.

## 6. What these numbers do not cover

- **The replay is not a persistence round-trip.** Both sides go through `ShadowRun.figures` — the
  published side over the first run's results, the shadow side over a second independent execution.
  Two executions reduced identically agree only if they computed identically, so the arithmetic is
  genuinely tested. A real DT-1 reads the published side off a shadow table, and a scale lost on the
  way into the database would show there and cannot show here. `ReplayUseCaseTest` pins the key
  convention and the scale sensitivity against hand-written literals; `eir-persistence-jdbc`'s
  live-cluster suite is where the round trip is exercised.
- **No database.** Every port is in-memory. A real close reads seven ports over JDBC, and the I/O is
  absent from every figure above.
- **One thread.** Core-hours equal wall-clock here by construction. A partitioned run trades one for
  the other and neither figure transfers without measurement.
- **A shared machine.** Read the spreads in § 2. The 55.5% spread on `CLOSE` at 100,000 contracts is
  noise on a sub-second phase, not a property of the close.
- **One mix.** `Archetype`'s shares are one plausible retail book. A book with more reset events
  solves more often, and the solve is the expensive path — 10‰ here.

## 7. Reproducing

```
mvn -B -o install -DskipTests -pl eir-domain,eir-calc,eir-policy,eir-gl,eir-application
HEAP=3g  tools/load-harness/run.sh 10000 --repeats=2
HEAP=6g  tools/load-harness/run.sh 100000,300000 --repeats=2
HEAP=11g tools/load-harness/run.sh 1000000 --repeats=1 --no-replay
```

`-DskipTests` rather than `-Dmaven.test.skip`: the population generator lives in
`eir-application/src/test`, so the harness needs `target/test-classes`. That is one source for the
population rather than two — the generator is compiled by the ordinary build and asserted by
`SyntheticBookTest`, and the harness only adds the timing.
