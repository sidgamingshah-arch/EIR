# 10 — Decision Register

Every item in this repository that needs a human to decide something. Forty-six of them. Ten are on
the critical path.

This document exists because sixteen of the seventeen open workstreams in [08](08-roadmap.md) are
things code can close, and three are not. An engine cannot approve its own materiality threshold,
cannot map a cost centre to a paragraph of ACPIR 53, and cannot make the IASB publish an Exposure
Draft. Pretending otherwise would be the worst outcome available: the register's whole purpose is to
make the undecided visible rather than let it be absorbed as a default.

**How to read a row.** Five fields, and the third and fourth are the ones that matter:

| Field | What it means |
|---|---|
| **Item** | The decision, in one line. |
| **Owner** | Who decides. Where a Board committee approves, the owner is whoever tables the paper and the approver is named separately. "Sub-committee" throughout means the ACPIR 57 Board committee including the CFO and CRO. |
| **The ask** | What has to be produced. Precise enough that the owner can tell when it is done. |
| **If it is not decided** | The consequence, stated as a mechanism rather than as a risk. Where the engine refuses, it says so; where the engine proceeds on a code default instead, it says that — those two are not the same exposure. |
| **Reference** | Where it is written down. Some rows say *code only* or *nowhere*, and those are the interesting ones. |

**Ordered by consequence, not by document order.** § 2 is the critical path — nothing downstream can
be correct, or the engine cannot lawfully run, until these close. § 3 blocks a run, a close or an
origination once the engine is live. § 4 blocks a reporting or disclosure position. § 5 is the
Phase 7 watch items, whose trigger is not ours. § 6 is the items that are a reading rather than a
judgement: the ask is to obtain a document and read it.

**What is deliberately not in here.** [08's scope table](08-roadmap.md#what-is-deliberately-not-in-scope)
excludes ECL measurement, origination and servicing, the general ledger, fair value measurement
beyond the ACPIR 19 transition, regulatory capital and derivative valuation. Those workstreams have
their own decisions and their own owners. A decision belongs in this register only if an EIR figure
this engine publishes is wrong or unauthorised without it.

---

## 1. Three things worth knowing before reading further

**The critical path is a data problem, not an accounting one, and it is external.**
[08 § 0](08-roadmap.md#0-the-sequencing-argument) states it and the risk register puts it first:
Brazil ran the closest structural analogue with a four-year lead time under CMN 4966, and banks were
still unprepared at go-live because the granularity of required data was underestimated. India has
less than a year to 1 April 2027. DR-01 and DR-02 are that item, split into the half finance owns
and the half HR owns.

**Three decisions in this register are written down nowhere but in a javadoc, and one is written
down nowhere at all.** DR-07 — what "fully collateralised low-fee" means — is the only route by
which a long-tenor instrument reaches the Tier 3 straight-line shortcut, and neither half of the
phrase is quantified anywhere in this repository. DR-08 (the T-bill tension) and DR-02's
`PROCESSING` finding live in `EquivalenceTestGate` and `CostFunction` javadoc and in no document.
Code is a poor place to keep a Board decision, because the audience that has to take it does not read
code.

**Two of the ten critical-path items are already late by their own terms.** DR-09's paragraph 19
rebuttal evidence must be built *during* FY27 rather than at the transition date, and evidence
assembled at the transition date is evidence about the transition date. DR-10's independent
validation is required by FR-906 and control C-08 *before* implementation, and the engine is built.
Neither can be recovered by working faster. Both can only be decided about.

---

## 2. On the critical path

Ten items. Each one either stops the engine, or lets it run on a number nobody approved.

### DR-01 — The fee master: every fee code classified, with a per-code default and a written reason

| | |
|---|---|
| **Owner** | Finance, as owner of the fee master. Approved by the sub-committee; change-controlled under maker–checker with a stored impact preview. |
| **The ask** | For every fee code in the source fee master: one of the five treatments of FR-201 (`INTEGRAL`, `AS_INCURRED`, `OVER_COMMITMENT_PERIOD`, `SEPARATE_SERVICE`, `EXCLUDED_BY_DIRECTION`); a `(code, *, *)` default row, mandatory per code; product and entity carve-out rows where the reading differs; and one sentence of rationale per row. |
| **If it is not decided** | An unmapped code is a refusal, not a classification. `FeeClassificationResolver` returns `FeeClassificationResolution.unmapped` with `classification == null` under category `UNMAPPED_FEE_CODE`, and the contract stops. At book scale that is a close that cannot complete, every month, until the taxonomy is finished. There is no partial answer to fall back on: ACPIR 52 states only the positive limb and carries no negative list, so without policy the bank has no principled basis to keep any fee out. |
| **Reference** | [08 § 0](08-roadmap.md#0-the-sequencing-argument) and the [risk register](08-roadmap.md#risk-register), first row · [03 § 3.2](03-calculation-spec.md#32-fee-and-cost-classification) · [04 § 2.5](04-data-model.md#25-fee_posting-and-fee_rule_set) · FR-201, FR-202, FR-210 · invariant RS-1 · [reference § 9](reference/acpir-2026-eir-application-reference.md) item 2 |
| **Critical path** | **Yes.** This is the item 08 § 0 names as the binding constraint on the whole programme. |

The engine half is finished and tested. `FeeRuleSet` holds a version, `FeeRule` carries the row and
refuses a blank rationale, `FeeRuleKey(feeCode, product, entity, effectiveFrom)` resolves
most-specific-wins with `FeeRuleKey.ANY` as the wildcard on product and entity and never on the fee
code, and `FeeClassificationResolver` returns a classification or a refusal and nothing else. What
does not exist is the content. The field-level form of this ask is
[the sourcing specification](papers/fee-cost-taxonomy-sourcing-specification.md).

### DR-02 — The `cost_function` split at source: four values cannot express ACPIR 53

| | |
|---|---|
| **Owner** | HR, for incentive schemes, jointly with the cost-centre owners in finance. The resulting mapping is approved by the sub-committee. |
| **The ask** | Three deliverables. (a) Every cost centre and incentive scheme that raises a cost against an exposure, mapped to one limb of ACPIR 53. (b) `PROCESSING` split at source into at least an externally-purchased-origination-service function — valuation, title search, legal opinion, bureau pull, broker — which capitalises, and an internal-credit-appraisal function, which is excluded. (c) `OTHER` no longer populated on any cost the bank intends to capitalise. |
| **If it is not decided** | `CostFunction.resolveForCapitalisation` returns `INDETERMINATE` for both `PROCESSING` and `OTHER`, so every integral cost carrying either is refused. The pressure that then arrives is a proposal to default one of them, and both defaults are wrong in the direction nobody checks: capitalising sweeps credit-appraisal salary into the gross carrying amount, expensing writes off a genuine external origination cost in year one. On [reference case 1](reference-cases/case-01-emi-loan-with-fees.md) the entire net integral fee is 5,000.00 on a 1,000,000.00 advance and it moves the EIR 56.6 basis points — a mis-swept appraisal cost centre is the same order of magnitude as the effect being measured. |
| **Reference** | FR-203 · [03 § 3.2](03-calculation-spec.md#32-fee-and-cost-classification) · [04 § 2.5](04-data-model.md#25-fee_posting-and-fee_rule_set) · [08 § 0](08-roadmap.md#0-the-sequencing-argument) · [reference § 9](reference/acpir-2026-eir-application-reference.md) item 5 · **the four-value finding is code only:** `CostFunction` and `CostFunctionCapitalisability` javadoc |
| **Critical path** | **Yes.** Months-long, owned by other teams, no shortcut. |

ACPIR 53 includes "fees and commission paid to agents (including employees acting as selling
agents), advisers, brokers and dealers" and excludes "internal administrative or holding costs". The
line is drawn at *selling*, not at *processing*. Of the four functions the ingestion layer can
populate — `SELLING`, `PROCESSING`, `ADMIN`, `OTHER` — only two land on a side of it. That finding is
recorded in `CostFunction`'s javadoc and in no document, which is why it is repeated here and
specified in [the sourcing specification](papers/fee-cost-taxonomy-sourcing-specification.md).

### DR-03 — The interpretive hierarchy, Board-approved

| | |
|---|---|
| **Owner** | Technical accounting tables the paper; the sub-committee approves; the statutory auditor is consulted before rather than after. |
| **The ask** | Adopt [03 § 2](03-calculation-spec.md#2-interpretive-hierarchy)'s four levels as written — ACPIR operative text, then other applicable RBI Directions, then IFRS 9 and Ind AS 109 as interpretive source, then Board-approved entity policy selecting among defensible readings — and with it the seven IFRS 9 elections § 2 lists by name. |
| **If it is not decided** | Every silence this engine closes is closed on an unapproved reading. Nine silences are mapped from `[HIGH]` to `[MED]` in [reference § 2](reference/acpir-2026-eir-application-reference.md) and the engine implements a reading of each. Adopting the hierarchy converts nine open questions into one documented, auditable choice. Not adopting it leaves nine. |
| **Reference** | [03 § 2](03-calculation-spec.md#2-interpretive-hierarchy) · [reference § 2](reference/acpir-2026-eir-application-reference.md) and § 9 item 1 · [00 § 4](00-product-vision.md#4-scope) |
| **Critical path** | **Yes, by dependency rather than by duration.** One paper and one meeting, and it is the authority every other row in this register rests on. |

### DR-04 — The Tier 1 wholesale threshold: a number the engine has no default for

| | |
|---|---|
| **Owner** | Sub-committee. Carried as a dated `PolicyVersion` of kind `TIER_ASSIGNMENT`. |
| **The ask** | A rupee threshold above which a wholesale exposure is measured at Tier 1, with an effective date and an approver. |
| **If it is not decided** | `TierAssignment` takes the threshold as a constructor argument and refuses a negative one. There is no default anywhere in the code, deliberately: a constant would be a Board decision without a date, and a period closed under last year's threshold must replay under last year's threshold. So either no tier assignment runs, or somebody picks a number and § 10's instrument-level tightened-tolerance population is whatever they picked. Note also that the comparison is strictly greater — an exposure struck exactly at the threshold is not above it, and thresholds get set at the round numbers exposures are also written at. |
| **Reference** | [03 § 10](03-calculation-spec.md#10-the-materiality-tier-gate) Tier 1 row · FR-107 · [reference § 9](reference/acpir-2026-eir-application-reference.md) item 26 · `TierAssignment`, `TierAssignmentInput.exceedsThreshold`, `TierAssignmentRule.TIER_1_WHOLESALE_ABOVE_BOARD_THRESHOLD`, `PolicyKind` |
| **Critical path** | **Yes.** The engine cannot assign a tier without it. |

### DR-05 — The Tier 3 equivalence-test threshold, in basis points, per population

| | |
|---|---|
| **Owner** | Finance performs the test; the sub-committee approves the threshold. Re-performed annually (`EquivalenceTestRecord.ANNUAL_WINDOW`, one year). |
| **The ask** | Per Tier 3 population: the sample design for the solved-versus-approximated comparison, and the threshold in basis points that the documented delta must sit inside. |
| **If it is not decided** | `EquivalenceTestRecord` carries `boardApprovedThresholdBps` per record and refuses a negative one, and [ADR-0008](adr/0008-no-manual-rate-override.md) forbids manual override, so there is no way to record a test without a threshold. With no threshold there is no test on file, and TG-1 demotes every Tier 3 population to Tier 2. That fallback is *correct* — Tier 2 is a pool EIR under the ACPIR 51 group presumption, more expensive and more right than contractual rate plus straight-line accretion — so the consequence is cost, and a book-wide change of measurement basis between one close and the next that nobody approved. |
| **Reference** | [03 § 10.2](03-calculation-spec.md#102-the-equivalence-test-tier-3) · FR-411 · invariant TG-1 · control C-10, [07 § 4.1](07-nfr-controls-audit.md#41-the-control-set) · [reference § 9](reference/acpir-2026-eir-application-reference.md) item 26 |
| **Critical path** | **Yes.** |

The threshold is set against the delta's *magnitude*. `EquivalenceTestRecord` states the reason: a
test understating the rate by 40 bps is exactly as indefensible as one overstating it by 40, which is
why a signed threshold would be the wrong instrument.

### DR-06 — `DEEP_DISCOUNT_ACCRETION_SHARE = 0.50`

| | |
|---|---|
| **Owner** | Sub-committee, on the paper at [papers/deep-discount-threshold-board-note.md](papers/deep-discount-threshold-board-note.md). |
| **The ask** | Approve the accretion share of total return at or above which an instrument is a deep-discount instrument for FR-412, and therefore refused Tier 3 at any tenor. The recommendation is one half. |
| **If it is not decided** | A constant in `eir-policy` decides which instruments a statutory refusal catches. Its own javadoc says as much: "a policy default of one half, not a specification figure". Neither [03 § 10.3](03-calculation-spec.md#103-where-approximation-is-never-permitted) nor [Case 9](reference-cases/case-09-straight-line-vs-eir.md) publishes a number — they publish the mechanism and an absolute prohibition. The zero-coupon limb does not depend on the threshold at all, so the absolute part of FR-412 holds wherever it is set; the deep-discount limb *is* the threshold. Set too high, the shortfall is the Cambodia failure mode, measured on Case 9 at **81.0%** overstatement of year-one income. Set too low, the cost is Tier 2 measurement — which 03 § 10.2 already names as the consequence of a stale test. |
| **Reference** | `EquivalenceTestSubject.DEEP_DISCOUNT_ACCRETION_SHARE` · [03 § 10.3](03-calculation-spec.md#103-where-approximation-is-never-permitted) · FR-412 · [Case 9](reference-cases/case-09-straight-line-vs-eir.md) · [the Board note](papers/deep-discount-threshold-board-note.md) |
| **Critical path** | **Yes**, before the first Tier 3 population is measured. |

### DR-07 — What "fully collateralised low-fee" means

| | |
|---|---|
| **Owner** | Credit, for the collateral-coverage measure; finance, for the fee-materiality measure. Approved by the sub-committee. |
| **The ask** | Two numbers and their bases: the collateral coverage ratio at which an exposure is "fully collateralised", and the measure and level at which its fees are "low". Together they define which exposures may carry the `FULLY_COLLATERALISED_LOW_FEE` attribute. |
| **If it is not decided** | This is the only route by which a **long-tenor** instrument reaches the Tier 3 straight-line shortcut. The two tenor rules bound Tier 3 at twelve months or less; this limb carries no tenor condition in § 10, and `TierAssignmentRule` evaluates it last precisely so that it catches only what the tenor rules leave. What they leave is a long-tenor exposure outside retail and MSME, fully secured, with immaterial fees — and that is the population [Case 9](reference-cases/case-09-straight-line-vs-eir.md) measures at **+81.0%** of year-one income when the shortcut is applied to a long instrument. The attribute is *supplied*. Nothing in this repository quantifies either half of the phrase, so today the definition sits with whoever populates the feed. |
| **Reference** | [03 § 10](03-calculation-spec.md#10-the-materiality-tier-gate) Tier 3 row · `TierAssignmentFeature.FULLY_COLLATERALISED_LOW_FEE`, `TierAssignmentRule.TIER_3_FULLY_COLLATERALISED_LOW_FEE` · [reference § 4A](reference/acpir-2026-eir-application-reference.md), § 9 item 26 · **nowhere quantified** |
| **Critical path** | **Yes.** |

This item was not previously written down anywhere. Reference § 9 item 26 — "materiality and
de minimis thresholds, quantified, with the basis" — is the nearest existing hook and does not name
it.

### DR-08 — The money-market Tier 3 reading: ratify the demotion, or approve a carve-out with a number

| | |
|---|---|
| **Owner** | Technical accounting tables it; the sub-committee decides. |
| **The ask** | Either ratify the engine's resolution — T-bills, CP and CD refused Tier 3 and measured at Tier 2 — or approve a carve-out returning money-market discount instruments to Tier 3, with the threshold and the basis stated. |
| **If it is not decided** | Two statements in the specification cannot both be applied as written. § 10's Tier 3 row names "T-bills, CP, CD" by hand; FR-412 and § 10.3 refuse Tier 3 for zero-coupon instruments at any tenor, and every one of those three *is* a discount instrument with no coupon leg. `EquivalenceTestGate` resolves the conflict in favour of the specific over the general and says why at length, including that the alternative "is a Board-approved carve-out with a number attached, recorded as policy — not a default buried in an evaluator". Until somebody decides, the measurement basis of the entire money-market book rests on that javadoc. |
| **Reference** | [03 § 10](03-calculation-spec.md#10-the-materiality-tier-gate) Tier 3 row against [§ 10.3](03-calculation-spec.md#103-where-approximation-is-never-permitted) · FR-412 · [reference § 4A](reference/acpir-2026-eir-application-reference.md) · **code only:** `EquivalenceTestGate` javadoc, "The T-bill tension, and how it is resolved" |
| **Critical path** | **Yes.** |

The gate's own argument is worth carrying into the paper: "at any tenor" appears twice and does no
work at all unless it binds short instruments, because every Tier 3 population is by construction
inside twelve months. On a 91-day bill the front-loading error is small in rupees and the price of
refusing the shortcut is a pool EIR the engine computes anyway for the retail book. That is an
argument for a reading, not a substitute for approving one.

### DR-09 — The paragraph 19 fair value rebuttal evidence file, built during FY27

| | |
|---|---|
| **Owner** | The programme, with finance. Evidence referenced per contract. |
| **The ask** | Per population where carrying cost is taken as best evidence of fair value at 1 April 2027: what the supporting file is, who assembles it, and on what dates during FY27. |
| **If it is not decided** | Invariant TF-1 refuses a transition fair value that relies on the ACPIR 19 presumption with no rebuttal evidence reference, per contract and per run, so the day-1 valuation cannot complete. The deeper problem is not the refusal but the clock: the window is FY27 and it does not reopen. Evidence assembled at the transition date is evidence about the transition date. |
| **Reference** | [04 § 6](04-data-model.md#6-transition-specific-structures) · [08 Phase 4](08-roadmap.md) · invariant TF-1 · [07 § 4.1.1](07-nfr-controls-audit.md#411-the-transition-controls-are-not-in-that-table-and-that-is-deliberate) · [reference § 9](reference/acpir-2026-eir-application-reference.md) item 29 |
| **Critical path** | **Yes, and time-boxed.** |

### DR-10 — Model inventory registration and independent validation, which FR-906 requires before implementation

| | |
|---|---|
| **Owner** | Model risk / independent validation. The sub-committee is responsible under ACPIR 57 for ensuring the validation function's independence. |
| **The ask** | Register the EIR computation in the model inventory with its tier and documentation, and complete independent validation. Then set the review cadence. |
| **If it is not decided** | FR-906 and control C-08 both say "before implementation", and the engine is built through Phase 5. The obligation is therefore already unmet, and the decision available now is about how much later it gets met — not about whether the sequence was followed. Saying that plainly is more useful than a plan that implies otherwise. The behavioural curves of DR-12 are where validation earns the most: [03 § 3.6](03-calculation-spec.md#36-expected-versus-contractual-flows) measures 3.73× of leverage on year-one fee recognition from an assumed-life change alone. |
| **Reference** | FR-906 · control C-08, [07 § 4.1](07-nfr-controls-audit.md#41-the-control-set) · ACPIR Chapter V · [08 Phase 0](08-roadmap.md) exit gate |
| **Critical path** | **Yes.** |

---

## 3. Blocks a run, a close or an origination once the engine is live

Ten items. Each one is a gate the engine will hold shut, correctly, until somebody decides.

| ID | Item | Owner | The ask | If it is not decided | Reference |
|---|---|---|---|---|---|
| **DR-11** | Commitment fee drawdown-probability thresholds, per product | Product control and credit; evidence from CBS drawdown history | A numeric threshold per product for "probable" drawdown, evidenced by historical drawdown rates | FR-204 requires a numeric `drawdown_probability` on every commitment fee, and `FeePosting` rejects one outside `[0,1]`. ACPIR 52 omits IFRS 9 B5.4.2(b)'s probable condition, so read literally **every** commitment fee defers, including on facilities that were never going to draw. With no threshold there is no basis to route any fee to `OVER_COMMITMENT_PERIOD` | FR-204 · [03 § 3.2](03-calculation-spec.md#32-fee-and-cost-classification) · [reference § 2](reference/acpir-2026-eir-application-reference.md) Silence 4, § 9 item 3 · `fee/CommitmentFeePolicy` |
| **DR-12** | Expected life method per product, and independent validation of the behavioural curves | Product control for method, risk for curves, model risk for validation | Per product family: contractual, next-reset shortcut, or modelled behavioural, with the reasoning. CPR curves by product, vintage and segment. Back-testing with escalation triggers | [03 § 3.6](03-calculation-spec.md#36-expected-versus-contractual-flows) measures the leverage: compressing assumed life from 20 years to 8 multiplies year-one fee recognition by **3.73×**. The 08 risk register names the UK restatement pattern, whose failure mode is curves that are optimistic and unchallenged rather than absent | FR-301, FR-306, FR-308, FR-310 · [03 § 3.6](03-calculation-spec.md#36-expected-versus-contractual-flows) · [Case 8](reference-cases/case-08-b544-repricing-shortcut.md) · [reference § 9](reference/acpir-2026-eir-application-reference.md) item 7 · [08 risk register](08-roadmap.md#risk-register) |
| **DR-13** | Pool definitions — **two different artefacts sharing one word** | Product control and risk | (a) FR-409 *measurement* pools: homogeneity criteria, the closed-cohort rule, the back-test sample, and the materiality threshold that forces contract-level measurement. (b) FR-608 *suspension* pools: which product books may suspend income at pool level | PL-1 refuses income suspension except under a pool definition in force on the date, and PL-2 restricts it to cards and KCC, so those books cannot suspend without (b). C-11's quarterly back-test has no threshold to breach without (a). 08 Phase 3 records that FR-608's artefact has no table at all: 04 § 2.10's `POOL` is the Tier 2 measurement cohort, a different thing that shares a word | [03 § 10.1](03-calculation-spec.md#101-pool-level-eir-tier-2) · FR-409, FR-608 · PL-1, PL-2, control C-11 · [04 § 2.10](04-data-model.md#210-pool) · [08 Phase 3](08-roadmap.md) · [reference § 9](reference/acpir-2026-eir-application-reference.md) item 25 · [ADR-0005](adr/0005-contract-level-default.md) |
| **DR-14** | Destination of the day-1 difference on below-market origination | Sub-committee, as a `POLICY_POSITION` version; HR for the staff book | Where the day-1 shortfall on a staff or concessional loan goes, and the market-rate reference it is measured against | BM-1 asserts that a Board position was **taken**, not that any destination is right, and refuses origination without one. Silence 6 is `[MED-HIGH]`: ACPIR 19 and 20 require fair value at initial recognition and give no guidance on the difference. The reference's reading — the shortfall is employee compensation, not a lending loss — is a reading | FR-909 · BM-1 · `transition/BelowMarketOrigination`, `DayOneDifferenceDestination` · [reference § 2](reference/acpir-2026-eir-application-reference.md) Silence 6, § 9 item 19 · [08 Phase 4](08-roadmap.md) |
| **DR-15** | Where the Stage 3 shadow unwind is presented, and the suspense ledger's disclosure language | Sub-committee with the statutory auditor; CRO for the ECL interface | The presentation and disclosure position for the unwind the engine computes and does not recognise | ACPIR suppresses income on Stage 3, but ECL is a present-value measure discounted at the EIR, so the discount unwinds mechanically every period. FR-603 has the engine compute and retain it and recognise nothing. Silence 8 is `[HIGH]`, and India needs a third answer because the amount is neither recognised as income nor released. The four-way reconciliation S3-1 will tie either way; what it cannot do is decide where the figure is presented | [03 § 7](03-calculation-spec.md#7-impairment-interaction-the-india-divergence) · FR-603, FR-604 · S3-1, ST-2, S3-2, control C-04 · [reference § 2](reference/acpir-2026-eir-application-reference.md) Silence 8, § 9 item 22 · [01](01-domain-primer.md) |
| **DR-16** | The penal-charge CBS remediation plan | Technology, as CBS owner, with finance | A dated plan to stop penal amounts routing through the interest ledger, and the interim volumes expected | `FeePosting` refuses `EXCLUDED_BY_DIRECTION` at construction, and PC-1 asserts the exclusion positively every period as an assertion rather than an assumption — because legacy core banking systems routinely book penal amounts into the interest ledger. Unremediated, the engine correctly refuses them at volume, which is a blocked close every month until the ledger is fixed | [03 § 3.4](03-calculation-spec.md#34-penal-charges-hard-exclusion) · FR-207 · PC-1, control C-03 · [reference § 9](reference/acpir-2026-eir-application-reference.md) item 21 |
| **DR-17** | Modification substantiality: the review band width and the approval authority | Sub-committee for the authority; technical accounting for the indicator and the qualitative triggers | The asset-side indicator, the band half-width around it, and who approves a conclusion inside the band | FR-511 requires approval for asset-side conclusions inside a configurable band. `ReviewBand` is a policy input rather than a constant, for a stated reason: the ratio moves with the projection assumptions behind the revised flows, so 10.02% and 9.98% carry the same information while the distance between their consequences could not be larger. The code offers a 9%–11% standard band. An unset band decides how much work reaches a person. B3.3.6's 10% is a **liability** rule; any asset-side analogue is the bank's own elected indicator | [03 § 6.4](03-calculation-spec.md#64-modification-versus-derecognition) · FR-511 · `calc/routing/ReviewBand` · [reference § 9](reference/acpir-2026-eir-application-reference.md) item 10 |
| **DR-18** | FITL and DCCO deferment: modification or not, and the EIR consequence | Sub-committee with credit | For each of FITL creation and DCCO deferment: whether it is a modification, and what happens to the EIR | § 10's Tier 1 row makes every restructured or modified exposure instrument-level, so this answer sets the tier as well as the rate. Silence 2 is `[HIGH]`, and the reference calls it the single most consequential gap for a bank with a restructuring book, because it determines whether the original EIR survives or a new one is struck | [03 § 6.4](03-calculation-spec.md#64-modification-versus-derecognition) · FR-511 · [reference § 2](reference/acpir-2026-eir-application-reference.md) Silence 2, § 4 item 13, § 9 item 11 |
| **DR-19** | The product taxonomy aligned to the ACPIR 82 floor categories | Finance and the capital/provisioning workstream, jointly | One product tag per contract, aligned to the floor categories and shared with the provisioning engine | FR-106 requires the tag to be shared and refuses gold loans grouped under secured retail (ACPIR 92). Two taxonomies are two answers to one question, and the EIR and ECL figures then disaggregate differently in the same disclosure. Control C-09 audits it | FR-106 · control C-09 · [04 § 2.2](04-data-model.md#22-product--the-shared-taxonomy) · [reference § 10](reference/acpir-2026-eir-application-reference.md) |
| **DR-20** | The ACPIR 51 contractual-fallback criteria | Sub-committee | The criteria for electing the contractual fallback per contract, with the explicit acknowledgement that it is intended for rare cases | FR-310 makes it an explicit per-contract election recording its justification, and FR-809 reports its incidence. Without criteria the fallback is the path of least resistance on any contract whose behavioural work is hard, and the incidence report is the only thing that would ever show it | FR-310, FR-809 · [reference § 9](reference/acpir-2026-eir-application-reference.md) item 27 |

---

## 4. Blocks a reporting or disclosure position

Seventeen items. None of them stops a run. Each of them stops a number being published with an
authority behind it.

| ID | Item | Owner | The ask | If it is not decided | Reference |
|---|---|---|---|---|---|
| **DR-21** | Investment book method: EIR or straight-line | Sub-committee, with treasury | The method, and — if EIR — the quantified transition impact; if straight-line, the documented basis and the disclosure | [Case 9](reference-cases/case-09-straight-line-vs-eir.md) is the argument and the wedge: on a 15-year zero-coupon at 8%, straight-line overstates year-one income by **81.0%** and understates the final year by **38.4%**, with life totals identical to the paisa at 684,758.30. Selectivity cannot be defended in this category, which makes it the lever for a full migration rather than a carve-out. Depends on DR-41 | [08 Phase 6](08-roadmap.md) · [Case 9](reference-cases/case-09-straight-line-vs-eir.md) · [reference § 5](reference/acpir-2026-eir-application-reference.md) item 7, § 9 item 12, § 12 unverified item 1 |
| **DR-22** | Liability-side EIR: extend symmetrically, or document the asymmetry | Sub-committee, with treasury | The election, plus two consequential positions: which bond issue expenses capitalise, and whether deposit brokerage capitalises into the deposit's EIR | Silence 5 `[MED]`: ACPIR 20 addresses financial assets and the 6(6) definition references only an asset's gross carrying amount, while IFRS 9 applies the method symmetrically. Undecided, the NIM asymmetry is real and undisclosed | [03 § 11](03-calculation-spec.md#11-liabilities) · [08 Phase 6](08-roadmap.md) · [reference § 2](reference/acpir-2026-eir-application-reference.md) Silence 5, § 9 items 16, 17, 18 |
| **DR-23** | Hedge accounting policy | Sub-committee, with treasury and technical accounting | Adoption of IFRS 9 Chapter 6, the portfolio-hedge carve-out election, and the basis-adjustment amortisation commencement policy | Silence 9 `[HIGH]`: no hedge accounting provisions at all and no reference to basis adjustment amortisation, which is a direct EIR mechanic rather than a peripheral one. HB-1 blocks a close where a discontinued hedge has no active amortisation schedule, and the commencement policy is what fills the schedule | [03 § 12](03-calculation-spec.md#12-hedge-accounting-interaction) · FR-701–FR-705 · HB-1, HB-2, control C-06 · [reference § 4B.2](reference/acpir-2026-eir-application-reference.md), § 9 item 32 |
| **DR-24** | Derivative interest presentation | Sub-committee, with the auditor | Schedule 13 or Other Income, and the hedging-result disclosure that restores the economic margin | FR-807 presents derivative net interest in Other Income and discloses the hedging result separately, so the economic margin on the hedged banking book is reconstructable. That is a reading of an unanswered question, not an answer to it | FR-807 · [reference § 4B.4](reference/acpir-2026-eir-application-reference.md), § 9 item 33, § 12 open question 8 |
| **DR-25** | Derivative provisioning perimeter | Sub-committee, with risk and the capital workstream | Whether derivative MTM receivables are treated as within ACPIR 17(7) scope pending clarification, and the quantified capital cost of the conservative reading | The repealed 2025 Directions carried an explicit derivative provisioning paragraph, and no equivalent appears in the operative text reviewed. Holding the conservative reading has a capital cost nobody has quantified. See DR-42 | [reference § 4B.1](reference/acpir-2026-eir-application-reference.md), § 9 item 31, § 12 unverified item 2 · [08 Phase 7](08-roadmap.md#phase-7--watch-items) |
| **DR-26** | Government interest subvention: inside or outside the EIR | Sub-committee | One position, applied consistently across agriculture, education and MSME | Silence 7 `[MED]`, at scale across three books. FR-209 excludes it unless the contract makes the borrower's own rate contingent on it — a reading that has to be adopted or replaced, and above all applied consistently, because inconsistency across the three books is what an auditor will find | FR-209 · [reference § 2](reference/acpir-2026-eir-application-reference.md) Silence 7, § 9 item 20 |
| **DR-27** | Callable and puttable securities, and perpetuals | Sub-committee, with treasury | Whether to follow RBI's residual-contractual-maturity treatment or IFRS 9 expected life, with the difference quantified either way; and for perpetuals, an SPPI screen first then earliest-call amortisation for those that pass | Two defensible readings give different answers on the same holding, and § 10's Tier 1 row already pulls any contingent-rate feature to instrument level. Unresolved, the treasury book's amortisation profile is whichever the projector's caller elected | [reference § 4](reference/acpir-2026-eir-application-reference.md) items 26 and 27, § 9 items 13 and 14, § 12 open question 5 · [09](09-cashflow-structures.md) |
| **DR-28** | Securitisation note prepayment assumptions | Treasury, with risk | The source, the review frequency, and the catch-up mechanics on revision | § 10's preamble names securitisation notes among the five long-tenor families holding roughly 80% of estimation risk. A revision routes to a catch-up, so the review frequency sets how large each one is | FR-306 · [reference § 4](reference/acpir-2026-eir-application-reference.md) item 29, § 9 item 15 |
| **DR-29** | POCI identification criteria, at origination and at transition | Sub-committee, with credit | The criteria for identifying a discount that reflects inherent credit losses (ACPIR 6(3)(v)) at both points, and confirmation that the credit-adjusted EIR is retained on cure | FR-110 identifies POCI on acquisition terms, and § 10's Tier 1 row makes all POCI instrument-level, so the criteria decide a tier and a rate basis together. Retention on cure is asserted by POCI-1 — and 08 Phase 3 records that it is asserted at a boundary where the rate has round-tripped through persistence rather than swept, because `PociAmortisation.afterCure` takes no rate parameter and a generated property would only assert what the signature already guarantees | FR-110, FR-407, FR-408 · POCI-1 · [Case 6](reference-cases/case-06-poci-credit-adjusted-eir.md) · [reference § 9](reference/acpir-2026-eir-application-reference.md) item 23 · [08 Phase 3](08-roadmap.md) |
| **DR-30** | Syndication fee bifurcation method | Technical accounting, approved by the sub-committee | The method for splitting arrangement service income from the EIR adjustment where the arranger's fee share and retained share diverge | FR-205 requires the bifurcation and does not fix the method. Without one, the whole fee either capitalises or does not, and the two answers differ by the excess | FR-205 · [reference § 4](reference/acpir-2026-eir-application-reference.md) item 17, § 9 item 4 |
| **DR-31** | Revolving facility fee treatment | Product control | The ACPIR 54 approximation elected per product — fee over the renewal or sanction period, or EIR over an expected utilisation profile — and the split between the renewal-linked credit-assessment component and the availability component | FR-307 implements whichever is configured, and FR-809 reports the incidence of the approximation. Cash credit, overdraft and cards are the volume centre of an Indian book, so an unelected approximation is not a small population | [03 § 3.7](03-calculation-spec.md#37-revolving-facilities) · FR-307, FR-809 · [reference § 9](reference/acpir-2026-eir-application-reference.md) item 6 |
| **DR-32** | Estimation technique for conditional terms | Technical accounting, approved by the sub-committee | Most likely outcome or probability-weighted, per instrument class, with the "better predicts the resolution" test as the stated basis | Any contingent rate feature is Tier 1 under § 10, so the technique is applied instrument by instrument, and its absence surfaces as inconsistency between two exposures carrying the same feature | [reference § 3](reference/acpir-2026-eir-application-reference.md) Layer 4, § 9 item 8 |
| **DR-33** | ESG contingency: data structure now, treatment position when the ED lands | Product control now; sub-committee on the position | The attribute captured at origination now, and the treatment position when the Exposure Draft is published | The data cannot be captured retrospectively for loans already written. The treatment position can wait for DR-38; the structure cannot | [reference § 4](reference/acpir-2026-eir-application-reference.md) item 19, § 9 item 30 · [08 Phase 7](08-roadmap.md#phase-7--watch-items) |
| **DR-34** | Legacy cohort segmentation criteria, the migration sequence, and the deemed-EIR methodology | The programme, approved by the sub-committee | Cohort definitions with membership rules; the sequence, ordered by survival past 31 March 2030; and the deemed-rate methodology for cohorts where reconstruction is infeasible | LC-1 refuses a plan that queues a 2030 survivor behind a cohort running off before it, and DE-1 refuses a deemed cohort with no approved derivation. `LegacyCohort.definition` is free text that nothing evaluates and `contractCount` is supplied rather than counted, so membership is asserted — the right place for code to stop and the wrong place for the programme to. The trap is concrete: ordered by size, a 1,200,000-contract auto book running off in 2029 precedes an 800-contract project finance cohort running to 2038, and it is the one that never needs a reconstructed rate at all | FR-908, FR-909 · LC-1, DE-1, TM-1 · [04 § 6](04-data-model.md#6-transition-specific-structures) · [08 Phase 4](08-roadmap.md) · [reference § 9](reference/acpir-2026-eir-application-reference.md) item 28 |
| **DR-35** | Day-count and compounding conventions by instrument class, in one document | Technical accounting, approved by the sub-committee | One table: convention per instrument class, and the compounding basis | Six conventions are implemented (FR-304), and the default for term lending is currently a method in the calculation module: `ProductTemplates.termLoanDayCount()` returns 30/360, described in its own javadoc as "a policy default that a product may override". A convention decides an accrual on every contract in its class | [03 § 3.9](03-calculation-spec.md#39-day-count-conventions) · FR-304 · [reference § 9](reference/acpir-2026-eir-application-reference.md) item 24 · `ProductTemplates.termLoanDayCount` |
| **DR-36** | The routing table version in force | Product control, under maker–checker with an impact preview | Approve a routing table version: driver tag to mechanism, for every driver in the taxonomy | [ADR-0006](adr/0006-configurable-event-routing.md) makes reset-versus-catch-up versioned data rather than code, and RT-1 refuses an event routed against a version the registry does not carry. What ships is the specification's defaults; approving a version is what converts a code default into policy. It is also the mechanism by which DR-38 is absorbed, so the approval route has to exist **before** the Exposure Draft lands, not after | [03 § 6.1](03-calculation-spec.md#61-routing-is-configuration-keyed-to-a-driver-taxonomy) · FR-504–FR-507 · [ADR-0006](adr/0006-configurable-event-routing.md) · RT-1 · [reference § 9](reference/acpir-2026-eir-application-reference.md) item 9 |
| **DR-37** | The 4-hour close window and ADR-0009's core-hour figures: accept the estimates, or measure | Engineering, with the programme | Either accept the design-time sizing as the basis of the NFR, or commission the 10M-contract load test that Phase 5's exit gate assumes | [07 § 1](07-nfr-controls-audit.md#1-scale-and-performance) targets a full-portfolio close inside 4 hours at roughly 700 contracts per second. 08 Phase 5 records the 4-hour figure as **untested and an estimate**, and [ADR-0009](adr/0009-par-gap-as-the-ordering-baseline.md) records 0.2 against 922 core-hours per 10M-contract close as "an estimate, not a measurement on this codebase", kept because the order of magnitude is what the decision turns on. Going live on an unmeasured NFR is a decision, and it should be taken deliberately rather than by default | [07 § 1](07-nfr-controls-audit.md#1-scale-and-performance) · [08 Phase 5](08-roadmap.md) · [ADR-0009](adr/0009-par-gap-as-the-ordering-baseline.md) · [ADR-0007](adr/0007-spring-batch-for-runs.md) |

---

## 5. Phase 7 watch items — the trigger is not ours

Six items, from [08 Phase 7](08-roadmap.md#phase-7--watch-items). What makes them different from
everything above is that the timing belongs to someone else, so the decision to take **now** is
usually the smaller one: what to do while waiting, and whether a mechanism exists to absorb the
answer when it arrives.

| ID | Watch item | Trigger | Response, and the now-action |
|---|---|---|---|
| **DR-38** | IASB Exposure Draft on Amortised Cost Measurement | Expected H2 2026; the April 2026 tentative decision would amend B5.4.5 | **The response already exists, and it is a routing-table version change rather than an engine change** — that is the entire point of [ADR-0006](adr/0006-configurable-event-routing.md), and it is why this row needs no engineering decision. Two now-actions remain, both small: confirm whether the ED has actually been published ([reference § 12](reference/acpir-2026-eir-application-reference.md) records it as unconfirmed as at the compilation date), and make sure DR-36's approval route works before it is needed under time pressure |
| **DR-39** | IASB modification project | February 2025 tentative decision toward principles-based substantiality | Update the qualitative triggers in policy — which is DR-17 and DR-18. Now-action: none beyond keeping those two decisions in a form that can be re-versioned |
| **DR-40** | ACPIR amendment Directions | Any | Re-run the divergence register; issue a new policy version. Now-action: none, except that the register in [reference § 5](reference/acpir-2026-eir-application-reference.md) is the artefact that gets re-run, so it has to stay current enough to be worth re-running |
| **DR-41** | Investment Portfolio Directions 2026 amendment | Text not reviewed in the domain reference | **There is a now-action, and it is not a decision.** The 2026 Amendment Directions were issued alongside ACPIR on 27 April 2026 and their full text has not been reviewed; they may settle whether the investment book amortises on an effective-interest or a straight-line basis. Obtain and read the text before DR-21 is signed |
| **DR-42** | Derivative provisioning successor | The repealed 2025 Directions' paragraph 115 has no visible successor in the operative text reviewed to approximately paragraph 105; Annexes 1–4 unreviewed | Raise through the IBA, and hold the conservative reading meanwhile. Now-actions: read the annexes (DR-44), and quantify the capital cost of the conservative reading (DR-25) so the position is not held blind |
| **DR-43** | CET1 add-back unwind, 80% to 20% across 2027–2031 | The transitional schedule | Disclose the fully-loaded position from the first reporting date, as the Seventh Amendment requires. Owned by the capital workstream rather than by this engine; recorded here because the relief is easy to let mask the terminal position |

---

## 6. Readings, not judgements

Three items where the ask is to obtain a document and read it. They are last because they are cheap,
and they are here because two of them sit underneath decisions above.

| ID | Item | Owner | The ask | If it is not done | Reference |
|---|---|---|---|---|---|
| **DR-44** | ACPIR Annexes 1, 2, 3 and 4 | Technical accounting | Obtain and read all four. Annex 3 covers the automation requirements and bears directly on the control set of [07 § 4.1](07-nfr-controls-audit.md#41-the-control-set); Annex 1 covers SICR information, Annex 2 the simplified approach for trade and lease receivables, Annex 4 the advances computation format | The control set was designed against the operative text alone. If Annex 3 imposes automation requirements the fifteen controls do not cover, the gap is found at audit rather than at design | [reference § 12](reference/acpir-2026-eir-application-reference.md) unverified items · [07 § 4.1](07-nfr-controls-audit.md#41-the-control-set) |
| **DR-45** | The nine open questions for RBI or the IBA: which to raise, and when | Technical accounting, through the IBA | A view on each of the nine, and a decision on which to raise formally | The highest-value one is the first: does paragraph 52's omission of the IFRS 9 B5.4.3 negative list mean it does not apply, or that it applies through RBI's stated alignment objective? **That answer is the authority DR-01's rationale column is currently supplying by election under DR-03.** An answer would make several hundred rows of fee-master reasoning either firmer or wrong, and the lead time is not ours — which is an argument for raising it early rather than for waiting | [reference § 12](reference/acpir-2026-eir-application-reference.md) open questions 1–9 · [reference § 2](reference/acpir-2026-eir-application-reference.md) Silence 3 |
| **DR-46** | Whether ACPIR 21 and ACPIR 50 are one obligation or two | Technical accounting | Confirm the reading | The engine already takes the conservative one — two obligations with a common deadline, tracked separately by `ECL_DISCOUNT_BASIS` and asserted by TM-1, because tracking them in one field would hide a gap. So the consequence of not confirming is small and one-directional: if they turn out to be one obligation, the engine has tracked something harmlessly twice | [04 § 6](04-data-model.md#6-transition-specific-structures) · TM-1 · [reference § 12](reference/acpir-2026-eir-application-reference.md) unverified items |

---

## 7. The index nobody wants: numbers the code supplies in the absence of a decision

Five figures currently live in `src/main` and set an accounting outcome. Four of them are decisions
in this register. The fifth is here for contrast, because it is the one that carries its own
derivation.

| Figure | Where | Status |
|---|---|---|
| `DEEP_DISCOUNT_ACCRETION_SHARE = 0.50` | `policy/tier/EquivalenceTestSubject` | **DR-06.** Public rather than private precisely so that it is visible as a policy figure; its javadoc states it is not a specification figure |
| `FULLY_COLLATERALISED_LOW_FEE`, undefined | `policy/tier/TierAssignmentFeature` | **DR-07.** Not a number at all yet — a supplied boolean with no definition behind it |
| The T-bill reading | `policy/tier/EquivalenceTestGate` javadoc | **DR-08.** A reading of two conflicting specification statements, argued in a comment |
| `termLoanDayCount() = 30/360` | `calc/projection/blueprint/ProductTemplates` | **DR-35.** Described in its own javadoc as a policy default a product may override |
| `ORDERING_EPSILON = 0.01` | `calc/amort/InvariantChecks` | **Not a decision, and the reason is instructive.** Published rather than private for the same reason as the first row — it decides when a fee is too small for its sign to be checkable — but [ADR-0009](adr/0009-par-gap-as-the-ordering-baseline.md) derives it from measurement: one paisa, ten thousand times above the arithmetic noise in computing the par gap and five hundred thousand times below reference case 1's 5,000 of fee net of gap. A figure with that derivation attached does not need a Board. A figure without one does |

The general rule this table argues for: **a number in `src/main` that decides an accounting outcome
either carries its measured derivation or carries an approval, and if it carries neither it belongs
in this register.**

---

## 8. Maintaining this register

Three rules, each of which exists because of a failure this programme has already recorded.

1. **A row leaves only when the decision is minuted, not when the code stops asking.** Wiring a
   default is how an open decision disappears without being taken — the same shape as
   [08 Phase 3](08-roadmap.md)'s finding that a computed control nobody asserts is
   indistinguishable from one that does not exist, and that a control asserting something other
   than its own statement is worse, because it reads as coverage.
2. **A decision taken gets an ADR or a policy version, and this register gets the reference.** Not a
   deletion. [The ADR index](adr/README.md) states the discipline: a decision is never edited to
   reverse it, and the reasoning trail survives.
3. **A number arriving in `src/main` without an approval or a measured derivation gets a row here in
   the same change.** § 7 is the list of the ones that got there before this document existed.
