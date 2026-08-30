# `tools/load-harness`

The thing `docs/08` says does not exist: a run to load, so that the 10M-contract close can be
timed and replayed rather than estimated.

```
mvn -B -o install -DskipTests -pl eir-domain,eir-calc,eir-policy,eir-gl,eir-application
HEAP=4g tools/load-harness/run.sh 10000,100000 --repeats=3
HEAP=8g tools/load-harness/run.sh 1000000 --repeats=2
HEAP=4g tools/load-harness/run.sh 100000 --repeats=1 --no-replay --sweep=0,10,50,100,300 --attribute
```

`-DskipTests` rather than `-Dmaven.test.skip`: the population generator is
`eir-application/src/test/java/com/crisil/eir/application/load/SyntheticBook.java`, so the harness
needs `eir-application/target/test-classes` on its classpath. That is one source for the population
rather than two — the generator is compiled by the ordinary build and asserted by
`SyntheticBookTest`, and the harness only adds the timing.

| file | what it is |
|---|---|
| `run.sh` | compiles `src/main/java` against the reactor's output and runs it; `HEAP` sets `-Xms`/`-Xmx` |
| `src/main/java/.../LoadHarness.java` | the phases, the timing, the heap measurement, the extrapolation |
| `RESULTS.md` | **the deliverable** — what was measured, on what, and which gate is met |
| `../../eir-application/src/test/java/.../load/SyntheticBook.java` | the population and all six ports |
| `../../eir-application/src/test/java/.../load/Archetype.java` | the mix, its shares, and where each share comes from |
| `../../eir-application/src/test/java/.../load/SyntheticBookTest.java` | the CI-sized version: asserts the accounting and the mix, never a duration |

Not a Maven module, on purpose: a module would put its own compile into every `mvn install`, and a
module depending on another module's *test* classes would be an edge in ADR-0001's inward-only graph
that nothing else needs.

Read `RESULTS.md` before quoting any number from a run of your own. It records which figures are
measured, which are extrapolated, what the extrapolation assumes, and the noise the measurements
were taken in.
