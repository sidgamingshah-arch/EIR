---
title: "Effective Interest Rate — Application Reference for an Indian Commercial Bank"
subtitle: "RBI ACPIR 2026 spine · IFRS 9 jurisdictional practice · Big 4 interpretive layer"
date: 2026-08-23
version: 1.1
anchor_regulation: "RBI (Commercial Banks — Asset Classification, Provisioning and Income Recognition) Directions, 2026"
effective: 2027-04-01
legacy_deadline: 2030-03-31
status: "Working reference — not audited advice"
---

# Effective Interest Rate: application reference for an Indian commercial bank

Anchored on the RBI (Commercial Banks — Asset Classification, Provisioning and Income
Recognition) Directions, 2026. Mapped against IFRS 9 as applied in thirteen jurisdictions that
have gone live, and against the interpretive positions published by the large accounting firms
and the IASB.

`Compiled 23 August 2026 · ACPIR effective 1 April 2027 · Legacy book EIR deadline 31 March 2030`

---

## Two things have changed the shape of this problem in 2026

**1. The standard itself is moving.** In April 2026 the IASB tentatively decided to amend
IFRS 9 B5.4.5 so that the EIR is adjusted for a re-estimation of contractual cash flows that
provides consideration for the time value of money *or for credit risk* — with an Exposure Draft
planned for H2 2026. The reset-versus-catch-up rule your engine is about to hard-code is under
active revision. Build the switch, not the constant.

**2. RBI has kept one large divergence on purpose.** The Seventh Amendment Directions confirm
that non-recognition of income on Stage 3 assets will not attract an auditor qualification.
IFRS 9 requires interest on credit-impaired assets to be recognised on *net* carrying amount.
India suppresses it entirely. Your engine must still unwind the discount internally — ECL is
defined as cash shortfalls discounted at the EIR — while keeping that unwind out of the P&L.

---

## Contents

- [0. How to use this document](#0-how-to-use-this-document)
- [1. The ACPIR 2026 EIR spine — what RBI actually said](#1-the-acpir-2026-eir-spine--what-rbi-actually-said)
- [2. What ACPIR does not say — the silence map](#2-what-acpir-does-not-say--the-silence-map)
- [3. Six decision layers behind every EIR](#3-six-decision-layers-behind-every-eir)
- [4. Product matrix — 38 instrument families](#4-product-matrix--38-instrument-families)
- [4A. The tenor lens and the materiality gate](#4a-the-tenor-lens-and-the-materiality-gate)
- [4B. Derivatives, hedge accounting and structured products](#4b-derivatives-hedge-accounting-and-structured-products)
- [5. Divergence register — ACPIR 2026 against IFRS 9](#5-divergence-register--acpir-2026-against-ifrs-9)
- [6. Jurisdiction dossiers](#6-jurisdiction-dossiers)
- [7. The live IASB project](#7-the-live-iasb-project--why-you-must-not-hard-code-the-reset-rule)
- [8. Data architecture and engine design](#8-data-architecture-and-engine-design)
- [9. Policy checklist — thirty positions](#9-policy-checklist--thirty-positions-requiring-board-sub-committee-approval)
- [10. Transition sequencing](#10-transition-sequencing)
- [11. Source library](#11-source-library)
- [12. Assumptions, unverified items and open questions](#12-assumptions-unverified-items-and-open-questions)

**Severity convention.** `[HIGH]` ACPIR is silent or divergent on something that changes reported
interest income or ECL materially, and the bank must take a defensible position. `[MED]`
Divergence exists but is bounded, or affects a limited portfolio. `[LOW]` Presentational or
emergent. `[ALIGNED]` ACPIR text tracks IFRS 9 closely enough that firm guidance transfers
directly.

---

## 0. How to use this document

This is built to be argued from, not read through.

**If you are drafting the accounting policy.** Start at §2 (the silences), then §5 (divergence
register). Every item in §5 is a position your Board sub-committee must take because RBI has not
taken it for you. §9 converts them into a sign-off checklist.

**If you are specifying the computation engine.** Start at §4 and §4A to size the product
taxonomy and set the materiality tiers, then §8 for the data dictionary, solver design and
reconciliation controls. §4 and §4A together are the requirements document.

**If you are building a client-facing view.** §6 (jurisdictions) and §7 (live IASB project) are
the differentiators — they answer "what did banks that already did this get wrong, and what is
about to change." §10 is the sequencing argument.

---

## 1. The ACPIR 2026 EIR spine — what RBI actually said

The Directions carry only nine operative EIR paragraphs. Knowing them verbatim matters, because
the entire policy exercise is about what sits in the space around them.

| Para | Subject | Substance and what it commits you to |
|---|---|---|
| 6(1) | Amortised cost | Carrying amount after principal repayments, cumulative EIR-method amortisation of the difference between initial recognition amount and maturity amount, adjusted for loss allowance. Standard IFRS 9 construction. |
| 6(4) | Credit-adjusted EIR | Rate that exactly discounts estimated future cash flows over the expected life of a POCI asset to its amortised cost at initial recognition. Note: "amortised cost", not gross — the lifetime ECL is inside the rate. |
| 6(6) | EIR | Rate that exactly discounts estimated future cash flows through the expected life of the instrument to the **gross carrying amount** of a financial asset. RBI's definition omits IFRS 9's reference to the amortised cost of a financial *liability* — see §5 item 11. |
| 6(12) | Gross carrying amount | Amortised cost before adjusting for any loss allowance. Confirms the decoupled construction: interest accretes on gross, ECL sits separately. |
| 6(29) | Transaction cost | Incremental costs directly attributable to the acquisition, issue or disposal of a financial asset. Tracks IFRS 9 Appendix A. |
| 19 | Fair valuation on transition | On 1 April 2027 banks fair value the **entire** loan portfolio. Difference against pre-transition carrying amount goes to opening retained earnings, not P&L. Rebuttable presumption: where facts indicate the transaction was on terms such that fair value is not materially different from carrying cost, carrying cost is the best evidence of fair value. |
| 20 | Initial & subsequent measurement | Loans originated on or after 1 April 2027: measure at fair value plus or minus directly attributable transaction costs; thereafter amortised cost using the EIR method. |
| 21 | Legacy book | All loans outstanding as at 31 March 2027 to be brought under the EIR regime no later than 31 March 2030, with resulting adjustments recognised in the financial statements. |
| 22 | Investments | Initial recognition and subsequent measurement of investments including debt securities is governed by the Investment Portfolio Directions, **not** by ACPIR. This single cross-reference is the source of the largest treasury-book divergence — see §5 item 7. |
| 23 | Commitments | For loan commitments and off-balance-sheet exposures, the date the bank becomes party to the irrevocable commitment is the date of initial recognition for ECL purposes. |
| 24 | POCI | Day-1 lifetime ECL is reflected in the cash flows used to compute the credit-adjusted EIR; no separate impairment allowance at that date. Thereafter only cumulative changes in lifetime ECL relative to the initial estimate are recognised. |
| 50 | Which rate for ECL | ECL for instruments originated or invested on or after 1 April 2027 uses the EIR determined at initial recognition; POCI uses the credit-adjusted EIR. Opening ECL at 1 April 2027 may, at the bank's discretion, use the contractual rate as an interim discount factor — but full migration to EIR by 31 March 2030. |
| 51 | How to compute | Estimate expected cash flows considering all contractual terms (prepayment, extension, call and similar options) but **not** expected credit losses. Include all fees integral to the EIR, transaction costs, and all premiums or discounts. Presumption that cash flows and expected life of a group of similar instruments can be estimated reliably; in rare cases where they cannot, use contractual cash flows over the full contractual term. This is IFRS 9 Appendix A almost verbatim. |
| 52 | Fees that are integral | Origination fees relating to the creation and acquisition of a financial asset, and commitment fees received to originate a loan. |
| 53 | Transaction costs | Include fees and commission paid to agents (**including employees acting as selling agents**), advisers, brokers and dealers. Exclude debt premiums or discounts, financing costs, and internal administrative or holding costs. Tracks IFRS 9 B5.4.8. |
| 54 | Discounting commitments & guarantees | ECL on loan commitments discounted at the EIR (or an approximation) that will apply to the resulting asset. For revolving facilities and guarantees where the EIR cannot be determined directly, use a rate reflecting the current market assessment of the time value of money and the risks specific to the cash flows. |

> **Read paragraph 53 twice.** "Including employees acting as selling agents" is doing real work
> in an Indian bank. Incentive payouts to branch staff whose function is loan sourcing become
> capitalisable transaction costs. Salary of the credit-appraisal team does not — that is an
> internal administrative cost, explicitly excluded. The dividing line is *selling*, not
> *processing*. Expect the HR and finance data to be structured along neither line, which makes
> this an early data-sourcing workstream rather than a late accounting one.

---

## 2. What ACPIR does not say — the silence map

ACPIR is roughly 1,200 words on EIR against IFRS 9's several thousand plus two decades of
interpretive material. The gaps are not accidental; RBI has consistently framed these Directions
as prudential rather than as a full accounting standard. But a bank cannot run an EIR engine on
1,200 words. Each silence below must be closed by Board-approved policy.

**Silence 1 — Subsequent changes in cash flows.** `[HIGH]` No equivalent of IFRS 9 B5.4.5
(floating-rate EIR reset) or B5.4.6 (cumulative catch-up through P&L). ACPIR tells you how to
strike the EIR at inception and then goes quiet. Yet every EBLR reset, every prepayment-curve
revision and every credit ratchet is a change in estimated cash flows.

**Silence 2 — Modification versus derecognition.** `[HIGH]` No equivalent of IFRS 9 5.4.3 /
3.2.3 or the "substantial modification" concept. For a bank with a restructuring book, a
DCCO-deferment book and FITL creation, this is the single most consequential gap: it determines
whether the original EIR survives or a new one is struck.

**Silence 3 — The negative fee list.** `[HIGH]` Paragraph 52 lists only what *is* integral.
IFRS 9 B5.4.3 lists what is not: loan servicing fees, commitment fees where a specific lending
arrangement is unlikely, and syndication fees where the arranger retains nothing (or retains at
the same EIR as other participants for comparable risk). Without the negative list, a bank has
no principled basis to keep any fee out.

**Silence 4 — The "probable" condition on commitment fees.** `[MED-HIGH]` IFRS 9 B5.4.2(b)
makes a commitment fee integral only where the instrument is not at FVTPL *and it is probable
the entity will enter into a specific lending arrangement*; if the commitment expires undrawn,
the fee is revenue on expiry. ACPIR drops the condition. Read literally, every commitment fee
defers — including on facilities that were never going to draw.

**Silence 5 — Liabilities.** `[MED]` Paragraph 20 addresses financial assets. The EIR
definition in 6(6) references only the gross carrying amount of an asset. Deposits, bond
issuances, Tier 2 and refinance lines are outside scope. IFRS 9 applies the effective interest
method symmetrically.

**Silence 6 — Below-market-rate origination.** `[MED-HIGH]` Paragraphs 19 and 20 require fair
value at initial recognition but give no guidance on what to do with a day-1 difference. For
staff housing loans and directed concessional lending this is not theoretical — and for public
sector banks the staff book is large.

**Silence 7 — Government interest subvention.** `[MED]` Neither ACPIR nor the Investment
Directions address whether a subvention or prompt-repayment incentive received from Government
is a cash flow to be built into the EIR. It affects agriculture, education and MSME books at
scale.

**Silence 8 — Presentation of the Stage 3 unwind.** `[HIGH]` Income is not recognised on
Stage 3. But ECL is a present-value measure discounted at the EIR, so the discount unwinds
mechanically each period. ACPIR does not say where that unwind goes. IFRS practice debates
whether it is interest revenue or an impairment-line movement; India needs a third answer
because it is neither recognised as income nor released.

**Silence 9 — Hedge accounting.** `[HIGH]` No hedge accounting provisions at all, and no
reference to basis adjustment amortisation. See §4B.2 — this is a direct EIR mechanic, not a
peripheral one.

> **The drafting posture to adopt.** The defensible position for an Indian bank is: *where ACPIR
> is silent, apply IFRS 9 and Ind AS 109 as the interpretive source, because RBI states in its
> own Introduction that the Directions are intended to align the regulatory framework more
> closely with internationally accepted financial reporting principles.* Write that sentence into
> the policy as the governing interpretive hierarchy. It converts nine open questions into one
> documented, auditable choice — and it is the position RBI's own framing invites.

---

## 3. Six decision layers behind every EIR

Product-level guidance in §4 is an application of these six layers. Get the layers right once and
the 38 rows fall out.

### Layer 1 — What is the opening balance the rate must solve to?

Fair value at initial recognition, plus or minus directly attributable transaction costs
(ACPIR 20). For an arm's-length loan priced at market, fair value equals the amount disbursed,
and the presumption in paragraph 19 does the work. The exceptions are the whole game:
concessional staff loans, directed lending below market, loans acquired at a discount reflecting
credit deterioration (which become POCI), and portfolios bought in a pool transaction.

### Layer 2 — Which cash flows go in?

Contractual principal and interest; fees integral to the EIR; transaction costs; premiums and
discounts. Explicitly excluded: expected credit losses (ACPIR 51), debt premiums or discounts as
a cost, financing costs, internal administration (ACPIR 53). India-specific hard exclusion: penal
charges, which under RBI's 2023 penal charges framework are charges rather than penal interest,
are not capitalised and bear no further interest — so they cannot enter the amortisation schedule
or the gross carrying amount at all.

### Layer 3 — Over what life?

Expected life, considering all contractual terms including prepayment, extension and call options
(ACPIR 51). Two subtleties that trip banks up:

- **Expected life for EIR ≠ lifetime for ECL.** ACPIR 46(1) defines the ECL horizon as the
  *maximum contractual* period including extension options. The EIR expected life may be
  materially shorter where prepayment is expected. Maintain both parameters and document why they
  differ; auditors will ask.
- **The B5.4.4 shortcut.** Where a premium, discount or fee relates to a variable that is
  repriced to market rates before maturity, it is amortised to the *next repricing date*. For a
  floating-rate retail mortgage this largely dissolves the behavioural-life estimation problem —
  a substantial simplification that many banks miss and then over-engineer around.

Where reliable estimation genuinely fails, ACPIR 51 permits fallback to contractual cash flows
over the full contractual term. The IASB staff have been explicit that this fallback is for
*rare* cases and that entities invoking it for convenience are misapplying the definition, not
exploiting an option.

### Layer 4 — How are conditional terms reflected at inception?

IFRS 9 does not prescribe a method. IASB outreach in 2025 found two methods in use, and the Board
decided in September 2025 to take no further action because the diversity flows from facts and
circumstances rather than from unclear requirements:

| Method | Where it is used | Practical read for an Indian bank |
|---|---|---|
| Most likely outcome | Most common overall; the only method used by many non-financial entities for liabilities | Binary management judgement — will the contingent event occur or not. Requires instrument-level estimation, so it is poorly suited to a collective approach. Use for large single-name corporate facilities with bespoke ratchets. |
| Probability-weighted (expected value) | Primarily collective estimates where outcomes are neither binary nor concentrated; banks use it for prepayment probability on homogeneous retail pools | The right method for retail mortgage prepayment, credit card behavioural life and KCC rollover. Requires a curve, not a judgement. |

Firms' manuals point to IAS 37 paragraphs 39–40 and IFRIC 23 by analogy for choosing between
them: use whichever better predicts the resolution of the uncertainty. Adopt that formulation in
policy — it is the position the IASB staff themselves endorsed.

### Layer 5 — What happens when estimates change?

This is the live controversy; see §7. The operative distinction is between the two mechanisms:

**EIR reset (B5.4.5).** Iteratively recompute the rate so the current gross carrying amount
unwinds to revised cash flows. No immediate P&L. The change is spread over remaining life.
Operationally simpler, aligns with contractual data already in the CBS, and avoids volatility.

**Cumulative catch-up (B5.4.6).** Retain the original EIR; recompute the gross carrying amount
as the PV of revised contractual cash flows at that original rate; recognise the difference in
P&L immediately. Preserves the cost-based character of amortised cost, but produces one-off gains
and losses.

IASB staff analysis is unambiguous that catch-up, not reset, is the mechanism more compatible
with amortised cost as a cost-based measure — and therefore that B5.4.5 was intended to be
narrow. Practice went the other way: most entities reset the EIR for nearly all changes in
interest cash flows regardless of whether those changes reflect market terms. The April 2026
tentative decision splits the difference. Design for both mechanisms and a routing rule between
them.

### Layer 6 — How does impairment interact?

Three distinct regimes, and the Indian overlay differs in one of them:

| Position | IFRS 9 | ACPIR 2026 |
|---|---|---|
| Stage 1 / 2 | EIR on gross carrying amount | Same. Interest income continues on gross; Stage 1/2 provisions shown separately in Schedule 5 and not netted from gross advances. |
| Stage 3 | EIR on amortised cost (gross less loss allowance). On cure, revert to gross; the accumulated difference has a defined presentation. | **Divergent.** Income is not recognised. The Seventh Amendment expressly protects this from auditor qualification on the basis that the standard permits postponement where collectability is significantly uncertain. |
| POCI | Credit-adjusted EIR embedding day-1 lifetime ECL; no day-1 allowance; only cumulative changes thereafter. cEIR is retained even after the asset cures. | Aligned — ACPIR 24 and 50 track this closely, including the no-day-1-allowance mechanic and the cumulative-changes-only rule. |

---

## 4. Product matrix — 38 instrument families

Each entry states the EIR position, the India-specific nuance, and the paragraph or interpretive
source that supports it. Class tags: `LOAN` `INV` `OBS` `LIAB`. Hard cases marked `†`.

### LOANS

#### 1. Term loan — fixed rate, retail or corporate `LOAN`
`ACPIR 20, 52, 53` · `[ALIGNED]`
- **EIR position.** Baseline case. EIR is the IRR equating net day-1 amount (disbursal less
  processing fee received plus DSA payout) to the contractual EMI schedule over expected life.
- **India nuance.** Processing fees of 0.25–2% and DSA/DMA payouts of 0.5–2% both enter. Today
  both are recognised upfront. The net effect is usually a small EIR uplift over the card rate
  and a deferred-fee liability on the balance sheet.

#### 2. Term loan — floating (EBLR / repo-linked / MCLR) `LOAN` †
`IFRS 9 B5.4.5, B5.4.4` · `[HIGH]`
- **EIR position.** On each reset, recompute the EIR so revised cash flows discount to the
  current gross carrying amount. No P&L catch-up. Unamortised fee balance carries forward into
  the new rate.
- **India nuance.** EBLR resets can be monthly or quarterly. A naive implementation re-solves the
  IRR on the whole schedule every reset for every account — computationally brutal at Indian
  retail volumes. Use the B5.4.4 shortcut: amortise fees to the next repricing date, which
  decouples fee amortisation from the reset loop entirely.

#### 3. Cash credit / overdraft (drawing-power based, revolving) `LOAN` †
`ACPIR 54; IFRS 9 B5.4.3` · `[HIGH]`
- **EIR position.** No contractual drawdown or amortisation schedule exists, so a conventional
  EIR cannot be struck on the funded balance. Amortise the renewal-linked fee over the sanction
  or renewal period; treat availability-based fees on the undrawn limit as service revenue.
- **India nuance.** The policy call is whether a CC processing fee is an origination fee (defers)
  or a facility service fee (upfront). Defensible split: the component compensating credit
  assessment and documentation at each renewal defers over the renewal period; the component
  compensating availability of the undrawn limit is service income. ACPIR 54 explicitly
  contemplates that the EIR cannot be determined directly for revolving facilities and permits an
  approximation.

#### 4. Working capital demand loan `LOAN`
`ACPIR 51` · `[MED]`
- **EIR position.** Short bullet tenor (7–180 days). Amortise fees over the drawdown tenor. If
  continuously rolled, assess whether substance is a revolving facility rather than a series of
  new instruments.
- **India nuance.** A 1% fee on a 30-day WCDL amortised over 30 days produces an EIR far above
  the card rate — arithmetically correct but it will look wrong in MIS. Pre-agree the presentation
  with finance before go-live rather than after the first quarter's variance analysis.

#### 5. Bills purchased / discounted (inland and export) `LOAN`
`ACPIR 9(1), 51` · `[ALIGNED]`
- **EIR position.** The discount collected upfront *is* the interest. EIR accretes the discount
  over the usance period. Normally no separate fee layer.
- **India nuance.** Cleanest product in the book — the EIR is already implicit in existing
  practice. LC-backed bill discounting carries a special NPA carve-out under ACPIR 9(1) but that
  affects staging, not the EIR. Rediscounting raises a derecognition question before it raises an
  EIR one.

#### 6. Credit cards `LOAN` †
`ACPIR 46(2)(iii)` · `[HIGH]`
- **EIR position.** Contractually auto-renewing revolver. Expected life must be determined
  behaviourally. Interest accretes only on revolving balances, not on transactions settled within
  the interest-free period.
- **India nuance.** ACPIR 46(2)(iii) requires analysis of historical default patterns, drawdown
  behaviour and the effectiveness of limit reduction, suspension or cancellation — an explicit
  behavioural-modelling mandate. Fee split: joining fee has an origination character; annual fee,
  interchange and late fees are service income. UK experience is the cautionary tale — spreading
  card economics over a modelled behavioural life creates a balance-sheet "EIR asset" whose size
  is pure estimate, and it has been a recurring source of restatement among consumer lenders.

#### 7. Gold loan (bullet, 6–12 months) `LOAN`
`ACPIR 92 Explanation` · `[MED]`
- **EIR position.** Bullet repayment with interest monthly or at maturity. EIR ≈ contractual plus
  fee accretion over the bullet tenor.
- **India nuance.** Rollovers are routine and each one poses the modification-versus-new-instrument
  question. Critically, ACPIR 92 *requires* gold loans to sit in their own product category and
  forbids grouping them under secured retail for floor purposes — so the EIR engine's product tag
  must be the same tag the floor engine uses. One taxonomy, two consumers.

#### 8. Housing loan to individuals (floating, long tenor) `LOAN` †
`IFRS 9 B5.4.4; ACPIR 51` · `[HIGH]`
- **EIR position.** The largest single EIR estimation judgement in an Indian bank's book. Two
  defensible designs: (a) contractual life with B5.4.5 resets, or (b) modelled behavioural life
  with a probability-weighted prepayment curve.
- **India nuance.** Observed behavioural life on Indian retail mortgages is materially shorter
  than the 15–20 year contractual tenor because of balance-transfer churn. But because these are
  floating-rate and reprice to market, the B5.4.4 shortcut makes design (a) both simpler and
  defensible. Separately, RBI's restrictions on foreclosure charges for floating-rate individual
  loans mean there is little prepayment-penalty cash flow left to model — which removes a
  variable that dominates the equivalent calculation in other jurisdictions.

#### 9. Education loan with course-plus-grace moratorium `LOAN` †
`ACPIR 9(6)(i)` · `[MED]`
- **EIR position.** Interest accrues and capitalises during moratorium, increasing the gross
  carrying amount. EIR is computed over total expected life inclusive of the moratorium period.
- **India nuance.** ACPIR 9(6)(i) confirms interest becomes due only after the moratorium, so it
  is not overdue in the interim. The live question is the Central Sector Interest Subsidy: is a
  subvention received from Government a cash flow "between the parties to the contract"?
  Recommended position — no; account for it separately and keep it out of the EIR, unless the loan
  contract makes the borrower's own rate contingent on the subsidy.

#### 10. Agricultural term loan / KCC (crop-cycle) `LOAN`
`ACPIR 9(7), 46(2)` · `[MED]`
- **EIR position.** Revolving KCC needs behavioural life; agricultural term loans follow the
  term-loan mechanics with crop-season staging overlaid.
- **India nuance.** Interest subvention and prompt repayment incentive raise the same question as
  education loans — settle it once, apply it to both. Fee quantum is typically de minimis, so a
  documented materiality threshold is the proportionate answer. ACPIR 9(7) crop-season
  classification affects staging, not the rate.

#### 11. Staff housing and concessional employee loans `LOAN` †
`ACPIR 19, 20, 9(6)(ii); IFRS 9 B5.1.1` · `[MED-HIGH]`
- **EIR position.** Priced below market, so fair value at initial recognition is below the amount
  disbursed. Recognise at fair value using a market rate as the EIR; the day-1 shortfall is
  employee compensation, not a lending loss.
- **India nuance.** ACPIR 19 and 20 require fair value at initial recognition, which makes this
  bite — it is not optional. For public sector banks with large staff books the day-1
  retained-earnings adjustment can be material. ACPIR 9(6)(ii) separately carves staff loans out
  of the normal overdue test where interest is payable after principal recovery.

#### 12. Project finance — pre-COD, DCCO deferment, IDC capitalisation `LOAN` †
`ACPIR 82(xii); IFRS 9 B5.4.6` · `[HIGH]`
- **EIR position.** Interest during construction capitalises into the gross carrying amount. EIR
  struck at financial closure over expected life including the construction period.
- **India nuance.** DCCO deferment triggers additional account-wise provisions of 0.375%
  (infrastructure) or 0.5625% (non-infrastructure) per quarter of deferment over the Stage 1
  floor — a provisioning consequence, not an EIR one. But whether the deferment is itself a
  modification requiring a catch-up adjustment is unanswered by ACPIR. This is the highest-value
  single position to get right for a wholesale bank.

#### 13. Restructuring / resolution plan / FITL creation `LOAN` †
`ACPIR 78–81; IASB Feb 2025` · `[HIGH]`
- **EIR position.** Test substantiality first. Non-substantial: retain the original EIR, recompute
  the gross carrying amount at that rate against revised cash flows, book the difference to P&L.
  Substantial: derecognise and re-originate at a fresh EIR.
- **India nuance.** ACPIR 78–81 govern stage migration on restructuring and cross-refer the
  Resolution of Stressed Assets Directions 2025, but say nothing about the accounting boundary.
  The IASB tentatively decided in February 2025 to require a principles-based qualitative
  assessment of substantiality — outcomes cannot be determined by a quantitative test alone.
  Indicative factors under consideration include a change in the basis for determining interest
  (fixed to floating) and changes made for commercial reasons that align terms to current market.
  Adopt the 10% test as an indicator, not a rule.

#### 14. Co-lending arrangement (80:20) `LOAN`
`ACPIR 9(3), 53` · `[MED]`
- **EIR position.** Each lender computes the EIR on its own share of the exposure.
- **India nuance.** Watch the direction of the fee. A sourcing fee the bank *pays* the NBFC
  partner for originating the bank's 80% share is a commission paid to an agent — a transaction
  cost that capitalises under ACPIR 53. A servicing fee the bank *receives* for administering the
  pool is service income. ACPIR 9(3) additionally requires borrower-level classification to be
  shared across both lenders on a near-real-time basis, so the staging feed and the EIR feed have
  different latencies.

#### 15. Inter-bank participation certificate (IBPC) `LOAN`
`ACPIR 11` · `[LOW]`
- **EIR position.** Risk-sharing IBPC transfers credit risk and raises a derecognition question;
  non-risk-sharing IBPC is in substance a borrowing. EIR applies to whatever is retained or
  recognised.
- **India nuance.** Predominantly a quarter-end balance-sheet management instrument in India, so
  volumes spike at reporting dates — exactly when the EIR engine is under load. Size the batch
  window accordingly.

#### 16. Direct assignment / securitisation — retained interest and MRR `LOAN`
`ACPIR 11` · `[MED]`
- **EIR position.** Resolve derecognition first. Where the exposure is retained, EIR applies to
  the retained tranche. Excess interest spread receivable is a separate asset, not an EIR input.
- **India nuance.** Governed by the Transfer and Distribution of Credit Risk Directions, which
  ACPIR 11 preserves without prejudice. The minimum retention requirement means a residual
  exposure almost always survives, so this is a live workstream not an edge case.

#### 17. Syndicated loan — lead arranger position `LOAN` †
`IFRS 9 B5.4.3(c)` · `[HIGH]`
- **EIR position.** Bifurcate. Syndication fee where the arranger retains no part of the package,
  or retains a part at the same EIR as other participants for comparable risk, is service
  revenue — not an EIR input. Where the arranger retains a disproportionately larger fee relative
  to its retained share, split the excess to arrangement service income and treat the balance as
  an EIR adjustment.
- **India nuance.** ACPIR 52 is silent on syndication entirely, and the arranger economics of
  Indian consortium lending make this material for large corporate desks. The EBA has taken a
  directly analogous position on commitment fees for supervisory reporting purposes, which is
  useful supporting authority.

#### 18. Foreign currency loans — ECB, FCNR(B), buyer's credit `LOAN`
`ACPIR 53, 101(3)` · `[MED]`
- **EIR position.** Compute the EIR in the currency of the instrument, then translate. Hedging
  costs are financing costs, explicitly excluded from transaction costs.
- **India nuance.** ACPIR 101(3) preserves the Reserve for Exchange Rate Fluctuations Account
  mechanic for rupee-disbursed foreign currency loans that turn overdue. Keep the EIR schedule in
  original currency and translate the resulting income — never translate first and then solve,
  which corrupts the rate.

#### 19. Sustainability-linked loan with ESG margin ratchet `LOAN` †
`IFRS 9 (2024 amdts); IASB Apr 2026` · `[LOW, rising]`
- **EIR position.** Under the May 2024 IFRS 9 amendments (effective 1 January 2026) such a loan
  can still meet SPPI provided the cash flows are not significantly different from an identical
  instrument without the feature. Coupons indexed to a carbon *price* index fail SPPI outright,
  because the variable is unrelated to basic lending risks.
- **India nuance.** Most entities do not build ESG contingencies into the day-1 EIR, citing
  materiality or absence of reliable data, and instead recognise the change when the contingent
  event occurs. The April 2026 tentative decision would put ESG ratchets *outside* B5.4.5 — they
  compensate for neither time value of money nor credit risk — routing them to a catch-up
  adjustment. Build the flag now even if the book is small; retrofitting a contingency type into a
  live engine is expensive.

#### 20. Loan against term deposit / LIC policy / KVP `LOAN`
`ACPIR 9(5), 82(vii)` · `[LOW]`
- **EIR position.** Fully collateralised, minimal fees. EIR ≈ contractual. A documented de minimis
  threshold is the proportionate treatment.
- **India nuance.** Exempt from NPA classification while margin holds at 100% (ACPIR 9(5)), and
  sits in its own floor category at 0.40% for both Stage 1 and Stage 2. Low-effort item — but the
  product tag still has to be right for the floor engine.

#### 21. Penal charges `LOAN` †
`RBI penal charges framework 2023` · `[MED]`
- **EIR position.** **Excluded entirely.** Not an EIR cash flow and not a component of the gross
  carrying amount.
- **India nuance.** India-specific with no IFRS 9 analogue. RBI's 2023 framework requires penal
  *charges* rather than penal *interest*, prohibits capitalisation and prohibits further interest
  accruing on them. The engine must therefore hard-exclude them at the cash-flow layer — a filter,
  not a judgement. Recognise when levied. This is a common implementation defect because legacy
  CBS often books penal amounts into the interest ledger.

### INVESTMENTS

#### 22. Central Government securities and SLR-eligible SDLs (HTM) `INV` †
`Investment Directions; ACPIR 22, 37–38` · `[HIGH]`
- **EIR position.** The Investment Portfolio Directions require any discount or premium on HTM
  securities to be amortised over the remaining life of the instrument, reflected under Income on
  Investments with a contra to Investments. The Directions do *not* specify the amortisation
  method.
- **India nuance.** This is the treasury book's central problem. ACPIR 22 defers to the Investment
  Directions; those Directions mandate amortisation but are silent on whether it is straight-line
  or effective-yield. Grant Thornton flagged precisely this gap when the framework was first
  proposed. Separately, ACPIR 37–38 exempt SLR investments from SICR testing and from Stage 1 ECL,
  so the discount-rate question does not arise for provisioning — but income recognition still
  runs off the Investment Directions. Recommended position: adopt the EIR method for the debt
  investment book as internal policy and quantify the straight-line differential before doing so.

#### 23. Treasury bills, commercial paper, certificates of deposit `INV`
`ACPIR 51` · `[ALIGNED]`
- **EIR position.** Pure discount instruments — accretion is the entire return. EIR is the IRR
  over residual tenor.
- **India nuance.** Requires an explicit day-count and compounding convention. Money-market
  practice in India is actual/365; EIR mathematics is compounding-convention dependent. Fix the
  convention in policy, apply it uniformly, and document it — otherwise treasury and finance will
  reconcile to different numbers indefinitely.

#### 24. Corporate bonds acquired at premium or discount (HTM / AFS) `INV`
`Investment Directions` · `[MED]`
- **EIR position.** Amortise over remaining life. Under IFRS 9 the effective interest method is
  mandatory for the amortised cost and FVOCI categories.
- **India nuance.** Good news on AFS: RBI routes amortisation through Income on Investments and
  fair-value movement to the AFS-Reserve without touching P&L, which is structurally the same
  shape as IFRS 9 FVOCI (EIR interest to P&L, residual fair value movement to OCI). The
  architectures converge; only the amortisation method needs settling.

#### 25. Zero-coupon and deep discount bonds `INV`
`ACPIR 51` · `[HIGH]`
- **EIR position.** The entire return is accretion. EIR is the only defensible measure.
- **India nuance.** Straight-line amortisation on a long-dated zero-coupon instrument is
  materially wrong and the error compounds with tenor. If the bank adopts EIR selectively rather
  than universally, this is the category where selectivity is indefensible. Treat it as the wedge
  for a full EIR migration on the investment book.

#### 26. Perpetual debt securities `INV` †
`RBI Investment FAQs` · `[MED]`
- **EIR position.** RBI's FAQ position: discount or premium on a perpetual debt security is
  amortised up to the earliest call date.
- **India nuance.** Note the perimeter carefully. AT1 instruments with discretionary coupons and
  loss-absorption features generally fail SPPI and would sit at FVTPL under IFRS 9, where no EIR
  arises at all. The RBI earliest-call-date treatment is therefore relevant to perpetual debt that
  is *not* AT1-like in its cash flow characteristics. Do the SPPI screen before applying the
  amortisation rule.

#### 27. Callable and puttable bonds `INV` †
`RBI Investment FAQs vs IFRS 9 Appendix A` · `[HIGH]`
- **EIR position.** **Direct divergence.** RBI: amortise the discount or premium over residual
  *contractual* maturity, including for securities with call or put options. IFRS 9: the EIR
  calculation considers all contractual terms including prepayment, extension and call options,
  and runs over *expected* life.
- **India nuance.** A clean, quantifiable divergence — one of the few in this document that can be
  measured exactly rather than argued. Compute both and disclose the difference. RBI's FAQ also
  treats exercise of a put before maturity as a sale from HTM unless triggered by credit downgrade
  or default, which links the accounting position to the portfolio-classification position.

#### 28. Floating rate bonds and floating rate notes `INV`
`IFRS 9 B5.4.4, B5.4.5` · `[LOW]`
- **EIR position.** B5.4.5 reset mechanics apply. For an FRB acquired at par there is no premium
  or discount to amortise, so the question is largely moot.
- **India nuance.** The exception that matters: FRBs bought away from par in the secondary market.
  Then the premium or discount interacts with the reset, and the B5.4.4 shortcut is the practical
  answer.

#### 29. Securitisation notes / pass-through certificates `INV` †
`IFRS 9 B5.4.6` · `[HIGH]`
- **EIR position.** Cash flows amortise on the underlying pool's prepayment behaviour. EIR
  requires a conditional prepayment rate assumption, and revisions to it are *not* movements in
  market rates of interest — so they route to a cumulative catch-up adjustment through P&L.
- **India nuance.** The canonical B5.4.6 case, and the one where the reset-versus-catch-up
  distinction produces the largest numbers. Equity tranches of securitisation transactions sit in
  FVTPL under the Investment Directions, so no EIR arises there. Senior and mezzanine tranches at
  amortised cost need the full machinery.

#### 30. Security receipts issued by ARCs `INV`
`Investment Directions` · `[MED]`
- **EIR position.** Recovery-driven, highly uncertain cash flows that typically fail SPPI, placing
  them at FVTPL with no EIR.
- **India nuance.** Where an SR is nonetheless carried at amortised cost, every revision to
  recovery expectations is a catch-up adjustment. Given the estimation uncertainty, FVTPL is
  usually both the correct and the operationally simpler answer.

#### 31. Preference shares with fixed dividend `INV`
`ACPIR 8(8), 9(2)` · `[MED]`
- **EIR position.** If a liability of the issuer with SPPI cash flows, amortised cost with EIR.
  Otherwise FVTPL.
- **India nuance.** ACPIR 8(8) refers NPI classification for fixed-dividend preference shares to
  the Investment Directions, and provides a specific carve-out: where *only* the preference shares
  are NPI, other performing securities of the same issuer and performing credit facilities to that
  borrower need not be treated as NPI or NPA — a rare exception to borrower-level contagion.

#### 32. POCI investments — distressed debt bought at deep discount `INV` †
`ACPIR 6(3)(v), 24, 50` · `[ALIGNED]`
- **EIR position.** Credit-adjusted EIR embedding lifetime ECL at day 1. No day-1 allowance. Only
  cumulative changes in lifetime ECL relative to the initial estimate flow through P&L thereafter.
- **India nuance.** ACPIR 6(3)(v) makes acquisition at a discount reflecting inherent credit
  losses an explicit credit-impairment indicator, and 24 requires separate POCI identification and
  accounting. Two practical consequences: the credit-adjusted EIR on paper bought at 30 paise is
  arithmetically very high, and it is retained even after the asset cures — a point the IFRS
  Interpretations Committee addressed directly in 2019. Do not reset it on cure.

### OFF-BALANCE SHEET

#### 33. Undrawn commitments and sanctioned-not-disbursed limits `OBS` †
`ACPIR 23, 52, 54; IFRS 9 B5.4.2(b)` · `[MED-HIGH]`
- **EIR position.** Initial recognition on the date the bank becomes party to the irrevocable
  commitment. Commitment fee is integral to the EIR if drawdown is probable; service revenue if it
  is not, recognised on expiry if the commitment lapses undrawn.
- **India nuance.** ACPIR 52 states the positive limb without the probability condition and 23
  fixes the recognition date, but the "probable" qualifier must be supplied by policy. ECL
  discounting per 54 uses the EIR that will apply to the resulting asset. Define "probable"
  numerically in policy — a historical drawdown rate by product is the auditable way to do it.

#### 34. Financial guarantees, bank guarantees, standby LCs `OBS`
`ACPIR 54, 87` · `[ALIGNED]`
- **EIR position.** Not an EIR instrument — no principal is advanced. Commission is earned over
  the guarantee period. For ECL, use a rate reflecting the current market assessment of the time
  value of money and the risks specific to the cash flows.
- **India nuance.** ACPIR 54 gives this discount-rate rule explicitly. On devolvement the exposure
  becomes a funded advance with its own EIR from that date. ACPIR 87 sets the Stage 3 floor for a
  non-performing performance guarantee by reference to the principal debtor's product category
  after applying the CCF; once devolved, the CCF drops away.

#### 35. Letters of credit and acceptances `OBS`
`ACPIR 54` · `[LOW]`
- **EIR position.** Commission recognised over the LC tenor. On devolvement, a funded advance
  arises with a fresh EIR struck at that date.
- **India nuance.** The system requirement is a clean handoff: the trade finance platform must
  pass a devolvement event to the EIR engine with the correct initial recognition date, not the
  original LC issuance date. This integration is routinely missed.

### LIABILITIES

#### 36. Term and recurring deposits `LIAB` †
`ACPIR scope gap` · `[MED]`
- **EIR position.** Under IFRS 9, liabilities at amortised cost carry an EIR. Brokerage paid to
  deposit-mobilisation agents is a transaction cost. Premature withdrawal options and penalties
  are contractual terms feeding expected life.
- **India nuance.** Outside ACPIR scope — paragraph 20 addresses assets and the 6(6) definition
  references only the gross carrying amount of an asset. The bank must decide whether to run EIR
  symmetrically. Recommendation: yes. An asset-only EIR produces a net interest margin that is
  internally inconsistent, and the Seventh Amendment's deletion of the erroneous instruction on
  booking securities acquisition as an expense signals RBI is tidying the liability side too.

#### 37. Bonds, Tier 2 and infrastructure bonds issued `LIAB`
`Ind AS 109; ITFG Bulletin 14` · `[MED]`
- **EIR position.** Issue expenses — arranger fees, rating fees, listing, trustee, stamp duty —
  are transaction costs amortised into the EIR of the liability. Call options drive expected life
  to first call.
- **India nuance.** Currently expensed upfront by most Indian banks. Ind AS practice is settled:
  origination fees paid on issuing financial liabilities at amortised cost are integral to the EIR
  and treated as an adjustment to it. The ITFG has confirmed the mechanic in the Indian context,
  including the interaction with borrowing cost capitalisation under Ind AS 23.

#### 38. Concessional refinance — NABARD, SIDBI, NHB `LIAB`
`IFRS 9 B5.1.1` · `[MED]`
- **EIR position.** Borrowing below market rate. Fair value at initial recognition is below
  proceeds received, creating a day-1 difference.
- **India nuance.** The same B5.1.1 analysis as concessional lending, mirrored. What has the bank
  received besides the below-market borrowing — and is the answer a government grant? The TLTRO III
  debate in Europe is the closest analogue and it was hard enough that the IFRS Interpretations
  Committee concluded the matter could not be addressed cost-effectively and referred it to the
  Board, where it now sits inside the Amortised Cost Measurement project.

### Two cross-cutting items

**Default loss guarantee covered portfolios** `LOAN` `OBS` · `ACPIR 88` · `[MED]`. DLG may be
considered in determining ECL across all stages, provided the arrangement is integral to the
contractual terms of the loan and not recognised separately. ACPIR 88 requires ECL to be recomputed
after each invocation, because the cover reduces to the extent invoked. That is an event-driven
recalculation trigger, not a periodic one — a distinct pattern from everything else in the engine,
and one that has to be built as such.

**Trade and lease receivables** `LOAN` · `ACPIR 41, Annex 2` · `[ALIGNED]`. Always lifetime ECL
regardless of stage, with a simplified approach permitted. Simplified approach means a provision
matrix rather than a PD-LGD-EAD build, which sidesteps most of the EIR discounting question for
this class. Take the simplification — it is the one place the Directions hand you a shortcut.

---

## 4A. The tenor lens and the materiality gate

Section 4 organises by product because that is how the bank's systems are organised. But
estimation risk does not distribute by product — it distributes by tenor. This section is the
sizing argument for where the engineering and validation budget goes.

> **The single most useful thing in this section.** Roughly 80% of EIR estimation risk in an
> Indian universal bank sits in five long-tenor families: individual housing loans, project and
> infrastructure finance, securitisation notes, long-dated held-to-maturity SDLs and corporate
> bonds, and the credit card book. Everything else is either arithmetically trivial or genuinely
> immaterial. Build a three-tier materiality gate and you avoid engineering a solver for products
> that do not need one.

### Tier structure — where full EIR machinery is warranted

| Tier | Population | Treatment | Basis and control |
|---|---|---|---|
| **Tier 1** — instrument-level `[HIGH]` | Wholesale exposures above a Board-set threshold · all project finance · all POCI · all restructured or modified exposures · anything with a contingent rate feature (credit ratchet, ESG, step-up) · any exposure designated in a hedge relationship | Full EIR solved per instrument. Reset-versus-catch-up routing applied per event. Reperformed on every contractual change. | These are the exposures where a single account can move reported income. Individual documentation of the expected-life and contingency assumptions. Reviewed by the ACPIR 57 sub-committee where individually significant. |
| **Tier 2** — pool-level `[MED]` | Retail and MSME exposures with original tenor above 12 months — housing, LAP, auto, personal, consumer durable, education, MSME term, gold rollovers, CV and CE · credit cards · KCC revolvers | Pool EIR under the ACPIR 51 group presumption. Prepayment and behavioural curves by product, vintage and segment. Periodic re-estimation on a defined calendar. | ACPIR 51 expressly presumes that cash flows and expected life of a group of similar instruments can be estimated reliably. Pooling criteria documented and validated under Chapter V; homogeneity reviewed per ACPIR 66–68. |
| **Tier 3** — documented approximation `[LOW]` | Original tenor 12 months or less — WCDL, bills purchased and discounted, packing credit, post-shipment credit, temporary overdrafts, T-bills, CP, CD, call and notice money, TREPS and market repo · plus fully collateralised low-fee products | EIR taken as contractual rate plus straight-line accretion of net fees over the tenor, having demonstrated once, by equivalence test, that the difference from a solved EIR is immaterial. | This is the proportionate answer, but it must be *evidenced*. Run the solved-versus-approximated comparison on a representative sample, document the delta, set the threshold, re-test annually. An undocumented shortcut is the Cambodia failure mode. |

> **Why the equivalence test matters more than the shortcut.** IASB staff analysis is explicit
> that where entities do not consider conditional terms in the EIR calculation, it is generally
> because of materiality assessments or because insufficient information was available to support
> a reliable estimate — not because the requirement was unclear. Diversity arising from judgement
> cannot be resolved by standard-setting, so the judgement itself is what gets audited. "We
> approximated because it was immaterial" is a complete answer *only* if the materiality
> assessment exists on paper with a number attached. ACPIR 51's fallback to contractual cash flows
> over the full contractual term is reserved for *rare* cases where reliable estimation is not
> possible — it is not a general convenience option.

### Short tenor — the four problems nobody anticipates

**1. The annualisation optic.** A 1% processing fee on a 30-day WCDL, amortised over 30 days,
produces an annualised EIR far above the card rate. The arithmetic is correct. The MIS looks
broken. Resolve the presentation convention with finance and the business before go-live, not
during the first quarter's variance review — otherwise the engine gets blamed for a reporting
design choice.

**2. Day-count sensitivity is inverted.** On a 20-year mortgage a day-count convention error is
noise. On a 7-day money-market instrument a three-day convention error is a materially wrong rate.
Short tenor is where convention discipline actually bites — actual/365 for money market, and a
single documented policy per instrument class.

**3. Rollover versus new instrument.** The dominant question for WCDL, gold loans, bill
discounting and CP. If a 90-day WCDL is rolled continuously for three years, is it a series of new
instruments each striking a fresh EIR, or is the substance a revolving facility? Answer it by
product in policy, not case by case at the desk. Substance-over-form here also feeds the
ACPIR 46(2)(ii) test on whether a renewal process is genuinely substantive.

**4. Repo, TREPS and collateralised borrowing.** Not derivatives, but adjacent. Accounted for as
collateralised lending and borrowing with an EIR over the tenor. RBI's own position is that repo
of securities out of HTM, where compliant with the Repo Directions, is not treated as a sale from
HTM — so the underlying instrument's EIR schedule continues undisturbed. Very short tenors put
these firmly in Tier 3.

### Long tenor — where the estimation risk actually lives

| Problem | Mechanism | Design response |
|---|---|---|
| Behavioural life dominance | On a 20-year mortgage the periodic fee amortisation is roughly inversely proportional to assumed life. Shifting assumed life from 20 years to 8 years multiplies annual fee recognition by about 2.5×. No other single assumption has that leverage. | Use the B5.4.4 shortcut where the instrument reprices to market before maturity — amortise fees to the next repricing date. For floating-rate Indian mortgages this largely dissolves the problem. Reserve behavioural modelling for the fixed-rate book and for cards. |
| Discount-rate compounding in ECL | ACPIR 50 makes the EIR the ECL discount rate. A 25bp EIR error on a 20-year exposure propagates into lifetime ECL with compounding effect. Short-tenor EIR errors self-extinguish; long-tenor errors do not. | Tighten the solver tolerance for Tier 1 and long-tenor Tier 2 pools specifically. Include EIR sensitivity in the ECL sensitivity disclosure, not just PD and LGD. |
| Multi-tranche disbursement | Project finance draws over 24–48 months against a projected schedule that never matches actual. Each deviation is a revision of estimated cash flows. The EIR struck at financial closure is obsolete by first drawdown. | Strike the EIR on the projected schedule at financial closure, then re-estimate at each material tranche deviation. Because a disbursement-timing change compensates for neither TVM nor credit risk, the April 2026 tentative decision routes it to a catch-up under B5.4.6. Build the trigger as a tolerance on cumulative deviation, not on every drawdown. |
| Non-level cash flow structures | Step-up, balloon, bullet, moratorium, IDC capitalisation and FITL all produce cash flow profiles with multiple sign changes. Newton-Raphson fails on these. | Bisection fallback is mandatory, not optional. Log non-convergence as an exception requiring manual review — never let the engine default silently to the contractual rate, which is the defect that quietly reproduces the pre-ACPIR position. |
| Zero-coupon and deep discount | The entire return is accretion, and the straight-line error compounds with tenor. On a 15-year zero-coupon instrument the front-loading error is severe. | No approximation permissible. This is the category that makes selective EIR adoption on the investment book indefensible, and therefore the wedge for a full migration. |

### Mid tenor, 1 to 5 years — the volume centre

Auto, personal, consumer durable, MSME term, commercial vehicle and construction equipment. No
conceptual difficulty: standard mechanics, pool-level EIR, prepayment curves by vintage. But it is
the largest account count and therefore the batch-window constraint. Size the compute for this tier
and the rest fits inside it. The one live judgement is prepayment on personal and auto loans, where
balance-transfer behaviour is genuine but the fee quantum is small enough that a simple curve
suffices.

---

## 4B. Derivatives, hedge accounting and structured products

> **The wrong answer, and why it is tempting.** "Derivatives are measured at fair value through
> profit or loss, so no effective interest rate arises, so derivatives are out of scope." Each
> clause is true. The conclusion is wrong, for five separate reasons set out below. The most
> consequential is hedge accounting, where a discontinued fair value hedge produces a basis
> adjustment that must be amortised *through the EIR* — and where the standard failure is to
> freeze it and never start.

### 4B.1 — Does ACPIR pull derivatives into ECL scope?

| Provision | Text | Consequence |
|---|---|---|
| ACPIR 17(2) | Debt securities *other than* those measured at FVTPL are in scope of the ECL chapter. | An explicit FVTPL carve-out — but drafted for debt securities, not for derivatives. |
| ACPIR 17(7) | "Any other financial assets having contractual right to receive cash, unless specifically excluded under these Directions." | A positive derivative mark-to-market is a contractual right to receive cash. On a literal reading, derivative receivables are swept in unless excluded — and derivatives are not named in any exclusion. |
| ACPIR 83 | "For financial instruments which are measured at FVTPL and categorised as Non-performing, banks shall apply impairment provisioning equivalent to stage 3 for category (xv)." | Decisive. FVTPL measurement does *not* exempt an instrument from provisioning once it is non-performing. Category (xv) is the residual bucket, so those Stage 3 floors apply. |
| IFRS 9 5.5.1 | Impairment requirements apply to financial assets at amortised cost and FVOCI, lease receivables, contract assets, loan commitments and financial guarantee contracts. | Derivatives at FVTPL are entirely outside the ECL model. A clean divergence from the ACPIR reading above. |
| Repealed 2025 Directions, para 115 | Credit exposures computed at current mark-to-market on interest rate and FX derivative transactions, credit default swaps and gold attracted provisioning as applicable to standard-category loan assets of the relevant counterparty. | **Open question.** ACPIR 2026 repeals the 2025 Directions. No equivalent derivative provisioning paragraph appears in the operative text reviewed. Whether the requirement was deliberately dropped, migrated to the Capital Charge for Credit Risk Directions, or sits in an annex not reviewed here must be confirmed. |

> **Working position pending clarification.** Treat derivative counterparty credit exposure as
> within the provisioning perimeter, defaulting to the residual category (xv) floors, and document
> the reading. The downside of over-providing is a quantified capital cost. The downside of
> under-providing on a literal-text reading that RBI intended is a supervisory finding. Raise it
> through the IBA channel — this is precisely the kind of drafting gap the SAMA and RBI
> consultation precedents show gets clarified when the industry asks collectively.

### 4B.2 — Fair value hedges: the direct EIR mechanic

This is the only place in the derivative universe where a genuine EIR calculation is required, and
it is technically the hardest item in this entire document.

**The mechanic**

1. A fixed-rate loan or bond at amortised cost is designated as the hedged item in a fair value
   hedge of benchmark interest rate risk, with an interest rate swap as the hedging instrument.
2. The carrying amount of the hedged item is adjusted for fair value changes attributable *only to
   the designated risk*, with those changes in P&L. IFRS 9 6.5.8(a) requires adjustment for the
   designated risk alone — benchmark rate risk, not credit spread movements.
3. That adjustment is the **basis adjustment**. Any basis adjustment to the carrying amount of a
   debt instrument at amortised cost must be amortised to P&L as an adjustment to the effective
   interest rate.
4. On discontinuation, the cumulative basis adjustment is amortised over the hedged item's
   remaining life using the effective interest method. IFRS 9 6.5.10 permits amortisation to begin
   at any point but requires it to begin no later than when the hedged item ceases to be adjusted
   for hedging gains and losses — the concession existing precisely to avoid constant EIR
   readjustment while the hedge runs.

**Failure mode 1 — the frozen adjustment.** The most commonly observed defect. The cumulative
basis adjustment is left sitting in the hedged item's carrying amount and amortisation never
starts, misstating interest income or expense in every period through to maturity. Where the
working file rolls the adjustment forward from the prior year without testing whether the hedge
relationship still exists, the misstatement recurs indefinitely and becomes progressively harder
to unwind.

**Failure mode 2 — over-adjusting.** Applying the basis adjustment to the full fair value change
of the hedged item rather than isolating the component attributable to the designated risk. This
embeds a measurement error that compounds across reporting periods and is difficult to reverse
once it has flowed through multiple sets of financial statements.

**The three-way interaction — hedge, modification and EIR simultaneously.** The hardest case, and
one that arose at scale during payment-holiday programmes. Where a hedged financial asset is
modified and amortisation of the basis adjustment has already commenced:

- The recalculated hedge adjustment is the difference between the hedged portion of the modified
  cash flows discounted at the hedged benchmark rate at hedge inception, and the hedged portion of
  the modified cash flows discounted at the current benchmark rate. The change in the hedge
  adjustment goes to P&L, producing recognised ineffectiveness.
- The new gross carrying amount of the hedged asset is the present value of the modified
  contractual cash flows discounted at the **revised EIR — that is, the EIR adjusted for hedge
  accounting effects**.
- The modification gain or loss is the difference between that new gross carrying amount and the
  old gross carrying amount **including any hedge adjustments**.
- A modification substantial enough to derecognise the original asset will generally require the
  hedging relationship designated against it to be discontinued.

Governing paragraphs: IFRS 9 5.4.3, B5.4.6, 6.5.10 and, for portfolio hedges of interest rate
risk, IAS 39.92. Any Indian bank running fair value hedges on a restructurable book — which is any
bank hedging fixed-rate corporate term loans — needs this documented before the first restructuring
in a hedged relationship, not after.

> **Portfolio fair value hedges of interest rate risk.** IFRS 9 5.2.3 and 6.1.3 preserve the option
> to apply IAS 39 paragraphs 89–94 for a fair value hedge of the interest rate exposure of a
> *portfolio* of financial assets or liabilities. For a bank macro-hedging a fixed-rate loan
> portfolio with interest rate swaps, the basis adjustment sits at portfolio level rather than
> instrument level and amortises across the portfolio — a different reconciliation from the
> instrument-level EIR ledger, and one the engine must produce separately. The IASB's Dynamic Risk
> Management project is the long-run replacement and is not yet complete, so the IAS 39 carve-out
> remains the operative route.

### 4B.3 — Embedded derivatives and the SPPI asymmetry

The single most important structural point, and one that is frequently got backwards.

| Side | Treatment | EIR consequence |
|---|---|---|
| Financial **assets** | No bifurcation. IFRS 9 assesses the instrument as a whole against SPPI. If the embedded feature causes SPPI to fail, the *entire* instrument goes to FVTPL. | No EIR at all. This is a cliff, not a gradient — which is why the SPPI screen must run before any EIR work is commissioned on a structured asset. |
| Financial **liabilities** | Bifurcation still applies. The embedded derivative is separated and measured at FVTPL; the host contract remains at amortised cost. | The host *does* carry an EIR — and it is not the headline rate on the structured product. This is where structured deposits sit. |

**Indian instruments that hit this**

- **Structured deposits issued.** Host deposit at amortised cost with its own EIR; the equity-,
  commodity- or rate-linked payoff separated at FVTPL. The bank's reported cost of funds on these
  is the host EIR, not the advertised return.
- **FCCBs and convertible bonds held.** Conversion feature almost always fails SPPI on the asset
  side, so FVTPL and no EIR.
- **Credit-linked notes and principal-protected structures held.** Same conclusion.
- **Instruments with commodity or index linkage.** Under the May 2024 amendments, effective
  1 January 2026, the assessment turns on what the entity is being compensated for rather than the
  amount of compensation. A rate adjustment contingent on a borrower's carbon emissions target can
  meet SPPI where it does not significantly alter the interest rate; a coupon indexed to a carbon
  *price index* fails, because the variable is unrelated to basic lending risks.
- **Prepayment and extension options.** Embedded derivatives that do *not* break SPPI, subject to
  the prepayment exception conditions. They stay inside the EIR as contractual terms feeding
  expected life — the closely-related-exception treatment, not a bifurcation.

### 4B.4 — Presentation: can swap interest sit in Schedule 13?

A live and awkward question for an Indian bank hedging the banking book.

The IFRS Interpretations Committee considered whether interest on a derivative can be presented in
the "interest revenue calculated using the effective interest method" line required by IAS 1 82(a).
The supporting analysis observed that a plain vanilla interest rate swap can be deconstructed into
two legs viewed as a pair of loans — a fixed-rate loan and a floating-rate loan on the same
notional — with interest under the effective interest method calculated on each leg and presented
within interest revenue and expense, and that adjustments to carrying amount arising under B5.4.5
and B5.4.6 would also be presented there. The post-IFRS 9 direction of travel, however, restricts
that line to instruments measured at amortised cost and FVOCI.

For an Indian bank the governing constraint is not IAS 1 but RBI's compilation instructions for
Schedule 13 "Interest Earned", which the Seventh Amendment Directions have just modified. Those
instructions enumerate interest and discount on cash credit, demand loans, overdrafts, export
loans, term loans, bills purchased and discounted, overdue interest and interest subsidy — with the
direction that for assets specified in ACPIR paragraph 17, the computation of interest shall be as
per ACPIR. Derivative net settlements are not enumerated.

> **Working position.** Derivative net interest belongs in Other Income, not Schedule 13. Accept
> that this creates a net interest margin presentation mismatch for the hedged banking book — the
> hedged item's income is EIR-based in Schedule 13, the hedge's offsetting flow sits in Other
> Income — and disclose the hedging result separately so users can reconstruct the economic margin.
> Treasury will contest this. Settle it in policy with the CFO before the first hedged quarter, and
> document the reasoning, because reversing a presentation choice mid-year is worse than making the
> less-preferred choice cleanly.

### 4B.5 — Hedging costs are not EIR inputs

ACPIR 53 excludes financing costs from transaction costs. The cost of a cross-currency swap hedging
an external commercial borrowing, or of an interest rate swap hedging a fixed-rate loan, is a
financing cost — it does not enter the EIR of the underlying instrument. It is accounted for as the
derivative it is. This sounds obvious and is routinely violated in practice, because business teams
think in all-in hedged cost and finance systems sometimes let them book it that way.

### 4B.6 — The new credit derivatives perimeter

> **Master Direction – RBI (Credit Derivatives) Directions, 2026** ·
> `FMRD.DIRD.03/14.03.004/2026-27` · effective 25 June 2026
>
> Issued *two months after* ACPIR, superseding the 2022 framework which was limited to single-name
> CDS on corporate bonds. The expanded suite covers single-name CDS, CDS on credit indices, Total
> Return Swaps on corporate bonds and permitted fixed income assets, and exchange-traded credit
> index futures. A FIMMDA-led Credit Derivatives Determinations Committee has binding authority
> over credit event determinations, modelled on the ISDA framework. FPIs may sell CDS protection up
> to 5% of outstanding corporate bond stock. Existing prudential norms on derivatives —
> mark-to-market, provisioning and capital charge — are extended or adapted to these products.

**Why this matters for EIR specifically**

- **Total Return Swaps are the interesting instrument.** A TRS transfers both the income and the
  price movements on a referenced bond. The prior question is therefore not what EIR applies to the
  TRS — none does, it is a derivative at FVTPL — but whether the bank's underlying bond continues
  to be recognised at all, or whether the arrangement achieves synthetic risk transfer. The
  Directions contemplate a fully funded TRS structure for non-resident synthetic exposure,
  described as operationally demanding but conservative. If the underlying bond stays on balance
  sheet, its EIR schedule continues undisturbed.
- **CDS protection bought does not adjust the EIR of the protected exposure.** It is a separate
  instrument. Whether it can be recognised as a credit risk mitigant for ECL purposes is an
  ACPIR 55 collateral question — the estimate of cash shortfalls reflects cash flows expected from
  collateral and other credit enhancements *that are part of the contractual terms* and to which
  the bank has valid recourse. A standalone CDS purchased from a third party is generally not part
  of the loan's contractual terms, which distinguishes it from the DLG treatment at ACPIR 88 where
  integration into the contractual terms is the explicit condition.
- **Watch for the ACPIR 88 analogy being over-extended.** ACPIR permits DLG arrangements to be
  considered in ECL across all stages, but only where the DLG is integral to the contractual terms
  of the loan and is not recognised separately, with recomputation required after each invocation.
  That conditional permission is not authority for netting purchased CDS protection against ECL.

### 4B.7 — Summary: the derivative decision tree

| Instrument or event | EIR applies? | What to build |
|---|---|---|
| Standalone derivative — IRS, FRA, FX forward, CDS, TRS, credit index future | No | Nothing in the EIR engine. But confirm the provisioning perimeter per §4B.1 and register the counterparty exposure. |
| Fair value hedge, live | Deferred | Basis adjustment tracked separately, designated-risk component only. Amortisation may be deferred while the hedge runs. |
| Fair value hedge, discontinued | **Yes — directly** | Amortise the cumulative basis adjustment over remaining life using the effective interest method. Hard control: no discontinued hedge may exist without an active amortisation schedule. |
| Hedged item modified while basis adjustment is amortising | **Yes — the hardest case** | Revised EIR adjusted for hedge accounting effects; new GCA as PV of modified cash flows at that rate; modification gain or loss against old GCA including hedge adjustments. Tier 1 instrument-level treatment, sub-committee visibility. |
| Portfolio fair value hedge of interest rate risk | Yes, at portfolio level | IAS 39 89–94 carve-out preserved by IFRS 9 5.2.3 and 6.1.3. Separate portfolio-level basis adjustment ledger and reconciliation. |
| Structured asset failing SPPI | No | FVTPL in full. Run the SPPI screen before commissioning any EIR work. No bifurcation on the asset side. |
| Structured deposit issued | **Yes, on the host** | Bifurcate. Host at amortised cost with its own EIR; embedded derivative at FVTPL. The host EIR is the true cost of funds. |
| Prepayment or extension option embedded in a loan | Yes — inside the EIR | Not bifurcated. A contractual term feeding expected life under ACPIR 51, subject to the prepayment exception conditions. |
| Repo, reverse repo, TREPS | Yes, Tier 3 | Collateralised lending and borrowing with EIR over tenor. Repo from HTM compliant with the Repo Directions is not a sale from HTM, so the underlying schedule continues. |
| Hedging cost on an underlying loan or borrowing | No | Financing cost, explicitly excluded by ACPIR 53. Positive control that no swap cost has entered an EIR cash flow stream. |

---

## 5. Divergence register — ACPIR 2026 against IFRS 9

Eighteen positions. Each requires a documented decision under the Board sub-committee mandate in
ACPIR 57. This is the agenda for that committee.

**1. Interest on Stage 3** `[HIGH]`
- *ACPIR:* Not recognised. Seventh Amendment protects this from auditor qualification on the basis
  that the standard permits postponement where collectability is significantly uncertain.
- *IFRS 9:* EIR applied to amortised cost (gross less loss allowance). Defined presentation for the
  accumulated difference on cure.
- *Action:* Run a shadow EIR unwind for ECL discounting with P&L recognition suppressed. Maintain a
  suspense ledger reconciling contractual interest, the shadow unwind and recognised income.
  Disclose the basis.

**2. Modification vs derecognition** `[HIGH]`
- *ACPIR:* Silent. Stage migration on restructuring covered at 78–81 with cross-reference to the
  Stressed Assets Directions.
- *IFRS 9:* 5.4.3 and 3.2.3, plus the IASB's February 2025 tentative decision requiring a
  principles-based qualitative substantiality assessment.
- *Action:* Board-approved substantiality policy: 10% quantitative test as an *indicator*,
  supplemented by qualitative factors including change in interest basis, change in currency,
  change in obligor, and alignment to current market terms. Document why each restructuring
  concluded as it did.

**3. Subsequent EIR changes** `[HIGH]`
- *ACPIR:* Silent. No reset mechanism, no catch-up mechanism.
- *IFRS 9:* B5.4.5 reset for floating-rate instruments repricing to market; B5.4.6 catch-up for all
  other revisions.
- *Action:* Adopt EIR reset for benchmark and market credit-spread repricing; catch-up for
  prepayment-curve revisions, ESG ratchets and other contractual contingencies. Build the routing
  rule as configuration so the April 2026 IASB decision can be absorbed without re-engineering.

**4. Two definitions of life** `[MED]`
- *ACPIR:* 46(1) sets the ECL horizon as the maximum contractual period including extensions. 51
  sets the EIR horizon as expected life.
- *IFRS 9:* Same bifurcation, and the same source of confusion in every implementation.
- *Action:* Maintain two parameters explicitly in the data model. Document the reconciliation
  between them per product. Do not let one system derive both from a single field.

**5. Fees integral to EIR** `[HIGH]`
- *ACPIR:* 52 lists origination and commitment fees only. No probability condition on commitment
  fees. No negative list.
- *IFRS 9:* B5.4.2 with the probability condition; B5.4.3 with an explicit negative list covering
  servicing fees, improbable commitments and non-retained syndication fees.
- *Action:* Adopt B5.4.2 and B5.4.3 in full as internal policy. Build a fee master with an
  EIR-eligibility flag per fee code, owned by finance, with change control.

**6. Transaction costs** `[ALIGNED]`
- *ACPIR:* 53 tracks IFRS 9 B5.4.8 substantially verbatim, including the employee-selling-agent
  inclusion and the internal-administration exclusion.
- *IFRS 9:* B5.4.8.
- *Action:* No divergence to close. The work is data sourcing: separating sourcing incentives from
  processing costs in HR and cost-centre data.

**7. Investment book method** `[HIGH]`
- *ACPIR:* 22 defers to the Investment Directions, which mandate amortisation over remaining life
  but do not specify the method, and per FAQ amortise over residual contractual maturity for
  callable and puttable securities, and to earliest call for perpetuals.
- *IFRS 9:* Effective interest method mandatory; expected life considering call and put options.
- *Action:* Adopt EIR for the debt investment book as internal policy notwithstanding the
  Directions' silence. Quantify the straight-line differential and the call-option differential
  separately before adoption, so the Board approves a number rather than a principle.

**8. Penal charges** `[MED]`
- *ACPIR:* Governed by the separate 2023 penal charges framework: charges not interest, no
  capitalisation, no further interest.
- *IFRS 9:* No equivalent restriction.
- *Action:* Hard exclusion at the cash-flow ingestion layer. Verify that legacy CBS is not routing
  penal amounts through the interest ledger — a common defect.

**9. Interest subvention** `[MED]`
- *ACPIR:* Silent.
- *IFRS 9:* Silent on government subsidy specifically; the analysis runs through whether the flow
  is between the parties to the contract.
- *Action:* Adopt: a subvention received from Government is not a cash flow between lender and
  borrower; account for it separately and exclude from EIR — unless the contract makes the
  borrower's own rate contingent on it. Apply consistently across agriculture, education and MSME.

**10. Below-market origination** `[MED-HIGH]`
- *ACPIR:* 19 and 20 require fair value at initial recognition but give no day-1 difference
  guidance.
- *IFRS 9:* B5.1.1 — establish what the entity has paid for or received besides the instrument.
- *Action:* Policy per category. Staff loan discount to employee benefit cost. Directed
  concessional lending analysed on its facts. Quantify the transition impact on 1 April 2027 for
  the staff book specifically.

**11. Liabilities** `[MED]`
- *ACPIR:* Out of scope. 6(6) references only the gross carrying amount of an asset.
- *IFRS 9:* Effective interest method applies symmetrically to liabilities at amortised cost.
- *Action:* Extend EIR to deposits and borrowings as internal policy, or document the asymmetry and
  its NIM consequence explicitly. Asymmetry is defensible but must be a decision, not an omission.

**12. POCI mechanics** `[ALIGNED]`
- *ACPIR:* 6(3)(v), 24, 50 — credit-adjusted EIR, no day-1 allowance, cumulative changes only.
- *IFRS 9:* Same construction.
- *Action:* No divergence. Confirm in policy that the credit-adjusted EIR is retained after cure,
  per the 2019 Interpretations Committee direction.

**13. Transition mechanics** `[MED]`
- *ACPIR:* 19 — day-1 fair valuation of the entire loan book to opening retained earnings, with a
  rebuttable presumption that carrying cost is the best evidence of fair value. 21 — legacy EIR by
  31 March 2030. 50 — contractual rate permitted as an interim ECL discount factor.
- *IFRS 9:* Different transition architecture — modified retrospective with practical expedients.
- *Action:* Prioritise legacy EIR reconstruction for exposures expected to remain on the books
  beyond 31 March 2030, as KPMG has recommended. Build the rebuttal evidence file for paragraph 19
  during FY27, not at the transition date.

**14. ESG-linked features** `[LOW]`
- *ACPIR:* Silent.
- *IFRS 9:* May 2024 amendments effective 1 January 2026 for the SPPI assessment; April 2026
  tentative decision would route ESG ratchets to catch-up rather than reset.
- *Action:* Build the contingency-type flag now. Book is small today; retrofitting a new contingency
  class into a live amortisation engine is disproportionately expensive.

**15. Commitment and guarantee discounting** `[ALIGNED]`
- *ACPIR:* 54 mirrors IFRS 9's approach for instruments without a determinable EIR.
- *IFRS 9:* Same.
- *Action:* No divergence. Document the market-rate proxy methodology and validate it as a model
  under Chapter V.

**16. Derivatives in ECL scope** `[HIGH]`
- *ACPIR:* 17(7) sweeps in any other financial asset with a contractual right to receive cash. 83
  applies Stage 3 category (xv) provisioning to FVTPL instruments that are non-performing. No
  derivative-specific exclusion. The derivative provisioning paragraph in the repealed 2025
  Directions has no visible successor.
- *IFRS 9:* Impairment requirements apply to amortised cost and FVOCI assets, lease receivables,
  contract assets, loan commitments and financial guarantees. Derivatives at FVTPL are outside the
  ECL model entirely.
- *Action:* Adopt the inclusive reading pending clarification and default to category (xv) floors.
  Raise the successor question for the repealed derivative provisioning paragraph through the IBA.
  Quantify the capital cost of the conservative position so the sub-committee approves a number.

**17. Hedge accounting basis adjustment** `[HIGH]`
- *ACPIR:* Silent. No hedge accounting provisions and no reference to basis adjustment amortisation.
- *IFRS 9:* 6.5.8(a) restricts adjustment to the designated risk; 6.5.10 requires amortisation of
  the basis adjustment through the EIR, beginning no later than when the hedged item ceases to be
  adjusted; 5.2.3 and 6.1.3 preserve IAS 39 89–94 for portfolio interest rate hedges.
- *Action:* Adopt IFRS 9 Chapter 6 as the interpretive source. Build two hard controls: no
  discontinued hedge without an active amortisation schedule, and designated-risk-only adjustment.
  Document the modification-plus-hedge interaction before the first restructuring inside a hedged
  relationship.

**18. Derivative interest presentation** `[MED]`
- *ACPIR:* Schedule 13 compilation instructions, as modified by the Seventh Amendment, enumerate
  interest and discount on advances and bills and direct that for paragraph 17 assets the
  computation shall follow ACPIR. Derivative net settlements are not enumerated.
- *IFRS 9:* IAS 1 82(a) requires a separate line for interest revenue calculated using the
  effective interest method; the IFRS IC has discussed whether derivative legs may be presented
  there, with the post-IFRS 9 reading restricting the line to amortised cost and FVOCI instruments.
- *Action:* Derivative net interest to Other Income. Disclose the hedging result separately so the
  economic margin on the hedged banking book is reconstructable. Settle with the CFO before the
  first hedged reporting period.

---

## 6. Jurisdiction dossiers

Selected for transferability to the Indian problem rather than for market size. Three selection
criteria: a prudential regulator that layered requirements on top of IFRS 9 (India's model), a live
experience with the specific EIR mechanics ACPIR leaves open, or a documented implementation
failure worth not repeating.

### United Kingdom — IAS 39 → IFRS 9, 2018 `[Highest transferability]`

**Why it matters for India.** The UK has the deepest practical experience of EIR estimation on
*retail* portfolios, which is where India's volume risk sits. British lenders have run
behavioural-life EIR models on mortgages and credit cards for two decades, through IAS 39 and then
IFRS 9.

**The transferable lesson.** Spreading fee and interest economics over a modelled behavioural life
creates a balance sheet item — an "EIR asset" or liability — whose entire magnitude is an estimate.
It has been a recurring source of restatement and regulatory attention among UK consumer lenders.
The error is never the formula; it is optimistic behavioural assumptions that go unchallenged
because the resulting asset has no external validation point.

**What to copy.**
- Independent validation of the behavioural curve, not just the arithmetic — the same discipline
  ACPIR Chapter V demands for PD and LGD models.
- Back-testing actual against modelled life, with a documented tolerance and an escalation trigger.
- Sensitivity disclosure on the estimate, in the spirit of the FRC's repeated thematic findings
  that estimate disclosures need granular assumptions and meaningful ranges rather than boilerplate.

**Structural difference to note.** UK mortgages carry meaningful early repayment charges, which
materially shape the prepayment cash flow. RBI's restrictions on foreclosure charges for
floating-rate individual loans remove that variable in India — so UK prepayment models cannot be
lifted wholesale, only their governance can.

### European Union / Euro area — IFRS 9, 2018 · ECB, EBA `[High]`

**Why it matters for India.** FINREP forces definitional discipline that voluntary policy does not.
Because European banks must report interest and fee income in prescribed templates, the
fee-classification question got answered explicitly rather than left to judgement.

**The single most useful precedent.** The EBA's Q&A on commitment fees states the position cleanly:
where it is probable the institution will enter a specific lending arrangement, the fee is treated
under B5.4.2(b) as interest and forms an integral part of the EIR; where it is unlikely, the fee
falls under IFRS 15 per B5.4.3(b) and cannot be treated as interest. The EBA also specifies the
balance-sheet presentation in the interim — other assets, where drawdown is probable but the loan
is not yet granted. This is exactly the gap ACPIR 52 leaves open, answered by a prudential
regulator in a form an Indian bank can cite in its own policy.

**The unresolved European problem India should watch.** TLTRO III. The ECB's operations linked both
the borrowing amount and the interest rate to the bank's own lending behaviour — conditional cash
flows in their purest form. The Interpretations Committee received requests on how to calculate the
applicable EIR and how to account for revised assessments of whether conditions were met, and
concluded the matters were not possible to address cost-effectively, referring them to the Board.
They now sit inside the Amortised Cost Measurement project. Any Indian bank with concessional
refinance from NABARD, SIDBI or NHB has a smaller version of the same problem.

**Supervisory monitoring detail worth replicating.** The EBA's IFRS 9 monitoring reports track,
institution by institution, the policy on recognition of penalty interest on Stage 3 exposures and
the policy on accrued interest for non-performing debt instruments at FVTPL. Both are exactly the
questions India's Stage 3 non-recognition regime raises.

### Australia — AASB 9, 2018 `[High — best practical guidance library]`

**Why it matters for India.** AASB 9 is IFRS 9 verbatim, and the Australian firms have published
the most usable practitioner Q&A material on exactly the two questions ACPIR leaves open:
modification accounting and unamortised cost treatment.

**The direct analogue for DSA and DMA payouts.** Australian mortgage broker commissions — both
upfront and trail — are treated as transaction costs capitalised into the loan and amortised over
expected life. This is settled practice, not a contested position. It is the closest available
precedent for the DSA and DMA payouts that Indian banks currently expense upfront, and it supports
the reading of ACPIR 53 that these capitalise.

**The modification guidance to lift.** KPMG Australia's published position on renegotiation costs
sets out mechanics an Indian policy can adopt directly: where a modification is non-substantial,
costs and fees adjust the carrying amount and are amortised over the remaining term of the modified
instrument, with the original EIR adjusted to absorb them — their worked example moves an EIR from
7% to 7.11%. Where the modification is non-substantial, unamortised costs and fees are captured in
the original EIR and continue to amortise at that rate. Where it is substantial, they fall into the
derecognition gain or loss. Their view on new-liability transaction costs is usefully strict: no
transaction costs should be included in the initial measurement of a new liability unless it can be
incontrovertibly demonstrated they relate solely to it — usually only taxes and registration fees
on execution.

**The accounting policy choice they flag.** For a modified floating-rate instrument, an entity may
choose — as a consistently applied policy — to revise the original EIR based on new terms, treating
the change either as a modification of contractual terms or as a re-estimation of cash flows under
the original contract. The choice must be made, applied consistently and disclosed. Indian banks
should make the same choice explicitly.

### Brazil — CMN 4966 / BCB 352, live 1 January 2025 `[Closest structural analogue to India]`

**Why this is the most important dossier here.** Brazil is not an IFRS-adoption story; it is a
*central bank prudential convergence* story, which is precisely what ACPIR is. CMN Resolution 4966,
supplemented by BCB Resolution 352, harmonised Brazilian banks' accounting with IFRS 9 and replaced
Resolution 2682/99 — the incurred-loss regime that had governed since 1999. India is replacing
IRACP the same way, on the same logic, roughly two years behind.

**What the framework explicitly covered.** The requirements establish, among other things, the
methodology for determining the effective interest rate of financial instruments alongside the
credit-loss provisioning framework. Note the sequencing: EIR methodology was specified *as part of*
the prudential instrument, not left to the accounting standard. ACPIR does less of this, which is
why §2 of this document exists.

**The implementation lesson, stated plainly.** Brazil gave banks a four-year lead time — the
resolution published November 2021 for a January 2025 go-live. Commentators nonetheless reported
that many banks were unprepared at go-live, having underestimated the complexity of the
requirements and particularly the level of data granularity required. India's timeline from the
April 2026 final Directions to the April 2027 effective date is *one year*, with a 2030 backstop
for the legacy book. The Brazilian experience says the binding constraint is data granularity, and
that lead time does not solve it by itself.

**Also worth noting.** Brazilian institutions faced material costs in system adaptation, team
training, and strengthening compliance and governance, with the requirement for constant
reassessment of financial assets and rigorous provisioning controls increasing operational
complexity — and continuing investment to maintain conformity. Banks that adapted early came out
stronger. That is the argument for starting the EIR workstream ahead of the ECL workstream, not
after it.

### Hong Kong — HKFRS 9, 2018 · HKMA `[High — the suspense-account precedent]`

**Why it matters for India.** Hong Kong is the clearest example of a prudential interest-suspense
regime coexisting with full IFRS 9 EIR accounting — India's exact configuration.

**The precedent.** The HKMA's long-standing guideline on recognition of interest income requires
authorised institutions to place interest in suspense or cease accruing it where there is
reasonable doubt about ultimate collectibility of principal or interest — *even if the loan is not
yet in breach of contractual requirements or the arrears period is not more than three months*. The
criteria deliberately combine subjective and objective factors, on the stated basis that the
decision must be largely judgmental. The guideline also recognises that portfolio-managed products
such as credit cards are high-volume and low-value, making individual analysis for
interest-suspension purposes impractical, and provides for portfolio treatment.

**The transferable design point.** Two things. First, a suspense regime can be reconciled to
accounting EIR if the reconciliation is explicit and the suspense ledger is a first-class object
rather than a memorandum. Second, the portfolio carve-out for card books is a practical necessity —
India will need the same for credit cards and KCC, and ACPIR does not provide it, so it must come
from policy.

### Nepal — NFRS · NRB guidance notes `[High — a South Asian regulator bridging both worlds]`

**Why this small jurisdiction is disproportionately useful.** Nepal Rastra Bank has issued guidance
notes that set out, side by side, a cash-basis incremental approach using interest suspense and an
approach using the effective or deemed effective interest rate. That is the exact bridge India
needs to build, published by a regulator working in the same accounting culture and the same
institutional constraints.

**The specific mechanics worth studying.** The NRB guidance recognises interest income on accrual
basis using either the coupon rate or the effective rate, with interest suspense at the beginning
of the quarter also recognised as interest income, and imposes operational constraints such as
interest suspense not exceeding accrued interest receivable at the same date. It works through a
determination of interest income using the effective or deemed effective interest rate approach in
an illustrative example.

**The concept to borrow.** "Deemed effective interest rate." Where a full EIR reconstruction is not
feasible for a legacy tranche, a deemed rate with documented derivation is more defensible than
either a contractual-rate fallback or an unsupported estimate. This is directly applicable to
India's 2027–2030 legacy migration window under ACPIR 21.

### Cambodia — CIFRS 9 `[High — the failure mode, documented]`

**Why it is here.** Because it is the clearest published account of the specific defect Indian
banks are most likely to reproduce.

**The defect.** Cambodian banks had been amortising loan processing fees on a straight-line basis
rather than using EIR, carrying an "unearned processing fee" balance. Under CIFRS 9 this creates a
potential risk of material misstatement on that line item, and banks found themselves having to
justify the impact to external auditors on an ongoing basis. A downstream consequence flowed from
the same defect: using the contractual rate rather than the EIR to discount expected credit losses.

**Why this is India's most likely failure mode.** It is the path of least resistance. Straight-line
fee amortisation is easy to build, looks approximately right on short-tenor products, and passes
casual review. It fails on long-tenor and zero-coupon exposures, and it contaminates ECL through
the discount rate — which is exactly what ACPIR 50 is designed to prevent. The remediation projects
described were run specifically to establish an EIR policy and tool aligned to the standard, and to
replace contractual-rate discounting in ECL. Doing it right first is cheaper.

### Singapore — SFRS(I) 9, 2018 · MAS `[Medium-high]`

**Why it matters for India.** Singapore runs the architecture India has adopted: full accounting
ECL under the standard, with a minimum regulatory loss allowance layered on top as a prudential
backstop. ACPIR's product-wise and stage-wise prudential floors are the same design pattern,
considerably more granular.

**The transferable point.** Where a prudential floor binds, the EIR-derived accounting number and
the reported provision diverge. Singapore banks manage this as a reconciliation with a defined
ordering: compute the accounting number, then apply the floor, then disclose both. ACPIR 90 sets
out an equivalent ordering — floors applied separately per product category, on a portfolio basis
for Stage 1 and Stage 2, and mandatorily at individual account level for Stage 3. The system must
produce the pre-floor number as a first-class output, not as an intermediate that gets overwritten.

### United Arab Emirates — IFRS 9, 2018 · CBUAE `[Medium-high]`

**Why it matters for India.** CBUAE has run a long supervisory cycle on IFRS 9 and its expectations
have hardened: stage transfer criteria formally defined, independently validated and consistently
applied; SICR criteria documented at instrument level; back-testing results retained and available
for inspection. The prudential filter that softened the capital impact of IFRS 9 provisioning has
been removed, so full ECL recognition flows straight through.

**The lesson for India's transition arithmetic.** ACPIR's four-year CET1 add-back — 80% declining
to 20% between 2027 and 2031 — is the equivalent of the filter the UAE has now removed. Model the
fully-loaded position from day one and disclose both, as the Seventh Amendment in fact requires:
whether the transitional arrangement has been applied, and its impact on capital and leverage
ratios against the fully-loaded position. Plan for the cliff at the end, not the relief at the
start.

**Regional note on Islamic finance.** Murabaha and Ijara profit recognition uses an effective
profit rate — the same mathematics under different terminology, with deferred profit unwinding over
the financing term. Relevant if the bank has an IFSC or GIFT City Islamic window.

### Saudi Arabia — IFRS 9, 1 January 2018 · SAMA `[Medium]`

**Why it matters for India.** SAMA's adoption process is the closest procedural template to what
RBI has just done. In 2016 and 2017 SAMA undertook quantitative impact studies alongside a detailed
consultation process with Saudi banks before the standard took effect on 1 January 2018.

**The transferable point.** RBI followed the same sequence — draft Directions in October 2025, a
Statement on Feedback Received, then final Directions in April 2026 with refinements specifically
on prudential floors, POCI computation and the determination of the EIR. The EIR clarifications in
the final text exist because banks pushed back during consultation. That establishes a precedent:
the industry channel on EIR mechanics is open, and the remaining silences in §2 are legitimate
subjects for a collective IBA representation rather than fifty separate bank policies.

**Supervisory posture.** SAMA requires probability-weighted ECL across base, optimistic and
pessimistic scenarios with documented macroeconomic overlays — scenario documentation as a
governance requirement rather than a best practice. ACPIR 47–49 imposes the same discipline.

### Canada — IFRS 9, fiscal 2018 · OSFI `[Medium]`

**Why it matters for India.** Canadian banks adopted early on a November fiscal year-end and have a
long run of comparable disclosure. Mortgage-heavy balance sheets with broker-originated volume make
the transaction-cost capitalisation question central, as in Australia.

**The transferable point.** OSFI's supervisory expectation of coherence between the accounting EIR
and internal funds transfer pricing. If the EIR the accounting engine produces differs materially
from the rate treasury uses to price the same exposure internally, one of them is wrong — and the
reconciliation between them is a useful early control on engine correctness. Build it as a
validation, not as a report.

### Sri Lanka — SLFRS 9, 2018 · CBSL `[Medium]`

**Why it matters for India.** The nearest South Asian jurisdiction with a full IFRS 9 operating
history, and one that has stress-tested the modification-versus-derecognition machinery under real
conditions through the domestic debt optimisation of sovereign holdings.

**The transferable point.** A sovereign or quasi-sovereign restructuring forces the substantiality
question at scale, simultaneously, across a whole banking system — with no time to develop policy.
India's equivalent trigger would be a large-scale directed restructuring or a state-guaranteed
exposure event. The policy has to exist before the event; §5 item 2 is not a 2029 deliverable.

### United States — ASC 310-20 / ASC 326 CECL `[Contrast case]`

**Why the contrast is useful.** Because it shows India's Stage 3 treatment is closer to US practice
than to IFRS 9, which is a defensible framing rather than an embarrassment.

**The three relevant differences.**
- *Interest recognition on impaired assets.* IFRS 9 specifies the amount to which the EIR is
  applied by reference to the credit stage. ASC 326 neither prescribes an EIR nor specifies when an
  asset moves to non-accrual, and may permit existing non-accrual practices to continue. India's
  non-recognition on Stage 3 is functionally a non-accrual regime.
- *Fee and cost deferral.* ASC 310-20 requires nonrefundable fees and origination costs to be
  deferred and recognised over the life of the loan as an adjustment of yield — the level-yield
  method. Same destination as EIR, different route and different detail on which costs qualify.
- *Floating-rate re-estimation.* IFRS 9 B5.4.5 alters the EIR for periodic re-estimation reflecting
  market rate movements, while noting that where the instrument was initially recognised at an
  amount equal to principal, re-estimating future interest payments normally has no significant
  effect on the carrying amount. This is a specifically IFRS mechanic with no clean US analogue.

**Practical use.** When an audit committee asks why India is not simply adopting IFRS 9, the honest
answer is that the world's largest banking market does not either, and that the divergences are on
defensible axes. Then show them the divergence register.

---

## 7. The live IASB project — why you must not hard-code the reset rule

> **Timeline as at August 2026**
>
> **H1 2025** — IASB conducts outreach across industries and regions to find the root causes of
> diversity in amortised cost application. **September 2025** — Staff papers on determining the EIR
> at initial recognition and on subsequent changes to the EIR; three alternatives tabled for
> clarifying B5.4.5. **Q4 2025** — Consultative group discussion. **February 2026** — Amortised
> Cost Measurement back on the agenda. **April 2026** — Board tentatively decides to amend B5.4.5
> to require EIR adjustment for a re-estimation of contractual cash flows that provides
> consideration for the time value of money or for credit risk; all 13 members agreed. **May
> 2026** — Modification of financial instruments taken back to the consultative group. **H2 2026** —
> Exposure Draft planned.

### The three alternatives the Board considered

| Option | Scope of B5.4.5 | Consequence | Staff assessment |
|---|---|---|---|
| A | EIR reset only for movements linked to general market-based variables — benchmark rate, inflation. Borrower-specific credit spread excluded. | Most changes route to catch-up. Significant change to current practice; system rebuild for most preparers. | Most consistent with amortised cost as a cost-based measure, uses only observable inputs, and aligns with the distinction B5.4.4 already draws. But application costs likely exceed the benefit of the resulting information. |
| B | EIR reset for market movements in *any* component, repricing the contractual rate to its prevailing market rate — benchmark, inflation and credit spread. | Closer to what many entities already do. Excludes pre-determined adjustments — credit ratchets, step-ups, ESG features — that move the rate independently of prevailing market rates. | Links "market rate of interest" to fair value under IFRS 13, reducing interpretive room. But measuring interest cash flows at fair value for an instrument classified at amortised cost is conceptually awkward and creates a hybrid measure. |
| C | EIR reset for any change in the contractual rate arising from any contractually specified variable, market-related or not. | Least change to current practice. Effectively every rate change resets the EIR. | Operationally simplest and aligned to contractual amounts, but similar to cash accounting and therefore incompatible with the accrual basis — and inconsistent with the explicit reference in B5.4.5 to market rate movements. |

### Where the April 2026 decision lands

The tentative decision — adjust the EIR for re-estimations providing consideration for *the time
value of money or for credit risk* — is substantially Alternative B. Benchmark movements and
credit-spread repricing reset the rate. Pre-determined adjustments that compensate for neither,
such as ESG ratchets and step-ups, fall outside and therefore route to a catch-up adjustment under
B5.4.6.

> **The design instruction that follows.** Build the routing rule as *configuration keyed to a
> cash-flow-driver taxonomy*, not as hard-coded logic keyed to product. Tag each contractual rate
> component with what it compensates for: time value of money, credit risk, or neither. Route on
> the tag. When the Exposure Draft lands and the final wording shifts, you change a mapping table
> rather than reopening the engine.
>
> This also explains a gap worth closing early. IASB staff observed that firms' manuals diverge
> here: one firm's view is that if the contract provides for cash flows to be reset to reflect
> changes in *any* component of market rates — time value, credit and other spreads, profit
> margin — B5.4.5 applies to all of them. Another considers B5.4.5 appropriate not only for
> instruments whose rates vary with observable benchmarks but also for those with features that
> reset to a current market rate, including inflation-indexed principal, variable credit-spread
> margin ratchets and ESG-linked adjustments that mirror the issuer's market credit spread — while
> noting that, given the absence of guidance, entities must make a consistent accounting policy
> choice, apply it consistently and disclose it. Your auditor's house view will matter. Ask now.

### The one question the Board closed

On determining the EIR at inception for instruments with conditions attached, the staff recommended
no further action, and the reasoning is directly useful. The definition in Appendix A is clear that
all contractual terms and conditions must be considered, except in rare cases where cash flows or
expected life cannot be reliably estimated. Where entities do not consider conditional terms,
outreach found it was because of materiality assessments or insufficient information for a reliable
estimate — not because the requirement was unclear. Diversity arising from judgement cannot be
fixed by standard-setting.

**Translation for policy:** "we didn't model the contingency" is only defensible on documented
materiality grounds or documented data insufficiency. Write the materiality threshold down and
evidence the data gap. Do not leave it implicit.

---

## 8. Data architecture and engine design

### Data dictionary — the minimum viable set

**Contract layer.** Sanction date · first disbursement date · tranche-wise disbursement schedule
(actual and projected) · contractual repayment schedule · rate type · benchmark identifier ·
spread · reset frequency and reset dates · day-count convention · compounding basis · moratorium
type and period · interest-capitalisation flag · call, put and extension option terms · prepayment
permission and charge terms.

**Fee layer.** Fee code · amount · date received · payer · **EIR-eligibility flag** · allocation
basis · linked facility identifier · for commitment fees, the drawdown-probability assessment and
its basis. The eligibility flag is the single highest-value field in the model and must be owned by
finance under change control, not defaulted by the source system.

**Cost layer.** DSA and DMA payout · broker and dealer commission · employee sourcing incentive
(separated from processing compensation) · bureau enquiry charge · technical and legal appraisal
fee where borne by the bank · valuation fee. Excluded and flagged as such: internal credit
appraisal staff cost, internal administration, financing cost, holding cost.

**Behavioural layer.** Prepayment curve by product, vintage and segment · renewal and rollover
history for revolving facilities · utilisation curve for cash credit and overdraft · behavioural
life for auto-renewing facilities with the supporting default-pattern, drawdown-behaviour and
limit-action analysis ACPIR 46(2)(iii) requires · attrition and balance-transfer rates for
mortgages.

**Product taxonomy.** A single tag, aligned to the ACPIR 82 floor categories, consumed by both the
EIR engine and the provisioning engine. ACPIR 92 requires strict segregation across product
categories and expressly forbids grouping gold loans under secured retail. ACPIR 93 requires
defined classification rules, origination systems that capture the category, periodic internal
audit of classification decisions and management oversight.

**EIR state layer.** Original EIR · current EIR · full reset history with driver tag · catch-up
adjustments with amount, date and reason · unamortised fee and cost balance · shadow Stage 3
unwind · suspense ledger balance · credit-adjusted EIR for POCI with day-1 lifetime ECL embedded ·
derecognition and re-origination events · hedge basis adjustment balance and amortisation schedule
(instrument and portfolio level).

### Solver design

- **Root-finding.** Newton-Raphson with bisection fallback. Newton-Raphson fails on irregular cash
  flow profiles with multiple sign changes — common in tranched project finance with moratoria — so
  the fallback is not optional. Set an explicit tolerance and iteration cap, and log
  non-convergence as an exception rather than defaulting silently to the contractual rate.
- **Granularity.** ACPIR 51 carries the presumption that cash flows and expected life of a *group*
  of similar instruments can be estimated reliably. Pool-level EIR is therefore permitted and is
  the only tractable answer for retail. Reserve instrument-level EIR for Tier 1 per §4A.
- **Convention discipline.** Fix day-count and compounding once, in policy, and apply it uniformly.
  Money-market instruments conventionally use actual/365 in India; term loans commonly 30/360. A
  mixed convention that is not documented will produce a permanent unexplained reconciliation
  difference between treasury and finance.
- **Reset efficiency.** Do not re-solve the full schedule on every EBLR reset for every account.
  Use the B5.4.4 shortcut where the instrument reprices to market before maturity.

### Controls that must exist before go-live

1. **The identity check.** EIR interest income must equal contractual interest, plus or minus net
   fee and cost amortisation, plus or minus catch-up adjustments, for every period at every level
   of aggregation. If this does not tie, nothing downstream is reliable.
2. **Unamortised balance roll-forward.** Opening balance plus additions less amortisation less
   derecognitions equals closing balance, per product, per period. Tie to the general ledger.
3. **Penal charge exclusion assertion.** A positive control confirming no penal charge amount
   entered any EIR cash flow stream or gross carrying amount in the period.
4. **Stage 3 suppression reconciliation.** Shadow unwind computed, income suppressed, suspense
   ledger movement explained. This is the control that will attract the most audit attention
   because it is where India diverges.
5. **Pre-floor and post-floor duality.** The pre-floor ECL, computed at the EIR, must be retained
   as a reported output, not overwritten by the floor. ACPIR 90 requires floors applied per product
   category, portfolio-basis for Stages 1 and 2 and mandatorily account-level for Stage 3.
6. **Hedge basis adjustment control.** No discontinued hedge relationship may exist without an
   active amortisation schedule. Designated-risk-only adjustment verified. See §4B.2.
7. **Hedging cost exclusion assertion.** No swap or hedge cost has entered an EIR cash flow stream.
8. **Model inventory registration.** The EIR calculation is a model. ACPIR Chapter V and the
   three-line model risk management framework apply — inventory, tiering, documentation,
   independent validation before implementation, and ongoing performance assessment.
9. **Product taxonomy audit.** ACPIR 93(3) requires periodic internal audit of classification
   decisions and ACPIR 94 requires robust documentation with no misclassification aimed at
   understating ECL. The EIR engine's product tag is in scope of that audit.
10. **Tier 3 equivalence re-test.** Annual re-performance of the solved-versus-approximated
    comparison supporting the §4A Tier 3 shortcut.

> **Automation is mandated, not optional.** ACPIR requires NPA and NPI classification, upgradation
> and provisioning to be fully system-driven with no manual intervention, and the system to be
> periodically validated by internal or external auditors for both controls and methodology. It
> further directs banks to automate the overall ECL framework to the extent feasible, with
> documented rules, maker-checker control, audit trails and exception logs. Annex 3 sets out the
> automation requirements. A spreadsheet-based EIR computation will not survive this — and KPMG's
> implementation experience across banks is explicit that spreadsheet reliance is sub-optimal given
> the data volumes involved and that banks should invest in either a strategic or a tactical system
> depending on volume.

---

## 9. Policy checklist — thirty positions requiring Board sub-committee approval

ACPIR 57 requires a Board committee or Board-approved committee including the CFO and CRO to
oversee implementation, review and challenge methodology, ensure data integrity, and ensure the
independence of internal model validation. These are the EIR items for that committee's agenda.

1. **Interpretive hierarchy.** Where ACPIR is silent, which source governs — IFRS 9, Ind AS 109, or
   firm guidance? State it once, explicitly, and cite RBI's own alignment objective as the basis.
2. **Fee master.** Every fee code classified EIR-integral or not, with reasoning. Owned by finance.
   Change-controlled.
3. **Commitment fee probability.** Numeric definition of "probable" drawdown, by product, evidenced
   by historical drawdown rates.
4. **Syndication fee bifurcation.** Method for splitting arrangement service income from EIR
   adjustment where retained share and fee share diverge.
5. **Sourcing versus processing cost.** Rule separating employee selling-agent incentives
   (capitalise) from internal appraisal cost (expense).
6. **Revolving facility fee treatment.** Split between the renewal-linked credit-assessment
   component and the availability component, for cash credit, overdraft and credit cards.
7. **Expected life method per product.** Contractual, next-reset shortcut, or modelled
   behavioural — with the reasoning for each product family.
8. **Estimation technique for conditional terms.** Most likely outcome or probability-weighted, per
   instrument class, with the IAS 37 / IFRIC 23 "better predicts the resolution" test as the stated
   basis.
9. **Reset versus catch-up routing rule.** Keyed to what each rate component compensates for.
   Configurable.
10. **Modification substantiality test.** Quantitative indicator plus qualitative factors. Approval
    authority for the conclusion on individually significant exposures.
11. **FITL and DCCO deferment.** Whether each constitutes a modification, and the resulting EIR
    consequence.
12. **Investment book method.** EIR or straight-line. If EIR, the quantified transition impact. If
    straight-line, the documented basis and the disclosure.
13. **Call and put options on securities.** Whether to follow the RBI residual-contractual-maturity
    treatment or the IFRS 9 expected-life treatment. Quantify the difference either way.
14. **Perpetual securities.** SPPI screen first, then earliest-call amortisation for those that pass.
15. **Securitisation note prepayment assumptions.** Source, review frequency, and the catch-up
    mechanics on revision.
16. **Liability-side EIR.** Extend symmetrically, or document the asymmetry and its NIM consequence.
17. **Bond issue expense capitalisation.** Which costs qualify; interaction with Ind AS 23 where
    applicable.
18. **Deposit brokerage.** Whether it capitalises into the EIR of the deposit liability.
19. **Staff loan fair value.** Market rate reference, and the destination of the day-1 difference.
20. **Government subvention.** Inside or outside the EIR. Applied consistently across agriculture,
    education and MSME.
21. **Penal charge exclusion.** System-level assertion, with the CBS remediation plan where penal
    amounts currently route through the interest ledger.
22. **Stage 3 shadow unwind.** Computation basis, suspense ledger design, disclosure language.
23. **POCI credit-adjusted EIR on cure.** Confirm retention. Document the identification criteria
    for POCI at both origination and transition.
24. **Day-count and compounding conventions.** By instrument class. One document.
25. **Pooling criteria for group EIR.** Homogeneity test, review frequency, validation approach.
    ACPIR 65–68 apply.
26. **Materiality and de minimis thresholds.** Quantified, with the basis, and the §4A Tier 3
    equivalence evidence. This is what makes "we didn't model it" defensible.
27. **Reliable-estimation failure.** Criteria for invoking the ACPIR 51 contractual fallback, with
    the explicit acknowledgement that it is intended for rare cases.
28. **Legacy migration prioritisation.** Which cohorts get full EIR reconstruction before
    31 March 2030, and the deemed-rate methodology for the remainder.
29. **Paragraph 19 fair value rebuttal evidence.** What file supports the presumption that carrying
    cost equals fair value, built during FY27 rather than at the transition date.
30. **ESG contingency flag.** Data structure now, treatment position on the Exposure Draft when it
    lands.

*Additional items arising from §4B, to be added to the same agenda:*

31. **Derivative provisioning perimeter.** Whether derivative MTM receivables are treated as within
    ACPIR 17(7) scope pending clarification, and the quantified capital cost of the conservative
    reading.
32. **Hedge accounting policy.** Adoption of IFRS 9 Chapter 6, the portfolio-hedge carve-out
    election under 5.2.3 / 6.1.3, and the basis adjustment amortisation commencement policy.
33. **Derivative interest presentation.** Schedule 13 versus Other Income, and the hedging-result
    disclosure that restores the economic margin.

---

## 10. Transition sequencing

| Window | EIR workstream | Why this order |
|---|---|---|
| Now to Mar 2027 | Fee master and cost taxonomy. Product taxonomy aligned to ACPIR 82 floor categories. Interpretive hierarchy and the thirty-three policy positions. Paragraph 19 fair value rebuttal evidence file. Solver build and the control set in §8. Tier assignment per §4A. | The fee and cost taxonomy is the binding constraint and it is a *data* problem, not an accounting one. Brazil's four-year lead time was insufficient because granularity was underestimated; India has one year to the effective date. |
| 1 Apr 2027 | Fair value the entire loan portfolio; difference to opening retained earnings, not P&L. EIR live for all new origination. Opening ECL may use the contractual rate as an interim discount factor. | The paragraph 50 interim concession buys time on ECL discounting but not on new-origination EIR. Two distinct go-live gates on the same date. |
| FY28 | Investment book method decision executed with the differential quantified. Liability-side decision executed. Legacy cohort prioritisation completed, with cohorts expected to survive beyond March 2030 identified and sequenced first. | KPMG's specific recommendation: use the extended timeline to prioritise EIR computation for loans expected to remain on the books after 31 March 2030. Reconstructing EIR for a loan that matures in 2029 is wasted effort. |
| Mar 2028 | First comparative disclosure under the ECL framework. Interim periods from June to December 2027 may present current-period provisioning on ECL against prior period on the erstwhile method without restatement. | The relief on interim comparatives is real and worth using — but it ends at the March 2028 annual reporting date, so the EIR-derived comparative has to be reconstructible by then. |
| By 31 Mar 2030 | Full legacy book on EIR. Interim contractual-rate ECL discounting fully migrated. | Hard deadline under ACPIR 21 and 50. There is no further extension mechanism in the text. |
| 2031 | Transitional CET1 add-back fully unwound — 80% declining to 20% across 2027 to 2031. | Disclose the fully-loaded position from the first reporting date, as the Seventh Amendment requires. Do not let the relief mask the terminal position. |

> **The sequencing argument in one line.** EIR is upstream of ECL, because ACPIR 50 makes the EIR
> the ECL discount rate. Every implementation programme that treats EIR as a finance-team workstream
> running parallel to the risk team's ECL build discovers this at integration testing. Sequence EIR
> first and the ECL build inherits a clean discount rate; sequence it second and the ECL model is
> rebuilt twice.

---

## 11. Source library

### Primary — India

- **RBI (Commercial Banks — Asset Classification, Provisioning and Income Recognition) Directions,
  2026** · RBI/DOR/2026-27/398 · DOR.STR.REC.No.6/21.06.011/2026-27 · 27 April 2026 · effective
  1 April 2027. EIR at paragraphs 6(1), 6(4), 6(6), 6(12), 6(29), 19–24, 50–54.
- **RBI (Commercial Banks — Financial Statements: Presentation and Disclosures) Seventh Amendment
  Directions, 2026** · RBI/2026-27/35 · DOR.STR.REC.15/21-04-018/2026-27 · 27 April 2026.
  Stage 1/2 provision presentation; interest income compilation; the AS 9 Stage 3 non-recognition
  protection; new credit-quality and loss-allowance reconciliation tables.
- **RBI (Commercial Banks — Classification, Valuation and Operation of Investment Portfolio)
  Directions** and the associated FAQs. Governs the investment book by virtue of ACPIR 22.
- **RBI penal charges framework, 2023.** Basis for the hard exclusion of penal charges from EIR.
- **RBI (Commercial Banks — Resolution of Stressed Assets) Directions, 2025.** Cross-referenced by
  ACPIR for restructuring; the accounting boundary sits between the two.

### Primary — derivatives and hedging

- **Master Direction – Reserve Bank of India (Credit Derivatives) Directions, 2026** ·
  FMRD.DIRD.03/14.03.004/2026-27 · 25 June 2026, immediate effect. Supersedes the 2022 CDS-only
  framework. Single-name CDS, credit index derivatives, TRS on corporate bonds, exchange-traded
  credit index futures. FIMMDA-led Determinations Committee. Draft issued 6 February 2026 following
  the Bi-monthly Monetary Policy Statement; final incorporates consultation feedback published as a
  separate annexure.
- **RBI (Commercial Banks — Income Recognition, Asset Classification and Provisioning) Directions,
  2025, paragraph 115** — repealed. The predecessor derivative provisioning requirement: credit
  exposures at current mark-to-market on interest rate and FX derivatives, CDS and gold attracting
  standard-asset provisioning of the relevant counterparty. Retain for the successor-provision
  argument.
- **IFRS 9 Chapter 6** — 6.5.8(a) designated-risk restriction; 6.5.9 firm commitments; 6.5.10 basis
  adjustment amortisation through the EIR; 6.5.6 rollover of hedging instruments; B6.5.22–28. Plus
  5.2.3 and 6.1.3 preserving IAS 39 89–94 for portfolio interest rate hedges.
- **IFRS IC Agenda Paper 3, November 2017** — Presentation of interest revenue for particular
  financial instruments. The swap-as-two-loans analysis and the treatment of B5.4.5 and B5.4.6
  adjustments within interest revenue and expense.
  `ifrs.org/content/dam/ifrs/meetings/2017/november/ifrs-ic/agenda-papers/ap3-presentation-of-interest-for-particular-financial-instruments.pdf`
- **KPMG — Hedging: impact of a payment holiday.** The recalculated hedge adjustment mechanics and
  the three-way modification, hedge and EIR interaction, with a numerical illustration. The most
  directly usable guidance on the hardest case in §4B.
  `kpmg.com/xx/en/our-insights/ifrg/2024/frut-financial-instruments-2h.html`
- **PwC — Achieving hedge accounting in practice under IFRS 9.** The three hedge models and why the
  hedged item must be measured on a present value basis for measurement consistency.
  `viewpoint.pwc.com/dt/gx/en/pwc/industry/industry_INT/corporate_treasury__1_INT/achieving_hedge_acco_INT/`

### Primary — IASB, and the live project

- **IFRS 9** Appendix A (EIR, credit-adjusted EIR, transaction costs); 5.4.1; 5.4.3; B5.1.1;
  B5.4.1–B5.4.3 (fees); B5.4.4 (repricing shortcut); B5.4.5 (floating-rate reset); B5.4.6
  (catch-up); B5.4.8 (transaction costs); B5.5.47 (commitment and guarantee discounting).
- **IASB Agenda Paper 11A, September 2025** — Determining Effective Interest Rate at initial
  recognition. Estimation methods; the review of firms' manuals; the recommendation of no further
  action.
  `ifrs.org/content/dam/ifrs/meetings/2025/september/iasb/ap11a-determining-effective-interest-rate.pdf`
- **IASB Agenda Paper 11B, September 2025** — Subsequent changes to the effective interest rate. The
  three alternatives; the reset-versus-catch-up analysis; Appendix B on firms' divergent views.
  `ifrs.org/content/dam/ifrs/meetings/2025/september/iasb/ap11b-subsequent-changes-effective-interest-rate.pdf`
- **IASB Update, April 2026** — the tentative decision to amend B5.4.5 for re-estimations providing
  consideration for time value of money or credit risk.
  `ifrs.org/news-and-events/updates/iasb/2026/iasb-update-april-2026/`
- **FICG Agenda Paper 4, May 2026** — Modification of financial instruments; the February 2025
  tentative decision on principles-based substantiality.
  `ifrs.org/content/dam/ifrs/meetings/2026/may/ficg/ap4-modification-financial-instruments.pdf`
- **IFRS Interpretations Committee, March 2019** — Curing of a credit-impaired financial asset. The
  Stage 3 interest mechanics and the presentation of the accumulated difference on cure.
  `ifrs.org/content/dam/ifrs/supporting-implementation/agenda-decisions/2019/ifrs9-curing-of-a-credit-impaired-financial-asset-mar-19.pdf`
- **IFRS IC Agenda Paper 7, November 2018** — Presentation of contractual interest following
  curing. The interest-in-suspense debate in IFRS terms.
- **Amendments to the Classification and Measurement of Financial Instruments**, May 2024, effective
  1 January 2026. Contingent features and ESG-linked SPPI.
  `ifrs.org/news-and-events/news/2024/05/iasb-issues-amendments-cmfi-ifrs7-ifrs9/`

### Big 4 and firm material — with what each is actually useful for

- **KPMG in India — Expected Credit Loss (ECL), May 2026.** The clearest statement that the final
  Directions refined EIR determination relative to the draft, and the operative recommendation to
  prioritise legacy EIR computation for loans surviving beyond March 2030. Also the
  implementation-challenge taxonomy and the warning on spreadsheet reliance.
  `kpmg.com/in/en/insights/2026/05/expected-credit-loss.html`
- **KPMG Australia — financial instruments Q&A series.** The most directly usable practitioner
  guidance on modification mechanics: renegotiation costs, unamortised costs on modification,
  floating-rate modification policy choice, and worked EIR adjustments. Lift the mechanics into
  Indian policy. `kpmg.com/au/en/insights/financial-reporting/financial-instruments/`
- **KPMG Cambodia — EIR implementation.** The documented failure mode: straight-line
  processing-fee amortisation creating material misstatement risk, and contractual-rate ECL
  discounting as the downstream consequence. Short, specific, and the closest published analogue to
  India's starting position.
  `assets.kpmg.com/content/dam/kpmg/kh/pdf/publication/2023/kpmg-effective-interest-rate-implementation.pdf`
- **KPMG Brazil — Harmonização da IFRS 9 em Instituições Financeiras.** The
  implementation-journey survey for CMN 4966. The comparator for a central-bank-led convergence
  programme. `kpmg.com/br/pt/home/insights/2025/04/instituicoes-financeiras-harmonizacao-ifrs-9.html`
- **KPMG in India — RBI amends classification and valuation norms, October 2023.** Maps the
  Investment Directions against Ind AS. Essential for the §5 item 7 investment-book argument.
  `assets.kpmg.com/content/dam/kpmg/in/pdf/2023/10/rbi-amends-classification-and-valuation-norms.pdf`
- **EY — Applying IFRS: Amendments to classification and measurement of financial instruments,
  December 2024.** The authoritative walkthrough of the contingent-features amendments, including
  why they are not ESG-specific.
  `ey.com/content/dam/ey-unified-site/ey-com/en-gl/technical/ifrs-technical-resources/documents/ey-gl-apply-fi-amendments-cm-12-2024.pdf`
- **EY — IFRS Developments 147: Curing of a credit-impaired financial asset.** The cleanest short
  exposition of the Stage 3 interest mechanics and what happens on cure.
- **PwC — IFRS 9 and IFRS 7 amendments in brief.** The SPPI flowchart for contingent features,
  including non-recourse.
  `viewpoint.pwc.com/dt/gx/en/pwc/in_briefs/in_briefs_INT/in_briefs_INT/ifrs-9-and-7-amendments.html`
- **PwC — IFRS and US GAAP: similarities and differences, 7.13.** The concise statement of why
  ASC 326 neither prescribes an EIR nor specifies non-accrual timing. Use it when defending India's
  Stage 3 position. `viewpoint.pwc.com/dt/us/en/pwc/accounting_guides/ifrs_and_us_gaap_sim/`
- **PwC India — ITFG Clarification Bulletin 14.** Indian authority on origination fees paid on
  liabilities being integral to the EIR, and the Ind AS 23 capitalisation interaction.
  `pwc.in/assets/pdfs/publications/2018/pwc-reportinginbrief-ind-as-transition-facilitation-group-itfg-clarification-bulletin-14.pdf`
- **Deloitte — comparison of US GAAP and IFRS, on B5.4.5 and B5.4.6.** The tightest available
  statement of the two mechanisms side by side. `dart.deloitte.com`
- **Deloitte IAS Plus — Amortised Cost Measurement project pages.** The running meeting-by-meeting
  record. The place to check whether the H2 2026 Exposure Draft has landed.
  `iasplus.com/en/meeting-types/iasb/`
- **Grant Thornton India — on the proposed investment norms.** Flagged in 2022 that the framework
  required amortisation of both premium and discount but did not specify the method, while global
  standards require effective-interest amortisation. The original articulation of the §5 item 7 gap.
  `grantthornton.in/insights/blogs/rbi-proposes-new-accounting-requirements-for-investments-of-commercial-banks/`
- **BDO Australia — Does IFRS 15 or IFRS 9 apply to fees charged to customers by lenders?** The
  two-step fee decision tree, with the negative list worked through. The most quotable statement of
  the B5.4.3 boundary.
- **RSM India — NBFC financial reporting whitepaper.** On the Ind AS 109 / Ind AS 115 boundary for
  fee income and multi-service contracts.
- **Uniqus — Implementation of Ind AS by banks in India.** States the current-practice baseline
  explicitly: banks recognise processing fees upfront and DMA costs as incurred, whereas Ind AS
  requires EIR-based recognition with directly attributable origination fees and costs amortised
  over expected life. `uniqus.com/implementation-of-ind-as-by-banks-in-india/`

### Supervisory and supranational

- **EBA Q&A 2021_5778** — commitment fee treatment and interim balance-sheet presentation. The best
  available authority for closing the ACPIR 52 probability gap.
  `eba.europa.eu/single-rule-book-qa/qna/view/publicId/2021_5778`
- **EBA IFRS 9 Implementation Monitoring Report** — including institution-level policy on penalty
  interest for Stage 3 and accrued interest on non-performing FVTPL debt. `EBA/Rep/2021/35`
- **HKMA — Guideline on recognition of interest income, MA(BS)2A Appendix 3.** The suspense-account
  precedent, including the portfolio-managed carve-out for credit cards.
  `hkma.gov.hk/media/eng/doc/key-functions/banking-stability/banking-policy-and-supervision/regulatory-framework/ma(bs)2aci(app3)_e.pdf`
- **Nepal Rastra Bank — guidance note on interest income recognition.** The
  cash-basis-versus-effective-rate bridge, and the "deemed effective interest rate" concept.
  `nrb.org.np/contents/uploads/2025/07/Notice1-Guidancenote-1.pdf`
- **SAMA Rulebook — IFRS 9.** Adoption from 1 January 2018 following quantitative impact studies
  and consultation across 2016–17.
  `rulebook.sama.gov.sa/en/ifrs-9-international-financial-reporting-standard-9`
- **BIS FSI — IFRS 9 and expected loss provisioning, Executive Summary.** The cleanest one-page
  statement of the three-stage interest mechanics for a Board audience. `bis.org/fsi/fsisummaries/ifrs9.pdf`
- **ESRB — Financial stability implications of IFRS 9.** On how stage transfers change both the ECL
  horizon and the gross-versus-net interest accrual basis.
  `esrb.europa.eu/pub/pdf/reports/20170717_fin_stab_imp_IFRS_9.en.pdf`
- **Basel Committee — Guidance on credit risk and accounting for expected credit losses, 2015.** The
  systems-and-controls framing that ACPIR Chapters III and V substantially reflect.

---

## 12. Assumptions, unverified items and open questions

### Assumptions

- The bank is a commercial bank within ACPIR scope — a banking company other than a small finance
  bank, payments bank or local area bank, a corresponding new bank, or the State Bank of India.
- The bank reports under Indian GAAP with RBI-prescribed formats, not Ind AS. Ind AS 109 and IFRS 9
  are used throughout as interpretive sources where ACPIR is silent, on the basis of RBI's own
  stated alignment objective — not as directly applicable standards.
- Product coverage assumes a universal bank with retail, MSME, agriculture, wholesale, project
  finance, treasury and trade finance books, plus co-lending, a card portfolio, and an interest rate
  and FX derivative book with hedge accounting applied. Entries not relevant to the bank's actual
  book can be deleted.
- Paragraph numbers are those of the Directions as notified on 27 April 2026. Any subsequent
  amendment Direction supersedes.
- Prudential floor percentages and CCF references are reproduced for context only; the capital and
  provisioning workstreams own them.

### Unverified items

- **Investment Directions amortisation method.** The Directions require amortisation of discount or
  premium over remaining life but do not, on the text reviewed, specify effective-interest versus
  straight-line. The 2026 Amendment Directions to the Investment Portfolio Directions were issued
  alongside ACPIR on 27 April 2026 and their full text has not been reviewed here — confirm whether
  they close this gap before finalising the §5 item 7 position.
- **Derivative provisioning successor.** The repealed 2025 Directions carried an explicit derivative
  provisioning paragraph. No equivalent appears in the ACPIR 2026 operative text reviewed (to
  approximately paragraph 105). Annexes 1 to 4 have not been reviewed. If a successor provision sits
  in an annex or in the Capital Charge Directions, §4B.1 requires rewriting rather than footnoting.
- **Whether the IASB Exposure Draft on Amortised Cost Measurement has been published.** Planned for
  H2 2026; not confirmed as issued as at the compilation date. Check the IASB work plan before
  signing the reset-versus-catch-up policy.
- **ACPIR Annex 1, 2, 3 and 4 detail.** Referenced in the operative text (SICR information, the
  simplified approach for trade and lease receivables, automation requirements, and the advances
  computation format) but not reproduced in the source consulted. Annex 3 in particular bears
  directly on the §8 control set.
- **The precise interaction between ACPIR 21 and ACPIR 50.** Paragraph 21 requires all loans
  outstanding at 31 March 2027 to be under the EIR regime by 31 March 2030; paragraph 50 requires
  ECL computation for those loans to migrate to EIR by the same date. Whether these are one
  obligation or two with a common deadline is not explicit; treat as two.

### Open questions worth raising with RBI or through the IBA

1. Does the absence of the IFRS 9 B5.4.3 negative list in paragraph 52 mean it does not apply, or
   that it applies by default through the alignment objective stated in the Introduction? This is
   the single highest-value clarification.
2. Where does the Stage 3 discount unwind go, given income is not recognised but ECL is a
   present-value measure discounted at the EIR?
3. Is paragraph 52's omission of the "probable drawdown" condition on commitment fees intentional?
4. Does the EIR regime extend to financial liabilities, and if not, how should the resulting NIM
   asymmetry be presented?
5. For the investment book, does RBI expect the effective interest method, and does the
   residual-contractual-maturity treatment of callable securities survive ACPIR's EIR objective?
6. Does the derivative provisioning requirement in paragraph 115 of the repealed 2025 Directions
   survive anywhere, and if so where? If it does not, is the ACPIR 17(7) plus 83 reading the
   intended replacement?
7. Are derivative mark-to-market receivables intended to fall within ACPIR 17(7), given that
   IFRS 9 excludes derivatives from the ECL model entirely?
8. May derivative net interest on hedges of the banking book be presented within Schedule 13
   "Interest Earned", or must it sit in Other Income?
9. Does ACPIR contemplate hedge accounting at all, and if so does the IAS 39 portfolio interest rate
   hedge carve-out remain available?

---

*End of document. Version 1.1, compiled 23 August 2026. Working reference — not audited advice.*
