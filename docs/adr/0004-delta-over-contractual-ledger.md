# ADR-0004 — The engine computes an accounting overlay; the CBS stays the book of record

**Status:** Accepted

## Context

A bank already has a core banking system that owns contractual balances, generates borrower
statements, bills EMIs, applies receipts and reports overdue status. ACPIR now requires interest
income to be recognised on an EIR basis that differs from what the borrower is billed.

Two shapes are possible: the new engine becomes the book of record and the CBS feeds it, or the CBS
stays authoritative and the engine computes the accounting difference on top.

## Decision

**The CBS/LMS remains authoritative for all contractual and customer-facing amounts.** This engine
maintains two parallel legs per contract — the contractual leg, which reconciles to the CBS, and the
EIR leg — and its output is their relationship.

Concretely: the engine **prefers to consume the schedule the CBS actually billed**
(`LMS_AUTHORITATIVE`) rather than deriving one, and it never originates a customer-facing figure.

## Rationale

**Reconciliation is the whole game.** Control C-14 requires zero unexplained difference between the
contractual leg and the CBS. If the engine derives its own schedule, that difference is guaranteed
non-zero every month for benign reasons — the CBS billed 47,073.47 where the true annuity is
47,073.472223 — and the control becomes noise. Consuming the billed schedule makes the reconciliation
trivially true and reserves the control for real breaks. This is why
`ExternalScheduleProjector` exists and why `LMS_AUTHORITATIVE` is the preferred residue policy.

**Replacing a CBS is a different programme.** Becoming the book of record means owning billing,
receipts, overdue calculation, customer statements and every downstream integration — with a
seven-month statutory deadline. Not viable, and not the problem being solved.

**The two-leg structure produces the required outputs for free.** The unamortised fee balance is
*defined* as the difference between the legs (invariant INV-4) rather than accumulated separately, so
it cannot drift. Fee amortisation per period is the difference in the interest legs. Both fall out of
the structure instead of needing their own machinery.

**Interest-in-suspense needs the contractual leg.** Under ACPIR Stage 3 suppression the suspense
ledger carries the **contractual** interest billed but not recognised. Without a contractual leg
reconciling to the CBS, that number has no source.

## Consequences

- Every contract carries two roll-forwards. Roughly doubles the balance columns; trivial against the
  benefit.
- The engine depends on CBS schedule quality. Where the CBS cannot supply a schedule, the derived
  path and the residue policies exist as fallback.
- The engine cannot be the single source of truth for "what does this borrower owe" — correct. It is
  not asked to be.
- Journal design follows the same shape: the contractual accrual and the EIR true-up are separate
  postings, which is what lets the contractual side tie to the CBS while the delta explains itself.

## Alternatives rejected

**Engine as book of record.** Correct in a greenfield core-banking replacement. Here it is a
different, far larger programme against an immovable deadline.

**Single EIR-only leg, deriving contractual figures on demand.** Saves storage, but the contractual
leg is needed every period for C-14 and for the suspense ledger, so it would be derived every period
anyway — with no stored figure to reconcile against.
