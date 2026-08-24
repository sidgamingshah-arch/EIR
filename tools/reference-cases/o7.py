from decimal import Decimal as D, getcontext
getcontext().prec = 40
from eir_ref import q, emi, solve_irr, npv, annualise, pow_d, amort_table

def smm(cpr): return D(1) - pow_d(D(1)-cpr, D(1)/D(12)) if cpr > 0 else D(0)

def pool_run(opening, level, rate, cpr, cap=400):
    m = smm(cpr); bal = opening; flows = {}; bals = []
    for t in range(1, cap+1):
        if bal <= D('0.01'): break
        intr = bal*rate
        p = level - intr
        if p < 0: p = D(0)
        if p > bal: p = bal
        after = bal - p
        pre = after*m
        # Billed, for the same reason the level payment is. A pool pays cash, and
        # FlowVectorAssembler emits every rung total at presentation scale because a
        # holder cannot be paid a fraction of a paisa. Leaving the flows unrounded here
        # while rounding the level was a third basis, reproducing neither the engine nor
        # the unrounded ideal, and it is why no single basis reproduced all three columns
        # of the premium row.
        flows[t] = q(intr + p + pre)
        bal = after - pre
        bals.append(bal)
    return flows, bals, len(flows)

print("="*78)
print("O7  SECURITISATION NOTE BOUGHT AWAY FROM PAR - the B5.4.6 catch-up")
print("="*78)
face = D('1000000'); cpn = D('0.10')/12; term = 60
# BILLED, not exact. A borrower cannot be paid fractions of a paisa, so the pool
# bills a level payment rounded to the currency's last place, and FlowVectorAssembler
# emits every rung total at presentation scale for the same reason. This line read
# emi(face, cpn, term) — the unrounded annuity 21,247.0447110715 — which is why the
# figures this file produced could not be reproduced by the engine, and why no single
# basis reproduced all three columns of the premium row.
LEVEL = q(emi(face, cpn, term))
print(f"  Pool face {q(face)}, coupon 10.00% p.a., {term}m contractual term")
print(f"  Level payment fixed at origination: {q(LEVEL)}")
print(f"  Contractual effective rate: {q(annualise(cpn)*100,6)}% p.a.\n")

for label, price in [("PREMIUM", D('1030000')), ("DISCOUNT", D('970000'))]:
    print("-"*78)
    print(f"  Bought at a {label}: price {q(price)}  ({'premium' if price>face else 'discount'} {q(abs(price-face))})")
    f0, b0, life0 = pool_run(face, LEVEL, cpn, D('0.10'))
    r0 = solve_irr([(k,v) for k,v in sorted(f0.items())], price)
    print(f"  Initial CPR 10% -> expected life {life0}m, initial EIR {q(annualise(r0)*100,6)}% p.a.")
    roll = amort_table(price, r0, f0, life0)
    gca24 = roll[23]['closing']; bal24 = b0[23]
    unam = bal24 - gca24
    print(f"  Month 24: pool balance {q(bal24)}, EIR carrying {q(gca24)}")
    print(f"            unamortised {'premium' if price>face else 'discount'} {q(-unam) if price>face else q(-unam)}  (INV-4)")
    f1, b1, life1 = pool_run(bal24, LEVEL, cpn, D('0.20'))
    restated = npv(r0, [(k,v) for k,v in sorted(f1.items())])
    catch = restated - gca24
    print(f"  CPR revised 10% -> 20%: remaining life {life1}m (was {life0-24}m)")
    print(f"  PV(revised flows @ ORIGINAL EIR) {q(restated)} vs carrying {q(gca24)}")
    print(f"  ** CATCH-UP TO P&L: {q(catch)}  ({'gain' if catch>0 else 'LOSS'}) **")
    r1 = solve_irr([(k,v) for k,v in sorted(f1.items())], gca24)
    print(f"  If the rate were re-solved instead (the defect): EIR "
          f"{q(annualise(r1)*100,6)}% p.a., catch-up 0.00 by construction")
    print(f"     -> {q(abs(catch))} of P&L silently disappears\n")

print("-"*78)
print("  Bought AT PAR: price 1,000,000")
f0, b0, life0 = pool_run(face, LEVEL, cpn, D('0.10'))
r0 = solve_irr([(k,v) for k,v in sorted(f0.items())], face)
roll = amort_table(face, r0, f0, life0)
gca24 = roll[23]['closing']; bal24 = b0[23]
f1, b1, life1 = pool_run(bal24, LEVEL, cpn, D('0.20'))
catch = npv(r0, [(k,v) for k,v in sorted(f1.items())]) - gca24
print(f"  EIR {q(annualise(r0)*100,6)}% p.a. == contractual. Carrying == pool balance.")
print(f"  CPR revised 10% -> 20%: CATCH-UP {q(catch)}")
print("  ZERO, and correctly so. With no premium or discount there is nothing for a")
print("  change in prepayment speed to accelerate: the revised flows always discount")
print("  to the outstanding balance at the contractual rate.")
print()
print("  THE DESIGN CONSEQUENCE. A behavioural-assumption change is only a P&L event")
print("  for an instrument held AWAY FROM PAR. This is why a floating-rate note bought")
print("  at par raises no reset-versus-catch-up question at all, while the same note")
print("  bought in the secondary market away from par raises the full question. The")
print("  engine must therefore key the catch-up on the unamortised premium/discount")
print("  balance, not on the fact that an assumption moved — otherwise it churns the")
print("  whole par-priced book every time a curve is refreshed, for no P&L effect.")
