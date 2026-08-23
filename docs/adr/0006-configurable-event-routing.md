# ADR-0006 — Reset-versus-catch-up routing as versioned configuration

**Status:** Accepted

## Context

When projected cash flows change, two mechanisms are available and they produce materially different
P&L:

- **Reset** (B5.4.5) — re-solve the EIR from the current carrying amount. No P&L catch-up.
- **Catch-up** (B5.4.6) — retain the original EIR, restate the carrying amount, recognise the
  difference immediately.

[Case 3](../reference-cases/case-03-b546-reestimation.md) and
[Case 4](../reference-cases/case-04-floating-rate-reset.md) are the same instrument in the same
month: one produces a 627.42 charge, the other nothing.

Two facts make this the highest-risk piece of logic in the engine:

1. **ACPIR is silent.** There is no equivalent of B5.4.5 or B5.4.6. The mechanics are adopted by
   election under the interpretive hierarchy, so the choice is the bank's and must be defensible.
2. **The rule is actively changing.** The IASB's April 2026 tentative decision would amend B5.4.5 to
   cover re-estimations providing consideration for the time value of money **or credit risk**, with
   an Exposure Draft expected H2 2026 — routing ESG ratchets and pre-determined step-ups to a
   catch-up instead. Firms' published manuals already diverge on the current wording.

Hard-coding today's reading guarantees a re-engineering event, on a live ledger, under audit.

## Decision

**Route on a driver taxonomy, through a versioned mapping table.**

Every contractual rate component carries a `driver` tag recording *what it compensates for*:
`TIME_VALUE_OF_MONEY`, `CREDIT_RISK_MARKET`, `CREDIT_RATCHET_PREDETERMINED`, `ESG_LINKED`,
`STEP_UP_PREDETERMINED`, `BEHAVIOURAL_ESTIMATE`, `DISBURSEMENT_TIMING`, `NEGOTIATED`.

The mapping from driver (plus the instrument's `rateType`) to mechanism is **data in an approved,
versioned table** — not a `switch` statement. Every `LIFECYCLE_EVENT` stores the
`routing_table_version_id` that produced its routing.

Build the switch, not the constant.

## Rationale

**A standards change becomes a policy change.** When the Exposure Draft lands, the response is a new
routing table version with an impact preview and checker approval — the same governed path as any
other policy change. Not a code change, a regression cycle and a release.

**Replay stays correct across the change.** Because each event records the routing table version that
routed it, a period closed under the old reading replays under the old reading. If routing were code,
a replay would silently apply today's logic to yesterday's events and DT-1 would fail — or worse,
pass while being wrong.

**It makes the auditor's house view explicit.** Firms differ on whether B5.4.5 covers a reset of
*any* market-rate component, or extends to inflation-indexed principal and credit-spread ratchets.
This is an accounting policy choice that must be made, applied consistently and disclosed. A table
with an approver is the natural home for it; code is not.

**The driver taxonomy is the durable abstraction.** Whatever the final wording, it will discriminate
on *what the rate component compensates for* — that is the axis the April 2026 decision chose. Tagging
on that axis means the taxonomy survives the wording change and only the mapping moves.

## Consequences

- The `driver` tag becomes **mandatory** on rate components and on events. The API rejects an event
  without one (FR-504) — deliberately, because a defaulted driver is a silently wrong routing.
- Source systems must supply it, which is an ingestion mapping exercise per product.
- The routing table joins the maker–checker set with a mandatory impact preview.
- Slight indirection cost at the routing site. Immaterial: routing happens per *event*, not per
  contract per period.
- The bank must take a position on each driver. That is the point — the positions exist either way,
  and this makes them visible and owned rather than implicit in a `switch`.

## Alternatives rejected

**Hard-code the current reading.** Simplest today. Guarantees a re-engineering event on a live
ledger, and makes historical replay wrong the moment the code changes.

**Route on product type.** What a naive implementation does — "mortgages reset, securitisation notes
catch up". Wrong: the same product can experience both, depending on why its flows moved. It is the
cause that determines treatment, never the instrument.

**Route on observed rate movement.** Superficially attractive and definitively wrong: a renegotiated
fixed-rate loan and an EBLR reset both "look like the rate moved", and they are a modification and a
reset respectively. Keying off the observation rather than the cause is
[03 §6.2](../03-calculation-spec.md#62-the-decision-table)'s trap row.

**A rules engine.** Considered and rejected in [05 §5.1](../05-architecture.md#51-why-not-a-rules-engine-drools-et-al):
this is a table lookup with versioning and approval, not inference. A rules engine would put an
opaque evaluation step in the middle of the audit trail.
