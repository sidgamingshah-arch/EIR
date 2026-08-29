# eir-api — running the tool

```
mvn -o -q install -DskipTests
mvn -o -q -pl eir-api dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "eir-api/target/classes:$(cat cp.txt)" com.crisil.eir.api.EirServer 8080
```

Then open <http://localhost:8080>. No network is needed at any point — there are no
external dependencies, no CDN, no font host and no database.

## What the console does

Seven endpoints, in the order an operator drives them.

| Step | Endpoint | What it shows |
|---|---|---|
| Book | `GET /api/book` | three contracts: one performing, one Stage 3 with recognition suppressed, one with movements and **no recorded opening balance** |
| 01 Recognise | `POST /api/onboard` | 05 § 3.1's ordered pipeline, and **which steps actually ran** — an SPPI failure exits before any fee lookup or solve |
| 02 Run | `POST /api/run` | population accounting first, then invariants, then per-contract figures |
| ↺ Repair | `POST /api/repair` | supplies the missing opening balance — the remediation the queue routes somebody to |
| ↺ Accept | `POST /api/accept` | four-eyes acceptance; put the same name on both sides and the close refuses it |
| 03 Post | `POST /api/post` | the journals reach the ledger, so SL-1's two sides are in the same state |
| 03 Close | `POST /api/close` | `PeriodCloseGate`'s verdict with **every** refusal reason |
| 04 Replay | `POST /api/replay` | DT-1, its coverage, and whether reproduction is *proven* |

## The arc worth walking

1. **Run.** Two contracts compute, one is quarantined. Not a clean close.
2. **Close.** Refused, five gate reasons, 41,358.04 of deviation. SL-1 is red because the journals
   are not posted; RC-1 is red because the CBS billed a contract the engine never projected.
3. **Repair**, then **re-run**. Three computed, none quarantined.
4. **Post**, then **close**. Permitted, nil deviation.
5. **Replay.** DT-1 green, reproduction proven.

Try an acceptance with the same identity on both sides instead of repairing, and watch
`SELF_APPROVED_ACCEPTANCE` come back — acceptance is the only route past a queued exception, which
makes it the control most worth attacking.

## Design notes

**No framework, and no ADR-0010 exemption.** Central is reachable and Spring Boot resolves; this is
a choice. `com.sun.net.httpserver` covers five routes and a page, and JSON is written by hand for the
same reason `RoutingTableFormat` parses a routing table by hand. The repository still builds with
`-o`, and there is nothing between an HTTP request and an invariant result.

**Every figure crosses the wire as a JSON string.** JSON's number type is a double in every browser
that will read it, and `0.010421491800` through a double loses the trailing zeros that say the rate
is stated to twelve places. The page formats for display and never computes.

**Refusals are 200s.** A refusal is a value in this engine and the whole list comes back. 400 is a
malformed request; 500 is a defect, reported rather than swallowed.

## What this is not

- **No authentication or authorisation.** FR-906's role model is unimplemented, so the maker-checker
  identity on a close is whatever the caller typed.
- **The book is a map, not `eir-persistence`.** It answers the same thing at every as-at boundary,
  so the replay reproduces and is *not* a fair test of bitemporality.
- **SL-1 cannot go red on a posted book.** Both sides derive from the book. A production
  `GeneralLedgerSource` reads the bank's trial balance, and then it can.
- **The pre-/post-floor tie is nil against nil**, because no regulatory floor is supplied. It is
  labelled as a caveat rather than left looking like a tie that passed.
- **One request thread.** `EirService` holds the last run so a close can read it; two simultaneous
  closes of one period is a question the domain already answers through the period's status.

All four are stated on the responses themselves, not only here.
