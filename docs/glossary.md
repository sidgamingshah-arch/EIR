# Glossary

Terms as used in this specification. Where a term has a narrower meaning here than in general usage,
the narrower meaning governs.

## Regulatory instruments

| Term | Meaning |
|---|---|
| **ACPIR** | RBI (Commercial Banks — Asset Classification, Provisioning and Income Recognition) Directions, 2026. The binding regulatory anchor. Effective 1 April 2027. |
| **Seventh Amendment Directions** | RBI (Commercial Banks — Financial Statements: Presentation and Disclosures) Seventh Amendment Directions, 2026. Carries the Stage 3 non-recognition protection and Schedule 13 compilation changes. |
| **Investment Portfolio Directions** | Governs initial recognition and subsequent measurement of investments by virtue of ACPIR 22 — the source of the treasury-book divergence. |
| **IFRS 9 / Ind AS 109** | **Interpretive sources only** in this system, applied where ACPIR is silent. Not directly applicable standards. |
| **Penal charges framework (2023)** | RBI framework requiring penal *charges* not penal *interest*; basis for the hard EIR exclusion. |

## Core measurement

| Term | Meaning |
|---|---|
| **EIR** | Effective interest rate. The rate that exactly discounts estimated future cash flows through the expected life of the instrument to its **gross carrying amount** (ACPIR 6(6)). |
| **Credit-adjusted EIR** | For POCI assets: the rate discounting **expected** cash flows, net of expected credit losses, to **amortised cost** at initial recognition (ACPIR 6(4)). Retained after cure. |
| **Deemed EIR** | A rate with a documented derivation, used where full reconstruction is infeasible for a legacy tranche. Not a contractual-rate fallback. |
| **Gross carrying amount (GCA)** | Amortised cost **before** adjusting for any loss allowance (ACPIR 6(12)). |
| **Amortised cost** | Initial recognition amount, less principal repayments, plus or minus cumulative EIR amortisation, adjusted for loss allowance (ACPIR 6(1)). |
| **Transaction cost** | Incremental cost directly attributable to acquisition, issue or disposal — one that would not have been incurred otherwise (ACPIR 6(29)). |
| **Integral fee** | A fee folded into the initial carrying amount and amortised via the EIR (ACPIR 52). |
| **Unamortised fee** | **Derived** as contractual carrying amount minus EIR carrying amount. Never an independent accumulator (invariant INV-4). |

## The two mechanisms

| Term | Meaning |
|---|---|
| **Reset** (B5.4.5) | Re-solve the EIR over remaining flows from the **current** carrying amount. Carrying amount unchanged, no P&L catch-up. Prospective. |
| **Catch-up** (B5.4.6) | Retain the **original** EIR; restate the gross carrying amount to the PV of revised flows at that rate; recognise the difference in P&L immediately. |
| **Driver tag** | What a contractual rate component *compensates for*: `TIME_VALUE_OF_MONEY`, `CREDIT_RISK_MARKET`, `ESG_LINKED`, `BEHAVIOURAL_ESTIMATE`, etc. Routing keys off this. |
| **Routing table** | The versioned driver→mechanism mapping. Configuration, not code — see [ADR-0006](adr/0006-configurable-event-routing.md). |
| **B5.4.4 shortcut** | Amortising a premium, discount or fee to the **next repricing date** where the instrument reprices to market before maturity. An election per product. |

## Impairment

| Term | Meaning |
|---|---|
| **Stage 1 / 2 / 3** | ECL staging. Stage 3 is credit-impaired. |
| **Stage 3 suppression** | ACPIR's non-recognition of interest income on Stage 3 assets. The India divergence from IFRS 9's net-basis mechanic. |
| **Shadow unwind** | `allowance × EIR`. Computed for the ECL roll-forward, **never** recognised in P&L. Equals gross-basis interest minus net-basis interest (invariant ST-2). |
| **Interest-in-suspense** | Contractual interest billed but not recognised. A first-class ledger object, not a memorandum. |
| **POCI** | Purchased or originated credit-impaired. Under ACPIR 6(3)(v), acquisition at a discount reflecting inherent credit losses is itself an indicator. |
| **Pre-floor / post-floor** | The ECL computed at the EIR, versus after applying ACPIR 90 prudential floors. Both retained as first-class outputs. |

## Tiering and pooling

| Term | Meaning |
|---|---|
| **Tier 1 / 2 / 3** | The materiality gate. Instrument-level, pool-level, and documented approximation respectively. |
| **Equivalence test** | The solved-versus-approximated comparison that makes a Tier 3 shortcut defensible. Annual (invariant TG-1). |
| **Closed cohort** | A pool whose membership is fixed once its EIR is struck. A later origination joins a later pool. |
| **Expected life (EIR)** | ACPIR 51 — expected life considering prepayment, extension and call options. |
| **ECL horizon** | ACPIR 46(1) — the **maximum contractual** period including extensions. **A separate field** from expected life. |
| **CPR** | Conditional prepayment rate. The behavioural assumption with the highest leverage in the model. |

## Products and India-specific

| Term | Meaning |
|---|---|
| **EBLR / MCLR** | External benchmark lending rate / marginal cost of funds based lending rate. Floating-rate benchmarks. |
| **DSA / DMA** | Direct selling agent / direct marketing agent. Sourcing commissions — capitalisable transaction costs. |
| **KCC** | Kisan Credit Card. A revolving agricultural facility needing behavioural life. |
| **WCDL** | Working capital demand loan. Short bullet tenor; Tier 3. |
| **IBPC** | Inter-bank participation certificate. Volumes spike at reporting dates. |
| **FITL** | Funded interest term loan, created on restructuring. |
| **DCCO** | Date of commencement of commercial operations. Deferment raises an unanswered modification question. |
| **IDC** | Interest during construction. Capitalises into the gross carrying amount pre-COD. |
| **DLG** | Default loss guarantee. Triggers event-driven ECL recomputation on invocation (ACPIR 88). |
| **Co-lending** | Typically 80:20. Each lender computes the EIR on its own share; watch the direction of the fee. |
| **SPPI** | Solely payments of principal and interest. On the **asset** side, failure sends the whole instrument to FVTPL and no EIR arises — a cliff, not a gradient. |
| **Schedule 13** | RBI-prescribed "Interest Earned" schedule. Derivative net settlements are not enumerated there. |

## Engine concepts

| Term | Meaning |
|---|---|
| **Flow vector** | The ordered `(date, amount, kind, driver)` sequence the solver consumes. It never reads contract terms directly. |
| **Contractual leg / EIR leg** | The two parallel roll-forwards. The contractual leg reconciles to the CBS; their difference is the unamortised fee. |
| **`LMS_AUTHORITATIVE`** | Consuming the schedule the core banking system actually billed rather than deriving one. Strongly preferred. |
| **Bitemporal** | Business time (`validFrom`/`validTo`) separate from system time (`recordedAt`/`supersededAt`). What makes replay possible. |
| **Replay** | Re-running a closed period under the versions then in force, byte-compared to the published figures (invariant DT-1). |
| **Restatement artefact** | How a closed period is corrected. History is never rewritten. |
| **Exception queue** | Where a contract goes instead of getting a silently-defaulted answer. |
| **Golden fixture** | A reference case. If the engine disagrees with one, the engine is wrong. |
