from decimal import Decimal as D, getcontext
getcontext().prec = 40
from eir_ref import q, emi, solve_irr, npv, annualise, pow_d, amort_table

def hdr(t):
    print("\n" + "="*78); print(t); print("="*78)

def smm(annual_cpr):
    return D(1) - pow_d(D(1)-annual_cpr, D(1)/D(12)) if annual_cpr > 0 else D(0)

def pool_run(opening, level, rate, cpr, max_periods):
    """Run a pool from `opening` with a FIXED level payment and a CPR overlay.
    Returns (flows dict, closing balances list, life)."""
    m = smm(cpr); bal = opening; flows = {}; bals = []
    for t in range(1, max_periods+1):
        if bal <= D('0.01'):
            break
        intr = bal*rate
        prin = level - intr
        if prin < 0: prin = D(0)
        if prin > bal: prin = bal
        after = bal - prin
        pre = after*m
        flows[t] = intr + prin + pre
        bal = after - pre
        bals.append(bal)
    return flows, bals, len(flows)

hdr("O7  SECURITISATION NOTE - CPR revision is the canonical B5.4.6 catch-up  [CORRECTED]")
note = D('1000000'); ncpn = D('0.10')/12; nterm = 60
LEVEL = emi(note, ncpn, nterm)          # fixed at origination, never recomputed
print(f"  Note {q(note)}, pool coupon 10.00% p.a., {nterm}m contractual term")
print(f"  Level payment fixed at origination : {q(LEVEL)}")

f0, b0, life0 = pool_run(note, LEVEL, ncpn, D('0.10'), 400)
r0 = solve_irr([(k, v) for k, v in sorted(f0.items())], note)
print(f"  Initial CPR assumption 10%  ->  expected life {life0} months")
print(f"  Initial EIR {q(r0*100,8)}% /mo, {q(annualise(r0)*100,6)}% p.a.")

# EIR-leg carrying amount at month 24
roll = amort_table(note, r0, f0, life0)
gca24 = roll[23]['closing']
bal24 = b0[23]                          # actual pool balance, original CPR path
print(f"  At month 24:  pool balance {q(bal24)}   EIR carrying amount {q(gca24)}")
print(f"  (they differ by {q(bal24-gca24)} — the unamortised premium/discount, INV-4)")

# CPR revised upward at month 24. Same level payment, faster prepayment.
f1, b1, life1 = pool_run(bal24, LEVEL, ncpn, D('0.20'), 400)
restated = npv(r0, [(k, v) for k, v in sorted(f1.items())])
catch = restated - gca24
print(f"\n  CPR revised 10% -> 20% at month 24 (faster prepayment)")
print(f"  Remaining expected life : {life1} months  (was {life0-24} on the old assumption)")
print(f"  PV of revised flows at the ORIGINAL EIR : {q(restated)}")
print(f"  Carrying amount before restatement     : {q(gca24)}")
print(f"  CATCH-UP TO P&L                        : {q(catch)}  "
      f"({'gain' if catch > 0 else 'loss'})")
r1 = solve_irr([(k, v) for k, v in sorted(f1.items())], gca24)
print(f"\n  For contrast, if the RATE were re-solved instead (the defect):")
print(f"    revised EIR {q(annualise(r1)*100,6)}% p.a., catch-up 0.00 by construction")
print(f"    -> a {q(abs(catch))} P&L effect silently disappears. This is exactly how a")
print(f"       B5.4.6 event gets converted into a B5.4.5 one.")
print(f"  Driver BEHAVIOURAL_ESTIMATE -> CATCH_UP. Retain the rate, restate the balance.")

hdr("O8  LEASE / CV FINANCE WITH RESIDUAL VALUE  [CORRECTED]")
lp = D('1000000'); li = D('0.11')/12; ln = 36; rv = D('200000')
pv_rv = rv/pow_d(D(1)+li, ln)
rent = q(emi(lp - pv_rv, li, ln))
f = {t: rent for t in range(1, ln+1)}; f[ln] = f[ln] + rv
print(f"  Asset {q(lp)}, 11.00% p.a., {ln} months, residual value {q(rv)}")
print(f"  PV of residual {q(pv_rv)}  ->  monthly rental {q(rent)}")
print(f"  Final period (rental + RV) {q(f[ln])}")
print(f"  Contractual effective rate {q(annualise(li)*100,6)}% p.a.")
flows_l = [(k, v) for k, v in sorted(f.items())]
for label, ca0, note_txt in [
    ("net integral FEE RECEIVED 12,000", lp - D('12000'), "GCA0 below par -> EIR ABOVE contractual"),
    ("net integral COST PAID     12,000", lp + D('12000'), "GCA0 above par -> EIR BELOW contractual"),
    ("no integral fee                  ", lp,             "GCA0 at par -> EIR EQUALS contractual"),
]:
    r = solve_irr(flows_l, ca0)
    print(f"  {label}: GCA0 {q(ca0)}  EIR {q(annualise(r)*100,6)}% p.a.")
    print(f"      {note_txt}")
print("  This is invariant INV-2 in both directions, which is the cheap sign check that")
print("  catches a whole class of fee-classification errors.")

hdr("O6  CPR expected life - corrected label")
Pm = D('5000000'); imm = D('0.09')/12; nm = 240; fee = D('50000')
E = q(emi(Pm, imm, nm))
print(f"  Mortgage {q(Pm)} at 9.00% p.a., {nm} months, net fee {q(fee)}, EMI {q(E)}")
print(f"  {'CPR':>5} {'life (m)':>9} {'principal-WAL':>14} {'EIR p.a.':>11}")
for cpr in [D('0'), D('0.08'), D('0.15'), D('0.25')]:
    m = smm(cpr); bal = Pm; flows = {}; prin_t = D(0); prin_sum = D(0)
    for t in range(1, nm+1):
        if bal <= D('0.01'): break
        intr = bal*imm
        p = E - intr
        if p > bal: p = bal
        after = bal - p
        pre = after*m
        flows[t] = intr + p + pre
        prin_t += (p+pre)*D(t); prin_sum += (p+pre)
        bal = after - pre
    wal = prin_t/prin_sum/D(12)
    r = solve_irr([(k,v) for k,v in sorted(flows.items())], Pm - fee)
    print(f"  {q(cpr*100,0):>4}% {len(flows):>9} {q(wal,2):>14} {q(annualise(r)*100,6):>11}")
print("  principal-WAL is principal-weighted, which is the market convention; the")
print("  earlier run reported a cash-flow-weighted figure and mislabelled it.")
