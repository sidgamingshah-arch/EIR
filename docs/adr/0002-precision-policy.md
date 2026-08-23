# ADR-0002 — `BigDecimal` throughout, 28-digit working precision, `HALF_UP`

**Status:** Accepted

## Context

The engine solves rates by iterative root-finding, then compounds those rates over up to 360 periods
on balances that can exceed 10^9. Published figures must reconcile to the paisa against the core
banking system and the general ledger, and must be reproducible years later.

## Decision

1. **`BigDecimal` for all money and all rates.** `double` and `float` are banned in `eir-domain` and
   `eir-calc`, enforced by a build-failing lint rule.
2. **Working precision `MathContext(28, RoundingMode.HALF_UP)`** — IEEE 754 decimal128. Intermediate
   values are never rounded to currency scale.
3. **Presentation rounding exactly once**, at the persistence or GL boundary, to ISO 4217 minor units.
4. **Rates persisted at 12 decimal places.** The persisted rate is the rate used in every downstream
   period.
5. **`HALF_UP`, not `HALF_EVEN`.**
6. **Money and rates cross the API as decimal strings**, never JSON numbers.

## Rationale

**On the type.** Binary floating point cannot represent 0.01. Accumulated over 360 periods the error
is not theoretical, and the resulting break is undiagnosable because it has no single cause. This is
[01 §11 #12](../01-domain-primer.md#11-common-failure-modes).

**On rounding once.** Rounding intermediates compounds the rounding error itself. Rounding at the
boundary confines it to one step whose behaviour is specified.

**On 12dp and using the stored rate.** Solving to 28 digits, persisting 12, and then rolling forward
with the unrounded 28-digit value produces a published amortisation that cannot be reproduced from
the published rate. That is an audit failure even though every figure is individually more accurate.
Round once, then use the rounded value.

**On `HALF_UP` over banker's rounding.** `HALF_EVEN` has the better statistical argument. It is
rejected because Indian financial reporting convention and every downstream reconciliation target —
core banking, GL, RBI returns — use `HALF_UP`. Consistency with the systems we must tie to outranks
statistical bias in a system whose purpose is tying.

**On JSON strings.** Most JSON parsers deserialise numbers to IEEE 754 doubles. Emitting money as a
JSON number would silently defeat the whole policy at the API boundary — the one place it is least
likely to be noticed.

## Consequences

- `BigDecimal` arithmetic is roughly an order of magnitude slower than primitive doubles. Mitigated
  by incremental discount-factor computation and by event-triggered rather than per-period solving
  ([03 §4.6](../03-calculation-spec.md#46-performance)). The target is met.
- Every arithmetic site must pass an explicit `MathContext`. `BigDecimal.divide` without one throws
  on a non-terminating expansion — a footgun, so division is centralised in `eir-domain`.
- API clients must parse decimal strings. Documented, and worth the friction.
- The terminal-residue question cannot be resolved by tolerance and needs an explicit policy
  ([03 §7.3](../03-calculation-spec.md)).

## Alternatives rejected

**`double` with an epsilon tolerance.** Tolerances hide the class of defect this system exists to
prevent. A reconciliation that passes within tolerance is not a reconciliation.

**Long integer paise.** Exact and fast for money, but rates need far more than 2dp, so the codebase
would carry two numeric regimes and conversions between them. Not worth the split.

**`HALF_EVEN`.** See above — the statistical argument loses to the reconciliation argument.
