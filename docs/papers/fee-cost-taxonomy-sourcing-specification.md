# Fee and cost taxonomy — sourcing specification

**For:** the fee master owner in finance; the cost-centre owners in finance; HR, for incentive
schemes; product control, for the product dimension and the commitment thresholds.
**Item:** DR-01 and DR-02 of the [decision register](../10-decision-register.md), § 2 — the two
critical-path rows.
**Status:** specification, August 2026.

---

## 0. What this document is

The engine that consumes this taxonomy is built and tested. What does not exist is the content:
which fee codes mean what, and which cost centres pay for selling as opposed to appraising.
[08 § 0](../08-roadmap.md#0-the-sequencing-argument) calls this the binding constraint on the whole
programme and says why — it is a **data-sourcing problem owned jointly with HR and finance, not an
accounting one**, and Brazil ran the closest structural analogue with a four-year lead time and
banks were still unprepared at go-live because the granularity of required data was underestimated.
India has less than a year to 1 April 2027.

This document states what has to be supplied, in the form the engine will accept, precisely enough
that HR and finance can act without a further round of translation. It is not an accounting paper:
where a judgement is needed it says who owns it and stops.

**Four deliverables.** Each is separately usable — the engine can classify a fee code the moment its
rows exist, so this is not an all-or-nothing programme.

| | Deliverable | Owner | § |
|---|---|---|---|
| **D1** | The fee rule table: every fee code, classified, with a per-code default and a written reason | Fee master owner, finance | [§ 3](#3-d1--the-fee-rule-table) |
| **D2** | A `cost_function` attribute on every cost posting the bank raises | The feed owners: CBS and the payables systems | [§ 4](#4-d2--the-cost_function-attribute-on-every-cost-posting) |
| **D3** | The ACPIR 53 cost-centre mapping — the substantive accounting deliverable | HR and the cost-centre owners, jointly | [§ 5](#5-d3--the-acpir-53-mapping-selling-agent-against-credit-appraisal) |
| **D4** | Drawdown-probability thresholds per product for commitment fees | Product control and credit | [§ 6](#6-d4--drawdown-probability-thresholds-for-commitment-fees) |

[§ 7](#7-why-an-unmapped-code-is-a-blocked-contract-and-never-a-default) is the section to read
before anyone proposes a default as a convenience.

---

## 1. What the engine already does, so that the deliverable is unambiguous

Resolution keys on four components and yields a treatment or a refusal. There is no third outcome and
no fallback.

```
FeeRuleKey(feeCode, product, entity, effectiveFrom)
      │
      ├── rules whose key matches, from the taxonomy version in force on the date
      │
      └── most specific wins, then the later effective date within one shape
             │
             ├── a classification, citing the rule and the rule-set version   → the projector
             └── a refusal naming the key, category UNMAPPED_FEE_CODE         → the exception queue
```

**Five treatments** ([03 § 3.2](../03-calculation-spec.md#32-fee-and-cost-classification), FR-201):

| Classification | Treatment |
|---|---|
| `INTEGRAL` | Into the initial carrying amount; amortised via the EIR |
| `AS_INCURRED` | P&L when incurred — servicing fees, contingent charges |
| `OVER_COMMITMENT_PERIOD` | Commitment fee where drawdown is **not** probable; recognised on expiry if undrawn |
| `SEPARATE_SERVICE` | A distinct performance obligation — insurance commission, advisory |
| `EXCLUDED_BY_DIRECTION` | Cannot enter any EIR stream or the gross carrying amount — penal charges under RBI's 2023 framework |

**Specificity ranks**, because they decide which of two rows governs a posting:

| Rank | Shape | What it expresses |
|---:|---|---|
| 3 | `(code, product, entity)` | An exact carve-out: this fee, this product, this legal entity |
| 2 | `(code, product, *)` | The product's own fee structure — the group-wide reading |
| 1 | `(code, *, entity)` | An entity-level override across that entity's products |
| 0 | `(code, *, *)` | **The mandatory per-code default** |

Two properties of that table are worth knowing when writing rows. **Product outranks entity**,
because the classification is a fact about what the fee is for and the product is what fixes that; an
entity dimension exists for the narrower thing, a jurisdictional or licensing carve-out inside a
legal entity. And **specificity dominates recency**: issuing a new per-code default in 2028 does not
change a product-specific rule written in 2027, because the 2027 rule is still the most specific
statement about that product. Superseding it takes a new rule at the same specificity.

**The fee code itself can never be a wildcard.** `*` and blank are both rejected as fee codes. A
global `(*, *, *)` row would classify everything, and then no code is ever unmapped and FR-202 is
unenforceable by construction — the exception queue would be empty not because the taxonomy is
complete but because nothing can miss.

---

## 2. Two clocks, and both of them go on the deliverable

Effective-dating happens at two levels and they are not the same question.

1. **Which version of the taxonomy is in force** on the date being asked about. The version with the
   latest effective date that is operative and not in the future governs. A version approved in
   March to take effect in April must not classify a March posting, so a pending version can be
   pre-loaded safely — and it must be, because that is what lets its impact preview be computed.
2. **Which rule inside that version** governs, by specificity then date.

A closed period resolves against the version its computation *stored*, not against today's. Every
computation row carries a `rule_set_version_id` for that purpose
([04 § 2.6](../04-data-model.md#26-eir_computation--the-audit-anchor)). The consequence for the
deliverable is concrete: **a correction is a new row under a later effective date, never an edit to
an existing row.** Rule sets are immutable once approved. Editing a row in place would change what a
closed period reproduces, which is invariant DT-1's one job.

---

## 3. D1 — the fee rule table

### 3.1 Fields per row

Six fields. All six are required; there are no optional columns.

| Field | Format the engine accepts | Who owns the value | Validation the engine enforces |
|---|---|---|---|
| `fee_code` | The code as it appears in the source fee master. Trimmed and upper-cased in a fixed locale, so `PROC_FEE` and `proc_fee` are one code | Fee master owner, finance | Never blank. Never `*`. Case and surrounding whitespace are normalised rather than rejected, because fee masters are exported from core banking in whatever case the originating screen used |
| `product` | A product identifier, or `*` for all products | Product control | `null`, blank and `*` all mean the same thing and normalise to `*` — source configuration files spell an absent dimension all three ways |
| `entity` | A legal-entity identifier, or `*` | Finance, as owner of the entity register | As above |
| `effective_from` | An ISO date. The **first date the rule governs**, inclusive | Finance, fixed when the version is approved | A rule dated tomorrow is not a candidate today, however specific |
| `classification` | Exactly one of the five values in [§ 1](#1-what-the-engine-already-does-so-that-the-deliverable-is-unambiguous) | Finance, approved by the ACPIR 57 sub-committee | Any other value is rejected |
| `rationale` | One or two sentences: why this code gets this treatment | Whoever wrote the row | **Never blank.** The engine refuses a row without one |

The `rationale` field is not documentation and it is not optional politeness. ACPIR 52 states only
the positive limb and carries no negative list, so every classification outside origination and
commitment fees is the bank adopting IFRS 9 B5.4.2 and B5.4.3 as its own configured policy. The
rationale is where that election is written down, one row at a time. An auditor asking why a code is
`AS_INCURRED` is asking this field, and **a rule set of five hundred unexplained rows is not an
accounting policy**.

### 3.2 Fields per version

The rows are delivered inside a version, which carries the approval trail:

| Field | Owner | Note |
|---|---|---|
| `version_id` | Finance | Unique. Two readings under one id make every posting classified by either indistinguishable on replay |
| `kind` | — | `FEE_RULE_SET`. The engine refuses a set approved under a version of any other kind: the posting would cite an approval that was about something else |
| `effective_from` | Finance | Two *operative* versions may not share a first day — nothing orders them, so which governs would depend on registration order |
| `maker`, `checker`, `approved_at` | Finance | Maker–checker, different people |
| `impact_preview_ref` | Finance | **Mandatory before the version can go effective**, for that draft's content. Invariant PG-1 |
| `status` | Finance | One of five: `DRAFT`, `PENDING_APPROVAL`, `APPROVED`, `EFFECTIVE`, `SUPERSEDED`. `SUPERSEDED` stays operative for replay purposes — a closed period resolves against the reading in force when it closed |

### 3.3 Worked rows

Using the codes of [reference case 1](../reference-cases/case-01-emi-loan-with-fees.md) and the
classifications 03 § 3.2 names by hand:

| `fee_code` | `product` | `entity` | `effective_from` | `classification` | `rationale` (abbreviated) |
|---|---|---|---|---|---|
| `PROC_FEE` | `*` | `*` | 2027-04-01 | `INTEGRAL` | Origination fee received, ACPIR 52 positive limb. Case 1's 15,000.00 |
| `DSA_COMM` | `*` | `*` | 2027-04-01 | `INTEGRAL` | Commission paid to a sourcing agent, ACPIR 53 positive limb. Case 1's 10,000.00 |
| `PENAL_CHG` | `*` | `*` | 2027-04-01 | `EXCLUDED_BY_DIRECTION` | Charge, not penal interest, under RBI's 2023 framework: not capitalised, bears no further interest |
| `SERVICING` | `*` | `*` | 2027-04-01 | `AS_INCURRED` | Loan servicing fee, IFRS 9 B5.4.3 negative list adopted as policy |
| `INS_COMM` | `*` | `*` | 2027-04-01 | `SEPARATE_SERVICE` | Distinct performance obligation, not a lending fee |
| `COMMIT_FEE` | `WCDL` | `*` | 2027-04-01 | `OVER_COMMITMENT_PERIOD` | Drawdown probability below the product threshold; see [§ 6](#6-d4--drawdown-probability-thresholds-for-commitment-fees) |

On Case 1, `PROC_FEE` of 15,000.00 received against `DSA_COMM` of 10,000.00 paid nets to 5,000.00 of
integral fee on a 1,000,000.00 advance, an opening gross carrying amount of 995,000.00, and an EIR
**56.6 basis points** above the contractual effective rate. That is the size of the effect a
misclassification moves.

### 3.4 The completeness test, and its number

The engine can tell you how far this deliverable has to go, as a single figure. Invariant **RS-1**
asserts that every fee code in the set has a per-code default row `(code, *, *)` in force on a given
date, and its deviation is **the count of codes that do not**. That count is directly the number of
fee codes that will raise `UNMAPPED_FEE_CODE` on any product or entity nobody has written a carve-out
for.

Two things follow. A partially-loaded taxonomy is a legitimate state during sourcing and the engine
reports the gap rather than refusing the set, so the work can be delivered incrementally against a
falling number. And the test is **asked of a date**, deliberately: a code whose only default is dated
1 January 2030 has a default and would report as complete on a set effective April 2027, while every
posting of that code between the two dates refuses.

---

## 4. D2 — the `cost_function` attribute on every cost posting

Every fee posting carries a `cost_function` attribute
([04 § 2.5](../04-data-model.md#25-fee_posting-and-fee_rule_set)). It is **mandatory on an integral
cost paid by the bank** and rejected when absent (FR-203).

| | |
|---|---|
| **Vocabulary** | Exactly four values: `SELLING`, `PROCESSING`, `ADMIN`, `OTHER`. A fifth value invented by a feed — `DSA`, `SALES` — is rejected, not absorbed: accepting it would make the vocabulary whatever the last feed said it was |
| **Normalisation** | Trimmed and upper-cased in a fixed locale. `null`, an empty string from a CSV with a trailing comma, and a single space from a fixed-width extract are all treated as absent, because all three arrive in practice |
| **Where it belongs** | On the **posting**, not on the rule. The rule set does not carry it, deliberately: a rule that also carried a cost function would be a second place for the ACPIR 53 line to live, and two places for one judgement are two places that can disagree |
| **Who owns it** | The feed owner for each system that raises a cost against an exposure — core banking, payables, the HR incentive system |

The attribute is a *fact about the posting*, sourced from the cost centre or scheme that paid it. The
mapping from cost centre to function is [D3](#5-d3--the-acpir-53-mapping-selling-agent-against-credit-appraisal),
and that is the accounting deliverable. This one is plumbing: get the field populated, on every
posting, from a mapping somebody owns.

---

## 5. D3 — the ACPIR 53 mapping: selling agent against credit appraisal

**This is the substantive accounting distinction in the whole taxonomy, and it is a cost-centre
mapping question only HR and finance can answer.**

### 5.1 The distinction

ACPIR 53 includes, in its positive limb:

> fees and commission paid to agents (**including employees acting as selling agents**), advisers,
> brokers and dealers

and excludes, in its negative limb:

> debt premiums or discounts, financing costs, and internal administrative or holding costs

The words in bold do real work in an Indian bank. An incentive paid to branch staff for **sourcing** a
loan is a capitalisable transaction cost. The salary of the credit-appraisal team **assessing** that
same loan is internal administrative cost and is excluded by name. **The line is drawn at selling, not
at processing** — and source HR and cost-centre data is structured along neither line. That is the
whole difficulty, and it is why [08 § 0](../08-roadmap.md#0-the-sequencing-argument) names this the
single hardest data problem in the programme and gives it a months-long duration with no shortcut.

Note what the distinction is *not*. It is not internal against external: an external valuer's invoice
capitalises and an internal sourcing incentive also capitalises. It is not staff-cost against
third-party-cost: the same branch employee's time can fall on either limb depending on which activity
the cost is paid for. It is the *function the cost served*.

### 5.2 Why the existing four values cannot express it

The engine states this finding rather than papering over it, because the tidy answer would be the more
dangerous artefact.

| Recorded function | ACPIR 53 | Why |
|---|---|---|
| `SELLING` | **Capitalise** | Squarely the positive limb — DSA and DMA commission, broker and dealer fees, and the employee selling-agent incentive paragraph 53 names |
| `ADMIN` | **Exclude** | Squarely the negative limb — internal administrative cost. The credit-appraisal team's salary is the named case |
| `PROCESSING` | **Neither** | It covers internal credit appraisal, which paragraph 53 excludes by name, **and** the external valuation, title search, legal opinion or credit-bureau charge bought in during origination, which is a directly attributable cost that capitalises. One string, two answers, opposite signs on the carrying amount |
| `OTHER` | **Neither** | A residual bucket carrying no accounting information at all — and in practice where a feed puts everything it could not attribute |

So the vocabulary decides half the question. Worse, `PROCESSING` is exactly the word that
[03 § 3.2](../03-calculation-spec.md#32-fee-and-cost-classification) uses for the *excluded* side
("the dividing line is selling, not processing"), which invites the reading that `PROCESSING` means
appraisal — while a bank's fee master routinely files an external valuer's invoice under processing,
where exclusion is wrong.

The engine's response is to refuse rather than choose. `PROCESSING` and `OTHER` resolve to
`INDETERMINATE`, and any posting carrying one that is headed for the carrying amount is refused. The
refusal is the honest answer: it neither overstates the asset nor expenses a genuine selling
commission.

### 5.3 What has to be delivered

Three things. The first two are mapping work; the third is a feed change.

**(a) A cost-centre and scheme register, mapped to a limb.** One row per cost centre or incentive
scheme that can raise a cost against an exposure:

| Field | Owner | Note |
|---|---|---|
| Cost centre or scheme identifier | Finance (cost centres), HR (schemes) | As it appears in the source system that raises the posting |
| What activity the cost pays for, in one line | The cost-centre owner | Sourcing, appraisal, an externally-purchased origination service, servicing, overhead |
| ACPIR 53 limb | The cost-centre owner, approved by the sub-committee | Positive (capitalise), negative (exclude), or split — see (b) |
| `cost_function` to populate | Derived from the limb | The value the feed writes onto every posting from this centre |
| Evidence | The cost-centre owner | What supports the attribution: the scheme document, the recharge basis, the invoice type |

**(b) `PROCESSING` split at source.** The register will contain cost centres that pay for both
activities. Those must be split into at least two functions:

- an **externally-purchased-origination-service** function — valuation, title search, legal opinion,
  bureau pull, broker fee: **capitalise**;
- an **internal-credit-appraisal** function — the appraisal team's salary and its recharges:
  **exclude**.

Where a single cost centre genuinely does both, the split is at the *cost* level, not the centre
level: the recharge has to be decomposed, or the two activities moved to two centres. A percentage
allocation is acceptable only if the basis is documented and the allocation is applied to postings
rather than asserted in a note — otherwise it is an unevidenced judgement wearing a number.

**(c) `OTHER` retired from any cost the bank intends to capitalise.** `OTHER` satisfies the
not-null check and passes an unattributed cost straight through the presence test, which is exactly
why the engine refuses it on the capitalisation route. A feed that cannot attribute a cost must not
be able to route around the requirement by populating the residual bucket.

**What this is not.** A fifth constant added to the engine's enum would be decoration: nothing could
populate it. The change is to the HR and cost-centre feeds. The engine will adopt the new functions
mechanically once something produces them.

### 5.4 The failure mode this prevents, concretely

A bank's fee master maps `PROC_FEE_PAID → INTEGRAL`. The cost centre populates `PROCESSING` for both
its in-house appraisal recharge and its external valuer invoices. The engine capitalises the lot.
Nothing downstream can distinguish the resulting EIR from a correct one — no reconciliation breaks, no
invariant trips — and the misclassification survives until an auditor samples the code.

The magnitude is not academic. On Case 1 the entire net integral fee is 5,000.00 on a 1,000,000.00
advance and it moves the EIR **56.6 basis points**. A mis-swept appraisal cost centre is the same
order of magnitude as the effect being measured.

---

## 6. D4 — drawdown-probability thresholds for commitment fees

ACPIR 52 omits IFRS 9 B5.4.2(b)'s *probable drawdown* condition. Read literally, **every** commitment
fee defers, including on facilities that were never going to draw. The engine therefore requires a
numeric assessment and a per-product threshold to compare it against.

| Field | Format | Owner |
|---|---|---|
| Product identifier | As in the product register | Product control |
| Threshold | A probability in `[0,1]`. Not a percentage string, not a band | Product control, evidenced by historical drawdown rates from credit |
| Evidence reference | The drawdown-history analysis supporting the number | Credit |
| Policy version | Kind `COMMITMENT_THRESHOLD`, with its own maker, checker and effective date | Product control |

Three engine behaviours to design against. A threshold outside `[0,1]` is rejected as a table defect,
because a definition of "probable" that is not a probability cannot classify anything. Two different
thresholds for one product — which happens when two source systems spell a product id differently and
they normalise to one key — is a defect and not a merge. And the table is carried by its **own**
policy kind rather than by the fee rule set's, so that repricing a fee does not force the commitment
thresholds through a re-approval nobody reads.

Below the threshold, the fee routes to `OVER_COMMITMENT_PERIOD` and is recognised on expiry if
undrawn. Each posting also carries its own `drawdown_probability`, in `[0,1]`, which is the
assessment for that facility rather than the product's threshold.

---

## 7. Why an unmapped code is a blocked contract and never a default

Read this section before proposing a default as a convenience. It will be proposed: the pressure
arrives the first time a close is held up by twelve unmapped codes, and the proposal will be framed
as pragmatism.

**Both available defaults are wrong, and wrong in the direction nobody checks.**

- Defaulting an unmapped fee to `INTEGRAL` moves it into the gross carrying amount and amortises it
  over the life of the instrument, so the error appears as a small yield difference spread across
  sixty periods.
- Defaulting it to `AS_INCURRED` keeps it out, so the error appears as period-one income that nobody
  questions.

Neither shows up as a reconciliation break. Neither trips an invariant. The misclassification survives
until an auditor samples the code.

**The leverage is measured.** On Case 1, 5,000 of net fee on a million moves the EIR 56.6 basis
points, and [03 § 3.6](../03-calculation-spec.md#36-expected-versus-contractual-flows)'s assumed-life
table shows year-one fee recognition moving **3.73×** on a change of assumption alone. A fee that
should never have been in the carrying amount carries the same leverage.

**A refusal is not an outage.** FR-905 requires per-contract failure isolation, so a refusal stops
*that contract* and the run continues. A ten-million-contract run collects refusals and carries on.
The engine returns the refusal as a value rather than throwing it, precisely so that this is true — an
unmapped fee code is a fact about the data, not a defect in the caller. What the refusal will not do
is produce a treatment: the classification field is null, and a caller reaching for it gets an
exception naming the key rather than a value.

**The refusal names which of three gaps it is, because they are three different teams' work.**

| What the refusal says | The gap | Who fixes it |
|---|---|---|
| No version of the taxonomy is in force on the date | The policy register | Finance, version management |
| The version in force states no rule for the code at all | The fee master | Fee master owner |
| It states rules for the code but none covering this product, entity and date | A product rollout | Product control |

The third is the most common in practice, because extending a product to a new book is a different
team's change from writing a fee code. A refusal that said only "unmapped fee code" would send all
three to the fee master.

**And there is a related trap worth naming.** A posting arriving with no entity attribute resolves
against the product rule and the per-code default, and never against an entity-specific carve-out —
a rule wildcard matches anything, a *lookup* wildcard matches only a wildcard rule. That is the
conservative reading, because an entity override claimed for a posting that never named an entity
would be a guess. The consequence for the deliverable: if a code has only entity-specific rows, every
posting that arrives without an entity attribute refuses. Write the `(code, *, *)` default.

---

## 8. Cadence and governance

| Question | Answer |
|---|---|
| **Who approves** | The ACPIR 57 sub-committee approves the taxonomy and the ACPIR 53 mapping. Row-level changes go through maker–checker in finance under the approved policy |
| **How a change is made** | A new version with a new effective date, a maker, a checker and a stored impact preview. **Never an edit to an approved row** |
| **What blocks a version going effective** | The absence of an impact preview for that draft's own content (invariant PG-1). The gate refuses `EFFECTIVE` without one |
| **Review cadence** | The fee master is change-controlled rather than periodic — a change is driven by a new product or a repricing. The ACPIR 53 mapping needs an annual review, because cost-centre structures move and a mapping that silently stops covering a reorganised centre reads as complete |
| **What an auditor will ask for** | The row that classified a posting, the version that approved the row, the rationale on the row, and the cost-centre attribution behind the `cost_function`. All four are stored per posting |
| **What replay requires** | Nothing extra, provided rows are never edited. Every computation stores the `rule_set_version_id` that classified it, and a replay pins resolution to that version rather than to the date |

---

## 9. Acceptance: what "sourced" means

A checklist that can be signed, in the order the work naturally falls.

- [ ] Every fee code in the source fee master appears in the rule table with a `(code, *, *)` default
      row in force on 1 April 2027 — that is, **RS-1's deviation is nil at that date**.
- [ ] Every row carries a non-blank rationale.
- [ ] Penal charge codes resolve to `EXCLUDED_BY_DIRECTION`, and the CBS remediation plan for penal
      amounts currently routed through the interest ledger has a date on it (decision register
      DR-16).
- [ ] Every product whose fee structure differs from the group reading has its `(code, product, *)`
      rows.
- [ ] Every cost centre and incentive scheme that can raise a cost against an exposure appears in the
      register of [§ 5.3](#53-what-has-to-be-delivered) with an ACPIR 53 limb and evidence.
- [ ] No cost centre intended to produce capitalisable costs still populates `PROCESSING` or `OTHER`.
- [ ] Every feed that raises a cost posting populates `cost_function` from that register.
- [ ] Every product that charges a commitment fee has a threshold in `[0,1]` in a
      `COMMITMENT_THRESHOLD` policy version, with the drawdown-history evidence referenced.
- [ ] A dry run over a representative extract produces an `UNMAPPED_FEE_CODE` count and a
      `MISSING_COST_FUNCTION` count that finance is willing to work as an exception queue rather
      than to be surprised by at the first close.

The last item is the one that catches what the others miss. Both counts are available before go-live,
against real postings, and a number nobody has looked at before the first close is a number that
arrives during it.

---

## 10. What the engine will not do, whatever is asked of it

Stated so that nobody spends time proposing it.

- **It will not default an unmapped fee code.** [§ 7](#7-why-an-unmapped-code-is-a-blocked-contract-and-never-a-default).
- **It will not accept a wildcard fee code**, which would be the same thing by another route.
- **It will not capitalise a cost whose function is `PROCESSING` or `OTHER`.**
- **It will not re-decide a classification in the projector.** The classification arrives already
  resolved by the rule set; a second opinion held downstream is two judgements that can disagree.
- **It will not hold the cost function on the rule.** Same reason, in the other direction.
- **It will not accept `EXCLUDED_BY_DIRECTION` into a cash flow stream.** The posting cannot even be
  constructed, and the exclusion is asserted positively every period (invariant PC-1) rather than
  assumed — because legacy core banking systems routinely route penal amounts through the interest
  ledger.
- **It will not take a manual override of a rate or a balance**, ever
  ([ADR-0008](../adr/0008-no-manual-rate-override.md), [07 § 5](../07-nfr-controls-audit.md#5-automation-mandate)).
