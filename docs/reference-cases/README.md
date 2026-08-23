# Reference Cases

Nine fully-worked numeric cases. They are the specification's centre of gravity: prose describes,
these decide. Every figure was computed independently of any implementation, and they become the
golden fixtures the engine is tested against as a **merge gate**.

**If the engine disagrees with a reference case, the engine is wrong.**

| # | Case | Demonstrates | Headline figure |
|---|---|---|---|
| 1 | [EMI loan with integral fees](case-01-emi-loan-with-fees.md) | The baseline: EIR determination, two-leg reconciliation, fee amortisation profile | EIR 13.248094% vs contractual 12.682503% p.a. |
| 2 | [Full prepayment](case-02-full-prepayment.md) | Closure accelerates the unamortised fee to P&L | 1,408.29 gain at month 12 |
| 3 | [Re-estimation catch-up](case-03-b546-reestimation.md) | B5.4.6: restate at the **original** EIR, catch-up to P&L | −627.42 charge, EIR unchanged |
| 4 | [Floating-rate reset](case-04-floating-rate-reset.md) | B5.4.5: re-solve prospectively, nil catch-up | EIR → 14.375386% p.a., 0.00 to P&L |
| 5 | [Stage 3 under ACPIR](case-05-stage-3-acpir-suppression.md) | **The India divergence.** Compute the unwind, suppress the income | 0.00 recognised; 3,304.08 + 2,202.72 = 5,506.79 |
| 6 | [POCI credit-adjusted EIR](case-06-poci-credit-adjusted-eir.md) | Expected losses inside the rate from inception | 29.141905% correct vs 64.703664% naive |
| 7 | [Solver stress](case-07-solver-stress.md) | Edge instruments; what must raise rather than guess | Zero-coupon recovers 1.000000000000% exactly |
| 8 | [B5.4.4 repricing shortcut](case-08-b544-repricing-shortcut.md) | Amortise fees to the next repricing date | Year-1 fee 5,000.00 vs 3,591.71 (+39.2%) |
| 9 | [Straight-line vs EIR](case-09-straight-line-vs-eir.md) | The error that nets to zero over life | Year 1 overstated by 81.0% |

## The three pairs worth knowing

**Cases 3 and 4 — the discriminator.** The same instrument, the same month, and in both cases "the
schedule changed". One produces a 627.42 charge with the rate held; the other produces nothing with
the rate re-solved. The difference is *why* the flows moved, which is why the engine routes on an
explicit driver tag rather than inferring anything from the observation that the rate changed. Reach
for this pair when explaining the routing table.

**Case 5 — why this is an Indian product.** IFRS 9 recognises 3,304.08 on the net basis; ACPIR
recognises nothing. But the ECL unwind of 2,202.72 must still be computed, because ACPIR 50 makes
the EIR the ECL discount rate. Reach for this when explaining why an off-the-shelf IFRS 9 sub-ledger
does not solve the problem.

**Cases 8 and 9 — the two ends of approximation.** Case 8 is a *permitted* simplification that
accelerates fee recognition legitimately and removes the reset-loop cost. Case 9 is a *prohibited*
one whose life totals tie exactly while every individual period is wrong. Both are about shortcuts;
only one is defensible.

## The number that appears three times

**1,408.29** — the unamortised net integral fee at month 12 on the Case 1 loan:

- Case 1, invariant INV-4: contractual GCA 529,815.61 − EIR GCA 528,407.32
- Case 2: the amount accelerated to P&L on full prepayment
- Case 8: the amount the B5.4.4 shortcut eliminates by amortising to the reset instead

Three independent routes to one figure. A useful engine cross-check, and a good illustration that
the unamortised fee balance is *defined* as the gap between the two legs rather than accumulated
separately — so it cannot drift away from them.

## Invariants exercised

| Invariant | Cases |
|---|---|
| IC-1 — GCA₀ = net cash flow at inception | 1 |
| TR-1 — terminal EIR-leg GCA = 0 | 1, 4, 6, 8 |
| INV-1 — Σ EIR interest = Σ contractual + net fee | 1 |
| INV-2 — EIR ≷ contractual by fee sign | 1 |
| INV-4 — unamortised fee = leg difference | 1, 2, 8 |
| CU-1 — EIR unchanged across a catch-up | 3 |
| CU-2 — catch-up = PV(revised, original EIR) − GCA_before | 3 |
| ST-2 — net interest + ECL unwind = gross interest | 5 |
| S3-1, S3-2 — Stage 3 four-way reconciliation, nil recognition | 5 |
| POCI-1 — credit-adjusted EIR retained on cure | 6 |
| Solver: NoSolution / MultipleRoots / no silent default | 7 |

## Regenerating

These files are generated, not hand-maintained. A change to a figure must come from a change to the
generator, and the pull request must say why the number moved. Reviewing a diff in a golden fixture
is the point — a silent edit to an expected value is how a regression becomes a specification.
