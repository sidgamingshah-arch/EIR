# Reference-case generator

Generates `docs/reference-cases/case-*.md` — the golden fixtures the engine is tested against.

```bash
cd tools/reference-cases
python3 gencases.py    # cases 1-5
python3 gencases2.py   # cases 6-9
python3 acpir_ref.py   # console exhibits: Stage 3 decomposition, B5.4.4, straight-line wedge,
                       # behavioural-life leverage
```

No dependencies beyond the Python standard library. All arithmetic in `decimal` at 40 significant
digits, presented at 2dp `HALF_UP` — matching the precision policy the Java implementation must
follow ([ADR-0002](../../docs/adr/0002-precision-policy.md)).

## Why Python, in a Java repository

**Deliberately a different language from the implementation.** The reference cases exist to be an
*independent* check on the engine, and an independent check written against the same libraries, the
same numeric types and the same author's assumptions is worth much less. A `BigDecimal` mistake in
`eir-calc` will not reproduce itself in `decimal`.

It also keeps the fixtures runnable by whoever needs to argue about a number — product control, the
accounting policy team, an auditor — without a JVM, a build, or the application.

## Files

| File | Contents |
|---|---|
| `eir_ref.py` | The primitives: bisection IRR solver, annuity, amortisation roll-forward, annualisation, quantisation. Also runs all seven original cases as a console report when executed directly. |
| `gencases.py` | Emits cases 1–5 |
| `gencases2.py` | Emits cases 6–9 |
| `acpir_ref.py` | The ACPIR-specific derivations: the Stage 3 three-way decomposition, the B5.4.4 comparison, the straight-line-versus-EIR wedge, and the behavioural-life leverage exhibit |

## Rules

1. **Never hand-edit a generated case file.** A change to a figure comes from a change here, and the
   pull request must say why the number moved. Reviewing the diff in a golden fixture is the point —
   a silent edit to an expected value is how a regression becomes a specification.
2. **The solver here is bisection only**, deliberately. It is slow and unconditionally convergent,
   so it cannot fail in the same way as the production Newton–Raphson path. If the two disagree, the
   production solver is wrong.
3. **No `float` anywhere.** `decimal` throughout, same as the implementation's `BigDecimal`.

## Structure fixtures (docs/09-cashflow-structures.md)

```bash
python3 structures.py    # S1-S6: equal principal, balloon, step-up, the three moratorium kinds
python3 options.py       # O1-O6, O8: call, put, extension, perpetual, CPR, lease
python3 o7.py            # O7: the par test — premium / discount / par catch-up
python3 options_fix.py   # corrected O6/O7/O8 derivations; supersedes those in options.py
```

Two corrections are preserved rather than silently folded in, because the errors are
instructive:

- **`options.py` O7 is wrong** and `o7.py` supersedes it. The first version rebuilt the
  revised pool as a fresh annuity off the *EIR carrying amount*, which makes the present
  value self-consistent by construction and yields a meaningless zero catch-up — and, as the
  tell, a *higher* CPR producing a *longer* life. A pool's level payment is fixed at
  origination; a CPR change alters the prepayment overlay, not the scheduled payment.
- **`options.py` O8 mislabels the fee direction.** It subtracts 12,000 from the carrying
  amount, which is a fee *received*, and then claims the resulting EIR uplift shows INV-2
  "with the sign flipped". `options_fix.py` runs all three directions — fee received, cost
  paid, and nil — and confirms INV-2 as normally stated.

The `o7.py` result is the one worth reading: the catch-up is **zero at par**, and non-zero
only away from par. That is invariant ST-9 and it is what stops the engine churning the
whole par-priced book on every curve refresh.
