"""Reference EIR computations used to produce the golden worked examples in docs/.

All arithmetic in Decimal with 28 significant digits, matching the precision
policy the spec mandates for the Java implementation (MathContext(28, HALF_UP)).
"""
from decimal import Decimal as D, getcontext, ROUND_HALF_UP

getcontext().prec = 40

TWO = D(2)


def q(x, places=2):
    return D(x).quantize(D(1).scaleb(-places), rounding=ROUND_HALF_UP)


def pow_d(base, exp):
    """base**exp for integer exp, exact in Decimal."""
    return base ** exp


def npv(rate, flows):
    """flows: list of (period_index, amount). Discount at per-period `rate`."""
    total = D(0)
    for t, amt in flows:
        total += D(amt) / pow_d(D(1) + rate, t)
    return total


def solve_irr(flows, target, lo=D("-0.9"), hi=D("2.0"), iters=400):
    """Bisection on per-period rate so that npv(rate, flows) == target."""
    f = lambda r: npv(r, flows) - target
    flo, fhi = f(lo), f(hi)
    assert flo * fhi < 0, f"no sign change: {flo} {fhi}"
    for _ in range(iters):
        mid = (lo + hi) / TWO
        fm = f(mid)
        if flo * fm <= 0:
            hi, fhi = mid, fm
        else:
            lo, flo = mid, fm
    return (lo + hi) / TWO


def emi(principal, rate, n):
    return D(principal) * rate / (D(1) - D(1) / pow_d(D(1) + rate, n))


def annualise(monthly):
    return pow_d(D(1) + monthly, 12) - D(1)


def amort_table(opening, eir, flows_by_period, n):
    """Generic amortised-cost roll-forward.
    flows_by_period: dict period -> cash received.
    Returns list of dicts."""
    rows = []
    bal = D(opening)
    for t in range(1, n + 1):
        interest = bal * eir
        cash = D(flows_by_period.get(t, 0))
        closing = bal + interest - cash
        rows.append(dict(period=t, opening=bal, interest=interest, cash=cash, closing=closing))
        bal = closing
    return rows


def show(rows, label, limit=None, places=2):
    print(f"\n--- {label} ---")
    print(f"{'Pd':>3} {'Opening':>14} {'EIR int':>12} {'Cash':>12} {'Closing':>14}")
    sel = rows if limit is None else rows[:limit]
    for r in sel:
        print(f"{r['period']:>3} {q(r['opening'],places):>14} {q(r['interest'],places):>12} "
              f"{q(r['cash'],places):>12} {q(r['closing'],places):>14}")
    ti = sum(r['interest'] for r in rows)
    tc = sum(r['cash'] for r in rows)
    print(f"{'TOT':>3} {'':>14} {q(ti):>12} {q(tc):>12}")
    return ti, tc


print("=" * 78)
print("CASE 1 - Fixed-rate EMI term loan with integral fees")
print("=" * 78)
P = D("1000000")
i_m = D("0.12") / 12                       # 12% p.a. nominal, monthly compounding
n = 24
E = emi(P, i_m, n)
print(f"Principal                    : {q(P)}")
print(f"Contractual monthly rate     : {i_m} ({i_m*100}%)")
print(f"Contractual nominal p.a.     : 12.000000%")
print(f"Contractual effective p.a.   : {q(annualise(i_m)*100, 6)}%")
print(f"EMI (unrounded)              : {q(E, 6)}")
E_r = q(E)                                  # billed EMI is rounded to paise
print(f"EMI (billed, 2dp)            : {E_r}")

fee_recd = D("15000")     # processing fee received, integral
cost_paid = D("10000")    # DSA commission paid, integral
net_fee = fee_recd - cost_paid
initial_ca = P - fee_recd + cost_paid
print(f"Processing fee received      : {q(fee_recd)}  (integral, deducted from disbursal)")
print(f"DSA commission paid          : {q(cost_paid)}  (integral, incremental)")
print(f"Net integral fee income      : {q(net_fee)}")
print(f"Initial gross carrying amt   : {q(initial_ca)}")
print(f"Cash actually disbursed      : {q(P - fee_recd)}")

flows = [(t, E_r) for t in range(1, n + 1)]
eir_m = solve_irr(flows, initial_ca)
print(f"\nEIR per month                : {q(eir_m, 10)} ({q(eir_m*100,8)}%)")
print(f"EIR nominal p.a. (x12)       : {q(eir_m*12*100, 6)}%")
print(f"EIR effective p.a.           : {q(annualise(eir_m)*100, 6)}%")
print(f"NPV check (should be ~0)     : {q(npv(eir_m, flows) - initial_ca, 12)}")

rows1 = amort_table(initial_ca, eir_m, {t: E_r for t in range(1, n + 1)}, n)
ti, tc = show(rows1, "Case 1 amortised-cost roll-forward (full 24 periods)")
print(f"Terminal balance             : {q(rows1[-1]['closing'], 8)}  (must be ~0)")
contractual_int = E_r * n - P
print(f"\nTotal EIR interest income    : {q(ti)}")
print(f"Total contractual interest   : {q(contractual_int)}")
print(f"Difference (= net fee)       : {q(ti - contractual_int)}  vs net fee {q(net_fee)}")

# Contractual (non-EIR) schedule for the reconciliation exhibit
rows1c = amort_table(P, i_m, {t: E_r for t in range(1, n + 1)}, n)
print("\nPer-period reconciliation: EIR interest vs contractual interest (first 6)")
print(f"{'Pd':>3} {'EIR int':>12} {'Contr int':>12} {'Fee amort':>12} {'Unamort fee':>13}")
cum = D(0)
for a, b in list(zip(rows1, rows1c)):
    diff = a['interest'] - b['interest']
    cum += diff
    if a['period'] <= 6:
        print(f"{a['period']:>3} {q(a['interest']):>12} {q(b['interest']):>12} "
              f"{q(diff):>12} {q(net_fee - cum):>13}")
print(f"Cumulative fee amortised over life: {q(cum)} (= net fee {q(net_fee)})")

print()
print("=" * 78)
print("CASE 2 - Prepayment in full at end of month 12 (accelerated fee)")
print("=" * 78)
bal12_contractual = rows1c[11]['closing']
bal12_eir = rows1[11]['closing']
print(f"Contractual outstanding principal at m12 : {q(bal12_contractual)}")
print(f"EIR amortised cost at m12                : {q(bal12_eir)}")
unamort = bal12_contractual - bal12_eir
print(f"Unamortised net fee at m12               : {q(unamort)}")
print("On full prepayment the borrower settles the contractual principal;")
print("the residual unamortised fee is recognised in P&L immediately.")
print(f"  Derecognition gain to P&L              : {q(unamort)}")
print(f"  Cumulative fee already amortised m1-12 : {q(net_fee - unamort)}")

print()
print("=" * 78)
print("CASE 3 - IFRS 9 B5.4.6 re-estimation, catch-up at ORIGINAL EIR")
print("=" * 78)
print("At end of month 12 the borrower is granted a 6-month tenor extension:")
print("remaining 12 EMIs are re-profiled to 18 smaller EMIs. Not a substantial")
print("modification, so no derecognition. Gross carrying amount is restated to")
print("the PV of revised cash flows discounted at the ORIGINAL EIR.")
new_n = 18
new_emi = q(emi(bal12_contractual, i_m, new_n))
print(f"\nRevised EMI (contractual rate unchanged)  : {new_emi}")
revised = [(t, new_emi) for t in range(1, new_n + 1)]
new_gca = npv(eir_m, revised)
print(f"PV of revised flows @ original EIR        : {q(new_gca)}")
print(f"Carrying amount before restatement        : {q(bal12_eir)}")
catchup = new_gca - bal12_eir
print(f"Catch-up adjustment to P&L                : {q(catchup)}  "
      f"({'gain' if catchup > 0 else 'loss'})")
rows3 = amort_table(new_gca, eir_m, {t: new_emi for t in range(1, new_n + 1)}, new_n)
show(rows3, "Case 3 post-modification roll-forward (EIR unchanged)", limit=6)
print(f"Terminal balance                          : {q(rows3[-1]['closing'], 8)}")

print()
print("=" * 78)
print("CASE 4 - IFRS 9 B5.4.5 floating rate reset, PROSPECTIVE, no catch-up")
print("=" * 78)
print("Same loan but priced at Repo + 550bps, reset annually. At end of month 12")
print("the benchmark rises 100bps, so the contractual rate goes 12% -> 13% p.a.")
print("EIR is revised prospectively; the carrying amount is NOT restated.")
i_m2 = D("0.13") / 12
new_emi2 = q(emi(bal12_contractual, i_m2, 12))
print(f"\nContractual monthly rate after reset      : {q(i_m2, 10)}")
print(f"Revised EMI for remaining 12 months       : {new_emi2}")
print(f"Carrying amount at reset (unchanged)      : {q(bal12_eir)}")
flows4 = [(t, new_emi2) for t in range(1, 13)]
eir_m2 = solve_irr(flows4, bal12_eir)
print(f"Revised EIR per month                     : {q(eir_m2, 10)} ({q(eir_m2*100,8)}%)")
print(f"Revised EIR effective p.a.                : {q(annualise(eir_m2)*100, 6)}%")
print(f"Catch-up adjustment to P&L                : 0.00  (prospective only)")
rows4 = amort_table(bal12_eir, eir_m2, {t: new_emi2 for t in range(1, 13)}, 12)
show(rows4, "Case 4 post-reset roll-forward", limit=6)
print(f"Terminal balance                          : {q(rows4[-1]['closing'], 8)}")

print()
print("=" * 78)
print("CASE 5 - Stage 3: interest on NET carrying amount")
print("=" * 78)
print("Loan enters Stage 3 at the start of month 13. ECL allowance = 40% of GCA.")
gca13 = rows1[11]['closing']
ecl = q(gca13 * D("0.40"))
net13 = gca13 - ecl
print(f"Gross carrying amount at m13 open         : {q(gca13)}")
print(f"Lifetime ECL allowance (40%)              : {q(ecl)}")
print(f"Net (amortised) cost                      : {q(net13)}")
print(f"EIR unchanged                             : {q(eir_m, 10)}")
print(f"Stage 1/2 interest would have been        : {q(gca13 * eir_m)}  (on GCA)")
print(f"Stage 3 interest recognised               : {q(net13 * eir_m)}  (on net)")
print(f"Interest income foregone in the month     : {q((gca13 - net13) * eir_m)}")
print("The Stage 3 amount is the 'unwinding of the discount'; it is presented")
print("within interest income, not netted against impairment.")

print()
print("=" * 78)
print("CASE 6 - POCI asset: credit-adjusted EIR")
print("=" * 78)
print("A distressed pool is acquired for 700,000. Contractual flows are the same")
print("24 EMIs of the Case 1 loan, but 20% of every flow is expected to be lost.")
price = D("700000")
exp_flows = [(t, E_r * D("0.80")) for t in range(1, 25)]
contractual_flows = [(t, E_r) for t in range(1, 25)]
ca_eir = solve_irr(exp_flows, price)
plain_eir = solve_irr(contractual_flows, price)
print(f"\nPurchase price                            : {q(price)}")
print(f"Expected monthly receipt (80% of EMI)     : {q(E_r * D('0.80'))}")
print(f"Credit-adjusted EIR per month             : {q(ca_eir, 10)} ({q(ca_eir*100,8)}%)")
print(f"Credit-adjusted EIR effective p.a.        : {q(annualise(ca_eir)*100, 6)}%")
print(f"(For contrast, EIR on CONTRACTUAL flows   : {q(plain_eir*100,8)}% per month, "
      f"{q(annualise(plain_eir)*100,6)}% p.a. - overstates income)")
rows6 = amort_table(price, ca_eir, {t: E_r * D("0.80") for t in range(1, 25)}, 24)
show(rows6, "Case 6 POCI roll-forward at credit-adjusted EIR", limit=6)
print(f"Terminal balance                          : {q(rows6[-1]['closing'], 8)}")

print()
print("=" * 78)
print("CASE 7 - Solver hardness: near-zero and sign-flipping flows")
print("=" * 78)
print("Zero-coupon 5y bullet, no fees: EIR must equal the contractual yield.")
zc_flows = [(60, D("1000000") * pow_d(D(1) + D("0.01"), 60))]
zc = solve_irr(zc_flows, D("1000000"))
print(f"  recovered monthly EIR = {q(zc,12)} (expected {q(D('0.01'),12)})")
print("Interest-only loan with bullet repayment and a large upfront fee:")
io = [(t, D("10000")) for t in range(1, 60)] + [(60, D("1010000"))]
io_eir = solve_irr(io, D("950000"))
print(f"  EIR = {q(io_eir*100,8)}% per month, {q(annualise(io_eir)*100,6)}% p.a.")
print("Deep-discount instrument (fee > 20% of principal):")
dd = [(t, E_r) for t in range(1, 25)]
dd_eir = solve_irr(dd, D("780000"))
print(f"  EIR = {q(dd_eir*100,8)}% per month, {q(annualise(dd_eir)*100,6)}% p.a.")
