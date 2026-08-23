# ADR-0005 — Contract-level measurement by default; pooling as a governed election

**Status:** Accepted

## Context

ACPIR 51 expressly presumes that cash flows and expected life of a **group** of similar instruments
can be estimated reliably, so a pool-level EIR is permitted. For a retail book of millions of
accounts it is also the only tractable answer for behavioural estimation.

But a pool EIR applied to a heterogeneous population is an audit finding waiting to happen, and the
usual justification for pooling — operational cost — is weaker at this system's scale than it is in a
spreadsheet estate.

## Decision

**Contract-level measurement is the default.** Pooling is an explicit, governed election, structured
by the [three-tier materiality gate](../03-calculation-spec.md#10-the-materiality-tier-gate):

- **Tier 1** — instrument level, mandatory. Wholesale above threshold, all project finance, all POCI,
  all restructured or modified exposures, anything with a contingent rate feature, anything in a
  hedge relationship.
- **Tier 2** — pool level, elected. Retail and MSME above 12-month tenor, cards, KCC revolvers.
- **Tier 3** — documented approximation, permitted only against a current equivalence test.

Where pooling is elected: pools are **closed cohorts**; homogeneity criteria are versioned and
approved; a **quarterly back-test** against contract-level computation on a statistical sample is
mandatory, and breaching the threshold forces the pool to contract-level; a contract leaving the pool
early is re-measured at contract level at its own EIR solved on exit.

## Rationale

**The cost argument for pooling is weaker here than it looks.** Solving is event-triggered, not
per-period ([03 §4.6](../03-calculation-spec.md#46-performance)): a fixed-rate contract with no
events solves **once**, at initial recognition. So contract-level measurement costs one solve per
contract per lifetime, not per period. The month-end run is roll-forward arithmetic either way.

**Where pooling genuinely earns its place is behavioural estimation, not arithmetic.** A CPR curve
cannot be estimated per contract; a probability-weighted prepayment assumption is inherently
collective. That is the real justification, and it is why Tier 2 exists and is drawn around exactly
the populations where behaviour dominates.

**Closed cohorts are non-negotiable.** Adding members after the pool's EIR is struck means new
contracts inherit a rate solved from someone else's fee structure and rate environment. A later
origination joins a later pool.

**The back-test is what makes the expedient defensible.** Without it, pooling is an assertion of
homogeneity that no one checks. With it, the assertion is measured quarterly and self-corrects.

## Consequences

- More `EIR_COMPUTATION` rows than a pool-only design. Acceptable; the table is small relative to
  balances and is the audit anchor.
- Pool machinery must still be built: definitions, versioning, back-testing, exit handling. Not
  avoided by defaulting to contract level.
- Tier assignment becomes a governed decision with a recorded basis (FR-107) — a policy artefact
  requiring maker–checker.
- Early pool exit needs a solve at exit, which is a small computational cost at an event that is
  already doing work.

## Alternatives rejected

**Pool-level throughout.** Cheaper, and standard practice in spreadsheet estates. Rejected: it
sacrifices accuracy for a cost saving that event-triggered solving largely removes, and it forces
homogeneity assertions onto populations that do not support them.

**Contract-level throughout, no pooling.** Cleanest, and wrong: behavioural assumptions are
irreducibly collective, and ACPIR 51 explicitly contemplates the group presumption. Refusing it would
mean either no behavioural estimation or a fiction of per-contract behavioural curves.
