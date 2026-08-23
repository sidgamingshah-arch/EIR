# 00 — Product Vision

## 1. The problem

**RBI ACPIR 2026** severs the link between the interest a bank *bills* and the interest it *reports*.
Interest income must be recognised using the effective interest rate: the single rate that discounts
the instrument's expected cash flows back to its gross carrying amount at initial recognition, where
that carrying amount has been adjusted for every fee and cost integral to originating the deal.

The clock is statutory and short. **Effective 1 April 2027** for new origination and for a day-1 fair
valuation of the entire loan book; **31 March 2030** for the legacy book. As at August 2026 that first
date is roughly seven months away, and the binding constraint is data granularity rather than
accounting judgement — the lesson from Brazil, which ran the closest structural analogue with a
four-year lead time and still found banks unprepared at go-live.

For a bank of any size this creates a computation with an awkward shape:

- **Per-contract.** Every loan has its own fee pattern, its own disbursement dating, its own
  prepayment behaviour. The EIR is a root-find, and it is a different root for each contract.
- **Path-dependent.** A rate reset is prospective. A revision of cash-flow estimates is a
  retrospective catch-up at the *original* rate. A substantial modification is a
  derecognition. Getting the wrong one produces the wrong P&L, and the three are easy to
  confuse because they all look like "the schedule changed".
- **High-volume and recurring.** The whole portfolio re-amortises every reporting period,
  on a month-end clock, against a close deadline.
- **Audited, and now mandatorily automated.** Every number must be explicable to a statutory auditor
  eighteen months later, from inputs that have since changed, under a policy that has since been
  revised. ACPIR further requires classification and provisioning to be fully system-driven with no
  manual intervention, with documented rules, maker–checker control, audit trails and exception logs.
- **Under-specified on purpose.** ACPIR carries roughly 1,200 words on EIR against IFRS 9's several
  thousand plus two decades of interpretive material. RBI has consistently framed the Directions as
  prudential rather than as a full accounting standard, so nine substantive questions — what happens
  when estimates change, modification versus derecognition, which fees are *not* integral, where the
  Stage 3 unwind goes — are left to Board-approved policy. A bank cannot run an EIR engine on 1,200
  words.

The near-universal response is a spreadsheet estate: one workbook per product, per entity,
maintained by two or three people, reconciled by hand. It works until it doesn't. The
observable failure modes:

| Failure mode | Consequence |
|---|---|
| No replay capability | A prior-period question cannot be answered; the auditor's sample cannot be reproduced |
| Rate frozen at origination and never revised | Floating-rate books drift from B5.4.5 |
| Re-estimation booked prospectively instead of as a catch-up | Understated or overstated modification gain/loss; a real restatement risk |
| Stage 3 interest recognised at all, on either basis | Recognises income ACPIR suppresses |
| Stage 3 unwind not computed *because* income is not recognised | ECL discounting silently wrong — ACPIR 50 makes the EIR the discount rate |
| Penal charges routed through the interest ledger into the carrying amount | Breaches the 2023 penal charges framework; inflates the GCA and the rate |
| Pre-floor ECL overwritten by the prudential floor | Loses the accounting number ACPIR 90 requires alongside it |
| Prepayment closes the loan and the unamortised fee is silently written off to a suspense account | Unexplained P&L, unreconciled fee balance |
| No policy versioning | The same contract yields different answers in different periods with no record of why |
| Manual pool-level approximation with no documented basis | An audit finding waiting to happen |

## 2. The product

A calculation engine plus a sub-ledger. It takes contract terms, cash-flow schedules, fee
and cost postings, and lifecycle events; it determines the EIR; it rolls the amortised cost
forward each period; it emits the interest income, the fee amortisation, the catch-up
adjustments and the journal entries; and it keeps enough of its own history to reproduce any
figure it has ever published.

### The India-specific core

Four things distinguish this from an off-the-shelf IFRS 9 sub-ledger, and they are the reason the
product exists:

1. **Stage 3 suppression with a computed unwind.** ACPIR recognises no income on Stage 3, but ACPIR 50
   makes the EIR the ECL discount rate, so the discount unwinds mechanically regardless. The engine
   computes it, keeps it out of the P&L, and reconciles four parallel quantities every period. The
   arithmetic is exact: net-basis interest + ECL unwind = gross-basis interest.
2. **Penal charges hard-excluded** at the ingestion boundary, with a positive assertion each period
   rather than an assumption.
3. **Pre-floor and post-floor duality**, with the accounting figure retained as a first-class output
   rather than an intermediate the floor overwrites.
4. **Reset-versus-catch-up routing as versioned configuration**, because ACPIR is silent on both
   mechanics *and* the IASB is actively amending the IFRS 9 rule the mechanics are borrowed from.

Its defining commitment is **determinism**. Given the same inputs and the same policy
version, the engine produces bit-identical output, and it retains the inputs and the policy
version alongside every result. Replay is not a reporting feature bolted on at the end; it
is the shape of the system. See [ADR-0003](adr/0003-event-sourced-recompute.md).

## 3. Users

| User | What they need | How they are served |
|---|---|---|
| **Financial controller** | The period closes on time, the numbers tie, the movement schedule explains itself | Close workflow with hard gates; movement and reconciliation reports; variance-vs-prior-period surfacing |
| **Product control / accounting policy** | Fee classification and expected-life assumptions applied consistently and defensibly | Versioned, maker–checker'd rule sets; documented basis on every assumption; impact preview before a policy goes live |
| **Statutory / internal auditor** | Trace any published figure to its inputs and the policy in force | Per-contract computation trace; immutable closed periods; replay of any past run |
| **Financial reporting** | Ind AS 107 disclosure inputs without a manual build | Movement schedules, interest income analysis, staged-basis splits |
| **Business finance / FP&A** | Understand yield on new business, and what a pricing change does to reported income | EIR-vs-contractual yield analytics; what-if on candidate fee structures |
| **Technology operations** | The month-end run finishes inside the window and fails loudly, not quietly | Partitioned batch with restartability, per-contract failure isolation, run dashboards |

## 4. Scope

### In scope

- Financial **assets** measured at amortised cost: term loans, EMI retail loans, working
  capital and cash-credit facilities, bill discounting, corporate loans, lease receivables,
  purchased loan pools, investments in debt instruments held at amortised cost.
- Financial **liabilities** measured at amortised cost: term borrowings, NCDs, commercial
  paper, ECBs, subordinated debt — issue costs and discounts amortised via EIR.
- Debt instruments at **FVOCI**, for which EIR interest income is recognised in P&L even
  though fair value moves through OCI.
- **POCI** assets, using a credit-adjusted EIR.
- Integral fee and transaction-cost identification, deferral and amortisation.
- The full lifecycle: disbursement (including tranched), reset, part-prepayment, full
  prepayment, restructuring, moratorium, stage transfer, write-off, sale, derecognition.
- Sub-ledger balances and GL postings.
- Parallel books, so that an IGAAP or tax basis can be maintained alongside Ind AS.

### Out of scope

Note one framing point that governs everything below: the bank reports under **Indian GAAP with
RBI-prescribed formats**, not Ind AS. IFRS 9 and Ind AS 109 are interpretive sources for mechanics
ACPIR leaves open — not directly applicable standards. So, for example, derivative net interest sits
in Other Income rather than Schedule 13 "Interest Earned", whose compilation instructions do not
enumerate it.

| Excluded | Boundary |
|---|---|
| ECL / impairment measurement | Consumed as input: stage assignment and allowance balance per contract per period. The engine applies them; it does not compute them. |
| Loan origination and servicing | The LMS owns contractual balances, billing, collections. See [ADR-0004](adr/0004-delta-over-contractual-ledger.md). |
| Fair value measurement | FVTPL instruments are out. For FVOCI the engine supplies the EIR interest leg only; valuation comes from elsewhere. |
| Hedge accounting | Out. Effective-interest interaction with fair-value hedge adjustments is a phase-3 consideration, flagged in the [roadmap](08-roadmap.md). |
| General ledger | The engine posts to a GL; it is not one. |
| Classification & measurement decisions (SPPI, business model) | The amortised-cost/FVOCI/FVTPL designation is an input attribute of the contract. |
| Tax computation | The engine can maintain a parallel book on a tax basis; it does not compute tax. |

The ECL boundary is the one worth stating twice, because it is where scope creep will come from — and
here the coupling runs **both ways**. Interest recognition depends on staging, which is an ECL output;
and ECL discounting depends on the EIR, which is this engine's output (ACPIR 50). Two systems with a
mutual dependency will be asked to merge.

They should not. Impairment modelling is a statistical discipline with a different release cadence,
different owners and different validation requirements. The engine takes staging and allowances as
**versioned inputs** and records which version it used — which is also what makes a Stage 3 replay
deterministic.

What the mutual dependency actually argues for is **sequencing, not merging**: build EIR first and
the ECL model inherits a clean discount rate; build it second and the ECL model is rebuilt twice
([08 §0](08-roadmap.md#0-the-sequencing-argument)).

## 5. Why build rather than buy

Sub-ledger modules from the large core-banking and ERP vendors will compute an IFRS 9 EIR. The
decisive gap is that they compute the *wrong one* for India: an IFRS 9 sub-ledger recognises Stage 3
interest on the net basis, and no configuration flag turns that into "suppress the income but retain
the unwind for ECL discounting". Nor do they carry the penal-charge exclusion, the pre-floor duality,
or a routing rule the bank can re-version when the IASB Exposure Draft lands.

Beyond that, the usual build arguments apply, and for an Indian universal bank at least two of these
hold:

- **Product structures the package will not model.** Step-up and step-down EMIs, structured
  and balloon repayments, moratoria with and without interest capitalisation, co-lending
  splits, direct assignment with a retained interest, tranched disbursement against
  milestones.
- **Pool-level practical expedients** with a documented, auditable basis, rather than the
  vendor's fixed grouping.
- **Replay of a closed period** under the policy then in force — rarely a first-class
  feature in packaged sub-ledgers.
- **Multi-entity, multi-GAAP** with entity-specific policy in one engine.
- **Scale economics.** Per-contract licensing over a multi-million-contract retail book is
  frequently the dominant line item.

The build is justified by control over the accounting policy surface and by replayability,
not by the arithmetic. The arithmetic is not the hard part.

## 6. Success measures

| Dimension | Target |
|---|---|
| Correctness | 100% agreement with the [reference cases](reference-cases/README.md), to the last paisa, as a merge gate |
| Regulatory timeliness | New origination on EIR from 1 April 2027; legacy book fully migrated by 31 March 2030, with both ACPIR 21 and ACPIR 50 tracked as separate obligations |
| Adaptability | An IASB or RBI change to the reset rule absorbed as an approved policy version, with no code change and no loss of historical replay |
| Close timeliness | Full-portfolio amortisation for 10M contracts inside a 4-hour window |
| Determinism | Byte-identical re-run of any prior period, verified by an automated nightly replay of a sampled period |
| Explicability | Any contract's period figures traceable to inputs and policy version in under 30 seconds, self-service |
| Reconciliation | EIR sub-ledger to GL: zero unexplained difference at close; contractual interest to LMS: zero unexplained difference |
| Audit outcome | No control deficiency attributable to interest recognition |
| Manual effort | Spreadsheet-based EIR maintenance eliminated for in-scope products |

## 7. Principles

1. **The reference cases are the specification.** Prose describes; the worked cases decide.
2. **Determinism over convenience.** No wall-clock reads, no unseeded randomness, no
   ambient state in the calculation path. Time is an input.
3. **Closed periods are immutable.** Correcting a closed period creates a restatement
   artefact; it never mutates history.
4. **Policy is data, and it is versioned.** A rule change is a new version with an effective
   date, an approver and a stated impact — not a code deploy. This extends to the reset-versus-catch-up
   routing rule, precisely because that rule is under active revision.
5. **Where ACPIR is silent, one declared hierarchy resolves it.** IFRS 9 and Ind AS 109 as
   interpretive sources, on the basis of RBI's own alignment objective, recorded once and stamped on
   every computation. Nine open questions become one documented, auditable choice.
6. **Explain, don't just compute.** Every figure carries its derivation. A number the
   controller cannot defend is a defect regardless of its accuracy.
7. **Precision is a policy, declared once.** Never a floating-point type for money, never an
   implicit rounding, never a difference resolved by tolerance where a rule would do.
8. **Fail per contract, not per run.** One malformed contract must not take down a
   ten-million-contract close.
