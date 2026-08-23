from decimal import Decimal as D, getcontext
getcontext().prec = 40
from eir_ref import q, emi, solve_irr, npv, annualise, pow_d, amort_table

def hdr(t):
    print("\n" + "="*78); print(t); print("="*78)

FACE = D('1000000')

def bond_flows(coupon, years, redemption):
    f = {t: coupon for t in range(1, years+1)}
    f[years] = f[years] + redemption
    return f

def solve(flows, price):
    return solve_irr([(t, v) for t, v in sorted(flows.items())], price)

# ---------------------------------------------- O1 CALLABLE BOND BOUGHT AT A PREMIUM
hdr("O1  CALLABLE BOND AT A PREMIUM - the quantifiable RBI / IFRS 9 divergence")
cpn = D('90000'); price = D('1050000'); call_yr = 5; mat_yr = 10
print(f"  Face {q(FACE)}, coupon 9.00% annual = {q(cpn)}, {mat_yr}y, callable at PAR at year {call_yr}")
print(f"  Purchase price {q(price)}  -> premium {q(price-FACE)}")
f_mat  = bond_flows(cpn, mat_yr, FACE)
f_call = bond_flows(cpn, call_yr, FACE)
r_mat  = solve(f_mat, price)
r_call = solve(f_call, price)
print(f"\n  Policy CONTRACTUAL_MATURITY  (RBI Investment Directions reading)")
print(f"    life {mat_yr}y   EIR {q(r_mat*100,6)}% p.a.   year-1 income {q(price*r_mat)}")
print(f"  Policy EARLIEST_CALL / expected life  (IFRS 9 Appendix A reading)")
print(f"    life {call_yr}y    EIR {q(r_call*100,6)}% p.a.   year-1 income {q(price*r_call)}")
d_rate = (r_mat-r_call)*10000; d_inc = price*r_mat - price*r_call
print(f"\n  DIVERGENCE  rate {q(d_rate,1)} bp   year-1 income {q(d_inc)}")
print(f"  Premium amortised in year 1: maturity basis {q(cpn - price*r_mat)}, "
      f"call basis {q(cpn - price*r_call)}")
print("  A premium amortised over 5 years instead of 10 is expensed roughly twice as")
print("  fast, so the call basis reports LOWER income. Both are computable; the")
print("  divergence is measurable rather than arguable, so the engine computes both.")
rm = amort_table(price, r_mat, f_mat, mat_yr)
rc = amort_table(price, r_call, f_call, call_yr)
print(f"  terminal, maturity basis  : {q(rm[-1]['closing'],6)}")
print(f"  terminal, call basis      : {q(rc[-1]['closing'],6)}")

# ---------------------------------------------- O2 CALLABLE BOND BOUGHT AT A DISCOUNT
hdr("O2  CALLABLE BOND AT A DISCOUNT - the divergence reverses direction")
price_d = D('950000')
r_mat_d  = solve(f_mat, price_d)
r_call_d = solve(f_call, price_d)
print(f"  Same bond bought at {q(price_d)} -> discount {q(FACE-price_d)}")
print(f"  CONTRACTUAL_MATURITY  {mat_yr}y  EIR {q(r_mat_d*100,6)}% p.a.  year-1 income {q(price_d*r_mat_d)}")
print(f"  EARLIEST_CALL         {call_yr}y   EIR {q(r_call_d*100,6)}% p.a.  year-1 income {q(price_d*r_call_d)}")
print(f"  DIVERGENCE  rate {q((r_call_d-r_mat_d)*10000,1)} bp   "
      f"year-1 income {q(price_d*r_call_d - price_d*r_mat_d)}")
print("  A discount accreted over 5 years instead of 10 recognises income FASTER, so")
print("  here the call basis reports MORE. The sign of the divergence depends on")
print("  whether the instrument was bought above or below par — which is why a single")
print("  blanket policy cannot be assumed to be conservative.")

# ---------------------------------------------------------- O3 PERPETUAL TO FIRST CALL
hdr("O3  PERPETUAL DEBT - RBI FAQ: amortise to the earliest call date")
p_price = D('1020000'); p_cpn = D('80000'); first_call = 5
f_perp = bond_flows(p_cpn, first_call, FACE)
r_perp = solve(f_perp, p_price)
print(f"  Perpetual, coupon 8.00% = {q(p_cpn)}, bought at {q(p_price)}, first call year {first_call}")
print(f"  Amortised to earliest call: EIR {q(r_perp*100,6)}% p.a.")
print(f"  Running yield for contrast: {q(p_cpn/p_price*100,6)}% p.a.")
print("  PERIMETER CHECK FIRST. An AT1-style instrument with discretionary coupons and")
print("  loss absorption generally fails SPPI and sits at FVTPL, where no EIR arises at")
print("  all. The earliest-call rule applies only to perpetual debt that PASSES SPPI.")
print("  Run the classification gate before commissioning any EIR work.")

# ---------------------------------------------------------------- O4 PUTTABLE BOND
hdr("O4  PUTTABLE BOND - the option sits with the holder")
put_yr = 3
f_put = bond_flows(cpn, put_yr, FACE)
r_put = solve(f_put, price)
print(f"  Same 9% bond at {q(price)}, holder may put at PAR at year {put_yr}")
print(f"  To put date  {put_yr}y  EIR {q(r_put*100,6)}% p.a.")
print(f"  To maturity {mat_yr}y  EIR {q(r_mat*100,6)}% p.a.")
print(f"  Spread {q((r_mat-r_put)*10000,1)} bp")
print("  A put held by the BANK is an asset-side right, so expected life is the bank's")
print("  own exercise judgement. A call held by the ISSUER is not: the bank must model")
print("  someone else's rational behaviour. Holder identity is therefore a required")
print("  field on every option, not decoration.")

# ------------------------------------------------------ O5 EXTENSION OPTION
hdr("O5  EXTENSION OPTION - expected life longer than stated maturity")
base_yr = 5; ext_yr = 3
f_base = bond_flows(cpn, base_yr, FACE)
f_ext  = bond_flows(cpn, base_yr+ext_yr, FACE)
r_base = solve(f_base, price); r_ext = solve(f_ext, price)
print(f"  9% bond at {q(price)}, {base_yr}y stated, extendable by {ext_yr}y")
print(f"  Stated maturity  {base_yr}y  EIR {q(r_base*100,6)}% p.a.")
print(f"  Extended         {base_yr+ext_yr}y  EIR {q(r_ext*100,6)}% p.a.")
print(f"  Spread {q((r_ext-r_base)*10000,1)} bp")
print("  ACPIR 46(1) sets the ECL horizon at the MAXIMUM contractual period including")
print(f"  extension options -> {base_yr+ext_yr}y. ACPIR 51 sets the EIR expected life at")
print(f"  what is expected -> possibly {base_yr}y. Two different numbers from one feature,")
print("  which is exactly why the data model carries them as separate fields.")

# -------------------------------------------- O6 RETAIL PREPAYMENT / CPR EXPECTED LIFE
hdr("O6  PREPAYMENT OPTION VIA A CPR CURVE - behavioural expected life")
Pm = D('5000000'); imm = D('0.09')/12; nm = 240; fee = D('50000')
E = q(emi(Pm, imm, nm))
print(f"  Mortgage {q(Pm)} at 9.00% p.a., {nm} months, net integral fee {q(fee)}")
print(f"  Contractual EMI {q(E)}")
for cpr in [D('0'), D('0.08'), D('0.15'), D('0.25')]:
    mcpr = D(1) - pow_d(D(1)-cpr, D(1)/D(12)) if cpr > 0 else D(0)
    bal = Pm; flows = {}; t = 0
    while t < nm and bal > D('0.01'):
        t += 1
        intr = bal*imm
        sch_prin = E - intr
        if sch_prin > bal: sch_prin = bal
        bal_after = bal - sch_prin
        prepay = bal_after * mcpr
        flows[t] = E + prepay if t < nm else E
        bal = bal_after - prepay
    life = t
    r = solve_irr([(k, v) for k, v in sorted(flows.items())], Pm - fee)
    wal = sum(k*v for k, v in flows.items()) / sum(flows.values()) / D(12)
    print(f"  CPR {q(cpr*100,0):>5}%  ->  life {life:>3} months  WAL {q(wal,2):>5}y  "
          f"EIR {q(annualise(r)*100,6)}% p.a.")
print("  Compressing expected life is the single highest-leverage assumption in the")
print("  model, which is why a CPR revision is a B5.4.6 catch-up across every affected")
print("  contract at once rather than a parameter update.")

# ------------------------------------ O7 SECURITISATION PTC - CPR REVISION CATCH-UP
hdr("O7  SECURITISATION NOTE - CPR revision is the canonical B5.4.6 catch-up")
note = D('1000000'); ncpn = D('0.10')/12; nterm = 60
def pool_flows(cpr, term, principal, coupon_rate):
    m = D(1) - pow_d(D(1)-cpr, D(1)/D(12)) if cpr > 0 else D(0)
    lvl = emi(principal, coupon_rate, term)
    bal = principal; f = {}
    for t in range(1, term+1):
        intr = bal*coupon_rate
        prin = lvl - intr
        if prin > bal: prin = bal
        after = bal - prin
        pre = after*m
        f[t] = q(intr + prin + pre)
        bal = after - pre
        if bal <= D('0.01'):
            break
    return f
f0 = pool_flows(D('0.10'), nterm, note, ncpn)
r0 = solve_irr([(k,v) for k,v in sorted(f0.items())], note)
print(f"  Note {q(note)}, pool coupon 10.00% p.a., {nterm}m, initial CPR assumption 10%")
print(f"  Initial EIR {q(annualise(r0)*100,6)}% p.a., expected life {max(f0)} months")
roll = amort_table(note, r0, f0, max(f0))
gca24 = roll[23]['closing']
print(f"  Carrying amount after 24 months : {q(gca24)}")
f1 = pool_flows(D('0.20'), nterm-24, gca24, ncpn)
restated = npv(r0, [(k,v) for k,v in sorted(f1.items())])
print(f"  CPR revised 10% -> 20% at month 24 (faster prepayment)")
print(f"  Revised expected remaining life : {max(f1)} months (was {max(f0)-24})")
print(f"  PV of revised flows at ORIGINAL EIR : {q(restated)}")
print(f"  Catch-up to P&L : {q(restated-gca24)}")
print("  Driver is BEHAVIOURAL_ESTIMATE, not a market movement, so it routes to")
print("  CATCH_UP - the rate is retained and the balance sheet is restated. Revising")
print("  the RATE instead would drive this to approximately zero and silently convert")
print("  a B5.4.6 event into a B5.4.5 one.")

# ---------------------------------------------------- O8 LEASE WITH RESIDUAL VALUE
hdr("O8  LEASE / CV FINANCE WITH RESIDUAL VALUE")
lp = D('1000000'); li = D('0.11')/12; ln = 36; rv = D('200000')
pv_rv = rv/pow_d(D(1)+li, ln)
rent = q(emi(lp - pv_rv, li, ln))
f = {t: rent for t in range(1, ln+1)}; f[ln] = f[ln] + rv
print(f"  Asset {q(lp)}, 11.00% p.a., {ln} months, residual value {q(rv)}")
print(f"  PV of residual {q(pv_rv)}  ->  monthly rental {q(rent)}")
print(f"  Final period (rental + RV) {q(f[ln])}")
lca = lp - D('12000')
rl = solve_irr([(k,v) for k,v in sorted(f.items())], lca)
print(f"  With 12,000 net integral cost capitalised (initial carrying {q(lca)}):")
print(f"  EIR {q(rl*100,8)}% /mo, {q(annualise(rl)*100,6)}% p.a.  "
      f"(contractual {q(annualise(li)*100,6)}%)")
print(f"  Net integral COST pushes EIR ABOVE contractual here — invariant INV-2 with")
print(f"  the sign flipped, because the cost was paid by the lessor.")
rr = amort_table(lca, rl, f, ln)
print(f"  terminal {q(rr[-1]['closing'],8)}")
